package com.biodex.server.bio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.UUID
import kotlin.random.Random

data class ClustalSequenceInput(
    val speciesId: String,
    val header: String,
    val fasta: String
)

data class ClustalRunResult(
    val alignedFastaText: String,
    val pimText: String?,
    val usedMock: Boolean
)

class ClustalOmegaExecutionException(message: String) : Exception(message)

object ClustalOmegaRunner {

    private const val TEMP_DIR_PATH = "/tmp/biodex_clustal"
    private const val TIMEOUT_MS = 5000L
    private const val MAX_SEQUENCES = 10

    private val mockModeEnabled: Boolean
        get() = System.getenv("ENABLE_MOCK_CLUSTAL")?.equals("true", ignoreCase = true) == true

    suspend fun runAlignment(
        sequences: List<ClustalSequenceInput>,
        sequenceType: String
    ): ClustalRunResult {
        if (sequences.size > MAX_SEQUENCES) {
            throw ClustalOmegaExecutionException(
                "Batch size ${sequences.size} exceeds maximum of $MAX_SEQUENCES sequences per request."
            )
        }

        if (mockModeEnabled) {
            return generateMockAlignment(sequences, sequenceType)
        }

        return runRealClustalOmega(sequences)
    }

    private suspend fun runRealClustalOmega(
        sequences: List<ClustalSequenceInput>
    ): ClustalRunResult = withContext(Dispatchers.IO) {
        val tempDir = File(TEMP_DIR_PATH).apply { mkdirs() }
        val runId = UUID.randomUUID().toString()
        val inputFile = File(tempDir, "input_$runId.fasta")
        val outputFile = File(tempDir, "output_$runId.fasta")
        val pimFile = File(tempDir, "output_$runId.pim")

        try {
            inputFile.writeText(buildMultiFasta(sequences))

            val command = listOf(
                "clustalo",
                "-i", inputFile.absolutePath,
                "-o", outputFile.absolutePath,
                "--pim-out=${pimFile.absolutePath}",
                "--force",
                "--outfmt=fasta"
            )

            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()

            val completed = withTimeoutOrNull(TIMEOUT_MS) {
                withContext(Dispatchers.IO) { process.waitFor() }
            }

            if (completed == null) {
                process.destroyForcibly()
                throw ClustalOmegaExecutionException("Clustal Omega execution exceeded ${TIMEOUT_MS}ms timeout.")
            }

            if (completed != 0) {
                val errorOutput = process.inputStream.bufferedReader().readText()
                throw ClustalOmegaExecutionException("clustalo exited with code $completed: $errorOutput")
            }

            if (!outputFile.exists()) {
                throw ClustalOmegaExecutionException("clustalo did not produce an output alignment file.")
            }

            val alignedFastaText = outputFile.readText()
            val pimText = if (pimFile.exists()) pimFile.readText() else null

            ClustalRunResult(
                alignedFastaText = alignedFastaText,
                pimText = pimText,
                usedMock = false
            )
        } finally {
            inputFile.delete()
            outputFile.delete()
            pimFile.delete()
        }
    }

    private fun buildMultiFasta(sequences: List<ClustalSequenceInput>): String {
        return sequences.joinToString("\n") { seq ->
            val header = if (seq.header.startsWith(">")) seq.header else ">${seq.header}"
            "$header\n${seq.fasta}"
        }
    }

    // --- Mock fallback engine ---

    private fun generateMockAlignment(
        sequences: List<ClustalSequenceInput>,
        sequenceType: String
    ): ClustalRunResult {
        val maxLen = sequences.maxOf { it.fasta.length }
        val paddedSequences = sequences.map { seq ->
            seq to padWithGaps(seq.fasta, maxLen)
        }

        val alignedFasta = paddedSequences.joinToString("\n") { (seq, aligned) ->
            val header = if (seq.header.startsWith(">")) seq.header else ">${seq.header}"
            "$header\n$aligned"
        }

        val pimText = buildMockPim(sequences)

        return ClustalRunResult(
            alignedFastaText = alignedFasta,
            pimText = pimText,
            usedMock = true
        )
    }

    private fun padWithGaps(sequence: String, targetLength: Int): String {
        if (sequence.length >= targetLength) return sequence
        val gapsNeeded = targetLength - sequence.length
        // Insert gaps at a pseudo-random-but-deterministic-ish position for mock realism
        val insertPos = (sequence.length / 2).coerceAtLeast(1)
        val gapChars = "-".repeat(gapsNeeded)
        return sequence.substring(0, insertPos) + gapChars + sequence.substring(insertPos)
    }

    private fun buildMockPim(sequences: List<ClustalSequenceInput>): String {
        val lines = mutableListOf<String>()
        lines.add(" ${sequences.size}")
        sequences.forEachIndexed { i, seqA ->
            val row = StringBuilder(seqA.speciesId.padEnd(20))
            sequences.forEachIndexed { j, seqB ->
                val identity = if (i == j) 100.0 else mockIdentityScore(seqA.fasta, seqB.fasta)
                row.append(String.format("%6.2f", identity))
            }
            lines.add(row.toString())
        }
        return lines.joinToString("\n")
    }

    private fun mockIdentityScore(a: String, b: String): Double {
        val minLen = minOf(a.length, b.length)
        if (minLen == 0) return 0.0
        var matches = 0
        for (i in 0 until minLen) {
            if (a[i] == b[i]) matches++
        }
        val baseIdentity = (matches.toDouble() / minLen) * 100.0
        // Add slight deterministic jitter so mock data isn't suspiciously uniform
        val jitter = Random(a.hashCode() xor b.hashCode()).nextDouble(-3.0, 3.0)
        return (baseIdentity + jitter).coerceIn(0.0, 100.0)
    }
}