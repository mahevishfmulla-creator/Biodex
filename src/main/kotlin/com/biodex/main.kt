package com.biodex

import io.ktor.server.engine.*
import io.ktor.server.netty.*

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        configureSecurity()
        configureHttp()
        configureSerialization()
        configureResources()
        configureRouting()
    }.start(wait = true)
}
