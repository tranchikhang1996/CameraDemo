package com.example.camerademo

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.media.Image
import android.media.ImageReader
import android.net.Uri
import android.os.*
import android.view.*
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.exifinterface.media.ExifInterface
import androidx.fragment.app.Fragment
import com.example.camerademo.databinding.Camera2FragmentLayoutBinding
import com.example.camerademo.utils.*
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.Executors

class Camera2Fragment : Fragment(), SurfaceHolder.Callback {
    private lateinit var binding: Camera2FragmentLayoutBinding
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    private val cameraManager: CameraManager by lazy {
        requireContext().applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    @Volatile
    private lateinit var cameraId: String

    @Volatile
    private lateinit var cameraCharacteristics: CameraCharacteristics

    @Volatile
    private lateinit var camera: CameraDevice

    @Volatile
    private lateinit var cameraCaptureSession: CameraCaptureSession

    @Volatile
    private lateinit var imageReader: ImageReader

    @Volatile
    private var rotation: Int = 0

    private val cameraThread = HandlerThread("CameraThread").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val cameraExecutor = Executors.newSingleThreadExecutor { cameraThread }
    private val imageReaderThread = HandlerThread("imageReaderThread").apply { start() }
    private val imageReaderHandler = Handler(imageReaderThread.looper)
    private val fileExecutor = Executors.newSingleThreadExecutor()

    private var rotationListener: OrientationEventListener? = null

    @Volatile
    private var isSurfaceCreated = false


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
        cameraId = getFirstCameraIdFacing(cameraManager) ?: return run { requireActivity().finish() }
        cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId).apply {
            setOrientationChangedListener(this)
        }
        rotationListener?.enable()
        binding.captureButton.isEnabled = false
        binding.captureButton.setOnClickListener {
            it.isEnabled = false
            takePhoto()
        }
        binding.viewFinder.holder.addCallback(this)
    }

    override fun onResume() {
        super.onResume()
        tryToOpenCamera()
    }

    override fun onPause() {
        super.onPause()
        kotlin.runCatching { camera.close() }
    }
    override fun surfaceCreated(holder: SurfaceHolder) {
        if (!isSurfaceCreated) {
            val previewSize = getPreviewOutputSize(
                binding.viewFinder.display,
                cameraCharacteristics,
                SurfaceHolder::class.java
            )
            binding.viewFinder.setAspectRatio(previewSize.width, previewSize.height)
            val imageSize = getImageOutputSize(
                cameraCharacteristics,
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
                    { reader -> savePhoto(reader.acquireLatestImage()) },
                    imageReaderHandler
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
        if (allPermissionsGranted()) {
            cameraHandler.post {
                @Suppress("ControlFlowWithEmptyBody")
                while (!isSurfaceCreated) { }
                openCamera(cameraId)
            }
        } else {
            permissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    private fun onPermissionResult() {
        if (!allPermissionsGranted()) {
            requireActivity().finish()
        }
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
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera(camId: String) {
        val callback = object : CameraDevice.StateCallback() {
            override fun onOpened(cameraDevice: CameraDevice) {
                onCameraOpenedSuccess(cameraDevice)
            }

            override fun onDisconnected(cameraDevice: CameraDevice) {
                requireActivity().finish()
            }

            override fun onError(cameraDevice: CameraDevice, error: Int) {
                val msg = when (error) {
                    ERROR_CAMERA_DEVICE -> "Fatal (device)"
                    ERROR_CAMERA_DISABLED -> "Device policy"
                    ERROR_CAMERA_IN_USE -> "Camera in use"
                    ERROR_CAMERA_SERVICE -> "Fatal (service)"
                    ERROR_MAX_CAMERAS_IN_USE -> "Maximum cameras in use"
                    else -> "Unknown"
                }
                Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
                requireActivity().finish()
            }
        }
        cameraManager.openCamera(camId, callback, cameraHandler)
    }

    private fun onCameraOpenedSuccess(cameraDevice: CameraDevice) {
        this.camera = cameraDevice
        val targets = listOf(binding.viewFinder.holder.surface, imageReader.surface)

        val callback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                onSessionConfigured(cameraCaptureSession)
            }

            override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                cameraCaptureSession.device.close()
            }
        }
        cameraDevice.createCaptureSession(targets, callback, cameraHandler)
    }

    private fun onSessionConfigured(cameraCaptureSession: CameraCaptureSession) {
        this.cameraCaptureSession = cameraCaptureSession
        val captureRequest =
            cameraCaptureSession.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(binding.viewFinder.holder.surface)
            }
        cameraCaptureSession.setRepeatingRequest(captureRequest.build(), null, cameraHandler)

        binding.captureButton.post { binding.captureButton.isEnabled = true }
    }

    private fun takePhoto() {
        val captureRequest =
            cameraCaptureSession.device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                .apply { addTarget(imageReader.surface) }
        cameraCaptureSession.capture(captureRequest.build(), null, cameraHandler)
    }

    private fun savePhoto(image: Image) = fileExecutor.execute {
        image.use {
            val mirrored = cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
            val exifOrientation = computeExifOrientation(rotation, mirrored)
            val buffer = it.planes[0].buffer
            val bytes = ByteArray(buffer.remaining()).apply { buffer.get(this) }
            val output = createFile(requireContext())
            FileOutputStream(output).buffered().use { buffered -> buffered.write(bytes) }
            val exif = ExifInterface(output.absolutePath)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, exifOrientation.toString())
            exif.saveAttributes()
            binding.captureButton.post { onImageSaved(output.toUri()) }
        }
    }

    private fun onImageSaved(uri: Uri) {
        binding.captureButton.isEnabled = true
        (requireActivity() as? MainActivity)?.showResult(uri)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroyView() {
        super.onDestroyView()
        rotationListener?.disable()
        binding.viewFinder.holder.removeCallback(this)
    }
    override fun onDestroy() {
        super.onDestroy()
        cameraHandler.removeCallbacksAndMessages(null)
        imageReaderHandler.removeCallbacksAndMessages(null)
        fileExecutor.shutdown()
        cameraExecutor.shutdown()
        cameraThread.quitSafely()
        imageReaderThread.quitSafely()
        permissionLauncher.unregister()
    }

    private fun createFile(context: Context, extension: String = "jpg"): File {
        val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
        return File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "IMG_${sdf.format(Date())}.$extension")
    }

    companion object {
        private const val IMAGE_BUFFER_SIZE = 1
        private val REQUIRED_PERMISSIONS = mutableListOf(Manifest.permission.CAMERA).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
}