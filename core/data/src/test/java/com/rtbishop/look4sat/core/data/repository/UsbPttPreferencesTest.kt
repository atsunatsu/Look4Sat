package com.rtbishop.look4sat.core.data.repository

import android.content.SharedPreferences
import com.rtbishop.look4sat.core.domain.model.*
import java.lang.reflect.Proxy
import org.junit.Assert.*
import org.junit.Test

class UsbPttPreferencesTest {
    private fun preferences(values: MutableMap<String, Any>): SharedPreferences {
        lateinit var editor: SharedPreferences.Editor
        editor = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
            if (method.name.startsWith("put")) values[args!![0] as String] = args[1]
            editor
        } as SharedPreferences.Editor
        return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
            when (method.name) {
                "edit" -> editor
                "getBoolean", "getString" -> values[args!![0] as String] ?: args[1]
                else -> error(method.name)
            }
        } as SharedPreferences
    }
    @Test fun legacyPreferencesKeepCatPttAndExistingHandshakeValues() {
        val values = mutableMapOf<String, Any>("radioCatDtr" to true)
        assertEquals(UsbPttSettings(), readUsbPttSettings(preferences(values)))
        assertEquals(mapOf("radioCatDtr" to true), values)
    }
    @Test fun everyPortLineAndPolaritySurvivesReloadWithoutChangingCatKeys() {
        for (port in UsbPttPort.entries) for (line in UsbPttLine.entries) for (high in listOf(false, true)) {
            val values = mutableMapOf<String, Any>("radioCatDtr" to true)
            val prefs = preferences(values)
            val wanted = UsbPttSettings(true, port, "7:4:5:100:200", "Dual USB / port 2", line, high)
            writeUsbPttSettings(prefs.edit(), wanted)
            assertEquals(wanted, readUsbPttSettings(prefs))
            assertEquals(true, values["radioCatDtr"])
        }
    }
    @Test fun unknownEnumsUseExplicitDefaults() {
        assertEquals(UsbPttSettings(), readUsbPttSettings(preferences(mutableMapOf("usbPttPort" to "unknown", "usbPttLine" to "unknown"))))
    }
}
