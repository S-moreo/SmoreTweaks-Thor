package com.thor.hotkeys.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.preference.PreferenceManager
import com.thor.hotkeys.R
import com.thor.hotkeys.model.BindingStore
import com.thor.hotkeys.ui.SettingsActivity

class HotkeyService : Service() {

    companion object {
        private const val TAG = "HotkeyService"
        private const val CHANNEL_ID = "smore_tweaks_channel"
        private const val NOTIFICATION_ID = 1

        const val ACTION_START = "com.thor.hotkeys.START"
        const val ACTION_STOP = "com.thor.hotkeys.STOP"
        const val ACTION_RELOAD = "com.thor.hotkeys.RELOAD"

        @Volatile
        var isRunning = false
            private set
    }

    private lateinit var inputReader: InputEventReader
    private lateinit var detector: HotkeyDetector
    private lateinit var executor: ActionExecutor
    private var fgPoller: Thread? = null
    @Volatile
    private var fgPolling = false

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")

        executor = ActionExecutor(this)

        detector = HotkeyDetector { binding ->
            executor.execute(binding)
        }

        inputReader = InputEventReader { keyName, isDown ->
            detector.onKeyEvent(keyName, isDown)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_RELOAD -> {
                reloadConfig()
                return START_STICKY
            }
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())

        reloadConfig()
        inputReader.start()
        isRunning = true

        Log.d(TAG, "Service started")
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        stopForegroundPoller()
        inputReader.stop()
        detector.reset()
        Log.d(TAG, "Service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun reloadConfig() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)

        // Hotkey bindings
        BindingStore.invalidateCache()
        detector.bindings = BindingStore.getBindings(this)
        detector.longPressMs = try {
            prefs.getString("long_press_ms", "600")?.toLong() ?: 600L
        } catch (_: ClassCastException) {
            prefs.getInt("long_press_ms", 600).toLong()
        }

        Log.d(TAG, "Config: ${detector.bindings.size} bindings")

        // Only poll foreground app if any binding uses it
        val needsFgPoll = detector.bindings.any { !it.foregroundApp.isNullOrEmpty() }
        if (needsFgPoll) {
            startForegroundPoller()
        } else {
            stopForegroundPoller()
        }
    }

    private fun startForegroundPoller() {
        if (fgPolling) return
        fgPolling = true
        fgPoller = Thread({
            while (fgPolling) {
                try {
                    val p = Runtime.getRuntime().exec(
                        arrayOf("su", "-c", "dumpsys activity activities 2>/dev/null | grep mResumedActivity")
                    )
                    val output = p.inputStream.bufferedReader().readText()
                    p.waitFor()
                    // Format: mResumedActivity: ActivityRecord{... com.package/.Activity ...}
                    val match = Regex("""\s(\S+)/""").find(output)
                    val pkg = match?.groupValues?.get(1) ?: ""
                    if (pkg.isNotEmpty()) {
                        detector.currentForegroundApp = pkg
                    }
                } catch (_: Exception) {}
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
            }
        }, "FgAppPoller")
        fgPoller!!.isDaemon = true
        fgPoller!!.start()
    }

    private fun stopForegroundPoller() {
        fgPolling = false
        fgPoller?.interrupt()
        fgPoller = null
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "S'more Tweaks Service",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Keeps tweaks and hotkey listener running"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, SettingsActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, HotkeyService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("S'more Tweaks Active")
            .setContentText("${detector.bindings.size} bindings loaded")
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, "Stop", stopIntent).build())
            .setOngoing(true)
            .build()
    }
}
