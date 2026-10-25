package com.andrerinas.openheadunit.hud

import android.content.Context
import android.util.Log
import java.lang.reflect.InvocationTargetException

/**
 * Factory HUD output using the installed BYD SDK.
 * Sends session heartbeat (sendAutoNaviStatus: 2) for DiLink 3.0 / 4.0 and
 * forwards turn maneuver and distance codes.
 */
internal class BydFactoryNavigationOutput(private val context: Context) {
    private var device: Any? = null
    private var showing = false
    private var logged = false

    fun update(icon: Int, exit: Int, distance: Int) {
        val turn = BydFactoryTurnCode.map(icon, exit) ?: run { clear(); return }
        if (distance !in 0..16777214) { clear(); return }
        try {
            val sdk = device ?: Class.forName("android.hardware.bydauto.instrument.BYDAutoInstrumentDevice")
                .getMethod("getInstance", Context::class.java).invoke(null, context).also { device = it }

            // 1. Session start & heartbeat (status 2 = active navigation)
            runCatching {
                sdk.javaClass.getMethod("sendAutoNaviStatus", Int::class.javaPrimitiveType).invoke(sdk, 2)
            }
            showing = true

            // 2. Instrument guide info & distance properties
            runCatching {
                val idsClass = Class.forName("android.hardware.bydauto.BYDAutoFeatureIds\$Instrument")
                val ids = arrayOf(
                    "INSTRUMENT_FRONT_CROSSING_DISTANCE_SET",
                    "INSTRUMENT_GUIDE_INFO_SIMPLE_SET",
                    "INSTRUMENT_GUIDE_INFO_AND_ROAD_AHEAD_DISTANCE_SET"
                ).mapNotNull { name ->
                    runCatching { idsClass.getField(name).getInt(null) }.getOrNull()
                }.toIntArray()

                if (ids.isNotEmpty()) {
                    val valueClass = Class.forName("android.hardware.bydauto.BYDAutoEventValue")
                    val value = valueClass.getConstructor().newInstance()
                    valueClass.getField("intArrayValue").set(value, intArrayOf(distance, turn, turn))
                    sdk.javaClass.getMethod("set", IntArray::class.java, valueClass).invoke(sdk, ids, value)
                }
            }.onFailure { e ->
                Log.d(TAG, "Factory property set note: ${e.message}")
            }

            if (!logged) {
                Log.i(TAG, "Factory navigation active turn=$turn distance=$distance")
                logged = true
            }
        } catch (error: Exception) {
            val cause = (error as? InvocationTargetException)?.targetException ?: error
            Log.d(TAG, "Factory navigation tick: ${cause.javaClass.simpleName}: ${cause.message}")
        }
    }

    fun clear() {
        if (!showing) return
        showing = false
        logged = false
        try {
            val sdk = device ?: return
            sdk.javaClass.getMethod("sendAutoNaviStatus", Int::class.javaPrimitiveType).invoke(sdk, 1)
            Log.i(TAG, "Factory navigation ended")
        } catch (error: Exception) {
            val cause = (error as? InvocationTargetException)?.targetException ?: error
            Log.d(TAG, "Factory navigation clear note: ${cause.message}")
        }
    }

    companion object { private const val TAG = "BYD-Factory-Navi" }
}
