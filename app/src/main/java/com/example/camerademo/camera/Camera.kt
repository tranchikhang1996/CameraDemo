package com.example.camerademo.camera

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Matrix
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE
import android.hardware.camera2.CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM
import android.hardware.camera2.CameraDevice.*
import android.hardware.camera2.CameraMetadata.LENS_FACING_BACK
import android.hardware.camera2.CameraMetadata.LENS_FACING_FRONT
import android.hardware.camera2.params.MeteringRectangle
import android.os.Build
import android.util.Range
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.core.math.MathUtils
import com.example.camerademo.CameraDemoApplication
import com.example.camerademo.camera.usecase.CameraUseCase
import com.example.camerademo.camera.utils.*
import com.example.camerademo.SupportRestartHandlerThread
import java.util.concurrent.Semaphore
import kotlin.math.roundToInt
import android.hardware.camera2.CaptureRequest.*

@SuppressLint("MissingPermission")
class Camera private constructor(
    private val facing: Int,
    private val cameraId: String,
    private var cameraUseCases: List<CameraUseCase>,
    private var setting: CameraSetting
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
        fun open(
            facing: Int,
            setting: CameraSetting,
            vararg cameraUseCases: CameraUseCase
        ): Camera {
            close()
            val cameraId = cameraIds[facing]?.get(0) ?: throw RuntimeException()
            return Camera(facing, cameraId, cameraUseCases.toList(), setting).apply {
                camera = this
                open()
            }
        }

        @Synchronized
        fun close() {
            camera?.close()
            camera = null
        }
    }

    private val cameraHandler = SupportRestartHandlerThread("camera thread")
    val characteristics = cameraManager.getCameraCharacteristics(cameraId)
    private var session: CameraCaptureSession? = null
    private var device: CameraDevice? = null
    private val streamSurfaces = mutableListOf<StreamSurface>()

    @Volatile
    var rotation: Int = Surface.ROTATION_0

    private var isActive = false
    @Volatile
    private var isOpened = false

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

    private fun open() {
        cameraLock.acquire()
        cameraHandler.restart()
        cameraHandler.submit {
            rotationListener.enable()
            cameraUseCases.forEach { kotlin.runCatching { it.onAttach(this@Camera) } }
            cameraManager.openCamera(cameraId, cameraDeviceCallBack, null)
        }
    }

    fun newSession() {
        cameraLock.acquire()
        if (!isOpened) {
            return cameraLock.release()
        }
        cameraHandler.submit {
            isActive = false
            session?.close()
            session = null
            streamSurfaces.clear()
            device?.createCaptureSession(
                cameraUseCases.map { it.getSurface() },
                sessionCallback,
                null
            )
        }
    }

    private fun close() {
        cameraLock.acquire()
        cameraHandler.submit {
            requestClose { cameraLock.release() }
        }
    }

    private fun requestClose(block: (() -> Unit)?) = cameraHandler.submit {
        rotationListener.disable()
        isActive = false
        isOpened = false
        cameraUseCases.forEach { kotlin.runCatching { it.onDetach(this) } }
        cameraUseCases = emptyList()
        kotlin.runCatching { session?.close() }
        session = null
        kotlin.runCatching { device?.close() }
        device = null
        cameraHandler.shutdown()
        block?.invoke()
    }

    fun capture(
        template: Int,
        callback: CameraCaptureSession.CaptureCallback?,
        configuredBlock: (Builder, CameraSetting) -> Unit
    ) = cameraHandler.submit {
        if (!isActive) return@submit
        session?.run {
            val requestBuilder = device.createCaptureRequest(template).apply {
                applySetting(setting)
                configuredBlock(this, setting)
                streamSurfaces.forEach { addTarget(it.surface) }
            }
            capture(requestBuilder.build(), callback, null)
        }
    }

    private val cameraDeviceCallBack = object : StateCallback() {
        override fun onOpened(cameraDevice: CameraDevice) {
            device = cameraDevice
            isOpened = true
            device?.createCaptureSession(
                cameraUseCases.map { it.getSurface() },
                sessionCallback,
                null
            )
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            kotlin.runCatching { cameraDevice.close() }
            requestClose { cameraLock.release() }
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            kotlin.runCatching { cameraDevice.close() }
            requestClose { cameraLock.release() }
        }
    }

    private val sessionCallback = object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) {
            this@Camera.session = session
            isActive = true
            cameraUseCases.forEach { kotlin.runCatching { it.onSessionCreated(this@Camera) } }
            cameraLock.release()
        }

        override fun onConfigureFailed(session: CameraCaptureSession) {
            kotlin.runCatching { session.close() }
            isActive = false
            cameraLock.release()
        }
    }

    private fun List<StreamSurface>.contain(surface: StreamSurface): Boolean {
        return find { it.name == surface.name } != null
    }

    private fun MutableList<StreamSurface>.removeSurface(surface: StreamSurface) {
        find { it.name == surface.name }?.let { remove(it) }
    }

    fun registerFrameStream(surface: StreamSurface, onRegistered: (() -> Unit)? = null) = cameraHandler.submit {
        if (isActive && !streamSurfaces.contain(surface)) {
            streamSurfaces.add(surface)
            requestRepeatingCapture()
            kotlin.runCatching { onRegistered?.invoke() }
        }
    }

    private fun requestRepeatingCapture(config: (Builder.() -> Unit)? = null) {
        if (streamSurfaces.isEmpty()) return
        session?.device?.createCaptureRequest(TEMPLATE_PREVIEW)?.run {
            streamSurfaces.forEach { addTarget(it.surface) }
            applySetting(setting)
            config?.invoke(this)
            session?.stopRepeating()
            session?.setRepeatingRequest(build(), null, null)
        }
    }

    fun unregisterFrameStream(surface: StreamSurface, onUnregistered: (() -> Unit)? = null) = cameraHandler.submit {
        if (isActive && streamSurfaces.contain(surface)) {
            streamSurfaces.removeSurface(surface)
            requestRepeatingCapture()
            kotlin.runCatching { onUnregistered?.invoke() }
        }
    }

    private fun Builder.applySetting(setting: CameraSetting) {
        set(SCALER_CROP_REGION, setting.zoomRegion)
        set(CONTROL_AWB_MODE, setting.wbMode)
        set(CONTROL_EFFECT_MODE, setting.effectMode)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            set(CONTROL_SCENE_MODE, CONTROL_SCENE_MODE_HDR)
        }
        set(SENSOR_SENSITIVITY, setting.iso)
        if (setting.focusDistance != null) {
            set(CONTROL_AF_MODE, CONTROL_AF_MODE_OFF)
            set(LENS_FOCUS_DISTANCE, setting.focusDistance)
        } else {
            set(CONTROL_MODE, CONTROL_MODE_AUTO)
            set(CONTROL_AF_MODE, CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            set(LENS_FOCUS_DISTANCE, null)
        }
    }

    fun manualFocus(x: Float, y: Float, size: Size) = cameraHandler.submit {
        if (!isActive) return@submit
        if (setting.focusDistance == null) {
            session?.stopRepeating()
            focusOnRegion(meteringRectangle(x, y, size))
        }
    }

    private fun focusOnRegion(region: MeteringRectangle) {
        session?.device?.createCaptureRequest(TEMPLATE_PREVIEW)?.apply {
            streamSurfaces.forEach { addTarget(it.surface) }
            applySetting(setting)
            set(CONTROL_AF_TRIGGER, CONTROL_AF_TRIGGER_CANCEL)
            set(CONTROL_AF_MODE, CONTROL_AF_MODE_OFF)
            session?.capture(build(), null, null)
            set(CONTROL_AF_REGIONS, arrayOf(region))
            set(CONTROL_MODE, CONTROL_MODE_AUTO)
            set(CONTROL_AF_MODE, CONTROL_AF_MODE_AUTO)
            set(CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START)
            session?.capture(build(), focusRegionRequestCallback, null)
        }
    }

    private val focusRegionRequestCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            cameraCaptureSession: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            super.onCaptureCompleted(cameraCaptureSession, request, result)
            requestRepeatingCapture {
                set(CONTROL_MODE, CONTROL_MODE_AUTO)
                set(CONTROL_AF_TRIGGER, CONTROL_AF_TRIGGER_IDLE)
                set(CONTROL_AF_MODE, CONTROL_AF_MODE_AUTO)
            }
            queueAFReset()
        }
    }

    private val afResetTask: Runnable = Runnable {
        requestRepeatingCapture {
            set(CONTROL_MODE, CONTROL_MODE_AUTO)
            set(CONTROL_AF_TRIGGER, null)
            set(CONTROL_AF_REGIONS, null)
            set(CONTROL_AF_MODE, CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }
    }

    private fun queueAFReset() {
        cameraHandler.submitDelayed(afResetTask, 3000L)
    }

    private fun meteringRectangle(x: Float, y: Float, size: Size): MeteringRectangle {
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!
        val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)!!

        val halfMeteringRectWidth = (0.15f * sensorSize.width()) / 2
        val halfMeteringRectHeight = (0.15f * sensorSize.height()) / 2

        val normalizedPoint = floatArrayOf(x / size.height, y / size.width)

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

    fun changeSetting(setting: CameraSetting) = cameraHandler.submit {
        if (!isActive) return@submit
        this.setting = setting
        requestRepeatingCapture()
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

data class StreamSurface(val name: String, val surface: Surface)