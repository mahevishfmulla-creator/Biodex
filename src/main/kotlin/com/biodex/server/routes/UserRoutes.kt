package com.biodex.server.routes

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable

// ---------- Request / Response models ----------

@Serializable
data class UserProfileResponse(
    val userId: String,
    val username: String,
    val level: Int,
    val currentXp: Int,
    val maxXp: Int,
    val totalCaptured: Int,
    val joinedAt: String
)

@Serializable
data class CollectionRequest(
    val speciesId: String,
    val iucnStatus: String
)

@Serializable
data class CollectionResponse(
    val success: Boolean,
    val speciesId: String,
    val earnedXp: Int,
    val newTotalXp: Int,
    val level: Int,
    val levelUp: Boolean,
    val unlockedRewardItem: String?
)

@Serializable
data class ErrorResponseUser(
    val error: String
)

// ---------- In-memory captured-species tracker (placeholder until PostgreSQL wiring lands) ----------
// TODO: replace with a real repository backed by PostgreSQL / Exposed.
// Tracks (userId -> set of speciesId) to detect duplicate captures.

object CaptureStore {
    private val capturedByUser = mutableMapOf<String, MutableSet<String>>()

    fun hasCaptured(userId: String, speciesId: String): Boolean =
        capturedByUser[userId]?.contains(speciesId) == true

    fun addCapture(userId: String, speciesId: String) {
        capturedByUser.getOrPut(userId) { mutableSetOf() }.add(speciesId)
    }
}

// ---------- XP / rarity logic ----------

private fun rarityBonusXp(iucnStatus: String): Int = when (iucnStatus.uppercase()) {
    "LC", "NT" -> 0
    "VU" -> 50
    "EN" -> 100
    "CR" -> 200
    else -> 0 // unrecognized status treated as no bonus rather than rejecting the capture
}

private const val BASE_CAPTURE_XP = 100

// ---------- Routes ----------

fun Route.userRoutes() {

    authenticate {
        route("/api/v1/user") {

            get("/profile") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asString()

                val user = userId?.let { UserStore.findById(it) }
                if (user == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponseUser("Invalid or expired token"))
                    return@get
                }

                call.respond(
                    HttpStatusCode.OK,
                    UserProfileResponse(
                        userId = user.userId,
                        username = user.username,
                        level = user.level,
                        currentXp = user.currentXp,
                        maxXp = user.maxXp,
                        totalCaptured = user.totalCaptured,
                        joinedAt = user.joinedAt
                    )
                )
            }

            post("/collection") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asString()

                val user = userId?.let { UserStore.findById(it) }
                if (user == null) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponseUser("Invalid or expired token"))
                    return@post
                }

                val request = call.receive<CollectionRequest>()

                if (request.speciesId.isBlank()) {
                    call.respond(HttpStatusCode.BadRequest, ErrorResponseUser("speciesId is required"))
                    return@post
                }

                if (CaptureStore.hasCaptured(user.userId, request.speciesId)) {
                    call.respond(HttpStatusCode.Conflict, ErrorResponseUser("Species already captured"))
                    return@post
                }

                // --- XP calculation ---
                val earnedXp = BASE_CAPTURE_XP + rarityBonusXp(request.iucnStatus)

                var newCurrentXp = user.currentXp + earnedXp
                var newLevel = user.level
                var levelUp = false

                // Level Threshold Formula: MaxXP = Level x 1000.
                // When currentXp >= maxXp, increment level and carry over remaining XP.
                var maxXp = newLevel * 1000
                while (newCurrentXp >= maxXp) {
                    newCurrentXp -= maxXp
                    newLevel += 1
                    levelUp = true
                    maxXp = newLevel * 1000
                }

                // --- Persist updated state ---
                user.currentXp = newCurrentXp
                user.level = newLevel
                user.maxXp = maxXp
                user.totalCaptured += 1
                UserStore.save(user)
                CaptureStore.addCapture(user.userId, request.speciesId)

                // Simple reward-unlock placeholder: any level-up unlocks a statue item.
                // TODO: replace with real reward/item lookup once that system exists.
                val unlockedRewardItem = if (levelUp) "statue_${request.speciesId.replace("-", "_")}" else null

                call.respond(
                    HttpStatusCode.OK,
                    CollectionResponse(
                        success = true,
                        speciesId = request.speciesId,
                        earnedXp = earnedXp,
                        newTotalXp = newCurrentXp,
                        level = newLevel,
                        levelUp = levelUp,
                        unlockedRewardItem = unlockedRewardItem
                    )
                )
            }
        }
    }
}
