package com.andrerinas.openheadunit.hud

import android.content.Context
import android.content.Intent
import android.util.Log
import java.text.Normalizer

/**
 * AutoNavi standard broadcast to the stock map service that feeds the cluster/HUD.
 * DiLink 5 ships it as com.byd.amapservice; DiLink 3.0 (Android 10) ships the same role as
 * com.example.amapservice. Global/European exports may use com.byd.automap or com.byd.navigation,
 * or listen on the implicit broadcast.
 */
internal class BydClusterOutput(private val context: Context, private val targetPackage: String? = null) {
    companion object {
        const val DILINK3_PACKAGE = "com.example.amapservice"
        val PACKAGES = listOf(
            "com.byd.amapservice",
            DILINK3_PACKAGE,
            "com.byd.automap",
            "com.byd.navigation",
            "com.autonavi.amapauto",
            "com.here.app.maps"
        )
        /** Shortest distance DiLink 3.0's HUD draws as a number. */
        const val DILINK3_MIN_DISTANCE_METERS = 10

        fun plainLetters(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

        fun installed(context: Context, pkg: String): Boolean =
            runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
    }

    private var showing = false

    private fun segmentDistance(meters: Int): Int =
        if (targetPackage == DILINK3_PACKAGE && meters in 0 until DILINK3_MIN_DISTANCE_METERS) DILINK3_MIN_DISTANCE_METERS
        else meters

    private fun roadName(road: String): String =
        if (targetPackage == DILINK3_PACKAGE) plainLetters(road) else road

    fun update(frame: BydGuidance?) {
        if (frame == null && !showing) return

        fun buildIntent(pkg: String?): Intent {
            val intent = Intent("AUTONAVI_STANDARD_BROADCAST_SEND")
                .addFlags(Intent.FLAG_RECEIVER_FOREGROUND or 0x01000000)
                .putExtra("IS_BYD_MAP", true)
                .putExtra("IS_BYD_BAIDU_MAP", false)
            if (pkg != null) intent.setPackage(pkg)

            if (frame != null) {
                intent.putExtra("KEY_TYPE", 10001)
                    .putExtra("TYPE", 0)
                    .putExtra("EXTRA_STATE", 0)
                    .putExtra("EXTRA_IS_FOREGROUND", 0)
                    .putExtra("NEW_ICON", frame.clusterIcon)
                    .putExtra("MANEUVER_IMAGE", frame.clusterIcon)
                    .putExtra("ICON", frame.clusterIcon)
                    .putExtra("ROUNG_ABOUT_NUM", frame.roundaboutExit)
                    .putExtra("SEG_REMAIN_DIS", segmentDistance(frame.distanceMeters))
                    .putExtra("NEXT_ROAD_NAME", roadName(frame.road))
                    .putExtra("ROUTE_REMAIN_DIS", frame.remainingMeters)
                    .putExtra("ROUTE_REMAIN_TIME", frame.remainingSeconds)
            } else {
                intent.putExtra("KEY_TYPE", 10019)
                    .putExtra("EXTRA_STATE", 9)
                    .putExtra("EXTRA_IS_FOREGROUND", 1)
                    .putExtra("NEW_ICON", -1)
                    .putExtra("SEG_REMAIN_DIS", -1)
                    .putExtra("NEXT_ROAD_NAME", "")
                    .putExtra("ROUTE_REMAIN_DIS", -1)
                    .putExtra("ROUTE_REMAIN_TIME", -1)
            }
            return intent
        }

        // 1. Send implicit broadcast for any listening HUD / Cluster daemon
        try {
            context.sendBroadcast(buildIntent(null))
        } catch (e: Exception) {
            Log.d("DiAuto-BYD", "Implicit AutoNavi broadcast error: ${e.message}")
        }

        // 2. Send targeted broadcast if a specific target was assigned
        if (targetPackage != null) {
            try {
                context.sendBroadcast(buildIntent(targetPackage))
            } catch (e: Exception) {
                Log.d("DiAuto-BYD", "Targeted AutoNavi broadcast ($targetPackage) error: ${e.message}")
            }
        }

        // 3. Also send to all discovered candidate packages
        val candidates = PACKAGES.filter { installed(context, it) && it != targetPackage }
        for (pkg in candidates) {
            try {
                context.sendBroadcast(buildIntent(pkg))
            } catch (e: Exception) {
                Log.d("DiAuto-BYD", "Candidate AutoNavi broadcast ($pkg) error: ${e.message}")
            }
        }

        showing = frame != null
    }
}
