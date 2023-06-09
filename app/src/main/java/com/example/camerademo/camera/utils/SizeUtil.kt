package com.example.camerademo.camera.utils

import android.graphics.Point
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.params.StreamConfigurationMap
import android.util.Size
import android.view.Display
import kotlin.math.max
import kotlin.math.min

class SmartSize(width: Int, height: Int) {
    var size = Size(width, height)
    var long = max(size.width, size.height)
    var short = min(size.width, size.height)
    override fun toString() = "SmartSize(${long}x${short})"
}

/** Standard High Definition size for pictures and video */
val SIZE_1080P: SmartSize = SmartSize(1920, 1080)

/** Returns a [SmartSize] object for the given [Display] */
fun getDisplaySmartSize(display: Display): SmartSize {
    val outPoint = Point()
    display.getRealSize(outPoint)
    return SmartSize(outPoint.x, outPoint.y)
}

/**
 * Returns the largest available PREVIEW size. For more information, see:
 * https://d.android.com/reference/android/hardware/camera2/CameraDevice and
 * https://developer.android.com/reference/android/hardware/camera2/params/StreamConfigurationMap
 */
fun <T> getPreviewOutputSize(
    display: Display,
    characteristics: CameraCharacteristics,
    targetClass: Class<T>,
    format: Int? = null
): Size {

    // Find which is smaller: screen or 1080p
    val screenSize = getDisplaySmartSize(display)
    val hdScreen = screenSize.long >= SIZE_1080P.long || screenSize.short >= SIZE_1080P.short
    val maxSize = if (hdScreen) SIZE_1080P else screenSize

    // If image format is provided, use it to determine supported sizes; else use target class
    val config = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
    if (format == null) {
        assert(StreamConfigurationMap.isOutputSupportedFor(targetClass))
    } else {
        assert(config.isOutputSupportedFor(format))
    }
    val allSizes = if (format == null)
        config.getOutputSizes(targetClass) else config.getOutputSizes(format)

    // Get available sizes and sort them by area from largest to smallest
    val validSizes = allSizes
        .sortedWith(compareBy { it.height * it.width })
        .map { SmartSize(it.width, it.height) }.reversed()

    // Then, get the largest output size that is smaller or equal than our max size
    return validSizes.first { it.long <= maxSize.long && it.short <= maxSize.short }.size
}

fun <T> getPreviewSizes(
    characteristics: CameraCharacteristics,
    targetClass: Class<T>,
): Map<String, Size> {
    val maxSize = SIZE_1080P
    val config = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
    val size = config.getOutputSizes(targetClass)
        .map { SmartSize(it.width, it.height) }
        .filter { it.long <= maxSize.long && it.short <= maxSize.short }
        .groupBy { findRatio(it.size.width, it.size.height) }
    return size.mapValues { entry -> entry.value.sortedWith(compareBy { it.size.height * it.size.width }).last().size }
}

fun <T> getImageSizes(
    characteristics: CameraCharacteristics,
    targetClass: Class<T>,
    format: Int? = null,
): Map<String, Size> {
    val config = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
    return (if (format == null) config.getOutputSizes(targetClass) else config.getOutputSizes(format))
        .groupBy { findRatio(it.width, it.height) }
        .mapValues { entry ->
            entry.value.sortedWith(compareBy<Size> { it.height * it.width }).last()
        }
}

fun <T> getVideoSizes(
    characteristics: CameraCharacteristics,
    targetClass: Class<T>,
    format: Int? = null,
): Map<String, Size> {
    val maxSize = SIZE_1080P
    val config = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
    val size = (if (format == null) config.getOutputSizes(targetClass) else config.getOutputSizes(format))
        .map { SmartSize(it.width, it.height) }
        .filter { it.long <= maxSize.long && it.short <= maxSize.short }
        .groupBy { findRatio(it.size.width, it.size.height) }
    return size.mapValues { entry -> entry.value.sortedWith(compareBy { it.size.height * it.size.width }).last().size }
}

private fun findRatio(width: Int, height: Int): String {
    var a = max(width, height)
    var b = min(width, height)
    while (a % b != 0) {
        val mod = a % b
        a = b
        b = mod
    }
    return "${width / b}:${height / b}"
}