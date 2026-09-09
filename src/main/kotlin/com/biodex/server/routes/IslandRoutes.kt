// File: src/main/kotlin/com/biodex/server/routes/IslandRoutes.kt
package com.biodex.server.routes

import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add
import java.sql.Connection

// =====================================================================
// DATA CONTRACTS
// JSON keys use exact camelCase required by the Android UI layer
// (Accounts 1, 3, 4).
// =====================================================================

@Serializable
data class PlacedItemDto(
    val id: String,
    val itemId: String,
    val spriteEmoji: String,
    val gridX: Int,
    val gridY: Int
)

@Serializable
data class InventoryItemDto(
    val itemId: String,
    val name: String,
    val rarity: String,
    val spriteEmoji: String
)

@Serializable
data class IslandStateDto(
    val userId: String,
    val islandLevel: Int,
    val currentExp: Int,
    val maxExp: Int,
    val biomeName: String,
    val gridSize: Int,
    val placedItems: List<PlacedItemDto>,
    val unlockedInventory: List<InventoryItemDto>
)

@Serializable
data class PlaceDecorRequest(
    val itemId: String,
    val gridX: Int,
    val gridY: Int
)

@Serializable
data class PlaceDecorResponse(
    val success: Boolean,
    val message: String
)

// @Serializable
// data class ErrorResponse(val error: String)
// Already defined in AuthRoutes.kt (same package)

// Fixed grid size for all islands. Adjust here if islands become variable-size.
private const val ISLAND_GRID_SIZE = 6

// EXP required to level up — used only to compute maxExp for the response.
private const val EXP_PER_LEVEL = 1000

// =====================================================================
// RARE ITEM DROP LOGIC (Section 4 of the task brief)
// Exposed as a reusable helper so the species-scan endpoint (owned by
// another module) can call it after a successful scan.
// =====================================================================

/**
 * Given a scanned species' IUCN status, returns the island_items.id that
 * should be granted, or null if no drop occurs.
 *
 * LC / NT  -> 10% chance of "flower_wild"
 * VU       -> guaranteed "flower_orchid"
 * EN / CR  -> guaranteed "statue_<speciesId>"
 */
fun resolveDropForScan(speciesId: String, iucnStatus: String): String? {
    return when (iucnStatus) {
        "LC", "NT" -> if (Math.random() < 0.10) "flower_wild" else null
        "VU" -> "flower_orchid"
        "EN", "CR" -> "statue_$speciesId"
        else -> null
    }
}

/**
 * Appends [itemId] to unlocked_inventory for [userId] if not already present.
 * Call this after resolveDropForScan returns a non-null itemId.
 */
fun grantInventoryItem(connection: Connection, userId: String, itemId: String) {
    val selectSql = "SELECT unlocked_inventory FROM user_island WHERE user_id = ?"
    val updateSql = "UPDATE user_island SET unlocked_inventory = ?::jsonb WHERE user_id = ?"

    connection.prepareStatement(selectSql).use { stmt ->
        stmt.setString(1, userId)
        stmt.executeQuery().use { rs ->
            if (rs.next()) {
                val currentJson = rs.getString("unlocked_inventory") ?: "[]"
                val currentArray = Json.parseToJsonElement(currentJson).jsonArray
                val alreadyOwned = currentArray.any { it.jsonPrimitive.content == itemId }

                if (!alreadyOwned) {
                    val updatedArray = buildJsonArray {
                        currentArray.forEach { add(it) }
                        add(JsonPrimitive(itemId))
                    }
                    connection.prepareStatement(updateSql).use { updateStmt ->
                        updateStmt.setString(1, updatedArray.toString())
                        updateStmt.setString(2, userId)
                        updateStmt.executeUpdate()
                    }
                }
            }
        }
    }
}

