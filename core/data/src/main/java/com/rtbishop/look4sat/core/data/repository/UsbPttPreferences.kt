package com.rtbishop.look4sat.core.data.repository

import android.content.SharedPreferences
import com.rtbishop.look4sat.core.domain.model.UsbPttLine
import com.rtbishop.look4sat.core.domain.model.UsbPttPort
import com.rtbishop.look4sat.core.domain.model.UsbPttSettings

internal fun readUsbPttSettings(preferences: SharedPreferences) = UsbPttSettings(
    enabled = preferences.getBoolean("usbPttEnabled", false),
    port = UsbPttPort.entries.firstOrNull { it.name == preferences.getString("usbPttPort", "") } ?: UsbPttPort.SEPARATE,
    address = preferences.getString("usbPttAddress", "").orEmpty(),
    name = preferences.getString("usbPttName", "").orEmpty(),
    line = UsbPttLine.entries.firstOrNull { it.name == preferences.getString("usbPttLine", "") } ?: UsbPttLine.RTS,
    activeHigh = preferences.getBoolean("usbPttActiveHigh", true)
)

internal fun writeUsbPttSettings(editor: SharedPreferences.Editor, settings: UsbPttSettings) {
    editor.putBoolean("usbPttEnabled", settings.enabled)
        .putString("usbPttPort", settings.port.name)
        .putString("usbPttAddress", settings.address)
        .putString("usbPttName", settings.name)
        .putString("usbPttLine", settings.line.name)
        .putBoolean("usbPttActiveHigh", settings.activeHigh)
}
