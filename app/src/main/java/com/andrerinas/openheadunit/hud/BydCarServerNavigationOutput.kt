package com.andrerinas.openheadunit.hud

import android.content.Context
import android.util.Log

/**
 * CarServer property-service navigation output.
 * Communicates with BYD's vehicle property service for instrument cluster & HUD updates.
 */
internal class BydCarServerNavigationOutput(private val context: Context) {
    private var showing = false
    private var logged = false

    fun update(icon: Int, exit: Int, distance: Int) {
        val turn = BydFactoryTurnCode.map(icon, exit) ?: run { clear(); return }
        if (distance !in 0..16777214) { clear(); return }
        try {
            val resState = BydCarServerTransport.write(context, STATE_USAGE_KEY, 2)
            if (resState == 0) {
                showing = true
                BydCarServerTransport.write(context, DISTANCE_KEY, distance)
                BydCarServerTransport.write(context, TURN_ROAD_AHEAD_KEY, turn)
                if (!logged) {
                    Log.i(TAG, "Car-server navigation active turn=$turn distance=$distance")
                    logged = true
                }
            }
        } catch (error: Exception) {
            Log.d(TAG, "Car-server navigation tick: ${error.message}")
        }
    }

    fun clear() {
        if (!showing) return
        showing = false
        logged = false
        try {
            BydCarServerTransport.write(context, STATE_USAGE_KEY, 1)
            BydCarServerTransport.write(context, DISTANCE_KEY, 0)
            BydCarServerTransport.write(context, TURN_ROAD_AHEAD_KEY, 11)
            Log.i(TAG, "Car-server navigation clear completed")
        } catch (error: Exception) {
            Log.d(TAG, "Car-server navigation clear note: ${error.message}")
        }
    }

    companion object {
        private const val TAG = "BYD-CarServer-Navi"
        private const val STATE_USAGE_KEY = "0x4C212030"
        private const val DISTANCE_KEY = "0x43F01018"
        private const val TURN_ROAD_AHEAD_KEY = "0x43F01030"
    }
}
