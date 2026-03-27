package com.thor.hotkeys.util

import android.util.Log

object RootShell {
    private const val TAG = "RootShell"

    /** Fire-and-forget root command. Logs warnings on failure, never throws. */
    fun cmd(cmd: String) {
        try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
            p.waitFor()
            if (p.exitValue() != 0) {
                val err = p.errorStream.bufferedReader().readText()
                Log.w(TAG, "Command '$cmd' exited ${p.exitValue()}: $err")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Root command failed: $cmd", e)
        }
    }

    /** Run root command and return stdout. */
    fun cmdOutput(cmd: String): String {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        p.waitFor()
        return p.inputStream.bufferedReader().readText()
    }

    /** Run root command, throw on non-zero exit. Drains stderr to avoid pipe deadlocks. */
    fun cmdStrict(cmd: String) {
        val pb = ProcessBuilder("su", "-c", cmd)
        pb.redirectErrorStream(true)
        val p = pb.start()
        // Drain combined output to prevent pipe buffer deadlock
        val output = p.inputStream.bufferedReader().readText()
        p.waitFor()
        if (p.exitValue() != 0) {
            throw RuntimeException("Command failed: $cmd → $output")
        }
    }
}
