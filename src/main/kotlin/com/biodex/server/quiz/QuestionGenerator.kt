package com.biodex.server.quiz

import kotlin.random.Random

enum class QuestionType {
    TAXONOMY_FAMILY, TAXONOMY_ORDER, TAXONOMY_GENUS,
    CONSERVATION_STATUS,
    ECOLOGY_HABITAT,
    BIOINFORMATICS_SEQUENCE_LENGTH
}

/** Minimal projection of Account 6's `species` + `taxonomy` join needed for question generation. */
data class SpeciesRecord(
    val id: String,
    val commonName: String,
    val habitat: String,
    val iucnStatus: String,
    val fastaSequence: String?,
    val kingdom: String,
    val phylum: String,
    val className: String,
    val orderName: String,
    val family: String,
    val genus: String,
)

data class GeneratedQuestion(
    val questionId: String,
    val speciesId: String,
    val targetSpeciesName: String,
    val questionType: QuestionType,
    val prompt: String,
    val options: List<String>,
    val correctAnswerIndex: Int,
    val explanation: String
)

/**
 * Abstraction over Account 6's species/taxonomy persistence layer.
 * Wire this to the actual Exposed/Ktorm repository once available.
 */
interface SpeciesDataSource {
    fun getSpeciesDueForReview(userId: String, limit: Int): List<SpeciesRecord>
    fun getCapturedSpecies(userId: String): List<SpeciesRecord>
    fun getOnboardingSpecies(limit: Int): List<SpeciesRecord>
    fun getDistractorCandidates(kingdom: String, phylum: String, excludeSpeciesId: String, limit: Int): List<SpeciesRecord>
}

class QuestionGenerationException(message: String) : Exception(message)

class QuestionGenerator(private val dataSource: SpeciesDataSource) {

    /**
     * Returns the highest-priority question for a user: a due review card if one exists,
     * otherwise a fallback onboarding/discovery question so new users with zero captures
     * still get a usable quiz experience.
     */
    fun nextQuestionForUser(userId: String): GeneratedQuestion {
        val dueSpecies = dataSource.getSpeciesDueForReview(userId, limit = 1)
        val target = dueSpecies.firstOrNull()
            ?: dataSource.getCapturedSpecies(userId).firstOrNull()
            ?: dataSource.getOnboardingSpecies(limit = 1).firstOrNull()
            ?: throw QuestionGenerationException("No species records available to generate a question.")

        val questionType = pickQuestionType(target)
        return buildQuestion(target, questionType)
    }

    private fun pickQuestionType(species: SpeciesRecord): QuestionType {
        val candidates = mutableListOf(
            QuestionType.TAXONOMY_FAMILY,
            QuestionType.CONSERVATION_STATUS,
            QuestionType.ECOLOGY_HABITAT
        )
        if (!species.fastaSequence.isNullOrBlank()) {
            candidates.add(QuestionType.BIOINFORMATICS_SEQUENCE_LENGTH)
        }
        return candidates.random()
    }

    private fun buildQuestion(target: SpeciesRecord, type: QuestionType): GeneratedQuestion {
        return when (type) {
            QuestionType.TAXONOMY_FAMILY -> buildTaxonomyQuestion(target, "family", target.family)
            QuestionType.TAXONOMY_ORDER -> buildTaxonomyQuestion(target, "order", target.orderName)
            QuestionType.TAXONOMY_GENUS -> buildTaxonomyQuestion(target, "genus", target.genus)
            QuestionType.CONSERVATION_STATUS -> buildConservationQuestion(target)
            QuestionType.ECOLOGY_HABITAT -> buildEcologyQuestion(target)
            QuestionType.BIOINFORMATICS_SEQUENCE_LENGTH -> buildBioinformaticsQuestion(target)
        }
    }

    private fun buildTaxonomyQuestion(target: SpeciesRecord, rankLabel: String, correctValue: String): GeneratedQuestion {
        val distractorRecords = dataSource.getDistractorCandidates(
            kingdom = target.kingdom,
            phylum = target.phylum,
            excludeSpeciesId = target.id,
            limit = 6
        )
        val distractorValues = distractorRecords
            .map { rankValue(it, rankLabel) }
            .filter { it.isNotBlank() && (it != correctValue) }
            .distinct()
            .shuffled()
            .take(3)

        val (options, correctIndex) = assembleOptions(correctValue, distractorValues)

        return GeneratedQuestion(
            questionId = generateQuestionId(),
            speciesId = target.id,
            targetSpeciesName = target.commonName,
            questionType = QuestionType.TAXONOMY_FAMILY,
            prompt = "Which taxonomic $rankLabel does the ${target.commonName} belong to?",
            options = options,
            correctAnswerIndex = correctIndex,
            explanation = "The ${target.commonName} belongs to the $rankLabel $correctValue."
        )
    }

