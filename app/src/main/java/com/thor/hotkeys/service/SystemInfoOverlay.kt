package com.thor.hotkeys.service

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import com.thor.hotkeys.util.RootShell
import java.io.File

class SystemInfoOverlay(private val context: Context) {

    companion object {
        private const val TAG = "SystemInfoOverlay"
        private const val INTERVAL_FILE = "/data/local/tmp/smore_overlay_interval"
        private const val DEFAULT_INTERVAL = 1000L
        private const val MIN_INTERVAL = 100L
        private const val SECONDARY_DISPLAY_ID = 4
    }

    private var textView: TextView? = null
    private var windowManager: WindowManager? = null
    private val handler = Handler(Looper.getMainLooper())
    private var running = false

    // CPU usage tracking
    private var prevCpuTotal = 0L
    private var prevCpuIdle = 0L

    // FPS tracking via SurfaceFlinger
    private var prevFrameCount = 0L
    private var prevFrameTime = 0L

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (!running) return
            updateOverlay()
            handler.postDelayed(this, readInterval())
        }
    }

    fun show() {
        if (textView != null) return

        // Target the secondary (bottom) display
        val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val secondaryDisplay = displayManager.displays.firstOrNull { it.displayId == SECONDARY_DISPLAY_ID }
        val displayContext = if (secondaryDisplay != null) {
            context.createDisplayContext(secondaryDisplay)
        } else {
            Log.w(TAG, "Secondary display $SECONDARY_DISPLAY_ID not found, falling back to default")
            context
        }

        windowManager = displayContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val tv = TextView(displayContext).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setTextColor(Color.WHITE)
            setBackgroundColor(0x88000000.toInt())
            setPadding(12, 2, 12, 2)
            typeface = android.graphics.Typeface.MONOSPACE
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        try {
            // Grant overlay permission via appops (requires root)
            RootShell.cmd("appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow")
            windowManager?.addView(tv, lp)
            textView = tv
            running = true
            handler.post(updateRunnable)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay", e)
        }
    }

    fun hide() {
        running = false
        handler.removeCallbacks(updateRunnable)
        textView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
        }
        textView = null
    }

    fun isShowing(): Boolean = textView != null

    private fun readInterval(): Long {
        return try {
            val text = File(INTERVAL_FILE).readText().trim()
            text.toLong().coerceAtLeast(MIN_INTERVAL)
        } catch (_: Exception) {
            DEFAULT_INTERVAL
        }
    }

    private fun updateOverlay() {
        val parts = mutableListOf<String>()

        // CPU usage
        val cpuUsage = readCpuUsage()
        if (cpuUsage >= 0) parts.add("CPU ${cpuUsage}%")

        // GPU frequency + busy
        val gpuFreq = readFile("/sys/class/kgsl/kgsl-3d0/gpuclk")
        if (gpuFreq != null) {
            val mhz = gpuFreq.toLongOrNull()?.div(1_000_000) ?: 0
            val busy = readFile("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage")?.trim()
            parts.add(if (busy != null) "GPU ${mhz}MHz $busy" else "GPU ${mhz}MHz")
        }

        // Temperature (cpu-0-1)
        val cpuTemp = readThermalZone("thermal_zone48/temp")
        if (cpuTemp > 0) parts.add("${cpuTemp / 1000}°C")

        // Battery temp
        val batTemp = readThermalZone("thermal_zone94/temp")
        if (batTemp > 0) parts.add("BAT ${batTemp / 1000}°C")

        // FPS
        val fps = readFps()
        if (fps >= 0) parts.add("${String.format("%.0f", fps)}FPS")

        textView?.text = parts.joinToString(" | ")
    }

    private fun readCpuUsage(): Int {
        return try {
            val line = File("/proc/stat").bufferedReader().readLine() ?: return -1
            val parts = line.split("\\s+".toRegex())
            if (parts.size < 8) return -1

            val user = parts[1].toLong()
            val nice = parts[2].toLong()
            val system = parts[3].toLong()
            val idle = parts[4].toLong()
            val iowait = parts[5].toLong()
            val irq = parts[6].toLong()
            val softirq = parts[7].toLong()

            val total = user + nice + system + idle + iowait + irq + softirq
            val deltaTotal = total - prevCpuTotal
            val deltaIdle = idle - prevCpuIdle

            prevCpuTotal = total
            prevCpuIdle = idle

            if (deltaTotal == 0L) return -1
            (100 - (deltaIdle * 100 / deltaTotal)).toInt()
        } catch (_: Exception) { -1 }
    }

    private fun readThermalZone(zone: String): Int {
        return try {
            File("/sys/devices/virtual/thermal/$zone").readText().trim().toInt()
        } catch (_: Exception) { 0 }
    }

    private fun readFile(path: String): String? {
        return try {
            File(path).readText().trim()
        } catch (_: Exception) { null }
    }

    private fun readFps(): Float {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "service call SurfaceFlinger 1013"))
            val output = p.inputStream.bufferedReader().readText()
            p.waitFor()

            if (!output.startsWith("Result: Parcel(") || output.contains("Error")) return -1f

            // Parse hex frame count from parcel output
            val start = if (output[15].isDigit()) 15 else 16
            val hex = output.substring(start, start + 8)
            val frameCount = hex.toLong(16)
            val now = android.os.SystemClock.uptimeMillis()

            val fps = if (prevFrameCount > 0 && prevFrameTime > 0) {
                val dt = now - prevFrameTime
                if (dt > 0) (frameCount - prevFrameCount) * 1000f / dt else -1f
            } else -1f

            prevFrameCount = frameCount
            prevFrameTime = now
            fps
        } catch (_: Exception) { -1f }
    }
}
