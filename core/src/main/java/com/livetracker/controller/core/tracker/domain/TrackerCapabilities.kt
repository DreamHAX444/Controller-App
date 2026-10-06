package com.livetracker.controller.core.tracker.domain

data class TrackerCapabilities(
    val location: Boolean = false,
    val camera: Boolean = false,
    val audio: Boolean = false,
    val screen: Boolean = false,
    val fileTransfer: Boolean = false
)
