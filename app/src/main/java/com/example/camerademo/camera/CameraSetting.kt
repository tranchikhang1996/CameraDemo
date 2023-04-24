package com.example.camerademo.camera

import android.graphics.Rect
import android.hardware.camera2.CaptureRequest

data class CameraSetting(
    val flashMode: Flash = FlashOff,
    val zoomRegion: Rect? = null,
    val zoomFactor: Float = 1f,
    val wbMode: Int = CaptureRequest.CONTROL_AWB_MODE_AUTO,
    val effectMode: Int = CaptureRequest.CONTROL_EFFECT_MODE_OFF,
    val focusDistance: Float? = null,
    val shutterSpeed: Long? = null,
    val hdr: Boolean = false,
    val iso: Int? = null
)

sealed interface Flash {
    fun config(builder: CaptureRequest.Builder)
}

object FlashOff : Flash {
    override fun config(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
    }
}

object FlashOn : Flash {
    override fun config(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
    }
}

class Zoom(private val rect: Rect) {
    fun config(builder: CaptureRequest.Builder) {
        builder.set(CaptureRequest.SCALER_CROP_REGION, rect)
    }
}


