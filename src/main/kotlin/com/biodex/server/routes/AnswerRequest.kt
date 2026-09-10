package com.biodex.server.routes

import kotlinx.serialization.Serializable

@Serializable
data class AnswerRequest(
    val questionId: String,
    val speciesId: String,
    val selectedOptionIndex: Int,
    val timeTakenSeconds: Double
)
