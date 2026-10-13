package com.example.ffcapture

object CaptureState {
    @Volatile var count = 0L
    @Volatile var currentFile: String? = null
}
