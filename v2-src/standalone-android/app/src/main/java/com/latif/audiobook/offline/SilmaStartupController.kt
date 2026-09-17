package com.latif.audiobook.offline

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Coroutine wrapper around synchronous ONNX model initialization.
 * It is service-owned in v3.3 so the Activity never creates a second giant model instance.
 */
class SilmaStartupController(context: Context) {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow<SilmaStartupState>(SilmaStartupState.Idle)
    val state: StateFlow<SilmaStartupState> = _state.asStateFlow()

    suspend fun load(
        onState: (SilmaStartupState) -> Unit = {},
    ): SilmaF5Engine = withContext(Dispatchers.IO) {
        val startedAt = SystemClock.elapsedRealtime()
        val engine = SilmaF5Engine(appContext)

        fun publish(value: SilmaStartupState) {
            _state.value = value
            onState(value)
        }

        publish(SilmaStartupState.Starting("Preparing local voice engine…", 1))
        try {
            engine.load { message ->
                publish(
                    SilmaStartupState.Starting(
                        message = message,
                        progress = progressFor(message),
                    ),
                )
            }
            currentCoroutineContext().ensureActive()
            publish(
                SilmaStartupState.Ready(
                    backend = engine.backendName,
                    workerThreads = engine.workerThreads,
                    elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                ),
            )
            engine
        } catch (e: CancellationException) {
            engine.close()
            publish(SilmaStartupState.Cancelled)
            throw e
        } catch (t: Throwable) {
            engine.close()
            publish(
                SilmaStartupState.Failed(
                    message = userFacingError(t),
                    cause = t,
                ),
            )
            throw t
        }
    }

    private fun progressFor(message: String): Int = when {
        message.contains("Checking", ignoreCase = true) -> 5
        message.contains("Validated", ignoreCase = true) -> 25
        message.contains("Installing", ignoreCase = true) -> 35
        message.contains("Initializing", ignoreCase = true) -> 60
        message.contains("Trying", ignoreCase = true) -> 75
        message.contains("ready", ignoreCase = true) -> 100
        else -> 50
    }

    private fun userFacingError(t: Throwable): String = when (t) {
        is SilmaModelException.AssetMissing -> "Required model asset is missing: ${t.assetPath}"
        is SilmaModelException.AssetTooSmall -> "Model asset is incomplete: ${t.assetPath}"
        is SilmaModelException.AssetSizeMismatch -> "Model asset size is incorrect: ${t.assetPath}"
        is SilmaModelException.AssetHashMismatch -> "Model integrity verification failed: ${t.assetPath}"
        is SilmaModelException.AllBackendsFailed -> "All ONNX backends failed. Check device compatibility."
        is SilmaModelException.OnnxInitializationFailed -> "ONNX initialization failed: ${t.backend}"
        is SilmaModelException.AssetExtractionFailed -> "Unable to install model asset: ${t.assetPath}"
        else -> t.message ?: t.javaClass.simpleName
    }
}
