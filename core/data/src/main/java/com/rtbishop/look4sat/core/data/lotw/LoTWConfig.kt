/*
 * Look4Sat-BA7OPF. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2026 BA7OPF.
 * Based on Look4Sat by Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.core.data.lotw

import com.rtbishop.look4sat.core.domain.logbook.QsoRecord
import com.rtbishop.look4sat.core.domain.logbook.LoTWSatelliteAliases
import com.rtbishop.look4sat.core.domain.logbook.displayMode
import com.rtbishop.look4sat.core.domain.repository.LoTWProblem
import com.rtbishop.look4sat.core.domain.repository.LoTWRegionField
import com.rtbishop.look4sat.core.domain.repository.LoTWRegionOption
import com.rtbishop.look4sat.core.domain.repository.LoTWStation
import com.rtbishop.look4sat.core.domain.repository.LoTWStationMeta
import com.rtbishop.look4sat.core.domain.repository.LoTWZonePair
import com.rtbishop.look4sat.core.domain.utility.StationGridSetIssue
import com.rtbishop.look4sat.core.domain.utility.stationGridSetIssue
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import java.io.InputStream
import java.io.StringReader
import java.util.Locale
import java.util.zip.GZIPInputStream
import javax.xml.parsers.DocumentBuilderFactory

/** Bundled ARRL configuration 11.35 from https://lotw.arrl.org/lotw/config.tq6. */
internal class LoTWConfig(input: InputStream) {
    private val root = input.use { source -> GZIPInputStream(source).use { gzip ->
        val xml = gzip.bufferedReader(Charsets.UTF_8).readText()
        require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true))
        // Android's DOM provider does not support Xerces-specific setFeature flags.
        DocumentBuilderFactory.newInstance().apply { isExpandEntityReferences = false }.newDocumentBuilder().apply {
            setEntityResolver { _, _ -> throw SAXException("External entities are not permitted") }
        }.parse(InputSource(StringReader(xml))).documentElement
    } }
    private val config = root.elements("tqslconfig").single()
    val version = config.getAttribute("majorversion") + "." + config.getAttribute("minorversion")
    private val spec = config.elements("sigspec").single { it.getAttribute("version") == "2.0" }
    val stationOrder = spec.elements("tSTATION").single().childElements().map { it.tagName }
    val contactOrder = spec.elements("tCONTACT").single().childElements().map { it.tagName }
    private val primaryRegionFields = setOf("US_STATE", "CA_PROVINCE", "RU_OBLAST", "CN_PROVINCE", "AU_STATE", "JA_PREFECTURE", "FI_KUNTA")
    private val secondaryRegionFields = setOf("US_COUNTY", "JA_CITY_GUN_KU")
    private val bands = config.elements("bands").single().elements("band")
    private val satellites = config.elements("satellite").associateBy { it.getAttribute("name").uppercase(Locale.US) }
    private val modes = config.elements("modes").single().elements("mode").map { it.textContent }.toSet()

    fun mode(record: QsoRecord): String {
        val display = record.displayMode
        if (display in modes) return display
        val mapped = config.elements("adifmode").firstOrNull { it.textContent.equals(display, true) }?.getAttribute("mode")
        return mapped ?: fail(LoTWProblem.MODE, display)
    }

    fun band(explicit: String, frequency: Long?, required: Boolean): String {
        if (frequency != null && frequency <= 0) fail(LoTWProblem.BAND)
        val frequencyBand = frequency?.let { hz -> bands.firstOrNull { band ->
            val unit = if (band.getAttribute("spectrum") == "HF") 1_000.0 else 1_000_000.0
            val mhz = hz / unit
            mhz >= band.getAttribute("low").toDouble() && mhz <= band.getAttribute("high").toDouble()
        }?.textContent }
        val value = explicit.trim().uppercase(Locale.US).ifBlank { frequencyBand.orEmpty() }
        if (value.isBlank() && !required && frequency == null) return ""
        if (bands.none { it.textContent == value } || (frequency != null && frequencyBand != value)) fail(LoTWProblem.BAND, value)
        return value
    }

    /** Every satellite name ARRL's config.tq6 knows, sorted: the only names LoTW accepts. */
    fun satelliteNames(): List<String> = satellites.keys.sorted()

    /**
     * Resolve a tracker name to the ARRL name, or null when it is not an ARRL satellite.
     * Exact official name first, then the alias table ("SAUDISAT 1C" -> "SO-50",
     * "ISS (ZARYA)" -> "ARISS"), then an official name contained in the tracker name.
     */
    fun resolveSatellite(name: String): String? = LoTWSatelliteAliases.resolve(name, satellites.keys)

    fun satellite(name: String, date: String): String {
        val normalized = name.trim().uppercase(Locale.US)
        // Tracker names differ from ARRL's: SatNOGS stores "SAUDISAT 1C" for "SO-50" and
        // "ISS (ZARYA)" for "ARISS". resolveSatellite() checks the exact name, the alias
        // table and finally substring containment; anything else is not uploadable.
        val official = resolveSatellite(name) ?: fail(LoTWProblem.SATELLITE, normalized)
        val sat = satellites.getValue(official)
        val first = sat.getAttribute("startDate")
        val last = sat.getAttribute("endDate")
        if ((first.isNotBlank() && date < first) || (last.isNotBlank() && date > last)) fail(LoTWProblem.SATELLITE, normalized)
        return sat.getAttribute("name").uppercase(Locale.US)
    }

    fun propagation(value: String): String {
        val normalized = value.trim().uppercase(Locale.US)
        if (normalized.isNotBlank() && config.elements("propmode").none { it.getAttribute("name") == normalized || it.textContent == normalized }) {
            fail(LoTWProblem.INVALID_CONTACT, normalized)
        }
        return normalized
    }

    /** Region-field metadata and the national zonemap for one DXCC entity. */
    fun stationMeta(dxcc: Int): LoTWStationMeta {
        val pageFields = config.elements("page").filter { it.getAttribute("dependency") == dxcc.toString() }
            .flatMap { it.elements("pageField") }.map { it.textContent }
        val primary = pageFields.firstOrNull { it in primaryRegionFields }
        val regionField = primary?.let { id ->
            val field = config.elements("field").single { it.getAttribute("Id") == id }
            val dependency = if (field.getAttribute("dependsOn") == "DXCC") dxcc.toString() else null
            val options = field.elements("enums").filter {
                it.getAttribute("dependency").isBlank() || it.getAttribute("dependency") == dependency
            }.flatMap { it.elements("enum") }.map { enum ->
                LoTWRegionOption(
                    code = enum.getAttribute("value").uppercase(Locale.US),
                    name = enum.textContent.trim().ifBlank { enum.getAttribute("value") },
                    zones = parseZonemap(enum.getAttribute("zonemap"))
                )
            }
            LoTWRegionField(id = id, label = field.getAttribute("label"), options = options)
        }
        val entity = config.elements("dxcc").flatMap { it.elements("entity") }
            .firstOrNull { it.getAttribute("arrlId") == dxcc.toString() }
        val countryZones = entity?.getAttribute("zonemap")?.let(::parseZonemap).orEmpty()
        return LoTWStationMeta(regionField, countryZones)
    }

    private fun parseZonemap(zonemap: String): List<LoTWZonePair> = zonemap.split(',').mapNotNull { pair ->
        val parts = pair.split(':')
        if (parts.size != 2) return@mapNotNull null
        val itu = parts[0].trim().toIntOrNull() ?: return@mapNotNull null
        val cq = parts[1].trim().toIntOrNull() ?: return@mapNotNull null
        LoTWZonePair(itu, cq)
    }

    fun stationFields(station: LoTWStation, dxcc: Int): Map<String, String> {
        fun normalized(value: String) = value.trim().uppercase(Locale.US)
        val grids = station.grid.split(',').map(::normalized).filter(String::isNotBlank).distinct()
        if (grids.size !in 1..4 || grids.any { !it.matches(Regex("[A-R]{2}[0-9]{2}([A-X]{2}([0-9]{2})?)?")) }) {
            fail(LoTWProblem.STATION_GRID)
        }
        // LoTW station locations allow one grid, a boundary pair, or a four-way intersection —
        // never three, and never a set that only has the right count. The format check above cannot
        // tell a mistyped field letter (OM00AX for PM00AX) from a real location, so reject invalid
        // geometry before signing instead of publishing a station location with distant grids.
        when (stationGridSetIssue(grids)) {
            StationGridSetIssue.COUNT -> fail(LoTWProblem.STATION_GRID_COUNT, grids.joinToString(","))
            StationGridSetIssue.NOT_ADJACENT ->
                fail(LoTWProblem.STATION_GRID_NOT_ADJACENT, grids.joinToString(","))
            null -> Unit
        }
        // TQSL stores a boundary pair or four-way intersection as one comma-separated GRIDSQUARE
        // station field. LoTW derives MY_VUCC_GRIDS in its QSO detail reports; it is not a tSTATION
        // field and must not be emitted as one. The complete value is also included in V2.0 signing.
        val fields = linkedMapOf("GRIDSQUARE" to grids.joinToString(","))
        fun zone(name: String, value: String, maximum: Int) {
            if (value.isBlank()) return
            val number = value.trim().toIntOrNull()?.takeIf { it in 1..maximum } ?: fail(LoTWProblem.STATION_ZONE)
            fields[name] = number.toString()
        }
        zone("CQZ", station.cqZone, 40)
        zone("ITUZ", station.ituZone, 90)
        if (station.iota.isNotBlank()) {
            val iota = normalized(station.iota)
            if (!iota.matches(Regex("(AF|AN|AS|EU|NA|OC|SA)-[0-9]{3}"))) fail(LoTWProblem.STATION_IOTA)
            fields["IOTA"] = iota
        }
        val pageFields = config.elements("page").filter { it.getAttribute("dependency") == dxcc.toString() }
            .flatMap { it.elements("pageField") }.map { it.textContent }
        val primary = pageFields.firstOrNull { it in primaryRegionFields }
        val secondary = pageFields.firstOrNull { it in secondaryRegionFields }
        fun region(name: String?, value: String) {
            if (value.isBlank()) return
            if (name == null) fail(LoTWProblem.STATION_REGION)
            val field = config.elements("field").single { it.getAttribute("Id") == name }
            val dependency = when (field.getAttribute("dependsOn")) {
                "DXCC" -> dxcc.toString()
                else -> fields[field.getAttribute("dependsOn")]
            }
            val options = field.elements("enums").filter {
                it.getAttribute("dependency").isBlank() || it.getAttribute("dependency") == dependency
            }.flatMap { it.elements("enum") }.map { it.getAttribute("value").uppercase(Locale.US) }
            val code = normalized(value)
            if (code !in options) fail(LoTWProblem.STATION_REGION, name)
            fields[name] = code
        }
        region(primary, station.region)
        region(secondary, station.county)
        return fields
    }
}

private fun Element.elements(tag: String): List<Element> = getElementsByTagName(tag).let { nodes ->
    (0 until nodes.length).map { nodes.item(it) as Element }
}
private fun Element.childElements(): List<Element> = (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
