package com.biodex

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import com.biodex.server.routes.authRoutes
import com.biodex.server.routes.alignRoutes
import com.biodex.server.routes.battleRoutes
import com.biodex.server.routes.blastRoutes
import com.biodex.server.routes.speciesRoutes
import com.biodex.server.routes.islandRoutes
import com.biodex.server.routes.quizRoutes
import com.biodex.server.routes.userRoutes
import com.biodex.server.quiz.ExposedSpeciesDataSource
import com.biodex.server.quiz.QuestionGenerator
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Database
import java.sql.Connection
import java.sql.DriverManager
import java.net.URI

// --- Parses Render's DATABASE_URL (postgresql://user:pass@host:port/dbname)
// into JDBC-friendly pieces. Falls back to local defaults if not set. ---

data class DbCredentials(val jdbcUrl: String, val user: String, val password: String)

fun resolveDbCredentials(): DbCredentials {
    val rawUrl = System.getenv("DATABASE_URL")

    if (rawUrl.isNullOrBlank()) {
        // Local development fallback
        return DbCredentials(
            jdbcUrl = "jdbc:postgresql://localhost:5432/biodex",
            user = "postgres",
            password = "password"
        )
    }

    // rawUrl looks like: postgresql://user:pass@host:port/dbname
    val uri = URI(rawUrl)
    val userInfo = uri.userInfo?.split(":") ?: listOf("", "")
    val user = userInfo.getOrElse(0) { "" }
    val password = userInfo.getOrElse(1) { "" }
    val host = uri.host
    val port = if (uri.port == -1) 5432 else uri.port
    val dbName = uri.path.removePrefix("/")

    val jdbcUrl = "jdbc:postgresql://$host:$port/$dbName"

    return DbCredentials(jdbcUrl, user, password)
}

fun Application.configureRouting() {
    val creds = resolveDbCredentials()

    // 1. Initialize Exposed for modern routes
    Database.connect(
        url = creds.jdbcUrl,
        driver = "org.postgresql.Driver",
        user = creds.user,
        password = creds.password
    )

    // 2. Prepare dependencies for Quiz
    val dataSource = ExposedSpeciesDataSource()
    val generator = QuestionGenerator(dataSource)

    // 3. Prepare raw JDBC connection factory for legacy routes
    val getConnection = {
        DriverManager.getConnection(creds.jdbcUrl, creds.user, creds.password)
    }

    routing {
        authRoutes()
        alignRoutes()
        battleRoutes()
        blastRoutes()
        speciesRoutes(getConnection)
        islandRoutes(getConnection)
        quizRoutes(generator, dataSource)
        userRoutes()

        // Health check
        get("/") {
            call.respondText("BioDex Server API is running.")
        }
    }
}
