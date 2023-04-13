package com.example.camerademo.utils

import android.os.Handler
import android.os.HandlerThread


class SupportRestartHandlerThread(private val name: String) {
    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler
    private var isShutdown = true

    @Synchronized
    fun shutdown() {
        if (!isShutdown) {
            handlerThread.quitSafely()
            isShutdown = true
        }
    }

    @Synchronized
    fun restart() {
        if (isShutdown) {
            handlerThread = HandlerThread(name).apply { start() }
            handler = Handler(handlerThread.looper)
            isShutdown = false
        }
    }

    @Synchronized
    fun submit(task: Runnable) {
        if (!isShutdown) {
            handler.post(task)
        }
    }
}