// =====================================================================
// ROUTE REGISTRATION
// Call islandRoutes(connection) from your central Routing.kt install
// block. Assumes a JWT auth provider named "auth-jwt" is already
// configured in Security.kt, matching the pattern used by
// AuthRoutes.kt / UserRoutes.kt. Adjust the provider name if different.
// =====================================================================

fun Route.islandRoutes(getConnection: () -> Connection) {
    authenticate {
        route("/api/v1/island") {

            // GET /api/v1/island
            get {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asString()

                if (userId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing or invalid token"))
                    return@get
                }

                val islandSql = """
                    SELECT user_id, island_level, current_exp, placed_items, unlocked_inventory
                    FROM user_island
                    WHERE user_id = ?
                """.trimIndent()

                var islandLevel = 1
                var currentExp = 0
                var placedItemsJson = "[]"
                var unlockedIdsJson = "[]"
                var found = false

                getConnection().use { conn ->
                    conn.prepareStatement(islandSql).use { stmt ->
                        stmt.setString(1, userId)
                        stmt.executeQuery().use { rs ->
                            if (rs.next()) {
                                found = true
                                islandLevel = rs.getInt("island_level")
                                currentExp = rs.getInt("current_exp")
                                placedItemsJson = rs.getString("placed_items") ?: "[]"
                                unlockedIdsJson = rs.getString("unlocked_inventory") ?: "[]"
                            }
                        }
                    }

                    if (!found) {
                        // Auto-provision a fresh island row for first-time users.
                        conn.prepareStatement(
                            "INSERT INTO user_island (user_id) VALUES (?)"
                        ).use { insertStmt ->
                            insertStmt.setString(1, userId)
                            insertStmt.executeUpdate()
                        }
                    }

                    // Resolve placed items (raw grid state -> DTO with sprite emoji)
                    val placedArray = Json.parseToJsonElement(placedItemsJson).jsonArray
                    val placedItems = placedArray.map { element ->
                        val obj = element.jsonObject
                        val itemId = obj["itemId"]!!.jsonPrimitive.content
                        val spriteEmoji = lookupSpriteEmoji(conn, itemId) ?: "❓"
                        PlacedItemDto(
                            id = obj["id"]!!.jsonPrimitive.content,
                            itemId = itemId,
                            spriteEmoji = spriteEmoji,
                            gridX = obj["gridX"]!!.jsonPrimitive.content.toInt(),
                            gridY = obj["gridY"]!!.jsonPrimitive.content.toInt()
                        )
                    }

                    // Resolve unlocked inventory (raw id list -> full DTOs)
                    val unlockedIdsArray = Json.parseToJsonElement(unlockedIdsJson).jsonArray
                    val unlockedInventory = unlockedIdsArray.mapNotNull { element ->
                        val itemId = element.jsonPrimitive.content
                        lookupInventoryItem(conn, itemId)
                    }

                    val response = IslandStateDto(
                        userId = userId,
                        islandLevel = islandLevel,
                        currentExp = currentExp,
                        maxExp = EXP_PER_LEVEL,
                        biomeName = biomeNameForLevel(islandLevel),
                        gridSize = ISLAND_GRID_SIZE,
                        placedItems = placedItems,
                        unlockedInventory = unlockedInventory
                    )

                    call.respond(HttpStatusCode.OK, response)
                }
            }

            // POST /api/v1/island/decor
            post("/decor") {
                val principal = call.principal<JWTPrincipal>()
                val userId = principal?.payload?.getClaim("userId")?.asString()

                if (userId.isNullOrBlank()) {
                    call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Missing or invalid token"))
                    return@post
                }

                val body = call.receive<PlaceDecorRequest>()

                // Validate grid bounds: 0 <= gridX < gridSize, 0 <= gridY < gridSize
                if (body.gridX !in 0 until ISLAND_GRID_SIZE ||
                    body.gridY !in 0 until ISLAND_GRID_SIZE
                ) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse("Grid position (${body.gridX}, ${body.gridY}) is out of bounds for a ${ISLAND_GRID_SIZE}x$ISLAND_GRID_SIZE island")
                    )
                    return@post
                }

                getConnection().use { conn ->
                    // Confirm the item is actually unlocked for this user.
                    val ownsItem = userOwnsItem(conn, userId, body.itemId)
                    if (!ownsItem) {
                        call.respond(
                            HttpStatusCode.Forbidden,
                            ErrorResponse("Item ${body.itemId} is not in this user's unlocked inventory")
                        )
                        return@post
                    }

                    val selectSql = "SELECT placed_items FROM user_island WHERE user_id = ?"
                    var currentPlacedJson = "[]"

                    conn.prepareStatement(selectSql).use { stmt ->
                        stmt.setString(1, userId)
                        stmt.executeQuery().use { rs ->
                            if (rs.next()) {
                                currentPlacedJson = rs.getString("placed_items") ?: "[]"
                            }
                        }
                    }

                    val currentArray = Json.parseToJsonElement(currentPlacedJson).jsonArray
                    val newId = "placed_${System.currentTimeMillis()}"

                    val updatedArray = buildJsonArray {
                        currentArray.forEach { add(it) }
                        add(
                            JsonObject(
                                mapOf(
                                    "id" to JsonPrimitive(newId),
                                    "itemId" to JsonPrimitive(body.itemId),
                                    "gridX" to JsonPrimitive(body.gridX),
                                    "gridY" to JsonPrimitive(body.gridY)
                                )
                            )
                        )
                    }

                    val updateSql = "UPDATE user_island SET placed_items = ?::jsonb WHERE user_id = ?"
                    conn.prepareStatement(updateSql).use { stmt ->
                        stmt.setString(1, updatedArray.toString())
                        stmt.setString(2, userId)
                        stmt.executeUpdate()
                    }

                    call.respond(
                        HttpStatusCode.OK,
                        PlaceDecorResponse(
                            success = true,
                            message = "Item placed on island grid cell (${body.gridX}, ${body.gridY})"
                        )
                    )
                }
            }
        }
    }
}

