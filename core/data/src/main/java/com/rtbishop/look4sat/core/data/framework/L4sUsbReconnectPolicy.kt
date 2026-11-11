package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.model.RadioControlSettings

/**
 * Match an attached USB device against the *selected* L4S CAT radio.
 * Device IDs are ephemeral after re-enumeration, so use the configured
 * VID/PID; the caller must also require a unique device and USB permission.
 */
fun matchesConfiguredL4sUsbDevice(settings: RadioControlSettings, vid: Int, pid: Int): Boolean {
    if (!settings.enabled || settings.catTransport != RadioControlSettings.TRANSPORT_USB ||
        settings.radioModel !in setOf(RadioControlSettings.MODEL_QUANSHENG_UVK1,
            RadioControlSettings.MODEL_QUANSHENG_UVK5V3)) return false
    val selector = settings.txRadioAddress.split(':')
    return selector.size == 5 && selector[3].toIntOrNull() == vid &&
        selector[4].toIntOrNull() == pid
}
