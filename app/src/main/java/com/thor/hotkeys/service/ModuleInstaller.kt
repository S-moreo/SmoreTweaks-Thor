package com.thor.hotkeys.service

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class ModuleInstaller(private val context: Context) {

    companion object {
        private const val TAG = "ModuleInstaller"
        private const val MODULE_DIR = "/data/adb/modules/smore-tweaks"
        private const val MAGISKBOOT = "/data/adb/ap/bin/magiskboot"
        private const val VENDOR_BOOT_PART = "/dev/block/by-name/vendor_boot_a"
        private const val BACKUP_DIR = "/data/adb/smore-tweaks-backup"
        const val MODULE_VERSION = "v1.2"
        const val MODULE_VERSION_CODE = 3

        // Old module IDs to purge
        private val OLD_MODULE_IDS = listOf(
            "thor-hotkeys",
            "gpu_oc_692"
        )
    }

    data class ModuleStatus(
        val installed: Boolean,
        val version: String?,
        val versionCode: Int?,
        val description: String?
    )

    fun getStatus(): ModuleStatus {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "cat $MODULE_DIR/module.prop 2>/dev/null"))
            p.waitFor()
            val props = p.inputStream.bufferedReader().readText()
            if (props.isBlank()) return ModuleStatus(false, null, null, null)

            val version = Regex("""(?m)^version=(.+)""").find(props)?.groupValues?.get(1)?.trim()
            val versionCode = Regex("""(?m)^versionCode=(\d+)""").find(props)?.groupValues?.get(1)?.trim()?.toIntOrNull()
            val description = Regex("""(?m)^description=(.+)""").find(props)?.groupValues?.get(1)?.trim()
            ModuleStatus(true, version, versionCode, description)
        } catch (_: Exception) {
            ModuleStatus(false, null, null, null)
        }
    }

    fun isInstalled(): Boolean = getStatus().installed

    fun install(onProgress: (String) -> Unit, onDone: (Boolean, String) -> Unit) {
        Thread {
            try {
                // Backup vendor_boot (only on first install — never overwrite existing backup)
                onProgress("Backing up vendor_boot...")
                rootCmdStrict("mkdir -p $BACKUP_DIR")
                val backupExists = rootCmdOutput("test -f $BACKUP_DIR/vendor_boot.img.bak && echo yes || echo no").trim()
                if (backupExists == "yes") {
                    Log.i(TAG, "Vendor_boot backup already exists, skipping")
                } else {
                    rootCmdStrict("dd if=$VENDOR_BOOT_PART of=$BACKUP_DIR/vendor_boot.img.bak")
                    Log.i(TAG, "Vendor_boot backed up to $BACKUP_DIR/vendor_boot.img.bak")
                }

                // Purge old modules
                onProgress("Removing old modules...")
                for (oldId in OLD_MODULE_IDS) {
                    rootCmd("rm -rf /data/adb/modules/$oldId")
                }

                onProgress("Creating module structure...")
                val apkPath = context.applicationInfo.sourceDir

                // Create full directory structure
                rootCmd("rm -rf $MODULE_DIR")
                rootCmd("mkdir -p $MODULE_DIR/system/bin")
                rootCmd("mkdir -p $MODULE_DIR/system/priv-app/ThorHotkeys")
                rootCmd("mkdir -p $MODULE_DIR/system/etc/permissions")
                rootCmd("mkdir -p $MODULE_DIR/system/vendor/lib/modules")
                rootCmd("mkdir -p $MODULE_DIR/system/vendor/firmware")

                // Copy APK
                onProgress("Installing app as system priv-app...")
                rootCmd("cp $apkPath $MODULE_DIR/system/priv-app/ThorHotkeys/ThorHotkeys.apk")
                rootCmd("chmod 644 $MODULE_DIR/system/priv-app/ThorHotkeys/ThorHotkeys.apk")

                // Extract all assets to temp
                val tmpDir = File(context.cacheDir, "module_tmp")
                tmpDir.deleteRecursively()
                tmpDir.mkdirs()

                // pservice binary
                onProgress("Installing GPU overclock (pservice)...")
                extractAsset("module/system/bin/pservice", File(tmpDir, "pservice"))
                rootCmd("cp ${tmpDir}/pservice $MODULE_DIR/system/bin/pservice")
                rootCmd("chmod 755 $MODULE_DIR/system/bin/pservice")

                // Kernel modules
                onProgress("Installing GPU kernel modules...")
                for (ko in listOf("gpucc-kalama.ko", "msm_lmh_dcvs.ko", "gpio5_pwm.ko")) {
                    extractAsset("module/system/vendor/lib/modules/$ko", File(tmpDir, ko))
                    rootCmd("cp ${tmpDir}/$ko $MODULE_DIR/system/vendor/lib/modules/$ko")
                    rootCmd("chmod 644 $MODULE_DIR/system/vendor/lib/modules/$ko")
                }

                // GPU firmware
                onProgress("Installing GPU firmware...")
                extractAsset("module/system/vendor/firmware/gmu_gen70200.bin", File(tmpDir, "gmu_gen70200.bin"))
                rootCmd("cp ${tmpDir}/gmu_gen70200.bin $MODULE_DIR/system/vendor/firmware/gmu_gen70200.bin")
                rootCmd("chmod 644 $MODULE_DIR/system/vendor/firmware/gmu_gen70200.bin")

                // Permissions XML
                onProgress("Setting up permissions...")
                extractAsset(
                    "module/system/etc/permissions/privapp-permissions-thor-hotkeys.xml",
                    File(tmpDir, "privapp-permissions-thor-hotkeys.xml")
                )
                rootCmd("cp ${tmpDir}/privapp-permissions-thor-hotkeys.xml $MODULE_DIR/system/etc/permissions/")
                rootCmd("chmod 644 $MODULE_DIR/system/etc/permissions/privapp-permissions-thor-hotkeys.xml")

                // module.prop
                onProgress("Writing module metadata...")
                extractAsset("module/module.prop", File(tmpDir, "module.prop"))
                rootCmd("cp ${tmpDir}/module.prop $MODULE_DIR/module.prop")

                // service.sh
                extractAsset("module/service.sh", File(tmpDir, "service.sh"))
                rootCmd("cp ${tmpDir}/service.sh $MODULE_DIR/service.sh")
                rootCmd("chmod 755 $MODULE_DIR/service.sh")

                // Patch vendor_boot DTB for GPU overclock
                onProgress("Patching vendor_boot DTB...")
                val vbDir = File(context.cacheDir, "vendor_boot_work")
                vbDir.deleteRecursively()
                vbDir.mkdirs()
                val vbPath = vbDir.absolutePath

                // Dump current vendor_boot
                rootCmdStrict("dd if=$VENDOR_BOOT_PART of=$vbPath/vendor_boot.img")

                // Unpack with magiskboot to extract DTB
                rootCmdStrict("cd $vbPath && $MAGISKBOOT unpack vendor_boot.img")

                // Copy DTB to app-writable location, patch, copy back
                rootCmdStrict("cp $vbPath/dtb $vbPath/dtb_work")
                rootCmdStrict("chmod 666 $vbPath/dtb_work")

                val dtbFile = File(vbPath, "dtb_work")
                patchDtbGpuFreq(dtbFile)

                rootCmdStrict("cp $vbPath/dtb_work $vbPath/dtb")

                // Repack
                rootCmdStrict("cd $vbPath && $MAGISKBOOT repack vendor_boot.img")

                // Flash patched image back
                onProgress("Flashing patched vendor_boot...")
                rootCmdStrict("dd if=$vbPath/new-boot.img of=$VENDOR_BOOT_PART")

                // Clean up vendor_boot work dir
                vbDir.deleteRecursively()

                // Set ownership
                rootCmd("chown -R 0:0 $MODULE_DIR")

                // Clean up
                tmpDir.deleteRecursively()

                onDone(true, "Module installed ($MODULE_VERSION) + vendor_boot patched. Reboot to activate.")
            } catch (e: Exception) {
                Log.e(TAG, "Install failed", e)
                onDone(false, "Install failed: ${e.message}")
            }
        }.start()
    }

    fun uninstall(onDone: (Boolean, String) -> Unit) {
        Thread {
            try {
                rootCmd("rm -rf $MODULE_DIR")
                // Also purge any old modules
                for (oldId in OLD_MODULE_IDS) {
                    rootCmd("rm -rf /data/adb/modules/$oldId")
                }
                onDone(true, "Module removed. Reboot to complete.")
            } catch (e: Exception) {
                onDone(false, "Uninstall failed: ${e.message}")
            }
        }.start()
    }

    fun hasVendorBootBackup(): Boolean {
        return rootCmdOutput("test -f $BACKUP_DIR/vendor_boot.img.bak && echo yes || echo no").trim() == "yes"
    }

    fun restoreVendorBoot(onProgress: (String) -> Unit, onDone: (Boolean, String) -> Unit) {
        Thread {
            try {
                onProgress("Restoring vendor_boot from backup...")
                rootCmdStrict("dd if=$BACKUP_DIR/vendor_boot.img.bak of=$VENDOR_BOOT_PART")
                onDone(true, "Vendor_boot restored. Reboot to apply.")
            } catch (e: Exception) {
                Log.e(TAG, "Restore failed", e)
                onDone(false, "Restore failed: ${e.message}")
            }
        }.start()
    }

    /**
     * Patch the GPU max frequency in the DTB from 680 MHz to 692 MHz.
     * Searches for the big-endian 32-bit value 680000000 (0x2887FA00)
     * and replaces with 692000000 (0x29457600).
     * Throws if not found or if multiple occurrences exist.
     */
    private fun patchDtbGpuFreq(dtbFile: File) {
        val stockFreq = 680_000_000
        val patchFreq = 692_000_000

        // Big-endian 32-bit representations
        val stockBytes = byteArrayOf(
            (stockFreq shr 24).toByte(),
            (stockFreq shr 16).toByte(),
            (stockFreq shr 8).toByte(),
            stockFreq.toByte()
        )
        val patchBytes = byteArrayOf(
            (patchFreq shr 24).toByte(),
            (patchFreq shr 16).toByte(),
            (patchFreq shr 8).toByte(),
            patchFreq.toByte()
        )

        val data = dtbFile.readBytes()

        // Find all occurrences of stock frequency
        val offsets = mutableListOf<Int>()
        for (i in 0..data.size - 4) {
            if (data[i] == stockBytes[0] && data[i + 1] == stockBytes[1] &&
                data[i + 2] == stockBytes[2] && data[i + 3] == stockBytes[3]) {
                offsets.add(i)
            }
        }

        // Check if already patched
        var alreadyPatched = false
        for (i in 0..data.size - 4) {
            if (data[i] == patchBytes[0] && data[i + 1] == patchBytes[1] &&
                data[i + 2] == patchBytes[2] && data[i + 3] == patchBytes[3]) {
                alreadyPatched = true
                break
            }
        }

        if (offsets.isEmpty() && alreadyPatched) {
            Log.i(TAG, "DTB already patched to 692 MHz, skipping")
            return
        }

        if (offsets.isEmpty()) {
            throw RuntimeException("GPU frequency 680 MHz not found in DTB — unexpected firmware")
        }

        Log.i(TAG, "Found ${offsets.size} occurrence(s) of 680 MHz in DTB at offsets: $offsets")

        // Patch all occurrences (all pwrlevel@0 entries across DTB copies)
        for (offset in offsets) {
            data[offset] = patchBytes[0]
            data[offset + 1] = patchBytes[1]
            data[offset + 2] = patchBytes[2]
            data[offset + 3] = patchBytes[3]
        }

        dtbFile.writeBytes(data)
        Log.i(TAG, "DTB patched: ${offsets.size} occurrence(s) of 680 MHz → 692 MHz")
    }

    private fun extractAsset(assetPath: String, dest: File) {
        context.assets.open(assetPath).use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun rootCmd(cmd: String) {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        p.waitFor()
        if (p.exitValue() != 0) {
            val err = p.errorStream.bufferedReader().readText()
            Log.w(TAG, "Command failed: $cmd → $err")
        }
    }

    private fun rootCmdOutput(cmd: String): String {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        p.waitFor()
        return p.inputStream.bufferedReader().readText()
    }

    private fun rootCmdStrict(cmd: String) {
        val p = Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        p.waitFor()
        if (p.exitValue() != 0) {
            val err = p.errorStream.bufferedReader().readText()
            throw RuntimeException("Command failed: $cmd → $err")
        }
    }
}
