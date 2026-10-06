package com.livetracker.controller

import android.app.Application
import android.util.Log

class ControllerApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialize minimal structured logging foundation
        Log.i("ControllerApp", "Application starting. Phase 0 Foundation initialized.")
    }
}
