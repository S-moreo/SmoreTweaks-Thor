package net.smoreo.thortweaks.service

import android.content.Context
import android.util.Log
import net.smoreo.thortweaks.util.PServerBinder
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
        private const val ROOT_WORK_DIR = "/data/local/tmp/smore_setup"

        private const val KPATCH_API = "https://api.github.com/repos/bmax121/KernelPatch/releases/latest"
        private const val APATCH_API = "https://api.github.com/repos/bmax121/APatch/releases/latest"

        private val OLD_MODULE_IDS = listOf("thor-hotkeys", "gpu_oc_692")

        /** Critical firmware partitions to back up before rooting. */
        private val FIRMWARE_PARTITIONS = listOf(
            "boot", "vendor_boot", "init_boot", "vbmeta", "vbmeta_system", "dtbo"
        )
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

    /** Execute with stderr redirected to stdout so errors are visible. */
    private fun execLogged(cmd: String, label: String? = null): String? {
        val result = exec("$cmd 2>&1")
        Log.d(TAG, "[${label ?: cmd.take(40)}] -> ${result?.take(500)}")
        return result
    }

    /** Verify a file exists via PServerBinder, throw if missing. */
    private fun requireFile(path: String, description: String) {
        if (exec("test -f $path && echo y")?.trim() != "y") {
            throw RuntimeException("$description not found at $path")
        }
    }

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

                // Stage binaries in root-accessible dir (SELinux allows execution here)
                onProgress("Preparing tools...")
                execLogged("rm -rf $ROOT_WORK_DIR && mkdir -p $ROOT_WORK_DIR", "create root work dir")
                execLogged("cp ${work.absolutePath}/kptools $ROOT_WORK_DIR/kptools", "stage kptools")
                execLogged("cp ${work.absolutePath}/kpimg $ROOT_WORK_DIR/kpimg", "stage kpimg")
                execLogged("cp ${work.absolutePath}/apatch.apk $ROOT_WORK_DIR/apatch.apk", "stage apatch")
                execLogged("chmod 755 $ROOT_WORK_DIR/kptools", "chmod kptools")
                execLogged("chcon u:object_r:shell_data_file:s0 $ROOT_WORK_DIR/kptools", "chcon kptools")

                // --- 1. Backup all firmware partitions ---
                onProgress("[1/6] Backing up firmware...")
                exec("mkdir -p $BACKUP_DIR")
                for (part in FIRMWARE_PARTITIONS) {
                    val bakFile = "$BACKUP_DIR/${part}.img.bak"
                    val blockDev = "/dev/block/by-name/$part$slot"
                    if (exec("test -f $bakFile && echo y") != "y") {
                        if (exec("test -e $blockDev && echo y") == "y") {
                            onProgress("[1/6] Backing up $part...")
                            execLogged("dd if=$blockDev of=$bakFile", "backup $part")
                        } else {
                            Log.w(TAG, "Partition $part not found, skipping backup")
                        }
                    }
                }
                requireFile("$BACKUP_DIR/boot.img.bak", "Boot partition backup")

                // --- 2. Install APatch Manager (needed for magiskboot) ---
                onProgress("[2/6] Installing APatch Manager...")
                val installResult = execLogged("pm install -r $ROOT_WORK_DIR/apatch.apk", "pm install apatch")
                if (installResult != null && installResult.contains("Failure")) {
                    throw RuntimeException("Failed to install APatch Manager: $installResult")
                }

                onProgress("[2/6] Waiting for APatch to initialize...")
                var apatchAvailable = false
                for (attempt in 1..10) {
                    try {
                        context.packageManager.getApplicationInfo(APATCH_PKG, 0)
                        apatchAvailable = true
                        break
                    } catch (_: Exception) {
                        Thread.sleep(1000)
                    }
                }
                if (!apatchAvailable) {
                    throw RuntimeException("APatch Manager installed but not available to package manager")
                }

                // Stage magiskboot from APatch's native libs
                val apatchInfo = context.packageManager.getApplicationInfo(APATCH_PKG, 0)
                val nativeLibDir = apatchInfo.nativeLibraryDir
                execLogged("cp $nativeLibDir/libmagiskboot.so $ROOT_WORK_DIR/magiskboot", "stage magiskboot")
                execLogged("chmod 755 $ROOT_WORK_DIR/magiskboot", "chmod magiskboot")
                execLogged("chcon u:object_r:shell_data_file:s0 $ROOT_WORK_DIR/magiskboot", "chcon magiskboot")

                // --- 3. Patch boot with KernelPatch ---
                onProgress("[3/6] Unpacking boot image...")
                // Check boot backup is valid
                execLogged("ls -la $BACKUP_DIR/boot.img.bak", "boot backup size")
                // Redirect all output to log file (PServerBinder truncates)
                exec(
                    "sh -c 'cd $ROOT_WORK_DIR && " +
                    "$ROOT_WORK_DIR/magiskboot unpack $BACKUP_DIR/boot.img.bak " +
                    "> $ROOT_WORK_DIR/unpack.log 2>&1'"
                )
                val unpackLog = execLogged("cat $ROOT_WORK_DIR/unpack.log", "unpack log")
                exec("ls -1 $ROOT_WORK_DIR > $ROOT_WORK_DIR/ls_post_unpack.log 2>&1")
                val postUnpackFiles = execLogged("cat $ROOT_WORK_DIR/ls_post_unpack.log", "ls after unpack")
                Log.i(TAG, "After unpack: $postUnpackFiles")
                requireFile("$ROOT_WORK_DIR/kernel", "Unpacked kernel (magiskboot log: $unpackLog)")

                onProgress("[3/6] Patching kernel with KernelPatch...")
                val patchResult = execLogged(
                    "$ROOT_WORK_DIR/kptools -p " +
                    "--image $ROOT_WORK_DIR/kernel " +
                    "--skey '$superKey' " +
                    "--kpimg $ROOT_WORK_DIR/kpimg " +
                    "--out $ROOT_WORK_DIR/kernel",
                    "kptools patch"
                )
                Log.i(TAG, "kptools: $patchResult")

                onProgress("[3/6] Repacking boot image...")
                // List files before repack to verify components
                exec("ls -1 $ROOT_WORK_DIR > $ROOT_WORK_DIR/ls_pre_repack.log 2>&1")
                val preRepackFiles = execLogged("cat $ROOT_WORK_DIR/ls_pre_repack.log", "files before repack")
                Log.i(TAG, "Files before repack: $preRepackFiles")

                exec(
                    "sh -c 'cd $ROOT_WORK_DIR && " +
                    "$ROOT_WORK_DIR/magiskboot repack $BACKUP_DIR/boot.img.bak " +
                    "> $ROOT_WORK_DIR/repack.log 2>&1'"
                )
                val repackLog = execLogged("cat $ROOT_WORK_DIR/repack.log", "repack log")
                Log.i(TAG, "Repack log: $repackLog")

                requireFile(
                    "$ROOT_WORK_DIR/new-boot.img",
                    "Repacked boot image (repack log: $repackLog)"
                )

                onProgress("[3/6] Flashing patched boot...")
                execLogged("dd if=$ROOT_WORK_DIR/new-boot.img of=/dev/block/by-name/boot$slot", "flash boot")

                // --- 4. Configure APatch + grant root ---
                onProgress("[4/6] Configuring APatch...")
                preseedSuperKey(superKey)
                deployApatchBinaries(work, uid)

                onProgress("[4/6] Granting root to S'more Tweaks...")
                grantRootAccess(uid)

                // --- 5. Patch vendor_boot (GPU OC) + install module ---
                onProgress("[5/6] Patching GPU clock tables...")
                val vbPart = "/dev/block/by-name/vendor_boot$slot"
                val vbFile = File(work, "vendor_boot.img")
                execLogged("dd if=$vbPart of=${vbFile.absolutePath}", "dd read vendor_boot")
                exec("chown $uid:$uid ${vbFile.absolutePath}")

                if (patchGpuFreq(vbFile)) {
                    execLogged("dd if=${vbFile.absolutePath} of=$vbPart", "dd write vendor_boot")
                }

                onProgress("[5/6] Installing module...")
                installModule(work)

                // --- 6. Clean up ---
                onProgress("[6/6] Cleaning up...")
                work.deleteRecursively()
                exec("rm -rf $ROOT_WORK_DIR")

                onDone(true, "Setup complete. Reboot to activate.\n\nAPatch SuperKey: $superKey\n(Auto-configured)")
            } catch (e: Exception) {
                Log.e(TAG, "Setup failed", e)
                try { exec("rm -rf $ROOT_WORK_DIR") } catch (_: Exception) {}
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
        // Save superkey to file for service.sh and recovery
        exec("echo '$superKey' > /data/local/tmp/smore_apatch_superkey")
        exec("chmod 600 /data/local/tmp/smore_apatch_superkey")

        // Pre-write superkey into APatch Manager's SharedPreferences
        // so it's recognized automatically after reboot (no manual entry needed)
        exec("am force-stop $APATCH_PKG")
        val prefsDir = "/data/data/$APATCH_PKG/shared_prefs"
        exec("mkdir -p $prefsDir")
        exec("echo \"<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\" > $prefsDir/config.xml")
        exec("echo '<map>' >> $prefsDir/config.xml")
        exec("echo '    <string name=\"super_key\">$superKey</string>' >> $prefsDir/config.xml")
        exec("echo '</map>' >> $prefsDir/config.xml")
        // Fix ownership to match APatch's app UID
        val apatchUid = exec("stat -c %u /data/data/$APATCH_PKG")?.trim()
        if (apatchUid != null) {
            exec("chown $apatchUid:$apatchUid $prefsDir")
            exec("chown $apatchUid:$apatchUid $prefsDir/config.xml")
        }
        exec("chmod 660 $prefsDir/config.xml")
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

    // --- APatch binary deployment ---

    /**
     * Extract native binaries from the installed APatch APK and deploy them
     * so the apd daemon and utilities are ready on first boot.
     *
     * Layout:
     *   /data/adb/apd           — APatch daemon (started by KernelPatch on boot)
     *   /data/adb/kpatch        — KernelPatch management tool
     *   /data/adb/ap/bin/       — magiskboot, busybox, magiskpolicy, resetprop
     *   /data/adb/ap/package_config — root grant list
     */
    private fun deployApatchBinaries(work: File, appUid: Int) {
        // Find the installed APatch APK's native lib dir
        val apatchInfo = context.packageManager.getApplicationInfo(APATCH_PKG, 0)
        val nativeLibDir = apatchInfo.nativeLibraryDir

        // Deploy apd daemon
        exec("cp $nativeLibDir/libapd.so /data/adb/apd")
        exec("chmod 755 /data/adb/apd")
        exec("chown 0:0 /data/adb/apd")

        // Deploy kpatch
        exec("cp $nativeLibDir/libkpatch.so /data/adb/kpatch")
        exec("chmod 755 /data/adb/kpatch")
        exec("chown 0:0 /data/adb/kpatch")

        // Deploy utility binaries to /data/adb/ap/bin/
        exec("mkdir -p /data/adb/ap/bin")
        val binaries = mapOf(
            "libmagiskboot.so" to "magiskboot",
            "libbusybox.so" to "busybox",
            "libmagiskpolicy.so" to "magiskpolicy",
            "libresetprop.so" to "resetprop",
            "libbootctl.so" to "bootctl"
        )
        for ((lib, name) in binaries) {
            val src = "$nativeLibDir/$lib"
            if (exec("test -f $src && echo y")?.trim() == "y") {
                exec("cp $src /data/adb/ap/bin/$name")
                exec("chmod 755 /data/adb/ap/bin/$name")
            }
        }
        exec("chown -R 0:0 /data/adb/ap")
    }

    /**
     * Pre-configure APatch to grant root access to this app (S'more Tweaks).
     * The package_config CSV format is: pkg,exclude,allow,uid,to_uid,sctx
     */
    private fun grantRootAccess(appUid: Int) {
        exec("mkdir -p /data/adb/ap")
        val configFile = "/data/adb/ap/package_config"
        val pkg = context.packageName
        // exclude=0, allow=1, to_uid=0 (root), sctx=u:r:su:s0
        exec("echo 'pkg,exclude,allow,uid,to_uid,sctx' > $configFile")
        exec("echo '$pkg,0,1,$appUid,0,u:r:su:s0' >> $configFile")
        exec("chmod 600 $configFile")
        exec("chown 0:0 $configFile")
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
