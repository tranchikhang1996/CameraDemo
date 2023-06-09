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
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.children
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.example.camerademo.camera.*
import com.example.camerademo.camera.TargetAspectRatio.SIZE_3_4
import com.example.camerademo.camera.usecase.PhotoCaptureUseCase
import com.example.camerademo.camera.usecase.PreviewUseCase
import com.example.camerademo.camera.usecase.VideoCaptureUseCase
import com.example.camerademo.camera.utils.*
import com.example.camerademo.databinding.Camera2FragmentLayoutBinding
import java.util.*
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow

class Camera2Fragment : Fragment(), SurfaceHolder.Callback {
    private lateinit var binding: Camera2FragmentLayoutBinding
    private lateinit var permissionLauncher: ActivityResultLauncher<Array<String>>

    private var camera: Camera? = null
    private var previewUseCase: PreviewUseCase? = null
    private var photoUseCase: PhotoCaptureUseCase? = null
    private var videoUseCase: VideoCaptureUseCase? = null
    private val lenFacings = listOf(LENS_FACING_BACK, LENS_FACING_FRONT).filter {
        !Camera.cameraIds[it].isNullOrEmpty()
    }

    private var currentLen: Int = LENS_FACING_BACK

    private var setting = CameraSetting()

    private var sliderTag: Any? = null

