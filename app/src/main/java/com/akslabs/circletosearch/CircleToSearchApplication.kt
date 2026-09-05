package com.akslabs.circletosearch

import android.app.Application
import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.util.Log

class CircleToSearchApplication : Application() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // ONNX Runtime checks this before its Java environment is created. Set it before
        // Application.attachBaseContext returns, which is earlier than ContentProvider startup.
        try {
            Os.setenv(ORT_DISABLE_TELEMETRY, "1", true)
        } catch (error: ErrnoException) {
            Log.e(TAG, "Unable to disable ONNX Runtime telemetry through the process environment", error)
        }
    }

    private companion object {
        private const val TAG = "CircleToSearchApp"
        private const val ORT_DISABLE_TELEMETRY = "ORT_DISABLE_TELEMETRY"
    }
}
