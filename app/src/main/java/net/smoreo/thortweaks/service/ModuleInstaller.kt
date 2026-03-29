package net.smoreo.thortweaks.service

import android.content.Context
import android.util.Log
import net.smoreo.thortweaks.util.RootShell
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
                RootShell.cmdStrict("mkdir -p $BACKUP_DIR")
                val backupExists = RootShell.cmdOutput("test -f $BACKUP_DIR/vendor_boot.img.bak && echo yes || echo no").trim()
                if (backupExists == "yes") {
                    Log.i(TAG, "Vendor_boot backup already exists, skipping")
                } else {
                    RootShell.cmdStrict("dd if=$VENDOR_BOOT_PART of=$BACKUP_DIR/vendor_boot.img.bak")
                    Log.i(TAG, "Vendor_boot backed up to $BACKUP_DIR/vendor_boot.img.bak")
                }

                // Purge old modules
                onProgress("Removing old modules...")
                for (oldId in OLD_MODULE_IDS) {
                    RootShell.cmd("rm -rf /data/adb/modules/$oldId")
                }

                onProgress("Creating module structure...")
                val apkPath = context.applicationInfo.sourceDir

                // Create full directory structure
                RootShell.cmd("rm -rf $MODULE_DIR")
                RootShell.cmd("mkdir -p $MODULE_DIR/system/bin")
                RootShell.cmd("mkdir -p $MODULE_DIR/system/priv-app/ThorHotkeys")
                RootShell.cmd("mkdir -p $MODULE_DIR/system/etc/permissions")
                RootShell.cmd("mkdir -p $MODULE_DIR/system/vendor/lib/modules")
                RootShell.cmd("mkdir -p $MODULE_DIR/system/vendor/firmware")

                // Copy APK
                onProgress("Installing app as system priv-app...")
                RootShell.cmd("cp $apkPath $MODULE_DIR/system/priv-app/ThorHotkeys/ThorHotkeys.apk")
                RootShell.cmd("chmod 644 $MODULE_DIR/system/priv-app/ThorHotkeys/ThorHotkeys.apk")

                // Extract all assets to temp
                val tmpDir = File(context.cacheDir, "module_tmp")
                tmpDir.deleteRecursively()
                tmpDir.mkdirs()

                // pservice binary
                onProgress("Installing GPU overclock (pservice)...")
                extractAsset("module/system/bin/pservice", File(tmpDir, "pservice"))
                RootShell.cmd("cp ${tmpDir}/pservice $MODULE_DIR/system/bin/pservice")
                RootShell.cmd("chmod 755 $MODULE_DIR/system/bin/pservice")

                // Kernel modules
                onProgress("Installing GPU kernel modules...")
                for (ko in listOf("gpucc-kalama.ko", "msm_lmh_dcvs.ko", "gpio5_pwm.ko")) {
                    extractAsset("module/system/vendor/lib/modules/$ko", File(tmpDir, ko))
                    RootShell.cmd("cp ${tmpDir}/$ko $MODULE_DIR/system/vendor/lib/modules/$ko")
                    RootShell.cmd("chmod 644 $MODULE_DIR/system/vendor/lib/modules/$ko")
                }

                // GPU firmware
                onProgress("Installing GPU firmware...")
                extractAsset("module/system/vendor/firmware/gmu_gen70200.bin", File(tmpDir, "gmu_gen70200.bin"))
                RootShell.cmd("cp ${tmpDir}/gmu_gen70200.bin $MODULE_DIR/system/vendor/firmware/gmu_gen70200.bin")
                RootShell.cmd("chmod 644 $MODULE_DIR/system/vendor/firmware/gmu_gen70200.bin")

                // Permissions XML
                onProgress("Setting up permissions...")
                extractAsset(
                    "module/system/etc/permissions/privapp-permissions-thor-hotkeys.xml",
                    File(tmpDir, "privapp-permissions-thor-hotkeys.xml")
                )
                RootShell.cmd("cp ${tmpDir}/privapp-permissions-thor-hotkeys.xml $MODULE_DIR/system/etc/permissions/")
                RootShell.cmd("chmod 644 $MODULE_DIR/system/etc/permissions/privapp-permissions-thor-hotkeys.xml")

                // module.prop
                onProgress("Writing module metadata...")
                extractAsset("module/module.prop", File(tmpDir, "module.prop"))
                RootShell.cmd("cp ${tmpDir}/module.prop $MODULE_DIR/module.prop")

                // service.sh
                extractAsset("module/service.sh", File(tmpDir, "service.sh"))
                RootShell.cmd("cp ${tmpDir}/service.sh $MODULE_DIR/service.sh")
                RootShell.cmd("chmod 755 $MODULE_DIR/service.sh")

                // Patch vendor_boot DTB for GPU overclock
                onProgress("Patching vendor_boot DTB...")
                val vbDir = File(context.cacheDir, "vendor_boot_work")
                vbDir.deleteRecursively()
                vbDir.mkdirs()
                val vbPath = vbDir.absolutePath

                // Dump current vendor_boot
                RootShell.cmdStrict("dd if=$VENDOR_BOOT_PART of=$vbPath/vendor_boot.img")

                // Unpack with magiskboot to extract DTB
                RootShell.cmdStrict("cd $vbPath && $MAGISKBOOT unpack vendor_boot.img")

                // Copy DTB to app-writable location, patch, copy back
                RootShell.cmdStrict("cp $vbPath/dtb $vbPath/dtb_work")
                RootShell.cmdStrict("chmod 666 $vbPath/dtb_work")

                val dtbFile = File(vbPath, "dtb_work")
                patchDtbGpuFreq(dtbFile)

                RootShell.cmdStrict("cp $vbPath/dtb_work $vbPath/dtb")

                // Repack
                RootShell.cmdStrict("cd $vbPath && $MAGISKBOOT repack vendor_boot.img")

                // Flash patched image back
                onProgress("Flashing patched vendor_boot...")
                RootShell.cmdStrict("dd if=$vbPath/new-boot.img of=$VENDOR_BOOT_PART")

                // Clean up vendor_boot work dir
                vbDir.deleteRecursively()

                // Set ownership
                RootShell.cmd("chown -R 0:0 $MODULE_DIR")

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
                uninstallSync()
                onDone(true, "Module removed. Reboot to complete.")
            } catch (e: Exception) {
                onDone(false, "Uninstall failed: ${e.message}")
            }
        }.start()
    }

    fun uninstallSync() {
        RootShell.cmd("rm -rf $MODULE_DIR")
        for (oldId in OLD_MODULE_IDS) {
            RootShell.cmd("rm -rf /data/adb/modules/$oldId")
        }
    }

    fun hasVendorBootBackup(): Boolean {
        return RootShell.cmdOutput("test -f $BACKUP_DIR/vendor_boot.img.bak && echo yes || echo no").trim() == "yes"
    }

    fun restoreVendorBoot(onProgress: (String) -> Unit, onDone: (Boolean, String) -> Unit) {
        Thread {
            try {
                onProgress("Restoring vendor_boot from backup...")
                restoreVendorBootSync()
                onDone(true, "Vendor_boot restored. Reboot to apply.")
            } catch (e: Exception) {
                Log.e(TAG, "Restore failed", e)
                onDone(false, "Restore failed: ${e.message}")
            }
        }.start()
    }

    fun restoreVendorBootSync() {
        RootShell.cmdStrict("dd if=$BACKUP_DIR/vendor_boot.img.bak of=$VENDOR_BOOT_PART")
    }

    /**
     * Patch the GPU max frequency in the DTB from 680 MHz to 692 MHz.
     * Searches for the big-endian 32-bit value 680000000 (0x2887FA00)
     * and replaces with 692000000 (0x29457600).
     * Throws if not found or if multiple occurrences exist.
     */
    private fun patchDtbGpuFreq(dtbFile: File) {
        val stockBytes = 680_000_000.toBigEndianBytes()
        val patchBytes = 692_000_000.toBigEndianBytes()

        val data = dtbFile.readBytes()
        val offsets = data.findAll(stockBytes)

        if (offsets.isEmpty()) {
            if (data.findAll(patchBytes).isNotEmpty()) {
                Log.i(TAG, "DTB already patched to 692 MHz, skipping")
                return
            }
            throw RuntimeException("GPU frequency 680 MHz not found in DTB — unexpected firmware")
        }

        Log.i(TAG, "Found ${offsets.size} occurrence(s) of 680 MHz in DTB at offsets: $offsets")

        for (offset in offsets) {
            patchBytes.copyInto(data, offset)
        }

        dtbFile.writeBytes(data)
        Log.i(TAG, "DTB patched: ${offsets.size} occurrence(s) of 680 MHz → 692 MHz")
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

    private fun extractAsset(assetPath: String, dest: File) {
        context.assets.open(assetPath).use { input ->
            FileOutputStream(dest).use { output ->
                input.copyTo(output)
            }
        }
    }

}
