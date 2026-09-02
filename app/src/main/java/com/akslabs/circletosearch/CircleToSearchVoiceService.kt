/*
 * Copyright (C) 2025 AKS-Labs (original author)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.akslabs.circletosearch

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession

private const val SESSION_BIND_WATCHDOG_MS = 900L

/**
 * The always-bound assistant entry point deliberately contains no OCR, bitmap,
 * UI, or AccessibilityService references. It runs in the lightweight `:voice`
 * process so a low-memory kill of the main OCR/UI process cannot strand the
 * system's assistant binding.
 */
class CircleToSearchVoiceService : VoiceInteractionService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var readyGeneration = 0L
    private var ready = false
    private var destroyed = false

    override fun onReady() {
        super.onReady()
        if (destroyed) return
        ready = true
        readyGeneration++
        android.util.Log.d("CircleToSearchVoiceService", "VoiceService Ready")
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onPrepareToShowSession(args: Bundle, flags: Int) {
        if (destroyed) return
        val sessionId = args.getInt(VoiceInteractionSession.KEY_SHOW_SESSION_ID).toLong()
        val expectedGeneration = readyGeneration
        android.util.Log.d(
            "CircleToSearchVoiceService",
            "Preparing assistant invocation $sessionId",
        )

        // The receiver runs in the main process and declines this request when
        // the real session already owns or completed the same invocation.
        mainHandler.postDelayed(
            {
                if (!destroyed && ready && readyGeneration == expectedGeneration) {
                    requestMainProcessRecovery(sessionId, "session bind watchdog")
                }
            },
            SESSION_BIND_WATCHDOG_MS,
        )
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    override fun onShowSessionFailed(args: Bundle) {
        if (destroyed) return
        val sessionId = args.getInt(VoiceInteractionSession.KEY_SHOW_SESSION_ID).toLong()
        requestMainProcessRecovery(sessionId, "Android reported session launch failure")
    }

    private fun requestMainProcessRecovery(sessionId: Long, reason: String) {
        sendBroadcast(
            Intent(this, AssistantRecoveryReceiver::class.java).apply {
                action = AssistantRecoveryReceiver.ACTION_RECOVER_ASSISTANT
                putExtra(AssistantRecoveryReceiver.EXTRA_SESSION_ID, sessionId)
                putExtra(AssistantRecoveryReceiver.EXTRA_REASON, reason)
            },
        )
    }

    override fun onShutdown() {
        ready = false
        readyGeneration++
        mainHandler.removeCallbacksAndMessages(null)
        android.util.Log.d("CircleToSearchVoiceService", "VoiceService Shutdown")
        super.onShutdown()
    }

    override fun onDestroy() {
        // onShutdown() is tied to assistant-role shutdown, whereas Android can
        // destroy a bound service process for other lifecycle reasons too.
        // Invalidate every delayed watchdog in both cases.
        destroyed = true
        ready = false
        readyGeneration++
        mainHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}
