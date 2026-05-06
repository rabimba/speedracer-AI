package com.trustableai.koru.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.trustableai.koru.model.VisionFeatureSnapshot
import kotlin.math.abs
import kotlin.math.max

class CameraLaneAnalyzer(
    private val onFeatures: (VisionFeatureSnapshot) -> Unit,
) : ImageAnalysis.Analyzer {
    private var frameIndex = 0L
    private var previousTimestampNs = 0L
    private var previousSamples: ByteArray? = null
    private var previousSampleCount = 0
    private var reusableSamples: ByteArray? = null

    override fun analyze(image: ImageProxy) {
        try {
            frameIndex += 1
            if (frameIndex % 2L != 0L) {
                return
            }

            val lumaPlane = image.planes.firstOrNull() ?: return
            val width = image.width
            val height = image.height
            if (width <= 0 || height <= 0) return

            val stepX = max(1, width / 24)
            val stepY = max(1, height / 24)
            val sampleCount = ((width + stepX - 1) / stepX) * ((height + stepY - 1) / stepY)
            val samples = reusableSamples?.takeIf { it.size >= sampleCount } ?: ByteArray(sampleCount)
            reusableSamples = null

            var index = 0
            var totalLuma = 0.0
            var leftLuma = 0.0
            var rightLuma = 0.0
            var topLuma = 0.0
            var bottomLuma = 0.0
            var centerLuma = 0.0
            var centerCount = 0
            var edgeLuma = 0.0
            var edgeCount = 0

            val centerLeft = width / 4
            val centerRight = width - centerLeft
            val centerTop = height / 4
            val centerBottom = height - centerTop
            val rowStride = lumaPlane.rowStride
            val pixelStride = lumaPlane.pixelStride
            val buffer = lumaPlane.buffer

            for (y in 0 until height step stepY) {
                for (x in 0 until width step stepX) {
                    val absoluteIndex = y * rowStride + x * pixelStride
                    val lumaByte = buffer.get(absoluteIndex)
                    val luma = lumaByte.toInt() and 0xFF
                    samples[index++] = lumaByte

                    val normalized = luma / 255.0
                    totalLuma += normalized
                    if (x < width / 2) leftLuma += normalized else rightLuma += normalized
                    if (y < height / 2) topLuma += normalized else bottomLuma += normalized

                    if (x in centerLeft until centerRight && y in centerTop until centerBottom) {
                        centerLuma += normalized
                        centerCount += 1
                    } else {
                        edgeLuma += normalized
                        edgeCount += 1
                    }
                }
            }

            if (index == 0) return
            val averageLuma = totalLuma / index
            val leftCount = index / 2.0
            val rightCount = index - leftCount
            val topCount = index / 2.0
            val bottomCount = index - topCount
            val lateralBalance = ((leftLuma / leftCount) - (rightLuma / rightCount)).coerceIn(-1.0, 1.0)
            val verticalBalance = ((topLuma / topCount) - (bottomLuma / bottomCount)).coerceIn(-1.0, 1.0)
            val centerAverage = if (centerCount > 0) centerLuma / centerCount else averageLuma
            val edgeAverage = if (edgeCount > 0) edgeLuma / edgeCount else averageLuma
            val centerContrast = (centerAverage - edgeAverage).coerceIn(-1.0, 1.0)

            val motionEnergy = previousSamples?.let { previous ->
                val compared = minOf(previousSampleCount, index)
                if (compared == 0) {
                    0.0
                } else {
                    var totalDiff = 0.0
                    for (sampleIndex in 0 until compared) {
                        val current = samples[sampleIndex].toInt() and 0xFF
                        val previousValue = previous[sampleIndex].toInt() and 0xFF
                        totalDiff += abs(current - previousValue) / 255.0
                    }
                    (totalDiff / compared).coerceIn(0.0, 1.0)
                }
            } ?: 0.0

            val timestampNs = image.imageInfo.timestamp
            val fps = if (previousTimestampNs > 0L && timestampNs > previousTimestampNs) {
                (1_000_000_000.0 / (timestampNs - previousTimestampNs)).coerceIn(0.0, 120.0)
            } else {
                0.0
            }

            previousTimestampNs = timestampNs
            val retiredPrevious = previousSamples
            previousSamples = samples
            previousSampleCount = index
            reusableSamples = retiredPrevious

            onFeatures(
                VisionFeatureSnapshot(
                    timestampMs = timestampNs / 1_000_000L,
                    averageLuma = averageLuma,
                    motionEnergy = motionEnergy,
                    lateralBalance = lateralBalance,
                    verticalBalance = verticalBalance,
                    centerContrast = centerContrast,
                    framesPerSecond = fps,
                ),
            )
        } finally {
            image.close()
        }
    }
}
