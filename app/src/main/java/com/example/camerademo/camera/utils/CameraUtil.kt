package com.example.camerademo.camera.utils

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata

fun getCameraIds(
    cameraManager: CameraManager,
    facing: Int
): List<String> = runCatching {
    cameraManager.cameraIdList.filter {
        val characteristics = cameraManager.getCameraCharacteristics(it)
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_BACKWARD_COMPATIBLE) ?: false
        capabilities && characteristics.get(CameraCharacteristics.LENS_FACING) == facing
    }
}.getOrDefault(emptyList())