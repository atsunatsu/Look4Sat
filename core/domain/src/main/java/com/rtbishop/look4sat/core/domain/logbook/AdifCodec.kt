/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.domain.logbook

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Small ADI codec for the logbook's actual field set (ADIF 3.1.7).
 *
 * Grid directions follow ADIF: the un-prefixed fields (GRIDSQUARE, VUCC_GRIDS) belong to
 * the OPPOSITE station, the MY_* fields (MY_GRIDSQUARE, MY_VUCC_GRIDS) to the operator.
 */
object AdifCodec {
    private val utc = TimeZone.getTimeZone("UTC")
    private val gridPattern = Regex("[A-R]{2}[0-9]{2}([A-X]{2}([0-9]{2})?)?")

    fun encode(records: List<QsoRecord>, includeStationCallsign: Boolean = true): String = buildString {
        append(field("ADIF_VER", "3.1.7"))
        append(field("PROGRAMID", "Look4Sat"))
        append("<EOH>\r\n")
        records.forEach { record ->
            val startDate = format(record.startUtcMillis, "yyyyMMdd")
            val startTime = format(record.startUtcMillis, "HHmmss")
            append(field("QSO_DATE", startDate))
            append(field("TIME_ON", startTime))
            record.endUtcMillis?.let {
                append(field("QSO_DATE_OFF", format(it, "yyyyMMdd")))
                append(field("TIME_OFF", format(it, "HHmmss")))
            }
            append(field("CALL", record.theirCallsign))
            if (includeStationCallsign) append(field("STATION_CALLSIGN", record.myCallsign))
            appendOptional("MODE", record.mode)
            appendOptional("SUBMODE", record.submode)
            appendOptional("GRIDSQUARE", record.theirGrid)
            // The OPPOSITE station's multi-grid set (2+ grids: a grid line or corner).
            // A single grid is carried by GRIDSQUARE; one value inside the VUCC field
            // makes Wavelog flag the record in its QSO view.
            // Prefer complete original locators when available, not VUCC's 4-char index.
            val theirExportGrids = record.theirFullGrids.ifEmpty { record.theirVuccGrids }
            if (theirExportGrids.size > 1) {
                appendOptional("VUCC_GRIDS", theirExportGrids.joinToString(","))
            }
            appendOptional("MY_GRIDSQUARE", record.myGrid)
            // The grid set this qso went out under (own operated grids, stamped at
            // upload). ADIF's MY_VUCC_GRIDS is its home — not VUCC_GRIDS, which belongs
            // to the opposite station and drives Wavelog's grid/distance/QRB display.
            appendOptional("MY_VUCC_GRIDS",
                record.myFullGrids.ifEmpty { record.vuccGrids }.joinToString(","))
            appendOptional("RST_SENT", record.sentReport)
            appendOptional("RST_RCVD", record.receivedReport)
            record.txFrequencyHz?.let { append(field("FREQ", hzToMhz(it))) }
            record.rxFrequencyHz?.let { append(field("FREQ_RX", hzToMhz(it))) }
            appendOptional("BAND", record.band)
            appendOptional("BAND_RX", record.rxBand)
            appendOptional("PROP_MODE", record.propagationMode.ifBlank { if (record.satelliteName.isNotBlank()) "SAT" else "" })
            appendOptional("SAT_NAME", record.satelliteName)
            appendOptional("SAT_MODE", record.satelliteMode)
            if (record.lotwConfirmed) append(field("LOTW_QSL_RCVD", "Y"))
            if (record.lotwReceived) append(field("LOTW_QSL_SENT", "Y"))
            appendOptional("LOTW_QSLRDATE", record.lotwQslDate)
            record.dxcc?.let { append(field("DXCC", it.toString())) }
            appendOptional("COUNTRY", record.country)
            record.cqZone?.let { append(field("CQZ", it.toString())) }
            appendOptional("STATE", record.region)
            appendOptional("IOTA", record.iota)
            appendOptional("MY_IOTA", record.myIota)
            appendOptional("COMMENT", record.comment)
            appendOptional("APP_LOOK4SAT_TRANSPONDER", record.transponderName)
            record.passAosUtcMillis?.let { append(field("APP_LOOK4SAT_PASS_AOS", it.toString())) }
            append(field("APP_LOOK4SAT_AUTOMATIC", if (record.automatic) "Y" else "N"))
            append(field("APP_LOOK4SAT_STATUS", record.status.name))
            append("<EOR>\r\n")
        }
    }

    fun decode(content: String): List<QsoRecord> = decodeRecords(splitRecords(content))