// =====================================================================
// INTERNAL HELPERS
// =====================================================================

private fun lookupSpriteEmoji(connection: Connection, itemId: String): String? {
    connection.prepareStatement("SELECT sprite_emoji FROM island_items WHERE id = ?").use { stmt ->
        stmt.setString(1, itemId)
        stmt.executeQuery().use { rs ->
            if (rs.next()) return rs.getString("sprite_emoji")
        }
    }
    return null
}

private fun lookupInventoryItem(connection: Connection, itemId: String): InventoryItemDto? {
    connection.prepareStatement(
        "SELECT id, name, rarity, sprite_emoji FROM island_items WHERE id = ?"
    ).use { stmt ->
        stmt.setString(1, itemId)
        stmt.executeQuery().use { rs ->
            if (rs.next()) {
                return InventoryItemDto(
                    itemId = rs.getString("id"),
                    name = rs.getString("name"),
                    rarity = rs.getString("rarity"),
                    spriteEmoji = rs.getString("sprite_emoji")
                )
            }
        }
    }
    return null
}

private fun userOwnsItem(connection: Connection, userId: String, itemId: String): Boolean {
    connection.prepareStatement(
        "SELECT unlocked_inventory FROM user_island WHERE user_id = ?"
    ).use { stmt ->
        stmt.setString(1, userId)
        stmt.executeQuery().use { rs ->
            if (rs.next()) {
                val json = rs.getString("unlocked_inventory") ?: "[]"
                val array = Json.parseToJsonElement(json).jsonArray
                return array.any { it.jsonPrimitive.content == itemId }
            }
        }
    }
    return false
}

private fun biomeNameForLevel(level: Int): String = when {
    level >= 10 -> "Ancient Canopy"
    level >= 5 -> "Rainforest Canopy"
    level >= 2 -> "Coastal Grove"
    else -> "Sapling Shore"
}
