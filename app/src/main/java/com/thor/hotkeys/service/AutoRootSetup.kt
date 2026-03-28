package com.thor.hotkeys.service

import android.content.Context
import android.util.Log
import com.thor.hotkeys.util.PServerBinder
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * One-click setup: roots device with APatch, patches vendor_boot for GPU OC,
 * and installs the S'more Tweaks module. Uses AYN's PServerBinder for root
 * command execution on stock firmware — no manual steps required.
 *
 * Downloads kptools/kpimg from KernelPatch and APatch Manager APK from GitHub.
 */
class AutoRootSetup(private val context: Context) {

    companion object {
        private const val TAG = "AutoRootSetup"
        private const val BACKUP_DIR = "/data/adb/smore-tweaks-backup"
        private const val MODULE_DIR = "/data/adb/modules/smore-tweaks"
        private const val APATCH_PKG = "me.bmax.apatch"

        private const val KPATCH_API = "https://api.github.com/repos/bmax121/KernelPatch/releases/latest"
        private const val APATCH_API = "https://api.github.com/repos/bmax121/APatch/releases/latest"

        private val OLD_MODULE_IDS = listOf("thor-hotkeys", "gpu_oc_692")
    }

    fun isRooted(): Boolean {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val output = p.inputStream.bufferedReader().readText()
            p.waitFor()
            output.contains("uid=0")
        } catch (_: Exception) {
            false
        }
    }

    private fun exec(cmd: String) = PServerBinder.exec(cmd)

    fun install(onProgress: (String) -> Unit, onDone: (Boolean, String) -> Unit) {
        Thread {
            try {
                val superKey = generateSuperKey()
                val slot = exec("getprop ro.boot.slot_suffix")?.trim() ?: ""
                val work = File(context.cacheDir, "setup")
                work.deleteRecursively()
                work.mkdirs()
                val uid = context.applicationInfo.uid

                // --- Download setup tools from GitHub ---
                onProgress("Downloading KernelPatch tools...")
                val kpAssets = fetchReleaseAssets(KPATCH_API)
                val kptoolsUrl = kpAssets["kptools-android"]
                    ?: throw RuntimeException("kptools-android not found in KernelPatch release")
                val kpimgUrl = kpAssets["kpimg-android"]
                    ?: throw RuntimeException("kpimg-android not found in KernelPatch release")
                download(kptoolsUrl, File(work, "kptools"), onProgress)

                onProgress("Downloading KernelPatch image...")
                download(kpimgUrl, File(work, "kpimg"), onProgress)

                onProgress("Downloading APatch Manager...")
                val apatchAssets = fetchReleaseAssets(APATCH_API)
                val apkUrl = apatchAssets.entries.firstOrNull {
                    it.key.endsWith(".apk")
                }?.value ?: throw RuntimeException("APK not found in APatch release")
                download(apkUrl, File(work, "apatch.apk"), onProgress)

                // --- 1. Backup boot partition ---
                onProgress("[1/6] Backing up boot...")
                exec("mkdir -p $BACKUP_DIR")
                if (exec("test -f $BACKUP_DIR/boot.img.bak && echo y") != "y") {
                    exec("dd if=/dev/block/by-name/boot$slot of=$BACKUP_DIR/boot.img.bak")
                }

                // --- 2. Patch boot with APatch ---
                onProgress("[2/6] Patching boot with APatch...")
                exec("chmod 755 ${work.absolutePath}/kptools")
                val patchResult = exec(
                    "${work.absolutePath}/kptools -p " +
                    "--image $BACKUP_DIR/boot.img.bak " +
                    "--skey '$superKey' " +
                    "--kpimg ${work.absolutePath}/kpimg " +
                    "--out ${work.absolutePath}/boot_patched.img"
                )
                Log.i(TAG, "kptools: $patchResult")

                onProgress("[2/6] Flashing patched boot...")
                exec("dd if=${work.absolutePath}/boot_patched.img of=/dev/block/by-name/boot$slot")

                // --- 3. Install APatch Manager + auto-configure ---
                onProgress("[3/6] Installing APatch Manager...")
                exec("pm install -r ${work.absolutePath}/apatch.apk")
                Thread.sleep(1000)
                preseedSuperKey(superKey)

                // --- 4. Backup + patch vendor_boot (GPU OC) ---
                onProgress("[4/6] Patching GPU clock tables...")
                val vbPart = "/dev/block/by-name/vendor_boot$slot"
                if (exec("test -f $BACKUP_DIR/vendor_boot.img.bak && echo y") != "y") {
                    exec("dd if=$vbPart of=$BACKUP_DIR/vendor_boot.img.bak")
                }
                val vbFile = File(work, "vendor_boot.img")
                exec("dd if=$vbPart of=${vbFile.absolutePath}")
                exec("chown $uid:$uid ${vbFile.absolutePath}")

                if (patchGpuFreq(vbFile)) {
                    exec("dd if=${vbFile.absolutePath} of=$vbPart")
                }

                // --- 5. Install module ---
                onProgress("[5/6] Installing module...")
                installModule(work)

                // --- 6. Clean up ---
                onProgress("[6/6] Cleaning up...")
                work.deleteRecursively()

                onDone(true, "Setup complete. Reboot to activate.")
            } catch (e: Exception) {
                Log.e(TAG, "Setup failed", e)
                onDone(false, "Failed: ${e.message}")
            }
        }.start()
    }

    // --- GitHub release fetching ---

    /** Returns map of asset name -> download URL for a GitHub release. */
    private fun fetchReleaseAssets(apiUrl: String): Map<String, String> {
        val conn = URL(apiUrl).openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.connectTimeout = 15000
        conn.readTimeout = 15000
        try {
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val assets = json.getJSONArray("assets")
            val map = mutableMapOf<String, String>()
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                map[asset.getString("name")] = asset.getString("browser_download_url")
            }
            return map
        } finally {
            conn.disconnect()
        }
    }

    private fun download(url: String, dest: File, onProgress: (String) -> Unit) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        try {
            // GitHub releases redirect — follow manually if needed
            if (conn.responseCode == 302 || conn.responseCode == 301) {
                val redirect = conn.getHeaderField("Location")
                conn.disconnect()
                return download(redirect, dest, onProgress)
            }
            val total = conn.contentLength
            var downloaded = 0L
            conn.inputStream.use { input ->
                FileOutputStream(dest).use { output ->
                    val buf = ByteArray(8192)
                    var n: Int
                    while (input.read(buf).also { n = it } != -1) {
                        output.write(buf, 0, n)
                        downloaded += n
                        if (total > 0) {
                            val pct = (downloaded * 100 / total).toInt()
                            onProgress("Downloading ${dest.name}... $pct%")
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    // --- Super key ---

    private fun preseedSuperKey(superKey: String) {
        val prefsDir = "/data/data/$APATCH_PKG/shared_prefs"
        val prefsFile = "$prefsDir/${APATCH_PKG}_preferences.xml"
        exec("mkdir -p $prefsDir")
        exec(
            "echo '<?xml version=\"1.0\" encoding=\"utf-8\" standalone=\"yes\" ?>" +
            "<map><string name=\"super_key\">$superKey</string></map>' > $prefsFile"
        )
        val appUid = exec("stat -c %u /data/data/$APATCH_PKG")?.trim()
        if (appUid != null) {
            exec("chown -R $appUid:$appUid $prefsDir")
            exec("chmod 771 $prefsDir")
            exec("chmod 660 $prefsFile")
        }
        exec("echo '$superKey' > /data/local/tmp/smore_apatch_superkey")
        exec("chmod 600 /data/local/tmp/smore_apatch_superkey")
    }

    // --- Vendor boot GPU patch ---

    /** Patch 680 MHz -> 692 MHz in raw vendor_boot image. Returns true if patched. */
    private fun patchGpuFreq(imageFile: File): Boolean {
        val stock = 680_000_000.toBigEndianBytes()
        val patch = 692_000_000.toBigEndianBytes()

        val data = imageFile.readBytes()
        val offsets = data.findAll(stock)

        if (offsets.isEmpty()) {
            if (data.findAll(patch).isNotEmpty()) {
                Log.i(TAG, "GPU already at 692 MHz, skipping")
                return false
            }
            throw RuntimeException("GPU freq 680 MHz not found in vendor_boot")
        }

        for (offset in offsets) {
            patch.copyInto(data, offset)
        }
        imageFile.writeBytes(data)
        Log.i(TAG, "GPU patched: ${offsets.size}x 680 -> 692 MHz")
        return true
    }

    // --- Module installation ---

    private fun installModule(work: File) {
        for (old in OLD_MODULE_IDS) {
            exec("rm -rf /data/adb/modules/$old")
        }

        exec("rm -rf $MODULE_DIR")
        exec("mkdir -p $MODULE_DIR/system/bin")
        exec("mkdir -p $MODULE_DIR/system/priv-app/ThorHotkeys")
        exec("mkdir -p $MODULE_DIR/system/etc/permissions")
        exec("mkdir -p $MODULE_DIR/system/vendor/lib/modules")
        exec("mkdir -p $MODULE_DIR/system/vendor/firmware")

        exec("cp ${context.applicationInfo.sourceDir} $MODULE_DIR/system/priv-app/ThorHotkeys/ThorHotkeys.apk")

        val files = mapOf(
            "module/module.prop" to "$MODULE_DIR/module.prop",
            "module/service.sh" to "$MODULE_DIR/service.sh",
            "module/system/bin/pservice" to "$MODULE_DIR/system/bin/pservice",
            "module/system/etc/permissions/privapp-permissions-thor-hotkeys.xml" to
                "$MODULE_DIR/system/etc/permissions/privapp-permissions-thor-hotkeys.xml",
            "module/system/vendor/firmware/gmu_gen70200.bin" to
                "$MODULE_DIR/system/vendor/firmware/gmu_gen70200.bin"
        )
        for ((asset, dest) in files) {
            val tmp = File(work, asset.substringAfterLast('/'))
            extractAsset(asset, tmp)
            exec("cp ${tmp.absolutePath} $dest")
        }
        for (ko in listOf("gpucc-kalama.ko", "msm_lmh_dcvs.ko", "gpio5_pwm.ko")) {
            val tmp = File(work, ko)
            extractAsset("module/system/vendor/lib/modules/$ko", tmp)
            exec("cp ${tmp.absolutePath} $MODULE_DIR/system/vendor/lib/modules/$ko")
        }

        exec("chmod 755 $MODULE_DIR/service.sh")
        exec("chmod 755 $MODULE_DIR/system/bin/pservice")
        exec("chmod 644 $MODULE_DIR/system/priv-app/ThorHotkeys/ThorHotkeys.apk")
        exec("find $MODULE_DIR/system -name '*.xml' -exec chmod 644 {} \\;")
        exec("find $MODULE_DIR/system -name '*.ko' -exec chmod 644 {} \\;")
        exec("find $MODULE_DIR/system -name '*.bin' -exec chmod 644 {} \\;")
        exec("chown -R 0:0 $MODULE_DIR")
    }

    // --- Utilities ---

    private fun extractAsset(assetPath: String, dest: File) {
        dest.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun generateSuperKey(): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        return (1..16).map { chars.random() }.joinToString("")
    }

    private fun Int.toBigEndianBytes() = byteArrayOf(
        (this shr 24).toByte(), (this shr 16).toByte(),
        (this shr 8).toByte(), this.toByte()
    )

    private fun ByteArray.findAll(pattern: ByteArray): List<Int> {
        val results = mutableListOf<Int>()
        for (i in 0..size - pattern.size) {
            if (pattern.indices.all { this[i + it] == pattern[it] }) results.add(i)
        }
        return results
    }
}
