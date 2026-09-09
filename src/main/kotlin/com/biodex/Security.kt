package com.biodex

import io.ktor.server.application.*
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*

fun Application.configureSecurity() {
    val jwtAudience = com.biodex.server.auth.JwtConfig.AUDIENCE
    val jwtDomain = com.biodex.server.auth.JwtConfig.ISSUER
    val jwtRealm = com.biodex.server.auth.JwtConfig.REALM
    val jwtSecret = com.biodex.server.auth.JwtConfig.SECRET
    authentication {
        jwt {
            realm = jwtRealm
            verifier(
                JWT
                    .require(Algorithm.HMAC256(jwtSecret))
                    .withAudience(jwtAudience)
                    .withIssuer(jwtDomain)
                    .build()
            )
            validate { credential ->
                if (credential.payload.audience.contains(jwtAudience)) JWTPrincipal(credential.payload) else null
            }
        }
    }
}