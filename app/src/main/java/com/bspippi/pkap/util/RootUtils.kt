package com.bspippi.pkap.util

import android.util.Log
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

object RootUtils {
    private const val TAG = "PKapRoot"

    @Volatile
    private var rootAvailable: Boolean? = null

    fun isRootAvailable(): Boolean {
        rootAvailable?.let { return it }
        val result = try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val exited = process.waitFor(3, TimeUnit.SECONDS)
            if (!exited) {
                process.destroy()
                false
            } else {
                val out = process.inputStream.bufferedReader().readText()
                process.destroy()
                out.contains("uid=0")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Root check failed: ${e.message}")
            false
        }
        rootAvailable = result
        return result
    }

    /** Run a command as root. Returns stdout or null on failure. */
    fun su(cmd: String, timeoutSec: Long = 10): String? {
        return try {
            val process = Runtime.getRuntime().exec("su")
            DataOutputStream(process.outputStream).use { os ->
                os.writeBytes("$cmd\n")
                os.writeBytes("exit\n")
                os.flush()
            }
            val finished = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!finished) {
                process.destroy()
                return null
            }
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            if (process.exitValue() != 0 && stdout.isBlank()) {
                Log.w(TAG, "su cmd failed: $cmd → $stderr")
                null
            } else stdout
        } catch (e: Exception) {
            Log.e(TAG, "su error: ${e.message}")
            null
        }
    }

    fun whichTcpdump(): String? {
        val candidates = listOf(
            "tcpdump",
            "/system/xbin/tcpdump",
            "/system/bin/tcpdump",
            "/data/local/tmp/tcpdump",
            "/data/adb/modules/tcpdump/system/bin/tcpdump"
        )
        for (c in candidates) {
            val out = su("which $c 2>/dev/null || ls $c 2>/dev/null")
            if (!out.isNullOrBlank() && !out.contains("No such")) return c.trim().lines().first()
        }
        // last resort: busybox
        val bb = su("busybox which tcpdump 2>/dev/null")
        if (!bb.isNullOrBlank()) return "busybox tcpdump"
        return null
    }

    fun listInterfaces(): List<String> {
        val out = su("ip link show 2>/dev/null || ifconfig -a 2>/dev/null") ?: return listOf("any")
        val ifaces = mutableListOf<String>()
        // simple parse
        Regex("""^\d+:\s+(\S+):""", RegexOption.MULTILINE).findAll(out).forEach {
            val name = it.groupValues[1].removeSuffix(":")
            if (name != "lo" && !name.startsWith("dummy")) ifaces.add(name)
        }
        if (ifaces.isEmpty()) ifaces.add("any")
        return ifaces.distinct()
    }
}
