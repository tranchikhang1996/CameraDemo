package com.example.camerademo.camera.usecase

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.media.Image
import android.media.ImageReader
import android.media.ImageReader.OnImageAvailableListener
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.view.Surface
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import com.example.camerademo.camera.Camera
import com.example.camerademo.camera.CameraSetting
import com.example.camerademo.camera.utils.getImageSizes
import com.example.camerademo.SupportRestartHandlerThread
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class PhotoCaptureUseCase(
    private val context: Context,
    private val targetAspectRatio: String,
    private val onImageSaved: ((Uri) -> Unit)?
) : CameraCaptureSession.CaptureCallback(), OnImageAvailableListener, CameraUseCase {
    @Volatile
    private var requestBuilder: CaptureRequest.Builder? = null
    @Volatile
    private lateinit var imageReader: ImageReader
    @Volatile
    private var camera: Camera? = null
    private val readerThread = SupportRestartHandlerThread("image_reader")
    private var savedImagedThread = SupportRestartHandlerThread("save_file")

    fun capture() {
        requestBuilder?.let { builder ->
            camera?.submitRequest { session -> session.capture(builder.build(), null, null) }
        }
    }

    override fun createSurface(): Surface = imageReader.surface

    override fun onAttach(camera: Camera) {
        this.camera = camera
        val outputSizes = getImageSizes(camera.characteristics, ImageFormat.JPEG)
        savedImagedThread.restart()
        outputSizes[targetAspectRatio]?.let {
            imageReader = ImageReader.newInstance(it.width, it.height, ImageFormat.JPEG, 1).apply {
                setOnImageAvailableListener(this@PhotoCaptureUseCase, readerThread.restart())
            }
        }
    }

    override fun onCameraReady(camera: Camera, setting: CameraSetting, session: CameraCaptureSession) {
        requestBuilder = session.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
            addTarget(imageReader.surface)
            applySetting(setting)
        }
    }

    override fun onDetach(camera: Camera) {
        this.camera = null
        readerThread.shutdown()
        savedImagedThread.shutdown()
    }

    private fun CaptureRequest.Builder.applySetting(setting: CameraSetting) {
        set(CaptureRequest.SCALER_CROP_REGION, setting.zoomRegion)
        set(CaptureRequest.CONTROL_AWB_MODE, setting.wbMode)
        set(CaptureRequest.CONTROL_EFFECT_MODE, setting.effectMode)
        setting.flashMode.config(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            set(CaptureRequest.CONTROL_SCENE_MODE, CaptureRequest.CONTROL_SCENE_MODE_HDR)
        }
        set(CaptureRequest.SENSOR_SENSITIVITY, setting.iso)
        if(setting.focusDistance != null) {
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            set(CaptureRequest.LENS_FOCUS_DISTANCE, setting.focusDistance)
        } else {
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            set(CaptureRequest.LENS_FOCUS_DISTANCE, null)
        }
    }

    override fun onSettingChanged(setting: CameraSetting) {
        requestBuilder?.applySetting(setting)
    }

    override fun onImageAvailable(reader: ImageReader) {
        savePhoto(reader.acquireLatestImage())
    }

    private fun savePhoto(image: Image) = savedImagedThread.submit {
        image.use {
            val buffer = it.planes[0].buffer
            val bytes = ByteArray(buffer.remaining()).apply { buffer.get(this) }
            val output = createFile(context)
            FileOutputStream(output).buffered().use { buffered -> buffered.write(bytes) }
            camera?.let { cam ->
                val exif = ExifInterface(output.absolutePath)
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, cam.getExifRotation().toString())
                exif.saveAttributes()
            }
            onImageSaved?.invoke(output.toUri())
        }
    }

    private fun createFile(context: Context, extension: String = "jpg"): File {
        val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
        return File(
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            "IMG_${sdf.format(Date())}.$extension"
        )
    }
}