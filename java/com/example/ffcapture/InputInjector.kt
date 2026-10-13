package com.example.ffcapture

import android.os.IBinder
import android.os.SystemClock
import android.view.InputEvent
import android.view.MotionEvent
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

/**
 * Touch injection routed through Shizuku, so events are dispatched from the
 * shell UID and enter the real input pipeline. No accessibility service.
 */
object InputInjector {

    private var inject: Method? = null
    private var service: Any? = null

    fun init(): Boolean = try {
        val binder: IBinder = ShizukuBinderWrapper(SystemServiceHelper.getSystemService("input"))
        val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
        service = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
        inject = Class.forName("android.hardware.input.IInputManager")
            .getMethod("injectInputEvent", InputEvent::class.java, Integer.TYPE)
        true
    } catch (e: Throwable) {
        false
    }

    /** Presses at (x1,y1), drags to (x2,y2) over [ms], then releases. */
    fun drag(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long, steps: Int = 3) {
        val m = inject ?: return
        val s = service ?: return
        val t0 = SystemClock.uptimeMillis()
        for (i in 0..steps) {
            val f = i.toFloat() / steps
            val action = when (i) {
                0 -> MotionEvent.ACTION_DOWN
                steps -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }
            val ev = MotionEvent.obtain(
                t0, t0 + (ms * f).toLong(), action,
                x1 + (x2 - x1) * f, y1 + (y2 - y1) * f, 0
            )
            runCatching { m.invoke(s, ev, 0) }   // 0 = INJECT_INPUT_EVENT_MODE_ASYNC
            ev.recycle()
        }
    }

    fun tap(x: Float, y: Float) = drag(x, y, x, y, 20L, 1)
}
