package com.andrerinas.openheadunit.hud

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.Parcelable
import android.util.Log
import com.byd.spi.ipc.cursor.BinderCursor
import dalvik.system.PathClassLoader
import java.lang.reflect.Array as ReflectArray
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * Binder transport to the installed car server's property service.
 * Safely resolves the BinderCursor provider and handles permissions gracefully.
 */
internal object BydCarServerTransport {
    private var service: Any? = null
    private var valueCtor: Constructor<*>? = null
    private var valueClass: Class<*>? = null
    private var setMethod: Method? = null
    private var readMethod: Method? = null

    @Synchronized
    private fun ensureService(context: Context): Any? {
        service?.let { return it }
        return runCatching {
            val loader = PathClassLoader(CAR_SERVER_APK, context.classLoader)
            val binder = queryBinder(context) ?: return null
            val stub = Class.forName("$INTERFACE\$Stub", true, loader)
            val proxy = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            runCatching {
                val pkg = listOf("com.byd.automap", "com.byd.navigation", context.packageName).firstOrNull() ?: context.packageName
                proxy.javaClass.getMethod("setPackageInfo", String::class.java, Int::class.javaPrimitiveType)
                    .invoke(proxy, pkg, android.os.Process.myPid())
            }
            valueClass = Class.forName("com.byd.car.property.CarPropertyValue", true, loader)
            valueCtor = valueClass!!.getConstructor(String::class.java, Any::class.java)
            val arrayClass = ReflectArray.newInstance(valueClass, 0).javaClass
            setMethod = proxy.javaClass.getMethod("setProperties", arrayClass)
            readMethod = proxy.javaClass.getMethod("getProperty", String::class.java)
            service = proxy
            proxy
        }.getOrNull()
    }

    private fun queryBinder(context: Context): IBinder? {
        return runCatching {
            context.contentResolver.query(
                Uri.parse(PROVIDER_URI), null, null, arrayOf(INTERFACE), null,
            )?.use { cursor ->
                val extras: Bundle = cursor.extras ?: return null
                extras.classLoader = BinderCursor.BinderParcelable::class.java.classLoader
                @Suppress("DEPRECATION")
                val wrapper = extras.getParcelable<Parcelable>("binder") as? BinderCursor.BinderParcelable
                wrapper?.binder
            }
        }.getOrNull()
    }

    /** Returns the completed property-handler status (0 = success, -1 = error). */
    fun write(context: Context, key: String, value: Int): Int {
        return runCatching {
            val proxy = ensureService(context) ?: return -1
            val values = ReflectArray.newInstance(valueClass, 1)
            ReflectArray.set(values, 0, valueCtor!!.newInstance(key, value as Any))
            val result = setMethod!!.invoke(proxy, values)
            val code = result.javaClass.getField("code").getInt(result)
            if (code != 0) {
                val description = result.javaClass.getField("description").get(result)
                Log.d("BYD-CarServer-Navi", "Property write key=$key code=$code: $description")
            }
            code
        }.getOrDefault(-1)
    }

    /** Reads a property through the same service; returns "code=value" description. */
    fun read(context: Context, key: String): String {
        return runCatching {
            val proxy = ensureService(context) ?: return "code=-1"
            val response = readMethod!!.invoke(proxy, key)
            val status = response.javaClass.getField("status").get(response)
            val code = status.javaClass.getField("code").getInt(status)
            val result = response.javaClass.getField("result").get(response)
            val value = result?.javaClass?.getField("mValue")?.get(result)
            "code=$code value=$value"
        }.getOrDefault("code=-1")
    }

    private const val CAR_SERVER_APK = "/system/priv-app/DiCarServer/DiCarServer.apk"
    private const val PROVIDER_URI = "content://com.byd.car.server.provider.CarServiceProvider/sync_binder"
    private const val INTERFACE = "com.byd.car.property.ICarPropertyService"
}
