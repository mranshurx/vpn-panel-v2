package com.example.ffcapture

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

object ShizukuHelper {

    const val REQ = 1001
    @Volatile var ready = false

    fun alive(): Boolean = try { Shizuku.pingBinder() } catch (e: Throwable) { false }

    fun granted(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) { false }

    fun request() {
        if (!alive()) return
        if (!granted()) runCatching { Shizuku.requestPermission(REQ) }
    }
}
