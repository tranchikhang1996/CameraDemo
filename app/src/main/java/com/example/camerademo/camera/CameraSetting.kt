package com.example.camerademo.camera

import android.graphics.Rect
import android.hardware.camera2.CaptureRequest

data class CameraSetting(
    var flashMode: Flash = FlashOff,
    var zoom: Zoom = Zoom(null)
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

class Zoom(private val rect: Rect?) {
    fun config(builder: CaptureRequest.Builder) {
        rect?.let { builder.set(CaptureRequest.SCALER_CROP_REGION, rect) }
    }
}


