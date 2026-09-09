package com.biodex.server.routes

import com.biodex.server.bio.BlastCliRunner
import com.biodex.server.bio.BlastExecutionException
import com.biodex.server.bio.BlastOutputParser
import com.biodex.server.bio.BlastParseException
import com.biodex.server.bio.BlastType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

/**
 * BlastRoutes
 *
 * REST endpoints for the BLAST Alignment Service (Account 8).
 *   POST /api/v1/blast/search  — run a BLAST search against the BioDex reference DB
 *   GET  /api/v1/blast/status  — diagnostic: binary availability + reference DB status
 */

@Serializable
data class BlastSearchRequest(
    val sequence: String,
    val type: String,
    val evalueThreshold: Double = 0.001,
    val maxHits: Int = 5
)

@Serializable
data class BlastStatusResponse(
    val blastnAvailable: Boolean,
    val blastpAvailable: Boolean,
    val nucleotideDbPresent: Boolean,
    val proteinDbPresent: Boolean,
    val mockModeActive: Boolean
)

@Serializable
data class BlastErrorResponse(
    val error: String,
    val detail: String? = null
)

fun Route.blastRoutes(blastCliRunner: BlastCliRunner = BlastCliRunner()) {

    post("/api/v1/blast/search") {
        val request = try {
            call.receive<BlastSearchRequest>()
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.BadRequest,
                BlastErrorResponse("INVALID_REQUEST_BODY", e.message)
            )
            return@post
        }

        // Validate FASTA header + sequence body
        val fastaValidationError = validateFasta(request.sequence)
        if (fastaValidationError != null) {
            call.respond(
                HttpStatusCode.BadRequest,
                BlastErrorResponse("INVALID_FASTA", fastaValidationError)
            )
            return@post
        }

        val blastType = when (request.type.uppercase()) {
            "BLASTN" -> BlastType.BLASTN
            "BLASTP" -> BlastType.BLASTP
            else -> {
                call.respond(
                    HttpStatusCode.BadRequest,
                    BlastErrorResponse("INVALID_TYPE", "type must be BLASTN or BLASTP, got '${request.type}'")
                )
                return@post
            }
        }

        if (request.maxHits <= 0) {
            call.respond(
                HttpStatusCode.BadRequest,
                BlastErrorResponse("INVALID_MAX_HITS", "maxHits must be greater than 0")
            )
            return@post
        }

        val queryId = extractQueryId(request.sequence)

        try {
            val cliResult = blastCliRunner.runBlast(
                fastaSequence = request.sequence,
                type = blastType,
                evalueThreshold = request.evalueThreshold
            )

            val response = BlastOutputParser.parse(
                rawOutput = cliResult.rawOutput,
                queryId = queryId,
                type = blastType,
                maxHits = request.maxHits
            )

            call.respond(HttpStatusCode.OK, response)
        } catch (e: BlastExecutionException) {
            call.respond(
                HttpStatusCode.GatewayTimeout,
                BlastErrorResponse("BLAST_EXECUTION_FAILED", e.message)
            )
        } catch (e: BlastParseException) {
            call.respond(
                HttpStatusCode.InternalServerError,
                BlastErrorResponse("BLAST_PARSE_FAILED", e.message)
            )
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.InternalServerError,
                BlastErrorResponse("UNEXPECTED_ERROR", e.message)
            )
        }
    }

    get("/api/v1/blast/status") {
        val dbStatus = blastCliRunner.referenceDbStatus()
        val mockActive = System.getenv("ENABLE_MOCK_BLAST")?.equals("true", ignoreCase = true) == true

        val response = BlastStatusResponse(
            blastnAvailable = blastCliRunner.isBinaryAvailable(BlastType.BLASTN),
            blastpAvailable = blastCliRunner.isBinaryAvailable(BlastType.BLASTP),
            nucleotideDbPresent = dbStatus["nucleotideDbPresent"] ?: false,
            proteinDbPresent = dbStatus["proteinDbPresent"] ?: false,
            mockModeActive = mockActive
        )

        call.respond(HttpStatusCode.OK, response)
    }
}

// ---------------------------------------------------------------------
// Validation helpers
// ---------------------------------------------------------------------

/** Returns an error message if the FASTA string is invalid, or null if valid. */
private fun validateFasta(sequence: String): String? {
    val trimmed = sequence.trim()
    if (trimmed.isEmpty()) {
        return "sequence must not be empty"
    }
    if (!trimmed.startsWith(">")) {
        return "sequence must be valid FASTA format starting with a '>' header line"
    }

    val lines = trimmed.lines()
    val header = lines.firstOrNull { it.startsWith(">") } ?: return "missing FASTA header"
    if (header.removePrefix(">").isBlank()) {
        return "FASTA header must not be empty (e.g. '>query_01')"
    }

    val body = lines.filterNot { it.startsWith(">") }.joinToString("") { it.trim() }
    if (body.isEmpty()) {
        return "FASTA sequence body must not be empty"
    }
    if (!body.matches(Regex("^[A-Za-z*\\-]+$"))) {
        return "FASTA sequence body contains invalid characters"
    }

    return null
}

private fun extractQueryId(fastaSequence: String): String {
    val headerLine = fastaSequence.lineSequence().firstOrNull { it.startsWith(">") }
    return headerLine?.removePrefix(">")?.trim()?.ifBlank { "query_unknown" } ?: "query_unknown"
}