package com.akslabs.circletosearch

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import com.akslabs.circletosearch.ocr.PaddleOcrEngine

class CircleToSearchApplication : Application() {
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (getProcessName() == packageName) PaddleOcrEngine.onTrimMemory(level)
    }

    @Suppress("DEPRECATION")
    override fun onLowMemory() {
        super.onLowMemory()
        if (getProcessName() == packageName) {
            PaddleOcrEngine.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        }
    }

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
