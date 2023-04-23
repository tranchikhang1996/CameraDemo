package com.example.camerademo.camera.usecase

import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import com.example.camerademo.camera.Camera
import com.example.camerademo.camera.StreamSurface
import com.example.camerademo.camera.utils.getPreviewSizes
import com.example.camerademo.view.AutoFitSurfaceView

class PreviewUseCase(
    private val surfaceView: AutoFitSurfaceView,
    private val targetAspectRatio: String
) : CameraUseCase {

    @Volatile
    private lateinit var previewSizes: Map<String, Size>

    @Volatile
    private lateinit var previewSize: Size

    override fun getSurface(): Surface = surfaceView.holder.surface

    override fun onAttach(camera: Camera) {
        previewSizes= getPreviewSizes(camera.characteristics, SurfaceHolder::class.java)
        previewSizes[targetAspectRatio]?.let {
            previewSize = it
            surfaceView.post { surfaceView.setAspectRatio(it.width, it.height) }
        }
    }

    override fun onSessionCreated(camera: Camera) {
        camera.registerFrameStream(StreamSurface("Preview", surfaceView.holder.surface))
    }

    fun getPreViewSize(): Size? {
        return if (::previewSize.isInitialized) previewSize else null
    }
}