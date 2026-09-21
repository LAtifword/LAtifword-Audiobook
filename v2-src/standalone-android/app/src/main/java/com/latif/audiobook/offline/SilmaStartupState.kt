package com.latif.audiobook.offline

sealed interface SilmaStartupState {
    data object Idle : SilmaStartupState

    data class Starting(
        val message: String,
        val progress: Int = 0,
    ) : SilmaStartupState

    data class Ready(
        val backend: String,
        val workerThreads: Int,
        val elapsedMs: Long,
    ) : SilmaStartupState

    data class Failed(
        val message: String,
        val cause: Throwable? = null,
    ) : SilmaStartupState

    data object Cancelled : SilmaStartupState
}
