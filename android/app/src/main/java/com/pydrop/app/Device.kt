package com.pydrop.app

data class Device(
    val id: String,
    val name: String,
    val address: String,
    val httpPort: Int,
    var lastSeen: Long = System.currentTimeMillis()
)
