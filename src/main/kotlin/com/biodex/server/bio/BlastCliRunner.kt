package com.biodex.server.bio

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * BlastCliRunner
 *
 * Encapsulates invocation of the local NCBI BLAST+ CLI binaries (blastn / blastp).
 * Handles temp query file lifecycle, process execution with a hard timeout, and
 * falls back to a realistic mock generator when binaries are unavailable or
 * ENABLE_MOCK_BLAST=true is set in the environment.
 */

enum class BlastType {
    BLASTN,
    BLASTP
}

data class BlastCliResult(
    val rawOutput: String,
    val wasMocked: Boolean,
    val exitCode: Int
)

class BlastExecutionException(message: String, cause: Throwable? = null) : Exception(message, cause)

class BlastCliRunner(
    private val nuclDbPath: String = "/data/blast/biodex_nucl",
    private val protDbPath: String = "/data/blast/biodex_prot",
    private val tempDir: String = "/tmp/biodex_blast_queries",
    private val timeoutSeconds: Long = 5
) {

    private val mockModeEnabled: Boolean
        get() = System.getenv("ENABLE_MOCK_BLAST")?.equals("true", ignoreCase = true) == true

    init {
        File(tempDir).mkdirs()
    }

    /**
     * Runs a BLAST search for the given FASTA sequence and returns the raw
     * JSON (-outfmt 15) stdout, or a mocked equivalent if binaries are
     * unavailable or mock mode is forced on.
     */
    suspend fun runBlast(
        fastaSequence: String,
        type: BlastType,
        evalueThreshold: Double
    ): BlastCliResult = withContext(Dispatchers.IO) {
        if (mockModeEnabled || !isBinaryAvailable(type)) {
            return@withContext BlastCliResult(
                rawOutput = generateMockOutput(fastaSequence, type),
                wasMocked = true,
                exitCode = 0
            )
        }

        val queryFile = writeTempFastaFile(fastaSequence)
        try {
            executeBlastProcess(queryFile, type, evalueThreshold)
        } finally {
            cleanupTempFile(queryFile)
        }
    }

    /** Diagnostic check used by GET /api/v1/blast/status */
    fun isBinaryAvailable(type: BlastType): Boolean {
        val binaryName = if (type == BlastType.BLASTN) "blastn" else "blastp"
        return try {
            val process = ProcessBuilder(binaryName, "-version")
                .redirectErrorStream(true)
                .start()
            val finished = process.waitFor(2, TimeUnit.SECONDS)
            finished && (process.exitValue() == 0)
        } catch (e: IOException) {
            false
        }
    }

    fun referenceDbStatus(): Map<String, Boolean> = mapOf(
        "nucleotideDbPresent" to File(nuclDbPath).exists(),
        "proteinDbPresent" to File(protDbPath).exists()
    )

    // ---------------------------------------------------------------------
    // Internal helpers
    // ---------------------------------------------------------------------

    private fun writeTempFastaFile(fastaSequence: String): File {
        val fileName = "query_${UUID.randomUUID()}.fasta"
        val file = File(tempDir, fileName)
        file.writeText(fastaSequence)
        return file
    }

    private fun cleanupTempFile(file: File) {
        try {
            if (file.exists()) file.delete()
        } catch (e: IOException) {
            // Best-effort cleanup; do not fail the request over this.
        }
    }

    private fun executeBlastProcess(
        queryFile: File,
        type: BlastType,
        evalueThreshold: Double
    ): BlastCliResult {
        val command = buildCommand(queryFile, type, evalueThreshold)

        val process = try {
            ProcessBuilder(command)
                .redirectErrorStream(false)
                .start()
        } catch (e: IOException) {
            throw BlastExecutionException("Failed to start BLAST process: ${e.message}", e)
        }

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            throw BlastExecutionException(
                "BLAST process exceeded ${timeoutSeconds}s timeout and was terminated"
            )
        }

        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exitCode = process.exitValue()

        if (exitCode != 0) {
            throw BlastExecutionException(
                "BLAST process exited with code $exitCode. stderr: $stderr"
            )
        }

        return BlastCliResult(rawOutput = stdout, wasMocked = false, exitCode = exitCode)
    }

    private fun buildCommand(
        queryFile: File,
        type: BlastType,
        evalueThreshold: Double
    ): List<String> {
        return when (type) {
            BlastType.BLASTN -> listOf(
                "blastn",
                "-db", nuclDbPath,
                "-query", queryFile.absolutePath,
                "-outfmt", "15",
                "-evalue", evalueThreshold.toString()
            )
            BlastType.BLASTP -> listOf(
                "blastp",
                "-db", protDbPath,
                "-query", queryFile.absolutePath,
                "-outfmt", "15",
                "-evalue", evalueThreshold.toString(),
                "-matrix", "BLOSUM62"
            )
        }
    }

    // ---------------------------------------------------------------------
    // Mock fallback generator
    // ---------------------------------------------------------------------

    /**
     * Generates a realistic-looking mock BLAST JSON (-outfmt 15 style) result
     * so downstream parsing/testing can proceed without local BLAST+ binaries.
     * Mock hits are deterministic-ish but vary identity slightly per call.
     */
    private fun generateMockOutput(fastaSequence: String, type: BlastType): String {
        val queryId = extractQueryId(fastaSequence)
        val querySeq = extractSequenceBody(fastaSequence)

        val mockSpecies = listOf(
            Triple("clouded-leopard", "Clouded Leopard", "Neofelis nebulosa"),
            Triple("amami-rabbit", "Amami Rabbit", "Pentalagus furnessi"),
            Triple("axolotl", "Axolotl", "Ambystoma mexicanum")
        )

        val hitCount = Random.nextInt(1, 3)
        val hitsJson = mockSpecies.shuffled().take(hitCount).mapIndexed { index, (id, common, scientific) ->
            val identity = if (index == 0) Random.nextDouble(95.0, 99.9) else Random.nextDouble(65.0, 90.0)
            val bitScore = Random.nextDouble(20.0, 60.0)
            val eValue = if (index == 0) 1.2e-12 else 4.0e-4
            """
            {
              "speciesId": "$id",
              "commonName": "$common",
              "scientificName": "$scientific",
              "identityPercent": ${"%.1f".format(identity)},
              "alignmentLength": ${querySeq.length},
              "eValue": $eValue,
              "bitScore": ${"%.1f".format(bitScore)},
              "queryAligned": "$querySeq",
              "subjectAligned": "$querySeq",
              "matchString": "${"|".repeat(querySeq.length)}"
            }
            """.trimIndent()
        }.joinToString(",\n")

        return """
        {
          "mock": true,
          "queryId": "$queryId",
          "blastType": "$type",
          "hits": [
        $hitsJson
          ]
        }
        """.trimIndent()
    }

    private fun extractQueryId(fastaSequence: String): String {
        val headerLine = fastaSequence.lineSequence().firstOrNull { it.startsWith(">") }
        return headerLine?.removePrefix(">")?.trim()?.ifBlank { "query_unknown" } ?: "query_unknown"
    }

    private fun extractSequenceBody(fastaSequence: String): String {
        return fastaSequence.lineSequence()
            .filterNot { it.startsWith(">") }
            .joinToString("") { it.trim() }
    }
}