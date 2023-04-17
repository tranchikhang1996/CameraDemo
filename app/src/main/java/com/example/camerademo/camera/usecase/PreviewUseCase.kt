package com.example.camerademo.camera.usecase

import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.params.MeteringRectangle
import android.util.Size
import android.view.Surface
import android.view.SurfaceHolder
import com.example.camerademo.camera.Camera
import com.example.camerademo.camera.CameraSetting
import com.example.camerademo.camera.utils.getPreviewSizes
import com.example.camerademo.view.AutoFitSurfaceView

class PreviewUseCase(
    private val surfaceView: AutoFitSurfaceView,
    private val targetAspectRatio: String
) : CameraCaptureSession.CaptureCallback(), CameraUseCase {
    @Volatile
    private var requestBuilder: CaptureRequest.Builder? = null

    @Volatile
    private lateinit var camera: Camera

    @Volatile
    private lateinit var previewSizes: Map<String, Size>

    @Volatile
    private lateinit var previewSize: Size

    override fun createSurface(): Surface = surfaceView.holder.surface

    override fun onAttach(camera: Camera) {
        this.camera = camera
        previewSizes= getPreviewSizes(camera.characteristics, SurfaceHolder::class.java)
        previewSizes[targetAspectRatio]?.let {
            previewSize = it
            surfaceView.post { surfaceView.setAspectRatio(it.width, it.height) }
        }
    }

    override fun onCameraReady(camera: Camera, session: CameraCaptureSession) {
        requestBuilder = session.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
            addTarget(surfaceView.holder.surface)
        }
        camera.submitRequest {
            requestBuilder?.let { builder -> session.setRepeatingRequest(builder.build(), this, null) }
        }
    }

    override fun onSettingChanged(setting: CameraSetting) {
        requestBuilder?.apply {
            setting.zoom.config(this)
            camera.submitRequest { session ->
                session.stopRepeating()
                session.setRepeatingRequest(build(), null, null)
            }
        }
    }

    override fun onCaptureCompleted(
        cameraCaptureSession: CameraCaptureSession,
        request: CaptureRequest,
        result: TotalCaptureResult
    ) {
        super.onCaptureCompleted(cameraCaptureSession, request, result)
        if (request.tag == "FOCUS_TAG") {
            requestBuilder?.apply {
                set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                camera.submitRequest { session ->
                    session.stopRepeating()
                    session.setRepeatingRequest(build(), null, null)
                }
            }
        }
    }

    override fun focus(x: Float, y: Float, setting: CameraSetting) {
        requestBuilder?.apply {
            set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            camera.submitRequest { session ->
                session.stopRepeating()
                session.capture(build(), null, null)
            }
            set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(meteringRectangle(x, y)))
            set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            setTag("FOCUS_TAG")
            camera.submitRequest { session ->
                session.stopRepeating()
                session.capture(build(), this@PreviewUseCase, null)
            }
        }
    }

    private fun meteringRectangle(x: Float, y: Float): MeteringRectangle {
        val sensorOrientation = camera.characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
        val sensorSize = camera.characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!

        val halfMeteringRectWidth = (0.15f * sensorSize.width()) / 2
        val halfMeteringRectHeight = (0.15f * sensorSize.height()) / 2

        val normalizedPoint = floatArrayOf(x / previewSize.height, y / previewSize.width)

        Matrix().apply {
            postRotate(-sensorOrientation.toFloat(), 0.5f, 0.5f)
            postScale(sensorSize.width().toFloat(), sensorSize.height().toFloat())
            mapPoints(normalizedPoint)
        }

        val meteringRegion = Rect(
            (normalizedPoint[0] - halfMeteringRectWidth).toInt().coerceIn(0, sensorSize.width()),
            (normalizedPoint[1] - halfMeteringRectHeight).toInt().coerceIn(0, sensorSize.height()),
            (normalizedPoint[0] + halfMeteringRectWidth).toInt().coerceIn(0, sensorSize.width()),
            (normalizedPoint[1] + halfMeteringRectHeight).toInt().coerceIn(0, sensorSize.height())
        )

        return MeteringRectangle(meteringRegion, MeteringRectangle.METERING_WEIGHT_MAX)
    }
}