package com.biodex.server.game

import kotlin.math.roundToInt

enum class IucnCategory { LC, NT, VU, EN, CR, EW, EX }

enum class MoveCategory { CONSERVED, MUTATION, SHIELD, BURST }

data class MoveDefinition(
    val id: String,
    val name: String,
    val power: Int,
    val accuracy: Double,
    val category: MoveCategory
)

data class FighterStats(
    val speciesId: String,
    val name: String,
    val level: Int,
    val maxHp: Int,
    val atk: Int,
    val def: Int,
    val alignmentBoost: Double,
    val moves: List<MoveDefinition>
)

object StatCalculator {

    private const val BASE_HP = 100
    private const val HP_PER_LEVEL = 15
    private const val BASE_ATK = 30
    private const val ATK_PER_LEVEL = 4
    private const val BASE_DEF = 20
    private const val DEF_PER_LEVEL = 3

    private const val BASE_CRIT_CHANCE = 0.05
    private const val CRIT_CHANCE_PER_SID = 0.15
    const val CRIT_MULTIPLIER = 1.5

    fun rarityBonus(category: IucnCategory): Int = when (category) {
        IucnCategory.LC, IucnCategory.NT -> 0
        IucnCategory.VU -> 10
        IucnCategory.EN -> 20
        IucnCategory.CR -> 30
        IucnCategory.EW, IucnCategory.EX -> 30
    }

    /** S_id = sequence identity percent / 100 */
    fun sequenceIdentityMultiplier(sequenceIdentityPercent: Double): Double {
        return (sequenceIdentityPercent / 100.0).coerceIn(0.0, 1.0)
    }

    /** alignmentBoost as shown in the API contract, e.g. 85.0% -> 1.425 */
    fun alignmentBoost(sequenceIdentityPercent: Double): Double {
        val sId = sequenceIdentityMultiplier(sequenceIdentityPercent)
        return round3(1.0 + sId * 0.5)
    }

    fun criticalHitChance(sequenceIdentityPercent: Double): Double {
        val sId = sequenceIdentityMultiplier(sequenceIdentityPercent)
        return round3(BASE_CRIT_CHANCE + sId * CRIT_CHANCE_PER_SID)
    }

    fun buildFighter(
        speciesId: String,
        displayName: String,
        level: Int,
        iucnCategory: IucnCategory,
        sequenceIdentityPercent: Double
    ): FighterStats {
        val bonus = rarityBonus(iucnCategory)
        val boost = alignmentBoost(sequenceIdentityPercent)

        val maxHp = ((BASE_HP + level * HP_PER_LEVEL + bonus) * 1.0).roundToInt()
        val atk = (((BASE_ATK + level * ATK_PER_LEVEL + bonus) * boost)).roundToInt()
        val def = (((BASE_DEF + level * DEF_PER_LEVEL + bonus) * boost)).roundToInt()

        return FighterStats(
            speciesId = speciesId,
            name = displayName,
            level = level,
            maxHp = maxHp,
            atk = atk,
            def = def,
            alignmentBoost = boost,
            moves = buildMoveset(sequenceIdentityPercent)
        )
    }

    fun buildMoveset(sequenceIdentityPercent: Double): List<MoveDefinition> {
        val sId = sequenceIdentityMultiplier(sequenceIdentityPercent)
        return listOf(
            MoveDefinition(
                id = "m_conserved",
                name = "Conserved Strike",
                power = (50 + (sId * 20)).roundToInt(),
                accuracy = 1.0,
                category = MoveCategory.CONSERVED
            ),
            MoveDefinition(
                id = "m_mutation",
                name = "Point Mutation",
                power = (65 * (0.7 + sId * 0.6)).roundToInt(),
                accuracy = 0.85,
                category = MoveCategory.MUTATION
            ),
            MoveDefinition(
                id = "m_shield",
                name = "Frameshift Shield",
                power = 0,
                accuracy = 1.0,
                category = MoveCategory.SHIELD
            ),
            MoveDefinition(
                id = "m_burst",
                name = "Deletion Burst",
                power = 120,
                accuracy = 0.75,
                category = MoveCategory.BURST
            )
        )
    }

    /** Frameshift Shield damage reduction: 40% scaled by S_id */
    fun shieldDamageReduction(sequenceIdentityPercent: Double): Double {
        val sId = sequenceIdentityMultiplier(sequenceIdentityPercent)
        return round3(0.40 * sId)
    }

    /** Point Mutation base power variance: 70%-130% of move base power */
    fun pointMutationVariance(basePower: Int, randomFactor: Double): Int {
        val clampedFactor = randomFactor.coerceIn(0.70, 1.30)
        return (basePower * clampedFactor).roundToInt()
    }

    private fun round3(value: Double): Double = Math.round(value * 1000.0) / 1000.0
}