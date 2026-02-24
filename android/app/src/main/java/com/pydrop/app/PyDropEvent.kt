package com.pydrop.app

sealed class PyDropEvent {
    data class FileReceived(
        val id: String,
        val name: String,
        val size: Long,
        val time: String
    ) : PyDropEvent()
}
