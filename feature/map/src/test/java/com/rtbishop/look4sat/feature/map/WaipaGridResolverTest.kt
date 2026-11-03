/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.feature.map

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WaipaGridResolverTest {
    private fun resolver(): WaipaGridResolver {
        val relative = "src/main/assets/awards/waipa.json"
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .mapNotNull { dir ->
                listOf(File(dir, relative), File(dir, "feature/map/$relative"))
                    .firstOrNull { it.isFile }
            }.firstOrNull() ?: error("Missing waipa.json")
        val data = JSONObject(file.readText()).getJSONArray("regions")
        val regions = (0 until data.length()).map { i ->
            val entry = data.getJSONObject(i)
            val rawRings = entry.getJSONArray("rings")
            val rings = (0 until rawRings.length()).map { j ->
                val rawPoints = rawRings.getJSONArray(j)
                (0 until rawPoints.length()).map { k ->
                    val p = rawPoints.getJSONArray(k)
                    doubleArrayOf(p.getDouble(0), p.getDouble(1))
                }
            }
            AwardRegion(
                code = entry.getString("code"),
                name = entry.getString("name"),
                nameEn = entry.getString("name_en"),
                labelLon = entry.getDouble("label_lon"),
                labelLat = entry.getDouble("label_lat"),
                rings = rings
            )
        }
        return WaipaGridResolver(regions)
    }

    @Test
    fun `locates six-character Indonesian grids from independent 38 province polygons`() {
        val lookup = resolver()
        assertEquals("JK", lookup("OI33KS")) // Jakarta
        assertEquals("BA", lookup("OI71OI")) // Bali / Denpasar
        assertEquals("YO", lookup("OI52EE")) // Yogyakarta
        assertEquals("PD", lookup("PI59PC")) // Sorong, Southwest Papua
        assertEquals("PB", lookup("PI79AD")) // Manokwari, West Papua
        assertEquals("AC", lookup("NJ75PN")) // Banda Aceh coast
    }


    @Test
    fun `eight-character locators are resolved at full precision`() {
        val lookup = resolver()
        assertEquals("BA", lookup("OI71OI42"))
        assertEquals("PD", lookup("PI59PC55"))
        assertEquals("PB", lookup("PI79AD55"))
        assertNull(lookup("PI59PCZZ"))
        assertNull(lookup("OI71OI4"))
    }

    @Test
    fun `four-character grids count only when all mapped land belongs to one province`() {
        val lookup = resolver()
        assertNull(lookup("OI71")) // touches Bali + East Java + sea
        assertEquals("PD", lookup("PI59")) // Sorong/Raja Ampat: only Southwest Papua land
        assertNull(lookup("PI79")) // spans Papua and West Papua
        assertNull(lookup("OJ82")) // spans East and North Kalimantan
        assertNull(lookup("XXXX"))
        assertNull(lookup("OI71O"))
        assertNull(lookup("OI71ZZ"))
        assertEquals("BA", lookup("oi71oi")) // case insensitive, cached
    }
    @Test
    fun `returns both provinces when a six-character cell crosses their shared border`() {
        fun rectangle(
            code: String,
            west: Double,
            south: Double,
            east: Double,
            north: Double
        ) = AwardRegion(
            code = code,
            name = code,
            labelLon = (west + east) / 2.0,
            labelLat = (south + north) / 2.0,
            rings = listOf(
                listOf(
                    doubleArrayOf(west, south),
                    doubleArrayOf(east, south),
                    doubleArrayOf(east, north),
                    doubleArrayOf(west, north)
                )
            )
        )

        // OJ82MA spans 117.0000..117.0833 E and 2.0000..2.0417 N.
        // The artificial province boundary at 117.04 E crosses that cell.
        val lookup = WaipaGridResolver(
            listOf(
                rectangle("KI", 116.9, 1.9, 117.04, 2.1),
                rectangle("KU", 117.04, 1.9, 117.2, 2.1)
            )
        )
        assertEquals(setOf("KI", "KU"), lookup.candidateProvinces("OJ82MA"))
        assertNull(lookup("OJ82MA"))
        assertEquals(setOf("KI", "KU"), lookup.candidateProvinces("OJ82"))
    }

}
