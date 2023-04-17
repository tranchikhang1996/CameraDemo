package com.example.camerademo

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.hardware.camera2.*
import android.hardware.camera2.CameraCharacteristics.*
import android.os.*
import android.util.Log
import android.view.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.example.camerademo.camera.*
import com.example.camerademo.camera.usecase.PhotoCaptureUseCase
import com.example.camerademo.camera.usecase.PreviewUseCase
import com.example.camerademo.camera.utils.*
import com.example.camerademo.databinding.Camera2FragmentLayoutBinding
import java.util.*

class Camera2Fragment : Fragment(), SurfaceHolder.Callback {
    private lateinit var binding: Camera2FragmentLayoutBinding
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    private var camera: Camera? = null
    private var captureUseCase: PhotoCaptureUseCase? = null
    private val lenFacings = listOf(LENS_FACING_BACK, LENS_FACING_FRONT).filter {
        !Camera.cameraIds[it].isNullOrEmpty()
    }

    private var currentLen: Int = LENS_FACING_BACK

    private val tapToFocusListener = object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(event: MotionEvent): Boolean {
            return tapToFocus(event)
        }
    }

    private fun tapToFocus(event: MotionEvent): Boolean {
        binding.focusView.showFocus(event.x.toInt(), event.y.toInt())
        camera?.zoom(1f)
        camera?.manualFocus(event.x, event.y)
        return true
    }

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

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (lenFacings.isEmpty()) {
            return requireActivity().finish()
        }
        if (lenFacings.size  < 2) {
            binding.switchCam.isVisible = false
        }
        currentLen = lenFacings.first()
        val tapToFocusGestureDetector = GestureDetector(requireContext(), tapToFocusListener)
        binding.viewFinder.setOnTouchListener { _, event ->
            tapToFocusGestureDetector.onTouchEvent(event)
            true
        }
        binding.captureButton.setOnClickListener { captureUseCase?.capture() }
        binding.flashLight.setOnClickListener {
            it.isSelected = !it.isSelected
            camera?.setFlash(if (it.isSelected) FlashOn else FlashOff)
        }
        binding.switchCam.setOnClickListener {
            switchLen()
        }
    }

    override fun onResume() {
        super.onResume()
        binding.viewFinder.holder.addCallback(this)
    }

    override fun onPause() {
        super.onPause()
        kotlin.runCatching {
            binding.viewFinder.holder.removeCallback(this)
            camera?.close()
            camera = null
        }
    }

    private fun onPermissionResult() {
        if (!allPermissionsGranted) {
            requireActivity().finish()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        binding.switchCam.isEnabled = true
        tryToOpenCamera()
    }

    override fun surfaceChanged(p0: SurfaceHolder, p1: Int, p2: Int, p3: Int) = Unit

    override fun surfaceDestroyed(p0: SurfaceHolder) {
        binding.switchCam.isEnabled = false
    }

    private fun switchLen() {
        currentLen = lenFacings[(lenFacings.indexOf(currentLen) + 1) % lenFacings.size]
        Log.d("CAMERA_TEST", "switch len with $currentLen")
        camera?.close()
        camera = null
        tryToOpenCamera()
    }

    private fun setupZoomControl(camera: Camera) {
        val zoomRange = camera.getZoomRange()
        binding.zoomSlider.valueFrom = zoomRange.first
        binding.zoomSlider.valueTo = zoomRange.second
        binding.zoomSlider.value = 1f
        binding.zoomSlider.clearOnChangeListeners()
        binding.zoomSlider.addOnChangeListener { _, value, _ -> camera.zoom(value) }
    }

    private fun tryToOpenCamera() {
        if (allPermissionsGranted) {
            val setting = CameraSetting(if (binding.flashLight.isSelected) FlashOff else FlashOff, Zoom(null))
            val previewUseCase = PreviewUseCase(binding.viewFinder, TargetAspectRatio.SIZE_3_4)
            val captureUseCase = PhotoCaptureUseCase(requireContext(), TargetAspectRatio.SIZE_3_4) {
                view?.post { (requireActivity() as MainActivity).showResult(it) }
            }
            this.captureUseCase = captureUseCase
            camera = Camera(currentLen, setting).apply {
                setupZoomControl(this)
                open(previewUseCase, captureUseCase)
            }
        } else {
            permissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    private val allPermissionsGranted
        get() = REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED
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