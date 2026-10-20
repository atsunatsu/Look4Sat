package com.rtbishop.look4sat.core.domain.utility

import com.rtbishop.look4sat.core.domain.model.SatRadio

object CtcssTones {
    val tenths = listOf(670,693,719,744,770,797,825,854,885,915,948,974,1000,1035,1072,1109,
        1148,1188,1230,1273,1318,1365,1413,1462,1514,1567,1598,1622,1655,1679,1713,1738,
        1773,1799,1835,1862,1899,1928,1966,1995,2035,2065,2107,2181,2257,2291,2336,2418,2503,2541)
}

/** Use explicit tone metadata in the transponder description, never a satellite-name guess. */
fun SatRadio.defaultTxCtcssHz(): Double? {
    val mode=(uplinkMode ?: downlinkMode)?.uppercase(java.util.Locale.ROOT)
    if (mode !in setOf("FM","FMN","NFM","FM-N") || uplinkLow == null) return null
    val pattern=Regex("(?i)(?:CTCSS|PL(?:\\s+TONE)?)\\s*[:=]?\\s*(\\d{2,3}(?:[.,]\\d{1,2})?)\\s*(?:Hz)?")
    val matches=pattern.findAll(info).filter { match ->
        val prefix=info.substring(maxOf(0,match.range.first-24),match.range.first)
        !Regex("(?i)(?:RX|DOWNLINK|ARM(?:ING)?|ACTIVAT\\w*|开机|激活)\\s*[:=/-]?\\s*$").containsMatchIn(prefix)
    }.mapNotNull { match ->
        val value=match.groupValues[1].replace(',','.').toDoubleOrNull() ?: return@mapNotNull null
        val tenths=kotlin.math.round(value*10).toInt()
        (tenths/10.0).takeIf { tenths in CtcssTones.tenths && kotlin.math.abs(value*10-tenths)<0.001 }
    }.distinct().toList()
    return matches.singleOrNull()
}