    fun decodeRecords(records: List<Map<String, String>>): List<QsoRecord> = records.mapNotNull { values ->
        val call = values["CALL"].orEmpty().trim().uppercase(Locale.US)
        val date = values["QSO_DATE"].orEmpty()
        val time = values["TIME_ON"].orEmpty()
        if (call.isBlank() || date.length != 8 || time.length !in setOf(4, 6)) return@mapNotNull null
        val start = parse(date, time) ?: return@mapNotNull null
        val end = values["QSO_DATE_OFF"]?.let { endDate ->
            values["TIME_OFF"]?.let { endTime -> parse(endDate, endTime) }
        }
        val theirVuccGrids = decodeGridSet(values["GRIDSQUARE"], values["VUCC_GRIDS"])
        // Boundary QSOs may carry only VUCC_GRIDS; keep a primary for legacy consumers.
        val theirGrid = values["GRIDSQUARE"].orEmpty().uppercase(Locale.US)
            .ifBlank { theirVuccGrids.firstOrNull().orEmpty() }
        QsoRecord(
            startUtcMillis = start,
            endUtcMillis = end,
            theirCallsign = call,
            myCallsign = values["STATION_CALLSIGN"].orEmpty().uppercase(Locale.US),
            theirGrid = theirGrid,
            theirVuccGrids = theirVuccGrids,
            theirFullGrids = decodeFullGridSet(values["GRIDSQUARE"], values["VUCC_GRIDS"]),
            myGrid = values["MY_GRIDSQUARE"].orEmpty().uppercase(Locale.US),
            myFullGrids = decodeFullGridSet(values["MY_GRIDSQUARE"], values["MY_VUCC_GRIDS"]),
            vuccGrids = decodeGridSet(values["MY_GRIDSQUARE"], values["MY_VUCC_GRIDS"]),
            sentReport = values["RST_SENT"].orEmpty(),
            receivedReport = values["RST_RCVD"].orEmpty(),
            txFrequencyHz = mhzToHz(values["FREQ"]),
            rxFrequencyHz = mhzToHz(values["FREQ_RX"]),
            band = values["BAND"].orEmpty(),
            rxBand = values["BAND_RX"].orEmpty(),
            mode = values["MODE"].orEmpty(),
            submode = values["SUBMODE"].orEmpty(),
            satelliteName = values["SAT_NAME"].orEmpty(),
            transponderName = values["APP_LOOK4SAT_TRANSPONDER"].orEmpty(),
            satelliteMode = values["SAT_MODE"].orEmpty(),
            passAosUtcMillis = values["APP_LOOK4SAT_PASS_AOS"]?.toLongOrNull(),
            automatic = values["APP_LOOK4SAT_AUTOMATIC"].equals("Y", true),
            status = values["APP_LOOK4SAT_STATUS"]?.let { runCatching { QsoStatus.valueOf(it) }.getOrNull() }
                ?: QsoStatus.COMPLETE,
            propagationMode = values["PROP_MODE"].orEmpty().trim().uppercase(Locale.US),
            lotwConfirmed = values["LOTW_QSL_RCVD"].equals("Y", true),
            lotwReceived = values["LOTW_QSL_SENT"].equals("Y", true),
            lotwQslDate = values["LOTW_QSLRDATE"].orEmpty(),
            qslConfirmed = values["QSL_RCVD"].equals("Y", true),
            eqslConfirmed = values["EQSL_QSL_RCVD"].equals("Y", true),
            dxcc = values["DXCC"]?.toIntOrNull(),
            country = values["COUNTRY"].orEmpty(),
            cqZone = values["CQZ"]?.toIntOrNull()?.takeIf { it in 1..40 },
            region = values["STATE"].orEmpty().substringBefore(" // ").trim().uppercase(Locale.US),
            iota = values["IOTA"].orEmpty().trim().uppercase(Locale.US),
            myIota = values["MY_IOTA"].orEmpty().trim().uppercase(Locale.US),
            comment = values["COMMENT"].orEmpty()
        )
    }

    private fun splitRecords(content: String): List<Map<String, String>> {
        val records = mutableListOf<Map<String, String>>()
        var values = linkedMapOf<String, String>()
        var index = 0
        while (index < content.length) {
            val start = content.indexOf('<', index)
            if (start < 0) break
            val end = content.indexOf('>', start + 1)
            if (end < 0) break
            val descriptor = content.substring(start + 1, end)
            val parts = descriptor.split(':')
            val name = parts.first().trim().uppercase(Locale.US)
            index = end + 1
            when (name) {
                "EOH" -> values.clear()
                "EOR" -> {
                    if (values.isNotEmpty()) records += values.toMap()
                    values = linkedMapOf()
                }
                else -> {
                    val length = parts.getOrNull(1)?.toIntOrNull() ?: continue
                    if (length < 0 || index + length > content.length) continue
                    values[name] = content.substring(index, index + length)
                    index += length
                }
            }
        }
        return records
    }

    /** Splits an ADIF grid list on commas, keeping only valid 4/6/8-char locators. */
    private fun decodeGrids(value: String?): List<String> = value.orEmpty().split(',')
        .map { it.trim().uppercase(Locale.US) }
        .filter { it.matches(gridPattern) }
        .distinct()

    /** Keep each source grid at original 4/6/8-character precision. */
    private fun decodeFullGridSet(primary: String?, multi: String?): List<String> =
        (decodeGrids(primary) + decodeGrids(multi)).distinct()

    /** Derive the legacy 4-char VUCC index without changing source locators. */
    private fun decodeGridSet(primary: String?, multi: String?): List<String> {
        // Single-grid records keep the legacy empty list and full locator precision.
        if (multi.isNullOrBlank()) return emptyList()
        return (decodeGrids(primary) + decodeGrids(multi))
            .map { it.take(4) }
            .distinct()
    }

    private fun StringBuilder.appendOptional(name: String, value: String) {
        if (value.isNotBlank()) append(field(name, value))
    }

    private fun field(name: String, value: String): String = "<$name:${value.length}>$value"

    private fun hzToMhz(value: Long): String = String.format(Locale.US, "%.6f", value / 1_000_000.0)

    /** ADIF frequencies are in MHz; keep decoding consistent across file import and LoTW sync. */
    fun mhzToHz(value: String?): Long? = value?.toDoubleOrNull()?.let { (it * 1_000_000.0).toLong() }

    private fun format(value: Long, pattern: String): String =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = utc }.format(Date(value))

    private fun parse(date: String, time: String): Long? = runCatching {
        val normalizedTime = if (time.length == 4) "${time}00" else time
        SimpleDateFormat("yyyyMMddHHmmss", Locale.US).apply {
            isLenient = false
            timeZone = utc
        }.parse(date + normalizedTime)?.time
    }.getOrNull()
}
