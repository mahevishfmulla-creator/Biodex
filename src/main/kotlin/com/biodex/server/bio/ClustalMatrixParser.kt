package com.biodex.server.bio

data class IdentityMatrixEntry(
    val speciesA: String,
    val speciesB: String,
    val identityPercent: Double
)

data class ParsedAlignmentResult(
    val alignedFasta: String,
    val identityMatrix: List<IdentityMatrixEntry>,
    val averageIdentityPercent: Double,
    val conservedRegionsCount: Int,
    val conservedMotifString: String
)

object ClustalMatrixParser {

    /**
     * Parses raw clustalo output (aligned FASTA + optional .pim text) into a structured result.
     * speciesIdByHeader maps FASTA header (without '>') -> original speciesId, since clustalo
     * output uses the header we supplied as the sequence identifier.
     */
    fun parse(
        alignedFastaText: String,
        pimText: String?,
        speciesIdByHeader: Map<String, String>
    ): ParsedAlignmentResult {
        val alignedSequences = parseFastaBlocks(alignedFastaText)

        val identityMatrix = if (pimText != null) {
            parsePimText(pimText, speciesIdByHeader)
        } else {
            computeIdentityMatrixFromAlignment(alignedSequences, speciesIdByHeader)
        }

        val averageIdentity = if (identityMatrix.isEmpty()) {
            0.0
        } else {
            identityMatrix.map { it.identityPercent }.average()
        }

        val motifString = buildConservedMotifString(alignedSequences.values.toList())
        val conservedCount = motifString.count { it == '*' }

        return ParsedAlignmentResult(
            alignedFasta = alignedFastaText.trim(),
            identityMatrix = identityMatrix,
            averageIdentityPercent = roundTo1Decimal(averageIdentity),
            conservedRegionsCount = conservedCount,
            conservedMotifString = motifString
        )
    }

    private fun parseFastaBlocks(fastaText: String): LinkedHashMap<String, String> {
        val result = LinkedHashMap<String, String>()
        var currentHeader: String? = null
        val currentSeq = StringBuilder()

        fastaText.lines().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith(">")) {
                currentHeader?.let { result[it] = currentSeq.toString() }
                currentHeader = line.removePrefix(">").trim()
                currentSeq.clear()
            } else {
                currentSeq.append(line)
            }
        }
        currentHeader?.let { result[it] = currentSeq.toString() }
        return result
    }

    private fun parsePimText(
        pimText: String,
        speciesIdByHeader: Map<String, String>
    ): List<IdentityMatrixEntry> {
        val lines = pimText.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.matches(Regex("^\\d+$")) }

        val labels = mutableListOf<String>()
        val scores = mutableListOf<List<Double>>()

        for (line in lines) {
            val parts = line.split(Regex("\\s+"))
            if (parts.size < 2) continue
            val label = parts[0]
            val values = parts.drop(1).mapNotNull { it.toDoubleOrNull() }
            if (values.isEmpty()) continue
            labels.add(label)
            scores.add(values)
        }

        val entries = mutableListOf<IdentityMatrixEntry>()
        val seen = mutableSetOf<Pair<Int, Int>>()

        for (i in labels.indices) {
            for (j in labels.indices) {
                if (i >= j) continue
                if ((i to j) in seen) continue
                seen.add(i to j)
                val identity = scores.getOrNull(i)?.getOrNull(j) ?: continue
                entries.add(
                    IdentityMatrixEntry(
                        speciesA = speciesIdByHeader[labels[i]] ?: labels[i],
                        speciesB = speciesIdByHeader[labels[j]] ?: labels[j],
                        identityPercent = roundTo1Decimal(identity)
                    )
                )
            }
        }
        return entries
    }

    private fun computeIdentityMatrixFromAlignment(
        alignedSequences: Map<String, String>,
        speciesIdByHeader: Map<String, String>
    ): List<IdentityMatrixEntry> {
        val headers = alignedSequences.keys.toList()
        val entries = mutableListOf<IdentityMatrixEntry>()

        for (i in headers.indices) {
            for (j in headers.indices) {
                if (i >= j) continue
                val seqA = alignedSequences[headers[i]] ?: continue
                val seqB = alignedSequences[headers[j]] ?: continue
                val identity = pairwiseIdentity(seqA, seqB)
                entries.add(
                    IdentityMatrixEntry(
                        speciesA = speciesIdByHeader[headers[i]] ?: headers[i],
                        speciesB = speciesIdByHeader[headers[j]] ?: headers[j],
                        identityPercent = roundTo1Decimal(identity)
                    )
                )
            }
        }
        return entries
    }

    private fun pairwiseIdentity(seqA: String, seqB: String): Double {
        val length = minOf(seqA.length, seqB.length)
        if (length == 0) return 0.0
        var matches = 0
        var comparable = 0
        for (i in 0 until length) {
            val a = seqA[i]
            val b = seqB[i]
            if (a == '-' || b == '-') continue
            comparable++
            if (a == b) matches++
        }
        if (comparable == 0) return 0.0
        return (matches.toDouble() / comparable) * 100.0
    }

    /**
     * Builds a Clustal-style consensus line:
     * '*' = fully conserved column, '.' = semi-conserved (majority match), ' ' = mismatch/gap
     */
    private fun buildConservedMotifString(alignedSeqs: List<String>): String {
        if (alignedSeqs.isEmpty()) return ""
        val alignmentLength = alignedSeqs.minOf { it.length }
        val motif = StringBuilder()

        for (col in 0 until alignmentLength) {
            val chars = alignedSeqs.map { it[col] }
            val nonGapChars = chars.filter { it != '-' }

            when {
                nonGapChars.isEmpty() -> motif.append(' ')
                nonGapChars.all { it == nonGapChars.first() } && nonGapChars.size == chars.size ->
                    motif.append('*')
                nonGapChars.groupingBy { it }.eachCount().values.maxOrNull() ?: 0 >= (chars.size / 2).coerceAtLeast(1) ->
                    motif.append('.')
                else -> motif.append(' ')
            }
        }
        return motif.toString()
    }

    private fun roundTo1Decimal(value: Double): Double {
        return Math.round(value * 10.0) / 10.0
    }
}