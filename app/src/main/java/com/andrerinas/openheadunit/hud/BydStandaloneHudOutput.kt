package com.andrerinas.openheadunit.hud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log
import java.security.MessageDigest

/** Ordinary-app IPC to the real stock receiver. No shell, local socket or permission grant. */
internal class BydStandaloneHudOutput private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("byd_standalone_hud", Context.MODE_PRIVATE)
    private val session = BydStandaloneSession(
        send = { packet ->
            // 1. Send targeted broadcast if com.byd.clusterdebug is available
            try {
                app.sendBroadcast(Intent("byd.hud.NAVIGATION").setComponent(TARGET)
                    .putExtra("normal", packet).addFlags(Intent.FLAG_RECEIVER_FOREGROUND or 0x01000000))
            } catch (e: Exception) {
                Log.d(TAG, "Targeted HUD broadcast error: ${e.message}")
            }
            // 2. Also send implicit broadcast for any listening HUD / CAN receiver
            try {
                app.sendBroadcast(Intent("byd.hud.NAVIGATION")
                    .putExtra("normal", packet).addFlags(Intent.FLAG_RECEIVER_FOREGROUND or 0x01000000))
            } catch (e: Exception) {
                Log.d(TAG, "Implicit HUD broadcast error: ${e.message}")
            }
            // 3. Send BYD stock CAN/HUD broadcast action
            try {
                app.sendBroadcast(Intent("com.byd.action.HUD_NAVIGATION")
                    .putExtra("normal", packet).addFlags(Intent.FLAG_RECEIVER_FOREGROUND or 0x01000000))
            } catch (e: Exception) {
                Log.d(TAG, "Stock HUD broadcast error: ${e.message}")
            }
            Log.d(TAG, "dispatch uid=${Process.myUid()} bytes=${packet.split(',').size}")
        },
        rememberPendingClear = { pending ->
            check(prefs.edit().putBoolean("pending_clear", pending).commit()) { "Cannot persist HUD cleanup" }
        },
        needsRecovery = prefs.getBoolean("pending_clear", false),
    )

    init {
        Log.i(TAG, "Standalone navigation ready uid=${Process.myUid()} helper=none")
        // Retain the journal if dispatch fails; the next scheduled tick retries.
        runCatching { session.clear() }.onFailure { Log.w(TAG, "Startup clear will retry", it) }
    }

    fun update(icon: Int, exit: Int, distanceMeters: Int, road: String) =
        session.update(icon, exit, distanceMeters, road)
    fun clear() = session.clear()

    companion object {
        private const val TAG = "BYD-Standalone-Live"
        private val TARGET = ComponentName("com.byd.clusterdebug", "com.byd.clusterdebug.BroadcastReceiverCAN")
        @Volatile var syntheticHold = false

        fun create(context: Context): BydStandaloneHudOutput? =
            if (available(context)) BydStandaloneHudOutput(context) else null

        /** Enable windshield HUD broadcast on BYD vehicles or when target receiver is present. */
        fun available(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < 28) return false
            val isByd = Build.MANUFACTURER.contains("BYD", ignoreCase = true) ||
                Build.BRAND.contains("BYD", ignoreCase = true) ||
                Build.FINGERPRINT.contains("BYD", ignoreCase = true)
            if (isByd) return true
            return runCatching {
                val manager = context.packageManager
                val receiver = manager.getReceiverInfo(TARGET, 0)
                receiver.enabled
            }.getOrDefault(false)
        }
    }
}
