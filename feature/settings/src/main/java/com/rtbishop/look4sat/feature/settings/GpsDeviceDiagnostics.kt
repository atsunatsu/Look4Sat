package com.rtbishop.look4sat.feature.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/**
 * Reproducible, privacy-preserving Android location diagnostics for the test APK.
 * Do not include latitude, longitude, grid, IMEI, device IDs or account details.
 */
internal fun gpsDeviceSnapshot(context: Context): String {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED
    val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    fun provider(name: String): String {
        if (manager == null) return "manager=null"
        return try {
            "exists=${LocationManagerCompat.hasProvider(manager, name)}, enabled=${manager.isProviderEnabled(name)}"
        } catch (e: RuntimeException) {
            "exception=${e.javaClass.simpleName}"
        }
    }
    val systemEnabled = if (manager == null) "manager=null" else try {
        LocationManagerCompat.isLocationEnabled(manager).toString()
    } catch (e: RuntimeException) {
        "exception=${e.javaClass.simpleName}"
    }
    val passiveAge = if (manager == null || (!fine && !coarse)) "not checked (permission missing)"
        else try {
            val last = manager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            last?.let { "${(System.currentTimeMillis() - it.time) / 1000}s" } ?: "none"
        } catch (e: RuntimeException) {
            "exception=${e.javaClass.simpleName}"
        }
    return buildString {
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Package: ${context.packageName}")
        appendLine("checkSelfPermission: fine=$fine; coarse=$coarse")
        appendLine("isLocationEnabled: $systemEnabled")
        appendLine("GPS provider: ${provider(LocationManager.GPS_PROVIDER)}")
        appendLine("Network provider: ${provider(LocationManager.NETWORK_PROVIDER)}")
        appendLine("Passive provider: ${provider(LocationManager.PASSIVE_PROVIDER)}")
        append("Passive lastKnown age: $passiveAge")
    }
}
