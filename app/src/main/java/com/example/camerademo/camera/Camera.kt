package com.example.camerademo.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE
import android.hardware.camera2.CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM
import android.hardware.camera2.CameraMetadata.LENS_FACING_BACK
import android.hardware.camera2.CameraMetadata.LENS_FACING_FRONT
import android.os.Build
import android.util.Range
import android.view.OrientationEventListener
import android.view.Surface
import androidx.core.math.MathUtils
import com.example.camerademo.CameraDemoApplication
import com.example.camerademo.camera.usecase.CameraUseCase
import com.example.camerademo.camera.utils.*
import com.example.camerademo.SupportRestartHandlerThread
import java.util.concurrent.Semaphore
import kotlin.math.roundToInt

@SuppressLint("MissingPermission")
class Camera private constructor(
    private val facing: Int,
    private var cameraSetting: CameraSetting
) {
    companion object {
        private var camera: Camera? = null
        val cameraManager: CameraManager by lazy {
            CameraDemoApplication.instance.applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        }
        val cameraIds = listOf(LENS_FACING_BACK, LENS_FACING_FRONT)
            .map { it to getCameraIds(cameraManager, it) }
            .filter { it.second.isNotEmpty() }
            .groupBy { it.first }
            .mapValues { entry -> entry.value.first().second }

        private val cameraLock = Semaphore(1)

        @Synchronized
        fun open(facing: Int, cameraSetting: CameraSetting, vararg useCases: CameraUseCase): Camera {
            close()
            return Camera(facing, cameraSetting).apply {
                open(useCases.toList())
                camera = this
            }
        }

        @Synchronized
        fun close() {
            camera?.close()
            camera = null
        }
    }

    private var session: CameraCaptureSession? = null

    private val cameraId: String = cameraIds[facing]?.first()!!

    val characteristics = cameraManager.getCameraCharacteristics(cameraId)

    private var cameraUseCases: List<CameraUseCase> = mutableListOf()

    @Volatile
    var rotation: Int = Surface.ROTATION_0

    private var isActive = false

    private val cameraHandler = SupportRestartHandlerThread("camera thread")

    private val rotationListener =
        object : OrientationEventListener(CameraDemoApplication.instance) {
            override fun onOrientationChanged(orientation: Int) {
                val surfaceRotation = when {
                    orientation <= 45 -> Surface.ROTATION_0
                    orientation <= 135 -> Surface.ROTATION_90
                    orientation <= 225 -> Surface.ROTATION_180
                    orientation <= 315 -> Surface.ROTATION_270
                    else -> Surface.ROTATION_0
                }
                rotation = computeRelativeRotation(characteristics, surfaceRotation)
            }
        }

    private fun open(useCases: List<CameraUseCase>) {
        cameraLock.acquire()
        cameraHandler.restart()
        cameraHandler.submit {
            cameraUseCases = useCases
            cameraUseCases.forEach { it.onAttach(this) }
            cameraManager.openCamera(cameraId, cameraDeviceCallBack, null)
        }
    }

    private fun close() {
        cameraLock.acquire()
        cameraHandler.submit {
            kotlin.runCatching {
                isActive = false
                rotationListener.disable()
                detachUseCases()
                session?.close()
                session?.device?.close()
                session = null
                cameraHandler.shutdown()
            }
            cameraLock.release()
        }
    }

    fun submitRequest(block: (CameraCaptureSession) -> Unit) {
        cameraHandler.submit {
            if (isActive) {
                session?.let { block(it) }
            }
        }
    }

    private fun detachUseCases() {
        cameraUseCases.forEach { it.onDetach(this) }
        cameraUseCases = emptyList()
    }

    private val cameraDeviceCallBack = object : CameraDevice.StateCallback() {
        override fun onOpened(cameraDevice: CameraDevice) {
            val targets = cameraUseCases.map { it.createSurface() }
            cameraDevice.createCaptureSession(targets, sessionCallback, null)
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            kotlin.runCatching {
                detachUseCases()
                cameraDevice.close()
            }
            cameraLock.release()
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            kotlin.runCatching {
                detachUseCases()
                cameraDevice.close()
            }
            cameraLock.release()
        }
    }

    private val sessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            kotlin.runCatching {
                this@Camera.session = session
                rotationListener.enable()
                cameraUseCases.forEach { it.onCameraReady(this@Camera, cameraSetting, session) }
                isActive = true
            }
            cameraLock.release()
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {
            kotlin.runCatching {
                detachUseCases()
                session.device.close()
            }
            cameraLock.release()
        }
    }

    fun manualFocus(x: Float, y: Float) = cameraHandler.submit {
        if (!isActive) return@submit
        if (cameraSetting.focusDistance == null) {
            cameraUseCases.forEach { it.focus(x, y, cameraSetting) }
        }
    }

    fun changeSetting(setting: CameraSetting) = cameraHandler.submit {
        if (!isActive) return@submit
        cameraSetting = setting
        cameraUseCases.forEach { it.onSettingChanged(cameraSetting) }
    }

    fun getExifRotation(): Int {
        val mirrored = facing == CameraCharacteristics.LENS_FACING_FRONT
        return computeExifOrientation(rotation, mirrored)
    }

    fun getZoomRegion(scale: Float): Rect? {
        return characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)?.let {
            val newZoom = MathUtils.clamp(scale, zoomRange.lower, zoomRange.upper)
            val centerX = it.width() / 2
            val centerY = it.height() / 2
            val deltaX = ((0.5f * it.width()) / newZoom).roundToInt()
            val deltaY = ((0.5f * it.height()) / newZoom).roundToInt()
            Rect(centerX - deltaX, centerY - deltaY, centerX + deltaX, centerY + deltaY)
        }
    }

    val zoomRange: Range<Float> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        characteristics.get(CONTROL_ZOOM_RATIO_RANGE) ?: Range(1f, 1f)
    } else {
        characteristics.get(SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)
            ?.takeIf { mz -> mz.compareTo(1f) > 0 }?.let { Range(1f, it) } ?: Range(1f, 1f)
    }
}