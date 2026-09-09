package com.biodex.server.quiz

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

data class ReviewCardState(
    val speciesId: String,
    val userId: String,
    var repetitionCount: Int,
    var easeFactor: Double,
    var intervalDays: Int,
    var masteryPercent: Int,
    var streakCount: Int,
    var nextReviewDue: Instant,
    var lastReviewedAt: Instant?,
)

data class SM2UpdateResult(
    val updatedState: ReviewCardState,
    val nextReviewDays: Int,
    val newMasteryPercent: Int,
    val updatedStreak: Int
)

object SpacedRepetitionEngine {

    private const val MIN_EASE_FACTOR = 1.3
    private const val DEFAULT_EASE_FACTOR = 2.5
    private const val PASSING_QUALITY_THRESHOLD = 3

    /**
     * Derives quality score q (0-5) from correctness and response time,
     * per the brief's response-speed/correctness rules.
     */
    fun deriveQuality(isCorrect: Boolean, wasCloseOption: Boolean, timeTakenSeconds: Double?, isBlank: Boolean): Int {
        if (isBlank) return 0
        if (!isCorrect) return if (wasCloseOption) 2 else 1
        val t = timeTakenSeconds ?: Double.MAX_VALUE
        return when {
            t < 5.0 -> 5
            t <= 15.0 -> 4
            else -> 3
        }
    }

    fun createInitialState(speciesId: String, userId: String): ReviewCardState {
        return ReviewCardState(
            speciesId = speciesId,
            userId = userId,
            repetitionCount = 0,
            easeFactor = DEFAULT_EASE_FACTOR,
            intervalDays = 0,
            masteryPercent = 0,
            streakCount = 0,
            nextReviewDue = Instant.now(),
            lastReviewedAt = null
        )
    }

    /**
     * Applies the modified SM-2 algorithm to update a card's scheduling state
     * given the quality score of the latest answer.
     */
    fun applyReview(current: ReviewCardState, quality: Int): SM2UpdateResult {
        val q = quality.coerceIn(0, 5)
        val now = Instant.now()

        val newEaseFactor = calculateNewEaseFactor(current.easeFactor, q)

        val (newRepetitionCount, newIntervalDays) = if (q < PASSING_QUALITY_THRESHOLD) {
            // Reset on failure
            1 to 1
        } else {
            val nextRep = current.repetitionCount + 1
            val nextInterval = when (nextRep) {
                1 -> 1
                2 -> 6
                else -> (current.intervalDays * newEaseFactor).roundToInt().coerceAtLeast(1)
            }
            nextRep to nextInterval
        }

        val newStreak = if (q >= PASSING_QUALITY_THRESHOLD) current.streakCount + 1 else 0
        val newMastery = calculateMasteryPercent(
            previousMastery = current.masteryPercent,
            quality = q,
            repetitionCount = newRepetitionCount
        )

        val updated = current.copy(
            repetitionCount = newRepetitionCount,
            easeFactor = newEaseFactor,
            intervalDays = newIntervalDays,
            masteryPercent = newMastery,
            streakCount = newStreak,
            nextReviewDue = now.plus(newIntervalDays.toLong(), ChronoUnit.DAYS),
            lastReviewedAt = now
        )

        return SM2UpdateResult(
            updatedState = updated,
            nextReviewDays = newIntervalDays,
            newMasteryPercent = newMastery,
            updatedStreak = newStreak
        )
    }

    fun isDue(state: ReviewCardState): Boolean {
        return state.nextReviewDue.isBefore(Instant.now())
    }

    private fun calculateNewEaseFactor(currentEf: Double, quality: Int): Double {
        val q = quality.toDouble()
        val newEf = currentEf + (0.1 - (5.0 - q) * (0.08 + (5.0 - q) * 0.02))
        return newEf.coerceAtLeast(MIN_EASE_FACTOR)
    }

    private fun calculateMasteryPercent(
        previousMastery: Int,
        quality: Int,
        repetitionCount: Int
    ): Int {
        val adjustment = when (quality) {
            5 -> 10
            4 -> 7
            3 -> 3
            2 -> -5
            1 -> -10
            else -> -15
        }
        // Higher repetition counts make mastery more stable
        val stabilityFactor = if (repetitionCount > 5) 0.5 else 1.0
        val finalAdjustment = (adjustment * stabilityFactor).roundToInt()
        
        return (previousMastery + finalAdjustment).coerceIn(0, 100)
    }
}