    private fun rankValue(record: SpeciesRecord, rankLabel: String): String = when (rankLabel) {
        "family" -> record.family
        "order" -> record.orderName
        "genus" -> record.genus
        else -> ""
    }

    private fun buildConservationQuestion(target: SpeciesRecord): GeneratedQuestion {
        val allStatuses = listOf("LC", "NT", "VU", "EN", "CR", "EW", "EX")
        val distractorValues = allStatuses
            .filter { it != target.iucnStatus }
            .shuffled()
            .take(3)

        val (options, correctIndex) = assembleOptions(target.iucnStatus, distractorValues)

        return GeneratedQuestion(
            questionId = generateQuestionId(),
            speciesId = target.id,
            targetSpeciesName = target.commonName,
            questionType = QuestionType.CONSERVATION_STATUS,
            prompt = "What is the IUCN conservation status of the ${target.commonName}?",
            options = options,
            correctAnswerIndex = correctIndex,
            explanation = "The ${target.commonName} is currently classified as ${target.iucnStatus}."
        )
    }

    private fun buildEcologyQuestion(target: SpeciesRecord): GeneratedQuestion {
        val distractorRecords = dataSource.getDistractorCandidates(
            kingdom = target.kingdom,
            phylum = target.phylum,
            excludeSpeciesId = target.id,
            limit = 6
        )
        val distractorValues = distractorRecords
            .map { it.habitat }
            .filter { it.isNotBlank() && it != target.habitat }
            .distinct()
            .shuffled()
            .take(3)

        val (options, correctIndex) = assembleOptions(target.habitat, distractorValues)

        return GeneratedQuestion(
            questionId = generateQuestionId(),
            speciesId = target.id,
            targetSpeciesName = target.commonName,
            questionType = QuestionType.ECOLOGY_HABITAT,
            prompt = "What is the primary habitat of the ${target.commonName}?",
            options = options,
            correctAnswerIndex = correctIndex,
            explanation = "The ${target.commonName}'s primary habitat is ${target.habitat}."
        )
    }

    private fun buildBioinformaticsQuestion(target: SpeciesRecord): GeneratedQuestion {
        val sequence = target.fastaSequence.orEmpty()
        val correctLength = sequence.length

        val distractorLengths = generateLengthDistractors(correctLength)
        val (options, correctIndex) = assembleOptions(
            correctLength.toString(),
            distractorLengths.map { it.toString() }
        )

        return GeneratedQuestion(
            questionId = generateQuestionId(),
            speciesId = target.id,
            targetSpeciesName = target.commonName,
            questionType = QuestionType.BIOINFORMATICS_SEQUENCE_LENGTH,
            prompt = "How many nucleotides long is the ${target.commonName}'s recorded sequence?",
            options = options,
            correctAnswerIndex = correctIndex,
            explanation = "The ${target.commonName}'s recorded sequence is $correctLength nucleotides long."
        )
    }

    private fun generateLengthDistractors(correctLength: Int): List<Int> {
        val offsets = listOf(-6, -3, 4, 7, 10).shuffled().take(3)
        return offsets.map { (correctLength + it).coerceAtLeast(1) }.distinct().let {
            if (it.size < 3) {
                (it + listOf(correctLength + 15, correctLength + 22, correctLength + 30)).asSequence().distinct().take(3).toList()
            } else it
        }
    }

    /** Shuffles correct answer among distractors and returns (options, correctIndex). Pads if fewer than 3 distractors found. */
    private fun assembleOptions(correctValue: String, distractors: List<String>): Pair<List<String>, Int> {
        val padded = if (distractors.size < 3) {
            distractors + List(3 - distractors.size) { "Unknown" }
        } else {
            distractors
        }
        val allOptions = (padded + correctValue).shuffled(Random(System.nanoTime()))
        val correctIndex = allOptions.indexOf(correctValue)
        return allOptions to correctIndex
    }

    private fun generateQuestionId(): String = "q_${System.currentTimeMillis()}${Random.nextInt(100, 999)}"
}