    private var mode: String = "photo"

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
        binding.switchCam.setOnClickListener { switchLen() }
        setupCaptureButton()
        setupSlider()
        setupModes()
    }

    private fun setupCaptureButton() {
        binding.captureButton.setOnClickListener {
            if (mode == "photo") {
                photoUseCase?.capture()
            } else {
                if(binding.captureButton.isSelected) {
                    videoUseCase?.stopRecord()
                } else {
                    videoUseCase?.startRecord()
                }
                binding.captureButton.isSelected = !binding.captureButton.isSelected
            }
        }
    }

    private fun setupModes() {
        binding.modes.children.forEach {
            it.isSelected = (it.tag as String) == mode
        }
        binding.photo.setOnClickListener { view ->
            for (child in binding.modes.children) {
                child.isSelected = view == child
            }
            changeMode(view.tag as String)
            binding.videoTime.isVisible = false
            binding.captureButton.setBackgroundResource(R.drawable.ic_capture)
        }

        binding.video.setOnClickListener { view ->
            mode = view.tag as String
            for (child in binding.modes.children) {
                child.isSelected = view == child
            }
            changeMode(view.tag as String)

            binding.videoTime.isVisible = true
            binding.captureButton.setBackgroundResource(R.drawable.video_button_background)
            binding.captureButton.isSelected = false
        }
    }

    private fun changeMode(mode: String) {
        this.mode = mode
        Camera.close()
        tryToOpenCamera()
    }

    @SuppressLint("SetTextI18n")
    private fun resetSetting() {
        setting = CameraSetting()
        binding.whiteBalanceMode.text = "WB\nAuto"
        binding.focusDistanceMode.text = "F\nAuto"
        binding.hdrMode.text = "HDR\nOff"
        binding.isoMode.text = "ISO\nAuto"
        binding.shutterSpeedMode.text = "S\nAuto"
        binding.effectMode.text = "Effect\nOriginal"
        binding.flashLight.isSelected = false
        sliderTag = null
        binding.sliderFrame.isVisible = false
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
                binding.shutterSpeedMode.tag -> {
                    val tagValues = binding.shutterSpeedMode.tag.toString().split(':')
                    val minValue = tagValues[1].toLong()
                    val maxValue = tagValues[2].toLong()
                    val ss = (minValue * (2.0.pow(value.toDouble()))).toLong()
                    val validSS = if(ss < minValue) minValue else if(ss > maxValue) maxValue else ss
                    binding.shutterSpeedMode.text = String.format(Locale.US, "S\n%s", formatSS(validSS))
                    setting.copy(shutterSpeed = validSS).submit()
                }
            }
        }

        binding.auto.setOnClickListener {
            when (sliderTag) {
                binding.focusDistanceMode.tag -> {
                    binding.focusDistanceMode.text = String.format(Locale.US, "F\n%s", "Auto")
                    setting.copy(focusDistance = null).submit()
                }
                binding.isoMode.tag -> {
                    binding.isoMode.text = String.format(Locale.US, "ISO\n%s", "Auto")
                    setting.copy(iso = null).submit()
                }
                binding.shutterSpeedMode.tag -> {
                    binding.shutterSpeedMode.text = String.format(Locale.US, "S\n%s", "Auto")
                    setting.copy(shutterSpeed = null).submit()
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
                    setting.copy(zoomFactor = newZoom, zoomRegion = cam.getZoomRegion(newZoom))
                        .submit()
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
        previewUseCase?.getPreViewSize()?.let {
            camera?.manualFocus(event.x, event.y, it)
        }
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
                binding.sliderFrame.isVisible = false
            } else {
                sliderTag = it.tag
                binding.sliderFrame.isVisible = true
                binding.valueSlider.stepSize = 0.1f
                binding.valueSlider.valueFrom = 0f
                binding.valueSlider.valueTo = minimumDistance
                binding.valueSlider.value = setting.focusDistance ?: 0f
            }
            it.isSelected = !it.isSelected
        }
    }

    private fun setupShutterSpeed(range: Range<Long>?) {
        range ?: return binding.shutterSpeedMode.setOnClickListener(null)
        val minimum = if(1_000_000L in range) 1_000_000L else range.lower
        val maximum = if(range.upper > 125_000_000L) 125_000_000L else range.upper
        val numStep = ceil(ln(maximum.toDouble() / minimum.toDouble()) / ln(2.0))
        binding.shutterSpeedMode.tag = "SS:${minimum}:${maximum}"
        binding.shutterSpeedMode.setOnClickListener {
            if(it.isSelected) {
                sliderTag = null
                binding.sliderFrame.isVisible = false
            } else {
                sliderTag = it.tag
                binding.sliderFrame.isVisible = true
                binding.valueSlider.stepSize = 1f
                binding.valueSlider.valueFrom = 0f
                binding.valueSlider.valueTo = numStep.toFloat()
                val sliderValue = setting.shutterSpeed?.let { ss ->
                    ceil(ln(ss.toDouble() / minimum.toDouble()) / ln(2.0)).toFloat()
                } ?: 0f
                binding.valueSlider.value = sliderValue
            }
            it.isSelected = !it.isSelected
        }
    }

    private fun formatSS(value: Long): String {
        val secondToNano = 1_000_000_000L
        return if (value >= secondToNano) {
            "${value / secondToNano}"
        } else {
            "1/${(secondToNano / value)}"
        }
    }

    private fun setupISOControl(range: Range<Int>?) {
        range ?: return binding.isoMode.setOnClickListener(null)
        binding.isoMode.setOnClickListener {
            if (it.isSelected) {
                sliderTag = null
                binding.sliderFrame.isVisible = false
            } else {
                sliderTag = it.tag
                binding.sliderFrame.isVisible = true
                binding.valueSlider.stepSize = 0.1f
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
            binding.hdrMode.text = String.format("HDR\n%s", if(it.isSelected) "On" else "Off")
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
        if (allPermissionsGranted) {
            tryToOpenCamera()
        } else {
            requireActivity().finish()
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        binding.switchCam.isVisible = true
        binding.modes.isVisible = true
        tryToOpenCamera()
    }

    override fun surfaceChanged(p0: SurfaceHolder, p1: Int, p2: Int, p3: Int) = Unit

    override fun surfaceDestroyed(p0: SurfaceHolder) {
        binding.switchCam.isVisible = false
        binding.modes.isVisible = false
    }

    private fun switchLen() {
        val oldLen = currentLen
        currentLen = lenFacings[(lenFacings.indexOf(currentLen) + 1) % lenFacings.size]
        if (oldLen == currentLen) return
        Log.d("CAMERA_TEST", "switch len with $currentLen")
        Camera.close()
        tryToOpenCamera()
    }

    private fun tryToOpenCamera() {
        if (!allPermissionsGranted) {
            return permissionLauncher.launch(REQUIRED_PERMISSIONS)
        }
        resetSetting()
        val previewUseCase = PreviewUseCase(binding.viewFinder, SIZE_3_4)
        val camera = when (mode) {
            "photo" -> {
                photoUseCase = PhotoCaptureUseCase(requireContext(), SIZE_3_4) {
                    view?.post { (requireActivity() as MainActivity).showResult(it) }
                }
                Camera.open(currentLen, setting, previewUseCase, photoUseCase!!)
            }
            "video" -> {
                videoUseCase = VideoCaptureUseCase(requireContext(), {
                    binding.root.post { binding.videoTime.text = it}
                }, {
                    binding.root.post { Toast.makeText(requireContext(), it, Toast.LENGTH_SHORT).show() }
                })
                Camera.open(currentLen, setting, previewUseCase, videoUseCase!!)
            }
            else -> throw RuntimeException("$mode is not support")
        }
        camera.apply {
            setupFlashControl()
            setupAWBControl()
            setupEffectControl()
            setupHdrControl()
            setupFocusDistanceControl(characteristics.get(LENS_INFO_MINIMUM_FOCUS_DISTANCE))
            setupISOControl(characteristics.get(SENSOR_INFO_SENSITIVITY_RANGE))
            setupShutterSpeed(characteristics.get(SENSOR_INFO_EXPOSURE_TIME_RANGE))
        }
        this.camera = camera
    }

    private val allPermissionsGranted
        get() = REQUIRED_PERMISSIONS.all {
            ContextCompat.checkSelfPermission(
                requireContext(),
                it
            ) == PackageManager.PERMISSION_GRANTED
        }

    override fun onDestroyView() {
        view?.handler?.removeCallbacksAndMessages(null)
        super.onDestroyView()
    }

    override fun onDestroy() {
        permissionLauncher.unregister()
        super.onDestroy()
    }

    companion object {
        private val REQUIRED_PERMISSIONS = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
}