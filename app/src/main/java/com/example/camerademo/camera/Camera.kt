package com.example.camerademo.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.*
import android.hardware.camera2.CameraMetadata.LENS_FACING_BACK
import android.hardware.camera2.CameraMetadata.LENS_FACING_FRONT
import android.view.OrientationEventListener
import android.view.Surface
import com.example.camerademo.CameraDemoApplication
import com.example.camerademo.camera.usecase.CameraUseCase
import com.example.camerademo.camera.utils.*
import com.example.camerademo.SupportRestartHandlerThread
import java.util.concurrent.Semaphore

@SuppressLint("MissingPermission")
class Camera(
    private val facing: Int,
    private var cameraSetting: CameraSetting
) {
    companion object {
        val cameraManager: CameraManager by lazy {
            CameraDemoApplication.instance.applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        }
        val cameraIds = listOf(LENS_FACING_BACK, LENS_FACING_FRONT)
            .map { it to getCameraIds(cameraManager, it) }
            .filter { it.second.isNotEmpty() }
            .groupBy { it.first }
            .mapValues { entry -> entry.value.first().second }

        private val cameraLock = Semaphore(1)
    }

    private var session: CameraCaptureSession? = null
    private val cameraId: String = cameraIds[facing]?.first() ?: throw IllegalStateException("camera len $facing is not support")
    val characteristics: CameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId)
    private var cameraUseCases: List<CameraUseCase> = mutableListOf()

    @Volatile var rotation: Int = Surface.ROTATION_0

    private var isActive = false

    private val cameraHandler = SupportRestartHandlerThread("camera thread")

    private val rotationListener = object : OrientationEventListener(CameraDemoApplication.instance) {
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

    fun open(vararg useCases: CameraUseCase) {
        cameraLock.acquire()
        cameraHandler.restart()
        cameraHandler.submit {
            cameraUseCases = useCases.asList()
            cameraUseCases.forEach { it.onAttach(this) }
            cameraManager.openCamera(cameraId, cameraDeviceCallBack, null)
        }
    }

    fun close() {
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
                cameraUseCases.forEach { it.onCameraReady(this@Camera, session) }
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

    fun zoom(value: Float) = cameraHandler.submit {
        if (!isActive) return@submit
        cameraSetting = cameraSetting.copy(zoom = Zoom(ZoomUtil.getZoomRegion(value, characteristics)))
        cameraUseCases.forEach { it.onSettingChanged(cameraSetting) }
    }

    fun manualFocus(x: Float, y: Float) = cameraHandler.submit {
        if (!isActive) return@submit
        cameraUseCases.forEach { it.focus(x, y, cameraSetting) }
    }

    fun setFlash(flashMode: Flash) = cameraHandler.submit {
        if (!isActive) return@submit
        cameraSetting = cameraSetting.copy(flashMode = flashMode)
        cameraUseCases.forEach { it.onSettingChanged(cameraSetting) }
    }

    fun getExifRotation(): Int {
        val mirrored = facing == CameraCharacteristics.LENS_FACING_FRONT
        return computeExifOrientation(rotation, mirrored)
    }

    fun getZoomRange(): Pair<Float, Float> {
        return ZoomUtil.getZoomRange(characteristics)
    }
}