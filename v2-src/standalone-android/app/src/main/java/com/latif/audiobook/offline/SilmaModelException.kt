package com.latif.audiobook.offline

import java.io.File

sealed class SilmaModelException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {

    class AssetMissing(
        val assetPath: String,
        cause: Throwable? = null,
    ) : SilmaModelException(
        "Required SILMA asset is missing: $assetPath",
        cause,
    )

    class AssetExtractionFailed(
        val assetPath: String,
        val destination: File,
        cause: Throwable? = null,
    ) : SilmaModelException(
        "Failed to extract SILMA asset '$assetPath' to ${destination.absolutePath}",
        cause,
    )

    class AssetTooSmall(
        val assetPath: String,
        val actualBytes: Long,
        val minimumBytes: Long,
    ) : SilmaModelException(
        "SILMA asset '$assetPath' is incomplete: $actualBytes bytes; minimum expected is $minimumBytes bytes",
    )

    class AssetSizeMismatch(
        val assetPath: String,
        val actualBytes: Long,
        val expectedBytes: Long,
    ) : SilmaModelException(
        "SILMA asset '$assetPath' has incorrect size: $actualBytes bytes; expected $expectedBytes bytes",
    )

    class AssetHashMismatch(
        val assetPath: String,
        val actualHash: String,
        val expectedHash: String,
    ) : SilmaModelException(
        "SILMA asset '$assetPath' has incorrect SHA-256. Actual=$actualHash Expected=$expectedHash",
    )

    class OnnxInitializationFailed(
        val backend: String,
        cause: Throwable,
    ) : SilmaModelException(
        "Unable to initialize ONNX Runtime backend '$backend': ${cause.message ?: cause.javaClass.simpleName}",
        cause,
    )

    class AllBackendsFailed(
        val failures: List<SilmaModelException>,
    ) : SilmaModelException(
        buildString {
            append("All SILMA ONNX Runtime backends failed.")
            failures.forEachIndexed { index, failure ->
                append("\n${index + 1}. ${failure.message}")
            }
        },
    )
}
