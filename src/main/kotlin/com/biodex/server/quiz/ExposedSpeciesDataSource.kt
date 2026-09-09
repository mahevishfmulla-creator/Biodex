package com.biodex.server.quiz

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.javatime.timestamp
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Instant

// --- Exposed table definitions matching Account 6's schema.sql ---

object SpeciesTable : Table("species") {
    val id = varchar("id", 50)
    val commonName = varchar("common_name", 100)
    val scientificName = varchar("scientific_name", 100)
    val habitat = varchar("habitat", 50)
    val iucnStatus = varchar("iucn_status", 5)
    val fastaSequence = text("fasta_sequence").nullable()
    override val primaryKey = PrimaryKey(id)
}

object TaxonomyTable : Table("taxonomy") {
    val speciesId = varchar("species_id", 50).references(SpeciesTable.id)
    val kingdom = varchar("kingdom", 50)
    val phylum = varchar("phylum", 50)
    val className = varchar("class_name", 50)
    val orderName = varchar("order_name", 50)
    val family = varchar("family", 50)
    val genus = varchar("genus", 50)
    override val primaryKey = PrimaryKey(speciesId)
}

object UserCapturesTable : Table("user_captures") {
    val userId = varchar("user_id", 50)
    val speciesId = varchar("species_id", 50).references(SpeciesTable.id)
    val capturedAt = timestamp("captured_at").defaultExpression(CurrentTimestamp())
    override val primaryKey = PrimaryKey(userId, speciesId)
}

object QuizReviewStateTable : Table("quiz_review_state") {
    val userId = varchar("user_id", 50)
    val speciesId = varchar("species_id", 50).references(SpeciesTable.id)
    val repetitionCount = integer("repetition_count").default(0)
    val easeFactor = double("ease_factor").default(2.5)
    val intervalDays = integer("interval_days").default(0)
    val masteryPercent = integer("mastery_percent").default(0)
    val streakCount = integer("streak_count").default(0)
    val nextReviewDue = timestamp("next_review_due").defaultExpression(CurrentTimestamp())
    val lastReviewedAt = timestamp("last_reviewed_at").nullable()
    override val primaryKey = PrimaryKey(userId, speciesId)
}

/**
 * Exposed-backed implementation of SpeciesDataSource, joining `species` + `taxonomy`
 * and using `user_captures` / `quiz_review_state` for per-user progress.
 */
class ExposedSpeciesDataSource : SpeciesDataSource {

    override fun getSpeciesDueForReview(userId: String, limit: Int): List<SpeciesRecord> = transaction {
        val now = Instant.now()

        (QuizReviewStateTable innerJoin SpeciesTable innerJoin TaxonomyTable)
            .select {
                (QuizReviewStateTable.userId eq userId) and
                    (QuizReviewStateTable.nextReviewDue lessEq now)
            }
            .orderBy(QuizReviewStateTable.nextReviewDue)
            .limit(limit)
            .map { it.toSpeciesRecord() }
    }

    override fun getCapturedSpecies(userId: String): List<SpeciesRecord> = transaction {
        (UserCapturesTable innerJoin SpeciesTable innerJoin TaxonomyTable)
            .select { UserCapturesTable.userId eq userId }
            .orderBy(UserCapturesTable.capturedAt)
            .map { it.toSpeciesRecord() }
    }

    override fun getOnboardingSpecies(limit: Int): List<SpeciesRecord> = transaction {
        // Fallback pool for brand-new users: species with the most complete records
        // (has FASTA + full taxonomy), so early questions are always answerable.
        (SpeciesTable innerJoin TaxonomyTable)
            .selectAll()
            .limit(limit)
            .map { it.toSpeciesRecord() }
    }

    override fun getDistractorCandidates(
        kingdom: String,
        phylum: String,
        excludeSpeciesId: String,
        limit: Int
    ): List<SpeciesRecord> = transaction {
        (SpeciesTable innerJoin TaxonomyTable)
            .select {
                (TaxonomyTable.kingdom eq kingdom) and
                    (TaxonomyTable.phylum eq phylum) and
                    (SpeciesTable.id neq excludeSpeciesId)
            }
            .limit(limit)
            .map { it.toSpeciesRecord() }
    }

