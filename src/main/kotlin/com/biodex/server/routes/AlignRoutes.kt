package com.biodex.server.routes

import com.biodex.server.bio.ClustalMatrixParser
import com.biodex.server.bio.ClustalOmegaExecutionException
import com.biodex.server.bio.ClustalOmegaRunner
import com.biodex.server.bio.ClustalSequenceInput
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class ClustalSequenceRequestDto(
    val speciesId: String,
    val header: String,
    val fasta: String
)

@Serializable
data class ClustalAlignRequest(
    val sequences: List<ClustalSequenceRequestDto>,
    val sequenceType: String
)

@Serializable
data class IdentityMatrixEntryDto(
    val speciesA: String,
    val speciesB: String,
    val identityPercent: Double
)

@Serializable
data class ClustalAlignResponse(
    val alignmentId: String,
    val sequenceType: String,
    val averageIdentityPercent: Double,
    val alignedFasta: String,
    val identityMatrix: List<IdentityMatrixEntryDto>,
    val conservedRegionsCount: Int,
    val conservedMotifString: String
)

@Serializable
data class AlignHealthResponse(
    val binaryReady: Boolean,
    val mockModeEnabled: Boolean,
    val maxSequencesPerRequest: Int,
    val timeoutMs: Long
)

// data class ErrorResponse(val error: String)

private const val MAX_SEQUENCES = 10
private val VALID_SEQUENCE_TYPES = setOf("NUCLEOTIDE", "PROTEIN")

fun Route.alignRoutes() {
    route("/api/v1/align") {

        post("/clustal") {
            val request = try {
                call.receive<ClustalAlignRequest>()
            } catch (e: Exception) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Malformed request body: ${e.message}"))
                return@post
            }

            val validationError = validateRequest(request)
            if (validationError != null) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(validationError))
                return@post
            }

            try {
                val sequenceInputs = request.sequences.map {
                    ClustalSequenceInput(speciesId = it.speciesId, header = it.header, fasta = it.fasta)
                }

                val runResult = ClustalOmegaRunner.runAlignment(sequenceInputs, request.sequenceType)

                val speciesIdByHeader = request.sequences.associateBy(
                    { it.header.removePrefix(">").trim() },
                    { it.speciesId }
                )

                val parsed = ClustalMatrixParser.parse(
                    alignedFastaText = runResult.alignedFastaText,
                    pimText = runResult.pimText,
                    speciesIdByHeader = speciesIdByHeader
                )

                val response = ClustalAlignResponse(
                    alignmentId = "msa_${System.currentTimeMillis()}",
                    sequenceType = request.sequenceType,
                    averageIdentityPercent = parsed.averageIdentityPercent,
                    alignedFasta = parsed.alignedFasta,
                    identityMatrix = parsed.identityMatrix.map {
                        IdentityMatrixEntryDto(it.speciesA, it.speciesB, it.identityPercent)
                    },
                    conservedRegionsCount = parsed.conservedRegionsCount,
                    conservedMotifString = parsed.conservedMotifString
                )

                call.respond(HttpStatusCode.OK, response)
            } catch (e: ClustalOmegaExecutionException) {
                call.respond(HttpStatusCode.UnprocessableEntity, ErrorResponse(e.message ?: "Alignment execution failed."))
            } catch (e: Exception) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse("Unexpected error: ${e.message}"))
            }
        }

        get("/health") {
            val mockEnabled = System.getenv("ENABLE_MOCK_CLUSTAL")?.equals("true", ignoreCase = true) == true
            val binaryReady = mockEnabled || isClustalBinaryAvailable()

            call.respond(
                HttpStatusCode.OK,
                AlignHealthResponse(
                    binaryReady = binaryReady,
                    mockModeEnabled = mockEnabled,
                    maxSequencesPerRequest = MAX_SEQUENCES,
                    timeoutMs = 5000L
                )
            )
        }
    }
}

private fun validateRequest(request: ClustalAlignRequest): String? {
    if (request.sequenceType !in VALID_SEQUENCE_TYPES) {
        return "sequenceType must be one of $VALID_SEQUENCE_TYPES, got '${request.sequenceType}'."
    }
    if (request.sequences.isEmpty()) {
        return "At least one sequence is required."
    }
    if (request.sequences.size < 2) {
        return "Multi-sequence alignment requires at least 2 sequences; received ${request.sequences.size}."
    }
    if (request.sequences.size > MAX_SEQUENCES) {
        return "Maximum of $MAX_SEQUENCES sequences allowed per request; received ${request.sequences.size}."
    }
    request.sequences.firstOrNull { it.fasta.isBlank() }?.let {
        return "Sequence '${it.speciesId}' has an empty fasta payload."
    }
    val emptyIds = request.sequences.any { it.speciesId.isBlank() }
    if (emptyIds) {
        return "All sequences must include a non-blank speciesId."
    }
    return null
}

private fun isClustalBinaryAvailable(): Boolean {
    return try {
        val process = ProcessBuilder("which", "clustalo").start()
        process.waitFor() == 0
    } catch (e: Exception) {
        false
    }
}