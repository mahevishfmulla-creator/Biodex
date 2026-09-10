package com.biodex.server.game

import kotlinx.serialization.Serializable

@Serializable
data class BattleTurnRequest(
    val battleId: String = "",
    val moveId: String = "",
    val turnNumber: Int = 1,
)