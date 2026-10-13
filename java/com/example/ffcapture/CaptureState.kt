package com.example.ffcapture

object CaptureState {
    @Volatile var count = 0L
    @Volatile var currentFile: String? = null
    @Volatile var currentDumpFile: String? = null
    @Volatile var scope = "-"
    @Volatile var running = false
}
