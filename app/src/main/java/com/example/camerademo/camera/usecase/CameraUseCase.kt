package com.example.camerademo.camera.usecase

import android.hardware.camera2.CameraCaptureSession
import android.view.Surface
import com.example.camerademo.camera.Camera
import com.example.camerademo.camera.CameraSetting

interface CameraUseCase {
    fun createSurface(): Surface
    fun onAttach(camera: Camera)
    fun onCameraReady(camera: Camera, setting: CameraSetting, session: CameraCaptureSession) = Unit
    fun onDetach(camera: Camera) = Unit
    fun onSettingChanged(setting: CameraSetting)
    fun focus(x: Float, y: Float, setting: CameraSetting) = Unit
}