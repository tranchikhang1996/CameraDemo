package com.example.camerademo.camera.utils

import android.hardware.camera2.CameraCharacteristics
import android.view.Surface
import androidx.exifinterface.media.ExifInterface

fun computeRelativeRotation(characteristics: CameraCharacteristics, surfaceRotation: Int): Int {
    val sensorOrientationDegrees =
        characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION)!!

    val deviceOrientationDegrees = when (surfaceRotation) {
        Surface.ROTATION_0 -> 0
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    // Reverse device orientation for front-facing cameras
    val sign = if (characteristics.get(CameraCharacteristics.LENS_FACING) ==
        CameraCharacteristics.LENS_FACING_FRONT
    ) 1 else -1

    // Calculate desired JPEG orientation relative to camera orientation to make
    // the image upright relative to the device orientation
    return (sensorOrientationDegrees - (deviceOrientationDegrees * sign) + 360) % 360
}

fun computeExifOrientation(rotationDegrees: Int, mirrored: Boolean) = when {
    rotationDegrees == 0 && !mirrored -> ExifInterface.ORIENTATION_NORMAL
    rotationDegrees == 0 && mirrored -> ExifInterface.ORIENTATION_FLIP_HORIZONTAL
    rotationDegrees == 180 && !mirrored -> ExifInterface.ORIENTATION_ROTATE_180
    rotationDegrees == 180 && mirrored -> ExifInterface.ORIENTATION_FLIP_VERTICAL
    rotationDegrees == 90 && !mirrored -> ExifInterface.ORIENTATION_ROTATE_90
    rotationDegrees == 90 && mirrored -> ExifInterface.ORIENTATION_TRANSPOSE
    rotationDegrees == 270 && mirrored -> ExifInterface.ORIENTATION_ROTATE_270
    rotationDegrees == 270 && !mirrored -> ExifInterface.ORIENTATION_TRANSVERSE
    else -> ExifInterface.ORIENTATION_UNDEFINED
}