/*
 * Copyright (C) 2025 AKS-Labs (original author)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package com.akslabs.circletosearch

import android.app.Activity
import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast

private const val ASSIST_FALLBACK_CAPTURE_DELAY_MS = 90L

/**
 * Handles Android's legacy ACTION_ASSIST fallback when the assistant role is
 * still assigned but the platform has dropped its VoiceInteractionService.
 *
 * The manifest alias intentionally shares the voice service's component name:
 * service binding still resolves the service, while startActivity resolves the
 * alias. This is a degraded, Accessibility-backed path; a healthy voice session
 * never launches this activity.
 */
class AssistantFallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        disableTransitions()
        window.decorView.visibility = View.INVISIBLE

        if (intent?.action != Intent.ACTION_ASSIST || !isCurrentAssistantRoleHolder()) {
            finishWithoutAnimation()
            return
        }

        android.util.Log.w(
            "AssistantFallback",
            "Android voice interaction is unavailable; using accessibility capture",
        )
        window.decorView.postDelayed(
            {
                when (CircleToSearchAccessibilityService.triggerCapture()) {
                    CaptureStartResult.STARTED,
                    CaptureStartResult.BUSY -> finishWithoutAnimation()

                    CaptureStartResult.UNAVAILABLE,
                    CaptureStartResult.UNSUPPORTED -> openRepairScreen()
                }
            },
            ASSIST_FALLBACK_CAPTURE_DELAY_MS,
        )
    }

    private fun isCurrentAssistantRoleHolder(): Boolean {
        val roleManager = getSystemService(RoleManager::class.java) ?: return false
        return roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT) &&
            roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)
    }

    private fun openRepairScreen() {
        Toast.makeText(
            this,
            "Android disconnected the assistant. Reconnect it or enable Accessibility.",
            Toast.LENGTH_LONG,
        ).show()
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            },
        )
        finishWithoutAnimation()
    }

    private fun disableTransitions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(OVERRIDE_TRANSITION_OPEN, 0, 0)
            overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0)
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }

    private fun finishWithoutAnimation() {
        finish()
        disableTransitions()
    }
}
