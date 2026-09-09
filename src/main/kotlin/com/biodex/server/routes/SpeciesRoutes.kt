// File: src/main/kotlin/com/biodex/server/routes/SpeciesRoutes.kt
package com.biodex.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.ResultSet

// =====================================================================
// DATA CONTRACTS
// JSON keys use exact camelCase required by the Android UI layer
// (Accounts 1, 3, 4).
// =====================================================================

@Serializable
data class SpeciesSummaryDto(
    val id: String,
    val commonName: String,
    val scientificName: String,
    val habitat: String,
    val iucnStatus: String,
    val iconEmoji: String
)

@Serializable
data class TaxonomyDto(
    val kingdom: String,
    val phylum: String,
    val className: String,
    val orderName: String,
    val family: String,
    val genus: String
)

@Serializable
data class SpeciesDetailDto(
    val id: String,
    val commonName: String,
    val scientificName: String,
    val habitat: String,
    val locationSummary: String?,
    val description: String?,
    val lengthCm: String?,
    val weightKg: String?,
    val lifespanYears: String?,
    val iucnStatus: String,
    val iconEmoji: String,
    val fastaSequence: String?,
    val taxonomy: TaxonomyDto?
)

// @Serializable
// data class ErrorResponse(val error: String)
// Already defined in AuthRoutes.kt (same package)

// =====================================================================
// ROUTE REGISTRATION
// Call speciesRoutes(connection) from your central Routing.kt install
// block, passing the shared java.sql.Connection / DataSource connection
// used by AuthRoutes.kt and UserRoutes.kt.
// =====================================================================

fun Route.speciesRoutes(getConnection: () -> Connection) {
    route("/api/v1/species") {

        // GET /api/v1/species?status=VU
        get {
            val statusFilter = call.request.queryParameters["status"]?.uppercase()

            val validStatuses = setOf("LC", "NT", "VU", "EN", "CR", "EW", "EX")
            if (statusFilter != null && statusFilter !in validStatuses) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse("Invalid iucnStatus filter: $statusFilter")
                )
                return@get
            }

            val sql = if (statusFilter != null) {
                """
                SELECT id, common_name, scientific_name, habitat, iucn_status, icon_emoji
                FROM species
                WHERE iucn_status = ?
                ORDER BY common_name
                """.trimIndent()
            } else {
                """
                SELECT id, common_name, scientific_name, habitat, iucn_status, icon_emoji
                FROM species
                ORDER BY common_name
                """.trimIndent()
            }

            val results = mutableListOf<SpeciesSummaryDto>()

            getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    if (statusFilter != null) {
                        stmt.setString(1, statusFilter)
                    }
                    stmt.executeQuery().use { rs: ResultSet ->
                        while (rs.next()) {
                            results.add(
                                SpeciesSummaryDto(
                                    id = rs.getString("id"),
                                    commonName = rs.getString("common_name"),
                                    scientificName = rs.getString("scientific_name"),
                                    habitat = rs.getString("habitat"),
                                    iucnStatus = rs.getString("iucn_status"),
                                    iconEmoji = rs.getString("icon_emoji")
                                )
                            )
                        }
                    }
                }
            }

            call.respond(HttpStatusCode.OK, results)
        }

        // GET /api/v1/species/{id}
        get("/{id}") {
            val speciesId = call.parameters["id"]
            if (speciesId.isNullOrBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Missing species id"))
                return@get
            }

            val sql = """
                SELECT
                    s.id, s.common_name, s.scientific_name, s.habitat, s.location_summary,
                    s.description, s.length_cm, s.weight_kg, s.lifespan_years,
                    s.iucn_status, s.icon_emoji, s.fasta_sequence,
                    t.kingdom, t.phylum, t.class_name, t.order_name, t.family, t.genus
                FROM species s
                LEFT JOIN taxonomy t ON t.species_id = s.id
                WHERE s.id = ?
            """.trimIndent()

            var detail: SpeciesDetailDto? = null

            getConnection().use { conn ->
                conn.prepareStatement(sql).use { stmt ->
                    stmt.setString(1, speciesId)
                    stmt.executeQuery().use { rs ->
                        if (rs.next()) {
                            val taxonomy = if (rs.getString("kingdom") != null) {
                                TaxonomyDto(
                                    kingdom = rs.getString("kingdom"),
                                    phylum = rs.getString("phylum"),
                                    className = rs.getString("class_name"),
                                    orderName = rs.getString("order_name"),
                                    family = rs.getString("family"),
                                    genus = rs.getString("genus")
                                )
                            } else null

                            detail = SpeciesDetailDto(
                                id = rs.getString("id"),
                                commonName = rs.getString("common_name"),
                                scientificName = rs.getString("scientific_name"),
                                habitat = rs.getString("habitat"),
                                locationSummary = rs.getString("location_summary"),
                                description = rs.getString("description"),
                                lengthCm = rs.getString("length_cm"),
                                weightKg = rs.getString("weight_kg"),
                                lifespanYears = rs.getString("lifespan_years"),
                                iucnStatus = rs.getString("iucn_status"),
                                iconEmoji = rs.getString("icon_emoji"),
                                fastaSequence = rs.getString("fasta_sequence"),
                                taxonomy = taxonomy
                            )
                        }
                    }
                }
            }

            if (detail == null) {
                call.respond(HttpStatusCode.NotFound, ErrorResponse("Species not found: $speciesId"))
            } else {
                call.respond(HttpStatusCode.OK, detail)
            }
        }
    }
}
