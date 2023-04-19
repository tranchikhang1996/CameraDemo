package com.example.camerademo

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.hardware.camera2.*
import android.hardware.camera2.CameraCharacteristics.*
import android.os.*
import android.util.Log
import android.util.Range
import android.view.*
import android.widget.AdapterView
import android.widget.ArrayAdapter
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

    private var setting = CameraSetting()

    private var sliderTag: Any? = null

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
        if (lenFacings.size < 2) {
            binding.switchCam.isVisible = false
        }
        currentLen = lenFacings.first()
        val tapToFocusGestureDetector = GestureDetector(requireContext(), tapToFocusListener)
        val pinToZoomDetector = ScaleGestureDetector(requireContext(), scaleListener)
        binding.viewFinder.setOnTouchListener { _, event ->
            pinToZoomDetector.onTouchEvent(event)
            tapToFocusGestureDetector.onTouchEvent(event)
            true
        }
        binding.captureButton.setOnClickListener { captureUseCase?.capture() }
        binding.switchCam.setOnClickListener { switchLen() }
        setupFlashControl()
        setupAWBControl()
        setupEffectControl()
        setupHdrControl()
        setupSlider()
    }

    @SuppressLint("SetTextI18n")
    private fun resetSetting() {
        setting = CameraSetting()
        binding.whiteBalanceMode.text = "WB\nAuto"
        binding.focusDistanceMode.text = "F\nAuto"
        binding.hdrMode.text = "HDR\nOff"
        binding.isoMode.text = "ISO\nAuto"
        binding.effectMode.text = "Effect\nOriginal"
        sliderTag = null
        binding.valueSlider.isVisible = false
    }

    private fun setupSlider() {
        binding.valueSlider.addOnChangeListener { _, value, fromUser ->
            if (!fromUser || sliderTag == null) return@addOnChangeListener
            when (sliderTag) {
                binding.focusDistanceMode.tag -> {
                    binding.focusDistanceMode.text = String.format(Locale.US, "F\n%.1f", value)
                    setting.copy(focusDistance = value).submit()
                }
                binding.isoMode.tag -> {
                    binding.isoMode.text = String.format(Locale.US, "ISO\n%.1f", value)
                    setting.copy(iso = value.toInt()).submit()
                }
            }
        }
    }

    private fun CameraSetting.submit() {
        setting = this
        camera?.changeSetting(this)
    }

    private val tapToFocusListener = object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(event: MotionEvent): Boolean {
            return tapToFocus(event)
        }
    }

    private val hideZoomInfo = { binding.zoomState.isVisible = false }

    private val scaleListener = object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            camera?.let { cam ->
                val zoomRange = cam.zoomRange
                val currentZoom = setting.zoomFactor
                var newZoom = currentZoom * detector.scaleFactor
                if (newZoom.compareTo(zoomRange.lower) < 0) {
                    newZoom = zoomRange.lower
                }
                if (newZoom.compareTo(zoomRange.upper) > 0) {
                    newZoom = zoomRange.upper
                }
                if (newZoom.compareTo(currentZoom) != 0) {
                    setting.copy(zoomFactor = newZoom, zoomRegion = cam.getZoomRegion(newZoom)).submit()
                    view?.handler?.removeCallbacks(hideZoomInfo)
                    view?.post {
                        binding.zoomState.isVisible = true
                        binding.zoomState.text = String.format(Locale.US, "%.1fX", newZoom)
                    }
                    view?.postDelayed(hideZoomInfo, 3000L)
                }
            }
            return true
        }
    }

    private fun tapToFocus(event: MotionEvent): Boolean {
        binding.focusView.showFocus(event.x.toInt(), event.y.toInt())
        camera?.manualFocus(event.x, event.y)
        return true
    }

    private fun setupFlashControl() {
        binding.flashLight.setOnClickListener {
            it.isSelected = !it.isSelected
            setting.copy(flashMode = if (it.isSelected) FlashOn else FlashOff).submit()
        }
    }

    private fun setupAWBControl() {
        ArrayAdapter.createFromResource(
            requireContext(),
            R.array.white_balance_array,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.wbSpinner.adapter = adapter
        }
        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long
            ) {
                val selected = parent?.getItemAtPosition(position) as? String
                val wbMode = when (selected) {
                    "Auto" -> CaptureRequest.CONTROL_AWB_MODE_AUTO
                    "Daylight" -> CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT
                    "Fluorescent" -> CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT
                    "Twilight" -> CaptureRequest.CONTROL_AWB_MODE_TWILIGHT
                    "Cloudy" -> CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT
                    else -> CaptureRequest.CONTROL_AWB_MODE_AUTO
                }
                setting.copy(wbMode = wbMode).submit()
                binding.whiteBalanceMode.text = String.format(Locale.US, "WB\n%s", selected)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.wbSpinner.onItemSelectedListener = listener
        binding.whiteBalanceMode.setOnClickListener {
            binding.wbSpinner.performClick()
        }
    }

    private fun setupEffectControl() {
        ArrayAdapter.createFromResource(
            requireContext(),
            R.array.effect_mode_array,
            android.R.layout.simple_spinner_item
        ).also { adapter ->
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            binding.effectSpinner.adapter = adapter
        }
        val listener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>,
                view: View?,
                position: Int,
                id: Long
            ) {
                val selected = parent.getItemAtPosition(position) as? String
                val effectMode = when (selected) {
                    "Original" -> CaptureRequest.CONTROL_EFFECT_MODE_OFF
                    "Aqua" -> CaptureRequest.CONTROL_EFFECT_MODE_AQUA
                    "Sepia" -> CaptureRequest.CONTROL_EFFECT_MODE_SEPIA
                    "White board" -> CaptureRequest.CONTROL_EFFECT_MODE_WHITEBOARD
                    "Mono" -> CaptureRequest.CONTROL_EFFECT_MODE_MONO
                    "Solarize" -> CaptureRequest.CONTROL_EFFECT_MODE_SOLARIZE
                    else -> CaptureRequest.CONTROL_EFFECT_MODE_OFF
                }
                setting.copy(effectMode = effectMode).submit()
                binding.effectMode.text = String.format(Locale.US, "Effect\n%s", selected)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        binding.effectSpinner.onItemSelectedListener = listener
        binding.effectMode.setOnClickListener {
            binding.effectSpinner.performClick()
        }
    }

    private fun setupFocusDistanceControl(minimumDistance: Float?) {
        minimumDistance ?: return binding.focusDistanceMode.setOnClickListener(null)
        binding.focusDistanceMode.setOnClickListener {
            if (it.isSelected) {
                sliderTag = null
                binding.valueSlider.isVisible = false
            } else {
                sliderTag = it.tag
                binding.valueSlider.isVisible = true
                binding.valueSlider.valueFrom = 0f
                binding.valueSlider.valueTo = minimumDistance
                binding.valueSlider.value = setting.focusDistance ?: 0f
            }
            it.isSelected = !it.isSelected

        }
    }

    private fun setupISOControl(range: Range<Int>?) {
        range ?: return binding.isoMode.setOnClickListener(null)
        binding.isoMode.setOnClickListener {
            if (it.isSelected) {
                sliderTag = null
                binding.valueSlider.isVisible = false
            } else {
                sliderTag = it.tag
                binding.valueSlider.isVisible = true
                binding.valueSlider.valueFrom = range.lower.toFloat()
                binding.valueSlider.valueTo = range.upper.toFloat()
                binding.valueSlider.value = (setting.iso ?: range.lower).toFloat()
            }
            it.isSelected = !it.isSelected
        }
    }

    private fun setupHdrControl() {
        binding.hdrMode.setOnClickListener {
            it.isSelected = !it.isSelected
            setting.copy(hdr = it.isSelected).submit()
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
            Camera.close()
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
        val oldLen = currentLen
        currentLen = lenFacings[(lenFacings.indexOf(currentLen) + 1) % lenFacings.size]
        if (oldLen == currentLen) return
        Log.d("CAMERA_TEST", "switch len with $currentLen")
        Camera.close()
        resetSetting()
        tryToOpenCamera()
    }

    private fun tryToOpenCamera() {
        if (allPermissionsGranted) {
            val previewUseCase = PreviewUseCase(binding.viewFinder, TargetAspectRatio.SIZE_3_4)
            captureUseCase = PhotoCaptureUseCase(requireContext(), TargetAspectRatio.SIZE_3_4) {
                view?.post { (requireActivity() as MainActivity).showResult(it) }
            }
            camera = Camera.open(currentLen, setting, previewUseCase, captureUseCase!!).apply {
                setupFocusDistanceControl(characteristics.get(LENS_INFO_MINIMUM_FOCUS_DISTANCE))
                setupISOControl(characteristics.get(SENSOR_INFO_SENSITIVITY_RANGE))
            }
        } else {
            permissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
    }

    private val allPermissionsGranted
        get() = REQUIRED_PERMISSIONS.all { ContextCompat.checkSelfPermission(requireContext(), it) == PackageManager.PERMISSION_GRANTED }

    override fun onDestroyView() {
        view?.handler?.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    override fun onDestroy() {
        permissionLauncher.unregister()
        super.onDestroy()
    }

    companion object {
        private val REQUIRED_PERMISSIONS = mutableListOf(Manifest.permission.CAMERA).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
}