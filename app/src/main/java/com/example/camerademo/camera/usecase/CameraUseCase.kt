package com.example.camerademo.camera.usecase

import android.view.Surface
import com.example.camerademo.camera.Camera

interface CameraUseCase {
    fun getSurface(): Surface

    fun onAttach(camera: Camera)
    fun onSessionCreated(camera: Camera) = Unit

    fun onDetach(camera: Camera) = Unit
}