package com.latif.audiobook.offline

import android.graphics.Canvas
import android.graphics.Paint

/**
 * Kotlin's sin() returns Double while Android Canvas uses Float coordinates.
 * Keep the waveform math readable and convert only at the drawing boundary.
 */
fun Canvas.drawLine(
    startX: Float,
    startY: Double,
    stopX: Float,
    stopY: Double,
    paint: Paint,
) {
    drawLine(startX, startY.toFloat(), stopX, stopY.toFloat(), paint)
}
