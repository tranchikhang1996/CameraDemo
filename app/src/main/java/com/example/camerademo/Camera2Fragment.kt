package com.example.camerademo

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.*
import android.hardware.camera2.CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM
import android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE
import android.hardware.camera2.CameraDevice.TEMPLATE_PREVIEW
import android.hardware.camera2.CameraDevice.TEMPLATE_STILL_CAPTURE
import android.media.Image
import android.media.ImageReader
import android.os.*
import android.view.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.math.MathUtils
import androidx.core.net.toUri
import androidx.core.view.isVisible
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import com.example.camerademo.databinding.Camera2FragmentLayoutBinding
import com.example.camerademo.utils.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class Camera2Fragment : Fragment(), SurfaceHolder.Callback {
    private lateinit var binding: Camera2FragmentLayoutBinding
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    private val cameraManager: CameraManager by lazy {
        requireContext().applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    @Volatile
    private lateinit var cameraId: String

    @Volatile
    private lateinit var characteristics: CameraCharacteristics

    @Volatile
    private lateinit var camera: CameraDevice

    @Volatile
    private lateinit var captureSession: CameraCaptureSession

    @Volatile
    private lateinit var imageReader: ImageReader

    @Volatile
    private var rotation: Int = 0

    private var cameraRequestExecutor = SupportRestartHandlerThread("camera request")
    private val cameraThread = HandlerThread("camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val readerThread = HandlerThread("imageReaderThread").apply { start() }
    private val readerHandler = Handler(readerThread.looper)
    private val fileExecutor = Executors.newSingleThreadExecutor()

    private var rotationListener: OrientationEventListener? = null

    @Volatile
    private var isSurfaceCreated = false

    @Volatile
    private var sensorSize: Rect? = null

    @Volatile
    private var maxZoom = 1f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val multiplePermissions = ActivityResultContracts.RequestMultiplePermissions()
        permissionLauncher = registerForActivityResult(multiplePermissions) { onPermissionResult() }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = Camera2FragmentLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.captureButton.setOnClickListener { takePhoto() }
        binding.flashLight.setOnClickListener { it.isSelected = !it.isSelected }
        binding.viewFinder.holder.addCallback(this)
    }

    override fun onResume() {
        super.onResume()
        tryToOpenCamera()
    }

    override fun onPause() {
        super.onPause()
        rotationListener?.disable()
        cameraRequestExecutor.shutdown()
        kotlin.runCatching { camera.close() }
    }

    private fun onPermissionResult() {
        if (!allPermissionsGranted) {
            requireActivity().finish()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if(!isSurfaceCreated) {
            cameraId = getFirstCameraIdFacing(cameraManager) ?: return run { requireActivity().finish() }
            characteristics = cameraManager.getCameraCharacteristics(cameraId).apply { setOrientationChangedListener(this) }
            val previewSize = getPreviewOutputSize(
                binding.viewFinder.display,
                characteristics,
                SurfaceHolder::class.java
            )
            binding.viewFinder.setAspectRatio(previewSize.width, previewSize.height)
            val imageSize = getImageOutputSize(
                characteristics,
                ImageFormat.JPEG,
                previewSize.width,
                previewSize.height
            )
            imageReader = ImageReader.newInstance(
                imageSize.size.width,
                imageSize.size.height,
                ImageFormat.JPEG,
                IMAGE_BUFFER_SIZE
            ).apply {
                setOnImageAvailableListener(
                    { reader -> savePhoto(reader.acquireNextImage()) }, readerHandler
                )
            }
        }
        isSurfaceCreated = true
    }

    override fun surfaceChanged(p0: SurfaceHolder, p1: Int, p2: Int, p3: Int) = Unit

    override fun surfaceDestroyed(p0: SurfaceHolder) {
        isSurfaceCreated = false
    }

    private fun tryToOpenCamera() {
        if (allPermissionsGranted) {
            cameraHandler.post {
                @Suppress("ControlFlowWithEmptyBody")
                while (!isSurfaceCreated) { }
                openCamera()
            }
        } else {
            permissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val cameraDeviceCallBack = object : CameraDevice.StateCallback() {
            override fun onOpened(cameraDevice: CameraDevice) {
                this@Camera2Fragment.camera = cameraDevice
                onCameraOpenedSuccess(cameraDevice)
            }

            override fun onDisconnected(cameraDevice: CameraDevice) {
                requireActivity().finish()
            }

            override fun onError(cameraDevice: CameraDevice, error: Int) {
                requireActivity().finish()
            }
        }
        cameraManager.openCamera(cameraId, cameraDeviceCallBack, cameraHandler)
    }

    private fun setOrientationChangedListener(cameraCharacteristics: CameraCharacteristics) {
        rotationListener = object : OrientationEventListener(requireContext().applicationContext) {
            override fun onOrientationChanged(orientation: Int) {
                val surfaceRotation = when {
                    orientation <= 45 -> Surface.ROTATION_0
                    orientation <= 135 -> Surface.ROTATION_90
                    orientation <= 225 -> Surface.ROTATION_180
                    orientation <= 315 -> Surface.ROTATION_270
                    else -> Surface.ROTATION_0
                }
                rotation = computeRelativeRotation(cameraCharacteristics, surfaceRotation)
            }
        }.apply { enable() }
    }

    private fun onCameraOpenedSuccess(cameraDevice: CameraDevice) {
        val targets = listOf(binding.viewFinder.holder.surface, imageReader.surface)
        val sessionCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) {
                this@Camera2Fragment.captureSession = session
                session.device.createCaptureRequest(TEMPLATE_PREVIEW).submitRepeating({
                    addTarget(binding.viewFinder.holder.surface)
                })
                view?.post { setupZoomControl() }
                cameraRequestExecutor.restart()
            }

            override fun onConfigureFailed(session: CameraCaptureSession) {
                session.device.close()
                requireActivity().finish()
            }
        }
        cameraDevice.createCaptureSession(targets, sessionCallback, cameraHandler)
    }

    private fun takePhoto() = cameraRequestExecutor.submit {
        view?.post { binding.captureButton.isEnabled = false }
        captureSession.device.createCaptureRequest(TEMPLATE_STILL_CAPTURE)
            .submit {
                addTarget(imageReader.surface)
                if (binding.flashLight.isSelected) {
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                } else {
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }
                getZoomRegion(binding.zoomSlider.value)?.let {
                    set(CaptureRequest.SCALER_CROP_REGION, it)
                }
            }
        view?.post { binding.captureButton.isEnabled = true }
    }

    private fun setupZoomControl() {
        binding.zoomSliderContainer.isVisible = false
        sensorSize = characteristics.get(SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        maxZoom = characteristics.get(SCALER_AVAILABLE_MAX_DIGITAL_ZOOM)?.takeIf { it.compareTo(1f) > 0 } ?: return

        binding.zoomSlider.valueFrom = 1f
        binding.zoomSlider.value = 1f
        binding.zoomSlider.valueTo = maxZoom
        binding.zoomSlider.clearOnChangeListeners()

        binding.zoomSlider.addOnChangeListener { _, zoom, _ ->
            cameraRequestExecutor.submit {
                captureSession.device.createCaptureRequest(TEMPLATE_PREVIEW).submitRepeating({
                    addTarget(binding.viewFinder.holder.surface)
                    getZoomRegion(zoom)?.let { set(CaptureRequest.SCALER_CROP_REGION, it) }
                })
            }
        }
        binding.zoomSliderContainer.isVisible = true
    }

    private fun getZoomRegion(zoomValue: Float): Rect? = sensorSize?.let {
        val newZoom = MathUtils.clamp(zoomValue, 1f, maxZoom)
        val centerX = it.width() / 2
        val centerY = it.height() / 2
        val deltaX = ((0.5f * it.width()) / newZoom).roundToInt()
        val deltaY = ((0.5f * it.height()) / newZoom).roundToInt()
        Rect(centerX - deltaX, centerY - deltaY, centerX + deltaX, centerY + deltaY)
    }

    private fun CaptureRequest.Builder.submitRepeating(
        config: CaptureRequest.Builder.() -> Unit,
        shouldStopPreRequest: Boolean = true
    ) {
        config(this)
        if (shouldStopPreRequest) {
            captureSession.stopRepeating()
        }
        captureSession.setRepeatingRequest(this.build(), null, null)
    }

    private fun CaptureRequest.Builder.submit(config: CaptureRequest.Builder.() -> Unit) {
        config(this)
        captureSession.capture(this.build(), null, null)
    }

    private fun savePhoto(image: Image) = fileExecutor.execute {
        image.use {
            val mirrored = characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            val exifOrientation = computeExifOrientation(rotation, mirrored)
            val buffer = it.planes[0].buffer
            val bytes = ByteArray(buffer.remaining()).apply { buffer.get(this) }
            val output = createFile(requireContext())
            FileOutputStream(output).buffered().use { buffered -> buffered.write(bytes) }
            val exif = ExifInterface(output.absolutePath)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
            exif.saveAttributes()
            binding.captureButton.post {
                (requireActivity() as? MainActivity)?.showResult(output.toUri())
            }
        }
    }

    private val allPermissionsGranted get() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.viewFinder.holder.removeCallback(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        fileExecutor.shutdown()
        cameraThread.quitSafely()
        readerThread.quitSafely()
        permissionLauncher.unregister()
    }

    private fun createFile(context: Context, extension: String = "jpg"): File {
        val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
        return File(
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            "IMG_${sdf.format(Date())}.$extension"
        )
    }

    companion object {
        private const val IMAGE_BUFFER_SIZE = 3
        private val REQUIRED_PERMISSIONS = mutableListOf(Manifest.permission.CAMERA).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
}