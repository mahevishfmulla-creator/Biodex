package com.biodex.server.game

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

data class LiveFighter(
    val stats: FighterStats,
    var currentHp: Int,
    val sequenceIdentityPercent: Double,
    var isShielded: Boolean = false
)

data class BattleSession(
    val battleId: String,
    val player: LiveFighter,
    val opponent: LiveFighter,
    var turnNumber: Int = 0,
    var isOver: Boolean = false,
    var winner: String? = null // "player" | "opponent" | "draw"
)

data class ActionResult(
    val moveName: String,
    val isHit: Boolean,
    val isCritical: Boolean,
    val damageDealt: Int,
    val remainingHp: Int
)

data class TurnResult(
    val battleId: String,
    val turnNumber: Int,
    val playerAction: ActionResult,
    val opponentAction: ActionResult?,
    val isBattleOver: Boolean,
    val winner: String?,
    val battleLog: String
)

class BattleNotFoundException(battleId: String) : Exception("No active battle found for battleId '$battleId'.")
class InvalidMoveException(moveId: String) : Exception("Move '$moveId' is not available for this fighter.")
class BattleAlreadyOverException(battleId: String) : Exception("Battle '$battleId' has already concluded.")

object BattleStateMachine {

    private val activeBattles = ConcurrentHashMap<String, BattleSession>()

    fun createBattle(player: LiveFighter, opponent: LiveFighter): BattleSession {
        val battleId = "bat_${System.currentTimeMillis()}${Random.nextInt(100, 999)}"
        val session = BattleSession(
            battleId = battleId,
            player = player,
            opponent = opponent
        )
        activeBattles[battleId] = session
        return session
    }

    fun getBattle(battleId: String): BattleSession {
        return activeBattles[battleId] ?: throw BattleNotFoundException(battleId)
    }

    fun processTurn(battleId: String, selectedMoveId: String): TurnResult {
        val session = getBattle(battleId)

        if (session.isOver) {
            throw BattleAlreadyOverException(battleId)
        }

        val playerMove = session.player.stats.moves.firstOrNull { it.id == selectedMoveId }
            ?: throw InvalidMoveException(selectedMoveId)

        session.turnNumber += 1

        // Determine turn order: higher level acts first; ties resolved by higher atk
        val playerFirst = when {
            session.player.stats.level != session.opponent.stats.level ->
                session.player.stats.level > session.opponent.stats.level
            else -> session.player.stats.atk >= session.opponent.stats.atk
        }

        val opponentMove = chooseOpponentMove(session.opponent)

        val logParts = mutableListOf<String>()

        var playerAction: ActionResult? = null
        var opponentAction: ActionResult? = null

        if (playerFirst) {
            playerAction = resolveMove(session, isPlayerActing = true, move = playerMove, logParts = logParts)
            if (session.opponent.currentHp > 0) {
                opponentAction = resolveMove(session, isPlayerActing = false, move = opponentMove, logParts = logParts)
            }
        } else {
            opponentAction = resolveMove(session, isPlayerActing = false, move = opponentMove, logParts = logParts)
            if (session.player.currentHp > 0) {
                playerAction = resolveMove(session, isPlayerActing = true, move = playerMove, logParts = logParts)
            }
        }

        evaluateBattleOutcome(session)

        // Edge case: playerAction must always be present in the response even if skipped due to KO;
        // synthesize a no-op result if the player never got to act.
        val finalPlayerAction = playerAction ?: ActionResult(
            moveName = playerMove.name,
            isHit = false,
            isCritical = false,
            damageDealt = 0,
            remainingHp = session.opponent.currentHp
        )

        val finalOpponentAction = opponentAction

        return TurnResult(
            battleId = session.battleId,
            turnNumber = session.turnNumber,
            playerAction = finalPlayerAction,
            opponentAction = finalOpponentAction,
            isBattleOver = session.isOver,
            winner = session.winner,
            battleLog = logParts.joinToString(" ")
        )
    }

    private fun resolveMove(
        session: BattleSession,
        isPlayerActing: Boolean,
        move: MoveDefinition,
        logParts: MutableList<String>
    ): ActionResult {
        val attacker = if (isPlayerActing) session.player else session.opponent
        val defender = if (isPlayerActing) session.opponent else session.player

        if (move.category == MoveCategory.SHIELD) {
            attacker.isShielded = true
            logParts.add("${attacker.stats.name} braces with Frameshift Shield!")
            return ActionResult(
                moveName = move.name,
                isHit = true,
                isCritical = false,
                damageDealt = 0,
                remainingHp = attacker.currentHp
            )
        }

        val isHit = Random.nextDouble() < move.accuracy
        if (!isHit) {
            logParts.add("${attacker.stats.name} used ${move.name} but missed!")
            return ActionResult(
                moveName = move.name,
                isHit = false,
                isCritical = false,
                damageDealt = 0,
                remainingHp = defender.currentHp
            )
        }

        val critChance = StatCalculator.criticalHitChance(attacker.sequenceIdentityPercent)
        val isCritical = Random.nextDouble() < critChance

        var power = move.power
        if (move.category == MoveCategory.MUTATION) {
            power = StatCalculator.pointMutationVariance(move.power, Random.nextDouble(0.70, 1.30))
        }

        var rawDamage = ((attacker.stats.atk + power) - defender.stats.def).coerceAtLeast(1)
        if (isCritical) {
            rawDamage = (rawDamage * StatCalculator.CRIT_MULTIPLIER).toInt()
        }

        if (defender.isShielded) {
            val reduction = StatCalculator.shieldDamageReduction(defender.sequenceIdentityPercent)
            rawDamage = (rawDamage * (1.0 - reduction)).toInt().coerceAtLeast(0)
            defender.isShielded = false
        }

        defender.currentHp = (defender.currentHp - rawDamage).coerceAtLeast(0)

        val critText = if (isCritical) "Critical " else ""
        logParts.add(
            "${attacker.stats.name} used ${move.name}${if (isCritical) " (Critical!)" else ""} dealing $rawDamage damage!"
        )

        return ActionResult(
            moveName = move.name,
            isHit = true,
            isCritical = isCritical,
            damageDealt = rawDamage,
            remainingHp = defender.currentHp
        )
    }

    private fun chooseOpponentMove(opponent: LiveFighter): MoveDefinition {
        // Simple AI: random selection among available moves, weighted slightly toward offense
        val offensiveMoves = opponent.stats.moves.filter { it.category != MoveCategory.SHIELD }
        return if (offensiveMoves.isNotEmpty() && Random.nextDouble() < 0.85) {
            offensiveMoves.random()
        } else {
            opponent.stats.moves.random()
        }
    }

    private fun evaluateBattleOutcome(session: BattleSession) {
        val playerDown = session.player.currentHp <= 0
        val opponentDown = session.opponent.currentHp <= 0

        when {
            playerDown && opponentDown -> {
                session.isOver = true
                session.winner = "draw"
            }
            opponentDown -> {
                session.isOver = true
                session.winner = "player"
            }
            playerDown -> {
                session.isOver = true
                session.winner = "opponent"
            }
        }
    }

    fun removeBattle(battleId: String) {
        activeBattles.remove(battleId)
    }
}