package com.biodex.server.routes

import com.biodex.server.auth.JwtConfig
import io.ktor.http.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.mindrot.jbcrypt.BCrypt
import java.util.UUID

// ---------- Request / Response models ----------

@Serializable
data class AuthRequest(
    val username: String,
    val password: String
)

@Serializable
data class AuthResponse(
    val token: String,
    val userId: String,
    val username: String,
    val level: Int,
    val currentXp: Int,
    val maxXp: Int
)

@Serializable
data class ErrorResponse(
    val error: String
)

// ---------- In-memory user store (placeholder until PostgreSQL wiring lands) ----------
// TODO: replace with a real repository backed by PostgreSQL / Exposed.

data class StoredUser(
    val userId: String,
    val username: String,
    val passwordHash: String,
    var level: Int = 1,
    var currentXp: Int = 0,
    var maxXp: Int = 1000,
    var totalCaptured: Int = 0,
    val joinedAt: String = java.time.Instant.now().toString()
)

object UserStore {
    private val usersByUsername = mutableMapOf<String, StoredUser>()
    private val usersById = mutableMapOf<String, StoredUser>()

    fun findByUsername(username: String): StoredUser? = usersByUsername[username]
    fun findById(userId: String): StoredUser? = usersById[userId]

    fun save(user: StoredUser) {
        usersByUsername[user.username] = user
        usersById[user.userId] = user
    }
}

// ---------- Routes ----------

fun Route.authRoutes() {

    route("/api/v1/auth") {

        post("/register") {
            val request = call.receive<AuthRequest>()

            if (request.username.isBlank() || request.password.isBlank()) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse("Username and password are required"))
                return@post
            }

            if (UserStore.findByUsername(request.username) != null) {
                call.respond(HttpStatusCode.Conflict, ErrorResponse("Username already taken"))
                return@post
            }

            val passwordHash = BCrypt.hashpw(request.password, BCrypt.gensalt())
            val userId = "usr_${UUID.randomUUID().toString().take(8)}"

            val newUser = StoredUser(
                userId = userId,
                username = request.username,
                passwordHash = passwordHash
            )
            UserStore.save(newUser)

            val token = JwtConfig.generateToken(newUser.userId, newUser.username)

            call.respond(
                HttpStatusCode.OK,
                AuthResponse(
                    token = token,
                    userId = newUser.userId,
                    username = newUser.username,
                    level = newUser.level,
                    currentXp = newUser.currentXp,
                    maxXp = newUser.maxXp
                )
            )
        }

        post("/login") {
            val request = call.receive<AuthRequest>()

            val user = UserStore.findByUsername(request.username)
            if (user == null || !BCrypt.checkpw(request.password, user.passwordHash)) {
                call.respond(HttpStatusCode.Unauthorized, ErrorResponse("Invalid username or password"))
                return@post
            }

            val token = JwtConfig.generateToken(user.userId, user.username)

            call.respond(
                HttpStatusCode.OK,
                AuthResponse(
                    token = token,
                    userId = user.userId,
                    username = user.username,
                    level = user.level,
                    currentXp = user.currentXp,
                    maxXp = user.maxXp
                )
            )
        }
    }
}
