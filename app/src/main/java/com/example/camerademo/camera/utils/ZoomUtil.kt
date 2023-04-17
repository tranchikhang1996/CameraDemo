package com.example.camerademo.camera.utils

import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM
import android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE
import androidx.core.math.MathUtils
import kotlin.math.roundToInt


object ZoomUtil {
    fun getZoomRange(characteristics: CameraCharacteristics) = characteristics.get(SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
        ?.takeIf { mz -> mz.compareTo(1f) > 0 }?.let { Pair(1f, it) } ?: Pair(1f, 1f)

    fun getZoomRegion(
        zoomValue: Float,
        characteristics: CameraCharacteristics
    ): Rect? {
        return characteristics.get(SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let {
            val zoomRange = getZoomRange(characteristics)
            val newZoom = MathUtils.clamp(zoomValue, zoomRange.first, zoomRange.second)
            val centerX = it.width() / 2
            val centerY = it.height() / 2
            val deltaX = ((0.5f * it.width()) / newZoom).roundToInt()
            val deltaY = ((0.5f * it.height()) / newZoom).roundToInt()
            Rect(centerX - deltaX, centerY - deltaY, centerX + deltaX, centerY + deltaY)
        }
    }
}