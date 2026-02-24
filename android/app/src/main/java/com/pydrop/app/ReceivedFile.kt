package com.pydrop.app

data class ReceivedFile(
    val id: String,
    val name: String,
    val size: Long,
    val time: String,
    val path: String
)
