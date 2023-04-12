package com.example.camerademo

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.example.camerademo.databinding.CameraxFragmentLayoutBinding
import java.io.File

class CameraXFragment : Fragment() {

    private lateinit var binding: CameraxFragmentLayoutBinding

    @Volatile
    private var imageCapture: ImageCapture? = null
    private lateinit var orientationEventListener: OrientationEventListener
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

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
        binding = CameraxFragmentLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.capture.setOnClickListener { takePhoto() }
        initOrientationEventListener()
        tryToStartCamera()
    }

    override fun onPause() {
        super.onPause()
        binding.zoomSlider.value = 1f
        binding.exposureSlider.value = 0f
    }

    private fun tryToStartCamera() {
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            permissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    private fun onPermissionResult() {
        if (allPermissionsGranted()) {
            startCamera()
        } else {
            Toast.makeText(requireContext(), "Permissions not granted!", Toast.LENGTH_SHORT).show()
            requireActivity().finish()
        }
    }

    private fun initOrientationEventListener() {
        orientationEventListener = object : OrientationEventListener(requireContext()) {
            override fun onOrientationChanged(orientation: Int) {
                imageCapture?.targetRotation = when (orientation) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }
        orientationEventListener.enable()
    }

    private fun setZoomSlider(camera: Camera) = binding.zoomSlider.apply {
        binding.zoomSliderContainer.isVisible = true
        val minZoomRatio = camera.cameraInfo.zoomState.value?.minZoomRatio ?: 0f
        val maxZoomRatio = camera.cameraInfo.zoomState.value?.maxZoomRatio ?: 1f
        valueFrom = minZoomRatio
        valueTo = maxZoomRatio
        stepSize = 1f
        value = 1f
        clearOnChangeListeners()
        addOnChangeListener { _, v, _ -> camera.cameraControl.setZoomRatio(v) }
    }

    private fun setTorch(camera: Camera) {
        binding.flashLight.setOnClickListener {
            it.isSelected = !it.isSelected
            camera.cameraControl.enableTorch(it.isSelected)
        }
    }

    private fun setExposureSlider(camera: Camera) = binding.exposureSlider.apply {
        binding.exposureSliderContainer.isVisible = true
        isVisible = camera.cameraInfo.exposureState.isExposureCompensationSupported
        valueFrom = camera.cameraInfo.exposureState.exposureCompensationRange.lower.toFloat()
        valueTo = camera.cameraInfo.exposureState.exposureCompensationRange.upper.toFloat()
        value = camera.cameraInfo.exposureState.exposureCompensationIndex.toFloat()
        stepSize = camera.cameraInfo.exposureState.exposureCompensationStep.toFloat()
        clearOnChangeListeners()
        addOnChangeListener { _, v, _ -> camera.cameraControl.setExposureCompensationIndex(v.toInt()) }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build()
                .also { it.setSurfaceProvider(binding.viewFinder.surfaceProvider) }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            imageCapture = ImageCapture.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .build()

            kotlin.runCatching {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture)
            }.getOrNull()?.let {
                setZoomSlider(it)
                setTorch(it)
                setExposureSlider(it)
            }
        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun takePhoto() {
        val file = File(requireContext().externalCacheDir, "capture.png")
        val outputFileOptions = ImageCapture.OutputFileOptions.Builder(file).build()
        imageCapture?.takePicture(outputFileOptions,
            ContextCompat.getMainExecutor(requireContext()),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    outputFileResults.savedUri?.let { onImageCaptured(it) }
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(requireContext(), "Saved Image error", Toast.LENGTH_SHORT).show()
                }
            })
    }

    private fun onImageCaptured(uri: Uri) {
        (activity as? MainActivity)?.showResult(uri)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroyView() {
        super.onDestroyView()
        orientationEventListener.disable()
    }

    override fun onDestroy() {
        super.onDestroy()
        permissionLauncher.unregister()
    }

    companion object {
        private val REQUIRED_PERMISSIONS = mutableListOf(Manifest.permission.CAMERA).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }

}