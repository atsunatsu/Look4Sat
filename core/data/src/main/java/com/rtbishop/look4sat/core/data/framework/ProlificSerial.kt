package com.rtbishop.look4sat.core.data.framework

/**
 * PL2303 register initialization adapted from usb-serial-for-android's ProlificSerialDriver.
 * Original port: Felix Hädicke, based on Emmanuel Blot's pyprolific driver.
 * https://github.com/mik3y/usb-serial-for-android (MIT; license shipped in assets/licenses).
 * Physical RTS/DTR levels follow the user's USB CAT line settings (default: deasserted).
 */
internal fun configureProlificSerial(
    productId: Int, deviceClass: Int, descriptors: ByteArray,
    baudRate: Int, stopBits: Int,
    rts: Boolean = false, dtr: Boolean = false,
    transfer: (type: Int, request: Int, value: Int, index: Int, data: ByteArray?) -> Int
): Boolean {
    if (descriptors.size < 14 || baudRate !in PROLIFIC_CAT_BAUD_RATES || stopBits !in 1..2) return false
    fun word(offset: Int) = (descriptors[offset].toInt() and 255) or
        ((descriptors[offset + 1].toInt() and 255) shl 8)
    val legacy01 = deviceClass == 2 || descriptors[7].toInt() and 255 != 64
    val version = word(12)
    val hxn = productId != 0x2303 || (!legacy01 && word(2) == 0x0200 &&
        !(version in setOf(0x0300, 0x0500) && transfer(0xC0, 1, 0x8080, 0, ByteArray(1)) == 1))
    val vendorRequest = if (hxn) 0x80 else 1
    fun out(value: Int, index: Int) = transfer(0x40, vendorRequest, value, index, null) == 0
    fun read(value: Int) = transfer(0xC0, 1, value, 0, ByteArray(1)) == 1
    if (hxn) {
        if (!out(7, 3)) return false // Clear both HXN data pipes.
    } else {
        if (!out(8, 0) || !out(9, 0)) return false
        if (!read(0x8484) || !out(0x0404, 0) || !read(0x8484) || !read(0x8383) ||
            !read(0x8484) || !out(0x0404, 1) || !read(0x8484) || !read(0x8383) ||
            !out(0, 1) || !out(1, 0) || !out(2, if (legacy01) 0x24 else 0x44)) return false
    }
    if (transfer(0x21, 0x22, cdcControlLineState(dtr, rts), 0, null) != 0) return false
    if (!out(if (hxn) 0x0A else 0, if (hxn) 0xFF else 0)) return false
    val line = byteArrayOf(baudRate.toByte(), (baudRate shr 8).toByte(),
        (baudRate shr 16).toByte(), (baudRate shr 24).toByte(),
        (if (stopBits == 2) 2 else 0).toByte(), 0, 8)
    if (transfer(0x21, 0x20, 0, 0, line) != line.size) return false
    return if (hxn) out(7, 3) else out(8, 0) && out(9, 0)
}

// Only the ordinary rates used by the existing CAT model catalog; no custom divisor encoding.
private val PROLIFIC_CAT_BAUD_RATES = setOf(300, 600, 1200, 2400, 4800, 9600, 19200, 38400,
    57600, 115200, 230400, 460800, 921600)