    private fun org.jetbrains.exposed.sql.ResultRow.toSpeciesRecord(): SpeciesRecord {
        return SpeciesRecord(
            id = this[SpeciesTable.id],
            commonName = this[SpeciesTable.commonName],
            habitat = this[SpeciesTable.habitat],
            iucnStatus = this[SpeciesTable.iucnStatus],
            fastaSequence = this[SpeciesTable.fastaSequence],
            kingdom = this[TaxonomyTable.kingdom],
            phylum = this[TaxonomyTable.phylum],
            className = this[TaxonomyTable.className],
            orderName = this[TaxonomyTable.orderName],
            family = this[TaxonomyTable.family],
            genus = this[TaxonomyTable.genus]
        )
    }

    /**
     * Persists SM-2 review state after an answer is submitted.
     * Call this from QuizRoutes after SpacedRepetitionEngine.applyReview(...).
     */
    fun upsertReviewState(state: ReviewCardState) = transaction {
        val exists = QuizReviewStateTable
            .select { (QuizReviewStateTable.userId eq state.userId) and (QuizReviewStateTable.speciesId eq state.speciesId) }
            .count() > 0

        if (exists) {
            QuizReviewStateTable.update({
                (QuizReviewStateTable.userId eq state.userId) and (QuizReviewStateTable.speciesId eq state.speciesId)
            }) {
                it[repetitionCount] = state.repetitionCount
                it[easeFactor] = state.easeFactor
                it[intervalDays] = state.intervalDays
                it[masteryPercent] = state.masteryPercent
                it[streakCount] = state.streakCount
                it[nextReviewDue] = state.nextReviewDue
                it[lastReviewedAt] = state.lastReviewedAt
            }
        } else {
            QuizReviewStateTable.insert {
                it[userId] = state.userId
                it[speciesId] = state.speciesId
                it[repetitionCount] = state.repetitionCount
                it[easeFactor] = state.easeFactor
                it[intervalDays] = state.intervalDays
                it[masteryPercent] = state.masteryPercent
                it[streakCount] = state.streakCount
                it[nextReviewDue] = state.nextReviewDue
                it[lastReviewedAt] = state.lastReviewedAt
            }
        }
    }

    fun loadReviewState(userId: String, speciesId: String): ReviewCardState? = transaction {
        QuizReviewStateTable
            .select { (QuizReviewStateTable.userId eq userId) and (QuizReviewStateTable.speciesId eq speciesId) }
            .map {
                ReviewCardState(
                    speciesId = it[QuizReviewStateTable.speciesId],
                    userId = it[QuizReviewStateTable.userId],
                    repetitionCount = it[QuizReviewStateTable.repetitionCount],
                    easeFactor = it[QuizReviewStateTable.easeFactor],
                    intervalDays = it[QuizReviewStateTable.intervalDays],
                    masteryPercent = it[QuizReviewStateTable.masteryPercent],
                    streakCount = it[QuizReviewStateTable.streakCount],
                    nextReviewDue = it[QuizReviewStateTable.nextReviewDue],
                    lastReviewedAt = it[QuizReviewStateTable.lastReviewedAt]
                )
            }
            .firstOrNull()
    }

    fun countDueToday(userId: String): Int = transaction {
        val now = Instant.now()
        QuizReviewStateTable
            .select { (QuizReviewStateTable.userId eq userId) and (QuizReviewStateTable.nextReviewDue lessEq now) }
            .count()
            .toInt()
    }

    fun loadAllReviewStates(userId: String): List<ReviewCardState> = transaction {
        QuizReviewStateTable
            .select { QuizReviewStateTable.userId eq userId }
            .map {
                ReviewCardState(
                    speciesId = it[QuizReviewStateTable.speciesId],
                    userId = it[QuizReviewStateTable.userId],
                    repetitionCount = it[QuizReviewStateTable.repetitionCount],
                    easeFactor = it[QuizReviewStateTable.easeFactor],
                    intervalDays = it[QuizReviewStateTable.intervalDays],
                    masteryPercent = it[QuizReviewStateTable.masteryPercent],
                    streakCount = it[QuizReviewStateTable.streakCount],
                    nextReviewDue = it[QuizReviewStateTable.nextReviewDue],
                    lastReviewedAt = it[QuizReviewStateTable.lastReviewedAt]
                )
            }
    }
}
