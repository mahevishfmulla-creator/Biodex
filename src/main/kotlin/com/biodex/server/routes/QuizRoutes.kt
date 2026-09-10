package com.biodex.server.routes

import com.biodex.server.quiz.ExposedSpeciesDataSource
import com.biodex.server.quiz.GeneratedQuestion
import com.biodex.server.quiz.QuestionGenerationException
import com.biodex.server.quiz.QuestionGenerator
import com.biodex.server.quiz.SpacedRepetitionEngine
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class NextQuestionResponse(
    val questionId: String,
    val speciesId: String,
    val targetSpeciesName: String,
    val questionType: String,
    val prompt: String,
    val options: List<String>,
    val streakCount: Int,
    val totalDueToday: Int,
)


@Serializable
data class AnswerResponse(
    val questionId: String,
    val isCorrect: Boolean,
    val correctAnswerIndex: Int,
    val explanation: String,
    val earnedXp: Int,
    val newMasteryPercent: Int,
    val nextReviewDays: Int,
    val updatedStreak: Int,
    val levelUp: Boolean,
)

@Serializable
data class QuizStatsResponse(
    val userId: String,
    val overallMasteryPercent: Int,
    val totalCardsTracked: Int,
    val cardsMastered: Int,
    val cardsLearning: Int,
    val currentStreak: Int,
)

// @Serializable
// data class ErrorResponse(val error: String)

// In-memory caches; swap for Redis/DB-backed persistence in production.
private val pendingQuestions = ConcurrentHashMap<String, GeneratedQuestion>()

fun Route.quizRoutes(questionGenerator: QuestionGenerator, dataSource: ExposedSpeciesDataSource) {
    route("/api/v1/quiz") {

        authenticate {
            get("/next") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asString()

                if (userId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing or invalid authorization token."))
                    return@get
                }

                try {
                    val question = questionGenerator.nextQuestionForUser(userId)
                    pendingQuestions[question.questionId] = question

                    val streak = dataSource.loadReviewState(userId, question.speciesId)?.streakCount ?: 0
                    val totalDue = dataSource.countDueToday(userId)

                    call.respond(
                        HttpStatusCode.OK,
                        NextQuestionResponse(
                            questionId = question.questionId,
                            speciesId = question.speciesId,
                            targetSpeciesName = question.targetSpeciesName,
                            questionType = question.questionType.name,
                            prompt = question.prompt,
                            options = question.options,
                            streakCount = streak,
                            totalDueToday = totalDue
                        )
                    )
                } catch (e: QuestionGenerationException) {
                    call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse(e.message ?: "Unable to generate question."))
                }
            }

            post("/answer") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asString()

                if (userId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing or invalid authorization token."))
                    return@post
                }

                val request = try {
                    call.receive<AnswerRequest>()
                } catch (e: Exception) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("Malformed request body: ${e.message}"))
                    return@post
                }

                val question = pendingQuestions[request.questionId]
                if (question == null) {
                    call.respond(HttpStatusCode.NotFound, ErrorResponse("No pending question found for questionId '${request.questionId}'."))
                    return@post
                }
                if (question.speciesId != request.speciesId) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponse("speciesId does not match the pending question."))
                    return@post
                }

                val isBlank = request.selectedOptionIndex < 0
                val isCorrect = !isBlank && (request.selectedOptionIndex == question.correctAnswerIndex)
                val wasClose = !isBlank && !isCorrect &&
                        (kotlin.math.abs(request.selectedOptionIndex - question.correctAnswerIndex) == 1)

                val quality = SpacedRepetitionEngine.deriveQuality(
                    isCorrect = isCorrect,
                    wasCloseOption = wasClose,
                    timeTakenSeconds = request.timeTakenSeconds,
                    isBlank = isBlank
                )

                val currentState = dataSource.loadReviewState(userId, question.speciesId)
                    ?: SpacedRepetitionEngine.createInitialState(question.speciesId, userId)

                val previousMastery = currentState.masteryPercent
                val sm2Result = SpacedRepetitionEngine.applyReview(currentState, quality)
                dataSource.upsertReviewState(sm2Result.updatedState)

                val earnedXp = calculateXp(quality, isCorrect)
                val levelUp = crossedLevelThreshold(previousMastery, sm2Result.newMasteryPercent)

                pendingQuestions.remove(request.questionId)

                call.respond(
                    HttpStatusCode.OK,
                    AnswerResponse(
                        questionId = question.questionId,
                        isCorrect = isCorrect,
                        correctAnswerIndex = question.correctAnswerIndex,
                        explanation = question.explanation,
                        earnedXp = earnedXp,
                        newMasteryPercent = sm2Result.newMasteryPercent,
                        nextReviewDays = sm2Result.nextReviewDays,
                        updatedStreak = sm2Result.updatedStreak,
                        levelUp = levelUp
                    )
                )
            }

            get("/stats") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asString()

                if (userId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing or invalid authorization token."))
                    return@get
                }

                val states = dataSource.loadAllReviewStates(userId)
                val totalTracked = states.size
                val mastered = states.count { it.masteryPercent >= 80 }
                val learning = totalTracked - mastered
                val overallMastery = if (totalTracked == 0) 0 else states.asSequence().map { it.masteryPercent }.average().toInt()
                val currentStreak = states.maxOfOrNull { it.streakCount } ?: 0

                call.respond(
                    HttpStatusCode.OK,
                    QuizStatsResponse(
                        userId = userId,
                        overallMasteryPercent = overallMastery,
                        totalCardsTracked = totalTracked,
                        cardsMastered = mastered,
                        cardsLearning = learning,
                        currentStreak = currentStreak
                    )
                )
            }
        }
    }
}

private fun calculateXp(quality: Int, isCorrect: Boolean): Int {
    if (!isCorrect) return 2 // small participation XP even on miss
    return when (quality) {
        5 -> 25
        4 -> 18
        3 -> 12
        else -> 5
    }
}

private fun crossedLevelThreshold(previousMastery: Int, newMastery: Int): Boolean {
    val previousTier = previousMastery / 20
    val newTier = newMastery / 20
    return newTier > previousTier
}