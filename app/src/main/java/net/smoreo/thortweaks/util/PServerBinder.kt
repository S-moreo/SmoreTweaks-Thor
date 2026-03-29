package net.smoreo.thortweaks.util

import android.os.IBinder
import android.os.Parcel
import android.util.Log

/**
 * AYN OdinOS provides a privileged system service called PServerBinder
 * that allows apps to execute root commands. Available on stock firmware
 * without any rooting — this is what powers "Run script as Root" in
 * Odin Settings and what O2P Tweaks uses for EZ Root.
 */
object PServerBinder {

    private const val TAG = "PServerBinder"
    private var binder: IBinder? = null

    val isAvailable: Boolean
        get() = getBinder() != null

    private fun getBinder(): IBinder? {
        binder?.let { return it }
        return try {
            val cls = Class.forName("android.os.ServiceManager")
            val svc = cls.getDeclaredMethod("getService", String::class.java)
                .invoke(null, "PServerBinder") as? IBinder
            binder = svc
            svc
        } catch (_: Throwable) {
            null
        }
    }

    /** Execute a root command and return stdout, or null on empty output. */
    fun exec(cmd: String): String? {
        val b = getBinder() ?: throw IllegalStateException("PServerBinder not available")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeStringArray(arrayOf(cmd, "1"))
            b.transact(0, data, reply, 0)
            val bytes = reply.createByteArray() ?: return null
            val result = String(bytes, Charsets.UTF_8).trim()
            return result.ifEmpty { null }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Execute a root command, throw if it appears to fail. */
    fun execOrThrow(cmd: String): String? {
        val result = exec(cmd)
        // PServerBinder doesn't give us exit codes, so we rely on output
        Log.d(TAG, "exec: $cmd -> ${result?.take(200)}")
        return result
    }
}
