package com.biodex

import io.ktor.server.application.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.resources.*
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
import java.sql.DriverManager

fun Application.configureRouting() {

    // --- Exposed DB connection (needed for Account 11's quiz persistence) ---
    // TODO: Replace hardcoded creds with env vars / config before shipping.
    Database.connect(
        url = "jdbc:postgresql://localhost:5432/biodex",
        driver = "org.postgresql.Driver",
        user = "postgres",
        password = "password"
    )

    org.jetbrains.exposed.sql.transactions.transaction {
        org.jetbrains.exposed.sql.SchemaUtils.create(
            com.biodex.server.quiz.SpeciesTable,
            com.biodex.server.quiz.TaxonomyTable,
            com.biodex.server.quiz.UserCapturesTable,
            com.biodex.server.quiz.QuizReviewStateTable
        )
    }

    routing {
        authRoutes()
        userRoutes()
        blastRoutes()
        alignRoutes()
        battleRoutes()

        val quizDataSource = ExposedSpeciesDataSource()
        val questionGenerator = QuestionGenerator(quizDataSource)
        quizRoutes(questionGenerator, quizDataSource)

        speciesRoutes {
            // TODO: Replace with actual database connection pool / DataSource
            DriverManager.getConnection("jdbc:postgresql://localhost:5432/biodex", "postgres", "password")
        }
        islandRoutes {
            DriverManager.getConnection("jdbc:postgresql://localhost:5432/biodex", "postgres", "password")
        }
        get("/") {
            call.respondText("Hello, World!")
        }
        get<Articles> { article ->
            // Get all articles ...
            call.respond("List of articles sorted starting from ${article.sort}")
        }
        get("/json/kotlinx-serialization") {
            call.respond(mapOf("hello" to "world"))
        }
    }
}