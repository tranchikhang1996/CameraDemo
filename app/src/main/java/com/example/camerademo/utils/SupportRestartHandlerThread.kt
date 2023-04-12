package com.example.camerademo.utils

import android.os.Handler
import android.os.HandlerThread


class SupportRestartHandlerThread(private val name: String) {
    private var handlerThread: HandlerThread = HandlerThread(name).apply { start() }
    private var handler = Handler(handlerThread.looper)
    private var isShutdown = false

    @Synchronized
    fun shutdown() {
        if (!isShutdown) {
            handler.removeCallbacksAndMessages(null)
            handlerThread.quitSafely()
            isShutdown = true
        }
    }

    @Synchronized
    fun restart() {
        if (isShutdown) {
            handlerThread = HandlerThread(name).apply { start() }
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