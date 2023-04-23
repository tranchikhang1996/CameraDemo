package com.example.camerademo.camera.usecase

import android.content.Context
import android.media.MediaRecorder
import android.media.MediaRecorder.AudioEncoder
import android.media.MediaRecorder.VideoEncoder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import com.example.camerademo.camera.Camera
import com.example.camerademo.camera.StreamSurface
import com.example.camerademo.camera.TargetAspectRatio
import com.example.camerademo.camera.utils.getVideoSizes
import java.io.File
import java.text.SimpleDateFormat
import java.util.*


class VideoCaptureUseCase(
    private val context: Context,
    private var recordTiming: ((String) -> Unit)?,
    private var onVideoSaved: ((String) -> Unit)?
) : CameraUseCase {

    private val mediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
    private lateinit var camera: Camera
    private var isReady = false

    @Volatile
    private var isPrePared = false

    @Volatile
    private var isRecordingVideo = false
    private lateinit var videoSize: Size
    private lateinit var mediaSurface: Surface

    private val timerThread = HandlerThread("video timer").apply { start() }
    private val timerHandler = Handler(timerThread.looper)
    @Volatile
    private var length = 0L
    private var path: String? = null

    @Synchronized
    private fun setupMediaRecorder() {
        if (isPrePared) return
        mediaRecorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoSize(videoSize.width, videoSize.height)
            setVideoFrameRate(30)
            val path = createFile().absolutePath
            this@VideoCaptureUseCase.path = path
            setOutputFile(path)
            setVideoEncodingBitRate(10_000_000)
            setAudioEncoder(AudioEncoder.AAC)
            setVideoEncoder(VideoEncoder.H264)
            setOrientationHint(90)
            prepare()
            this@VideoCaptureUseCase.mediaSurface = surface
            isPrePared = true
        }
    }

    override fun getSurface(): Surface = mediaSurface

    override fun onAttach(camera: Camera) {
        this.camera = camera
        videoSize = getVideoSizes(
            camera.characteristics,
            MediaRecorder::class.java
        )[TargetAspectRatio.SIZE_3_4]!!
        setupMediaRecorder()
    }

    @Synchronized
    override fun onSessionCreated(camera: Camera) {
        if (isRecordingVideo && isPrePared) {
            camera.registerFrameStream(StreamSurface("Video", mediaSurface)) {
                startTimer()
                mediaRecorder.start()
            }
        }
        isReady = true
    }

    @Synchronized
    override fun onDetach(camera: Camera) {
        timerHandler.removeCallbacksAndMessages(null)
        timerThread.quitSafely()
        mediaRecorder.stop()
        mediaRecorder.reset()
        isPrePared = false
        isRecordingVideo = false
        isReady = false
        recordTiming = null
        onVideoSaved = null
    }

    @Synchronized
    fun startRecord() {
        if (!isReady || isRecordingVideo) return
        if (isPrePared) {
            camera.registerFrameStream(StreamSurface("Video", mediaSurface)) {
                startTimer()
                mediaRecorder.start()
            }
        } else {
            setupMediaRecorder()
            camera.newSession()
        }
        isRecordingVideo = true
    }

    private fun startTimer() {
        length = 0L
        recordTiming?.invoke("0:00")
        increaseTime()
    }

    private fun increaseTime() {
        timerHandler.postDelayed(
            {
                length += 1
                recordTiming?.invoke("${length / 60}:${length % 60}")
                increaseTime()
            },
            1000L
        )
    }

    private fun stopTimer() {
        timerHandler.removeCallbacksAndMessages(null)
        length = 0L
        recordTiming?.invoke("0:00")
    }

    @Synchronized
    fun stopRecord() {
        if (!isReady || !isRecordingVideo) return
        camera.unregisterFrameStream(StreamSurface("Video", mediaSurface)) {
            isPrePared = false
            isRecordingVideo = false
            stopTimer()
            mediaRecorder.stop()
            mediaRecorder.reset()
            path?.let { onVideoSaved?.invoke(it) }
        }
    }

    private fun createFile(): File {
        val sdf = SimpleDateFormat("yyyy_MM_dd_HH_mm_ss_SSS", Locale.US)
        return File(
            context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
            "Vid_${sdf.format(Date())}.mp4"
        )
    }
}