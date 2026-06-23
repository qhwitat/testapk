package com.notyet.terraria.core.server

data class ServerConfig(
    val maxPlayers: Int = 8,
    val port: Int = 7777,
    val worldName: String = "NotyetWorld",
    val worldSize: Int = 2, // 1=Small, 2=Medium, 3=Large
    val password: String? = null,
    val difficulty: Int = 0 // 0=Normal, 1=Expert, 2=Master, 3=Journey
)
