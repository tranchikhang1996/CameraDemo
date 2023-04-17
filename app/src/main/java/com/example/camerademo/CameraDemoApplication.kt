package com.example.camerademo

import android.app.Application

class CameraDemoApplication : Application() {
    companion object {
        @Volatile lateinit var instance: CameraDemoApplication
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}