package com.rtbishop.look4sat.core.data.framework.transport

import android.content.Context
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings

/** Null means use the existing classic Bluetooth SPP controller unchanged. */
fun makeSerialChannel(context: Context, transport: String, address: String, baudRate: Int): SerialChannel? =
    when (transport) {
        RadioControlSettings.TRANSPORT_USB -> UsbSerialChannel(context.applicationContext, address, baudRate)
        RadioControlSettings.TRANSPORT_BLE -> BleUartChannel(context.applicationContext, address)
        else -> null
    }
