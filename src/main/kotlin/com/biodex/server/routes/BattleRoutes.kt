package com.biodex.server.routes

import com.biodex.server.game.BattleAlreadyOverException
import com.biodex.server.game.BattleNotFoundException
import com.biodex.server.game.BattleStateMachine
import com.biodex.server.game.FighterStats
import com.biodex.server.game.InvalidMoveException
import com.biodex.server.game.IucnCategory
import com.biodex.server.game.LiveFighter
import com.biodex.server.game.MoveDefinition
import com.biodex.server.game.StatCalculator
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class BattleInitRequest(
    val playerSpeciesId: String,
    val playerLevel: Int,
    val opponentSpeciesId: String,
    val opponentLevel: Int,
    val sequenceIdentityPercent: Double,
    val playerName: String? = null,
    val opponentName: String? = null,
    val playerIucnCategory: String? = null,
    val opponentIucnCategory: String? = null
)

@Serializable
data class MoveDto(
    val id: String,
    val name: String,
    val power: Int,
    val accuracy: Double,
    val category: String
)

@Serializable
data class PlayerFighterDto(
    val speciesId: String,
    val name: String,
    val level: Int,
    val maxHp: Int,
    val currentHp: Int,
    val atk: Int,
    val def: Int,
    val alignmentBoost: Double,
    val moves: List<MoveDto>
)

@Serializable
data class OpponentFighterDto(
    val speciesId: String,
    val name: String,
    val level: Int,
    val maxHp: Int,
    val currentHp: Int,
    val atk: Int,
    val def: Int,
    val alignmentBoost: Double
)

@Serializable
data class BattleInitResponse(
    val battleId: String,
    val playerFighter: PlayerFighterDto,
    val opponentFighter: OpponentFighterDto
)

@Serializable
data class BattleTurnRequest(
    val battleId: String,
    val selectedMoveId: String
)

@Serializable
data class ActionResultDto(
    val moveName: String,
    val isHit: Boolean,
    val isCritical: Boolean,
    val damageDealt: Int,
    val remainingHp: Int
)

@Serializable
data class BattleTurnResponse(
    val battleId: String,
    val turnNumber: Int,
    val playerAction: ActionResultDto,
    val opponentAction: ActionResultDto?,
    val isBattleOver: Boolean,
    val winner: String?,
    val battleLog: String
)

// @Serializable
// data class ErrorResponse(val error: String)

fun Route.battleRoutes() {
    route("/api/v1/battle") {

        post("/init") {
            val request = try {
                call.receive<BattleInitRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Malformed request body: ${e.message}"))
                return@post
            }

            val validationError = validateInitRequest(request)
            if (validationError != null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(validationError))
                return@post
            }

            val playerCategory = parseIucnCategory(request.playerIucnCategory) ?: IucnCategory.LC
            val opponentCategory = parseIucnCategory(request.opponentIucnCategory) ?: IucnCategory.LC

            val playerStats: FighterStats = StatCalculator.buildFighter(
                speciesId = request.playerSpeciesId,
                displayName = request.playerName ?: displayNameFromId(request.playerSpeciesId),
                level = request.playerLevel,
                iucnCategory = playerCategory,
                sequenceIdentityPercent = request.sequenceIdentityPercent
            )

            val opponentStats: FighterStats = StatCalculator.buildFighter(
                speciesId = request.opponentSpeciesId,
                displayName = request.opponentName ?: displayNameFromId(request.opponentSpeciesId),
                level = request.opponentLevel,
                iucnCategory = opponentCategory,
                sequenceIdentityPercent = request.sequenceIdentityPercent
            )

            val playerLive = LiveFighter(
                stats = playerStats,
                currentHp = playerStats.maxHp,
                sequenceIdentityPercent = request.sequenceIdentityPercent
            )
            val opponentLive = LiveFighter(
                stats = opponentStats,
                currentHp = opponentStats.maxHp,
                sequenceIdentityPercent = request.sequenceIdentityPercent
            )

            val session = BattleStateMachine.createBattle(playerLive, opponentLive)

            val response = BattleInitResponse(
                battleId = session.battleId,
                playerFighter = PlayerFighterDto(
                    speciesId = playerStats.speciesId,
                    name = playerStats.name,
                    level = playerStats.level,
                    maxHp = playerStats.maxHp,
                    currentHp = playerLive.currentHp,
                    atk = playerStats.atk,
                    def = playerStats.def,
                    alignmentBoost = playerStats.alignmentBoost,
                    moves = playerStats.moves.map { it.toDto() }
                ),
                opponentFighter = OpponentFighterDto(
                    speciesId = opponentStats.speciesId,
                    name = opponentStats.name,
                    level = opponentStats.level,
                    maxHp = opponentStats.maxHp,
                    currentHp = opponentLive.currentHp,
                    atk = opponentStats.atk,
                    def = opponentStats.def,
                    alignmentBoost = opponentStats.alignmentBoost
                )
            )

            call.respond(HttpStatusCode.OK, response)
        }

        post("/turn") {
            val request = try {
                call.receive<BattleTurnRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Malformed request body: ${e.message}"))
                return@post
            }

            if (request.battleId.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("battleId is required."))
                return@post
            }
            if (request.selectedMoveId.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("selectedMoveId is required."))
                return@post
            }

            try {
                val result = BattleStateMachine.processTurn(request.battleId, request.selectedMoveId)

                call.respond(
                    HttpStatusCode.OK,
                    BattleTurnResponse(
                        battleId = result.battleId,
                        turnNumber = result.turnNumber,
                        playerAction = result.playerAction.toDto(),
                        opponentAction = result.opponentAction?.toDto(),
                        isBattleOver = result.isBattleOver,
                        winner = result.winner,
                        battleLog = result.battleLog
                    )
                )
            } catch (e: BattleNotFoundException) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse(e.message ?: "Battle not found."))
            } catch (e: InvalidMoveException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(e.message ?: "Invalid move."))
            } catch (e: BattleAlreadyOverException) {
                call.respond(HttpStatusCode.Conflict, ErrorResponse(e.message ?: "Battle already over."))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Unexpected error: ${e.message}"))
            }
        }
    }
}

private fun validateInitRequest(request: BattleInitRequest): String? {
    if (request.playerSpeciesId.isBlank()) return "playerSpeciesId is required."
    if (request.opponentSpeciesId.isBlank()) return "opponentSpeciesId is required."
    if (request.playerLevel <= 0) return "playerLevel must be a positive integer."
    if (request.opponentLevel <= 0) return "opponentLevel must be a positive integer."
    if (request.sequenceIdentityPercent < 0.0 || request.sequenceIdentityPercent > 100.0) {
        return "sequenceIdentityPercent must be between 0 and 100."
    }
    return null
}

private fun parseIucnCategory(raw: String?): IucnCategory? {
    if (raw.isNullOrBlank()) return null
    return try {
        IucnCategory.valueOf(raw.uppercase())
    } catch (e: IllegalArgumentException) {
        null
    }
}

private fun displayNameFromId(speciesId: String): String {
    return speciesId.split("-").joinToString(" ") { part ->
        part.replaceFirstChar { it.uppercase() }
    }
}

private fun MoveDefinition.toDto(): MoveDto = MoveDto(
    id = id,
    name = name,
    power = power,
    accuracy = accuracy,
    category = category.name
)

private fun com.biodex.server.game.ActionResult.toDto(): ActionResultDto = ActionResultDto(
    moveName = moveName,
    isHit = isHit,
    isCritical = isCritical,
    damageDealt = damageDealt,
    remainingHp = remainingHp
)