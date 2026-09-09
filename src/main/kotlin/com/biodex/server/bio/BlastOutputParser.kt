package com.biodex.server.bio

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * BlastOutputParser
 *
 * Parses raw BLAST stdout (either -outfmt 15 JSON, our own mock JSON shape,
 * or -outfmt 6 tabular) into structured Kotlin data objects matching the
 * BioDex API contract (Section 2 of the Account 8 brief).
 */

@Serializable
data class BlastHit(
    val speciesId: String,
    val commonName: String,
    val scientificName: String,
    val identityPercent: Double,
    val alignmentLength: Int,
    val eValue: Double,
    val bitScore: Double,
    val queryAligned: String,
    val subjectAligned: String,
    val matchString: String
)

@Serializable
data class BlastSearchResponse(
    val queryId: String,
    val sequenceType: String,
    val totalHits: Int,
    val hits: List<BlastHit>
)

class BlastParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

object BlastOutputParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Species metadata lookup used to enrich tabular (-outfmt 6) results,
     * which only contain a subject ID (e.g. "clouded-leopard") and no
     * common/scientific name. In production this should be backed by the
     * shared species reference table/service rather than a static map.
     */
    private val speciesMetadata: Map<String, Pair<String, String>> = mapOf(
        "clouded-leopard" to ("Clouded Leopard" to "Neofelis nebulosa"),
        "amami-rabbit" to ("Amami Rabbit" to "Pentalagus furnessi"),
        "axolotl" to ("Axolotl" to "Ambystoma mexicanum")
    )

    /**
     * Entry point: parses either our mock JSON, standard -outfmt 15 JSON,
     * or tabular -outfmt 6 stdout, and builds the final API response.
     */
    fun parse(
        rawOutput: String,
        queryId: String,
        type: BlastType,
        maxHits: Int
    ): BlastSearchResponse {
        val trimmed = rawOutput.trim()
        val sequenceType = if (type == BlastType.BLASTN) "NUCLEOTIDE" else "PROTEIN"

        if (trimmed.isEmpty()) {
            return BlastSearchResponse(
                queryId = queryId,
                sequenceType = sequenceType,
                totalHits = 0,
                hits = emptyList()
            )
        }

        val hits = try {
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                parseJsonOutput(trimmed)
            } else {
                parseTabularOutput(trimmed)
            }
        } catch (e: Exception) {
            throw BlastParseException("Failed to parse BLAST output: ${e.message}", e)
        }

        val limitedHits = hits.sortedByDescending { it.bitScore }.take(maxHits)

        return BlastSearchResponse(
            queryId = queryId,
            sequenceType = sequenceType,
            totalHits = limitedHits.size,
            hits = limitedHits
        )
    }

    // ---------------------------------------------------------------------
    // JSON parsing (mock output + -outfmt 15)
    // ---------------------------------------------------------------------

    private fun parseJsonOutput(rawJson: String): List<BlastHit> {
        val root = json.parseToJsonElement(rawJson).jsonObject

        // Our own mock generator's shape: { "mock": true, "hits": [...] }
        if (root.containsKey("mock") && root.containsKey("hits")) {
            return parseMockHits(root["hits"]!!.jsonArray)
        }

        // Standard NCBI -outfmt 15 shape: { "BlastOutput2": [ { "report": { "results": { "search": { "hits": [...] } } } } ] }
        val blastOutput2 = root["BlastOutput2"]?.jsonArray
        if (!blastOutput2.isNullOrEmpty()) {
            return parseNcbiJsonHits(blastOutput2)
        }

        throw BlastParseException("Unrecognized BLAST JSON structure")
    }

    private fun parseMockHits(hitsArray: JsonArray): List<BlastHit> {
        return hitsArray.map { element ->
            val obj = element.jsonObject
            BlastHit(
                speciesId = obj.stringField("speciesId"),
                commonName = obj.stringField("commonName"),
                scientificName = obj.stringField("scientificName"),
                identityPercent = obj.doubleField("identityPercent"),
                alignmentLength = obj.intField("alignmentLength"),
                eValue = obj.doubleField("eValue"),
                bitScore = obj.doubleField("bitScore"),
                queryAligned = obj.stringField("queryAligned"),
                subjectAligned = obj.stringField("subjectAligned"),
                matchString = obj.stringField("matchString")
            )
        }
    }

    private fun parseNcbiJsonHits(blastOutput2: JsonArray): List<BlastHit> {
        val hits = mutableListOf<BlastHit>()

        for (outputEntry in blastOutput2) {
            val report = outputEntry.jsonObject["report"]?.jsonObject ?: continue
            val results = report["results"]?.jsonObject ?: continue
            val search = results["search"]?.jsonObject ?: continue
            val hitList = search["hits"]?.jsonArray ?: continue

            for (hitElement in hitList) {
                val hitObj = hitElement.jsonObject
                val description = hitObj["description"]?.jsonArray?.firstOrNull()?.jsonObject
                val speciesId = description?.get("id")?.jsonPrimitive?.contentOrNull
                    ?: description?.get("accession")?.jsonPrimitive?.contentOrNull
                    ?: "unknown-species"
                val title = description?.get("title")?.jsonPrimitive?.contentOrNull ?: speciesId

                val hsp = hitObj["hsps"]?.jsonArray?.firstOrNull()?.jsonObject
                    ?: throw BlastParseException("Hit for $speciesId missing hsps block")

                val identity = hsp["identity"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                val alignLen = hsp["align_len"]?.jsonPrimitive?.intOrNull ?: 0
                val identityPercent = if (alignLen > 0) (identity / alignLen) * 100.0 else 0.0

                val (commonName, scientificName) = speciesMetadata[speciesId]
                    ?: (title to title)

                hits.add(
                    BlastHit(
                        speciesId = speciesId,
                        commonName = commonName,
                        scientificName = scientificName,
                        identityPercent = identityPercent,
                        alignmentLength = alignLen,
                        eValue = hsp["evalue"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                        bitScore = hsp["bit_score"]?.jsonPrimitive?.doubleOrNull ?: 0.0,
                        queryAligned = hsp["qseq"]?.jsonPrimitive?.contentOrNull ?: "",
                        subjectAligned = hsp["hseq"]?.jsonPrimitive?.contentOrNull ?: "",
                        matchString = hsp["midline"]?.jsonPrimitive?.contentOrNull ?: ""
                    )
                )
            }
        }

        return hits
    }

    // ---------------------------------------------------------------------
    // Tabular parsing (-outfmt 6)
    // Default columns: qseqid sseqid pident length mismatch gapopen
    //                   qstart qend sstart send evalue bitscore
    // ---------------------------------------------------------------------

    private fun parseTabularOutput(rawTabular: String): List<BlastHit> {
        return rawTabular.lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                val cols = line.split("\t")
                if (cols.size < 12) {
                    throw BlastParseException("Malformed tabular BLAST line, expected 12 columns, got ${cols.size}")
                }

                val speciesId = cols[1]
                val identityPercent = cols[2].toDoubleOrNull() ?: 0.0
                val alignmentLength = cols[3].toIntOrNull() ?: 0
                val eValue = cols[10].toDoubleOrNull() ?: 0.0
                val bitScore = cols[11].toDoubleOrNull() ?: 0.0

                val (commonName, scientificName) = speciesMetadata[speciesId]
                    ?: (speciesId to speciesId)

                BlastHit(
                    speciesId = speciesId,
                    commonName = commonName,
                    scientificName = scientificName,
                    identityPercent = identityPercent,
                    alignmentLength = alignmentLength,
                    eValue = eValue,
                    bitScore = bitScore,
                    // Tabular format (-outfmt 6) does not include aligned sequence
                    // strings; these require -outfmt 15 (JSON) or 0 (pairwise) to populate.
                    queryAligned = "",
                    subjectAligned = "",
                    matchString = ""
                )
            }
            .toList()
    }

    // ---------------------------------------------------------------------
    // JsonObject field helpers
    // ---------------------------------------------------------------------

    private fun JsonObject.stringField(key: String): String =
        this[key]?.jsonPrimitive?.contentOrNull ?: ""

    private fun JsonObject.doubleField(key: String): Double =
        this[key]?.jsonPrimitive?.doubleOrNull ?: 0.0

    private fun JsonObject.intField(key: String): Int =
        this[key]?.jsonPrimitive?.intOrNull ?: 0
}