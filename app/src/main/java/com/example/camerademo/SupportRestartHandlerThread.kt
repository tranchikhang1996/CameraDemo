package com.example.camerademo

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
    fun restart(): Handler {
        return if (isShutdown) {
            handlerThread = HandlerThread(name).apply { start() }
            Handler(handlerThread.looper).apply {
                this@SupportRestartHandlerThread.handler = this
                isShutdown = false
            }
        } else handler
    }

    @Synchronized
    fun submit(task: Runnable) {
        if (!isShutdown) {
            handler.post(task)
        }
    }
}