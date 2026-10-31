/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
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
package com.rtbishop.look4sat.core.domain.source

object Sources {
    // Default TLE sources, aligned with upstream's "most specific to most generic" order:
    // the first source that knows a satellite gets to name it, with the full catalogue
    // (CelesTrak active) as the fallback. Upstream dropped the old CelesTrak category
    // groups (amateur/cubesat/weather/...) — a single `active` group covers them and keeps
    // the request count (and CelesTrak's 2-hourly "already downloaded" 403s) down.
    // The three BA7OPF additions keep their own spots: Kaggle LAPAN-A2 first and the two
    // AutoTLE mirrors next to R4UAB (Chinese amateur catalogues, freshest for CN sats).
    val satelliteDataUrls = mapOf(
        "LAPAN-A2" to "https://www.kaggle.com/api/v1/datasets/download/muazamnugroho/lapan-a2-satellite-two-line-element-tle-dataset/LAPAN-A2_TLE_latest.txt",
        "ARISS" to "https://live.ariss.org/iss.txt",
        "R4UAB" to "https://r4uab.ru/satonline.txt",
        "BI4PYM AutoTLE (GitHub)" to "https://raw.githubusercontent.com/BI4PYM/AutoTLE/refs/heads/master/AutoTLE.txt",
        "BI4PYM AutoTLE (Mirror)" to "https://autotle.bi4pym.cn/AutoTLE.txt",
        "Classified" to "https://www.mmccants.org/tles/classfd.zip",
        "Amsat" to "https://amsat.org/tle/current/nasabare.txt",
        "All" to "https://celestrak.org/NORAD/elements/gp.php?GROUP=active&FORMAT=csv",
        "SatNOGS" to "https://db.satnogs.org/api/tle/?format=3le",
        "Other" to "" // key for sats filter
    )
    // Transceiver sources. Both publish the same SatNOGS-uuid records (measured 2026-10-04:
    // 2760 shared uuids), and the sync keeps the FIRST source's copy for a shared uuid
    // (distinctBy { uuid }). SatNOGS is kept first on purpose: its copies carry the
    // up-to-date descriptions (e.g. AO-91 "no CTCSS any longer") and it is the only source
    // with the newest satellites' transmitters (T-18 objects etc.), while R4UAB mainly adds
    // long-dead entries plus a handful of live extras. Upstream lists R4UAB first; this fork
    // intentionally differs.
    val transceiversDataUrls = mapOf(
        "SatNOGS" to "https://db.satnogs.org/api/transmitters/?format=json&status=active",
        "R4UAB" to "https://r4uab.ru/transmitters.json"
    )
    /**
     * Hardcoded AMSAT Live FM satellites (NORAD catnums):
     * SO-50 (27607), ISS ZARYA (25544), AO-123 ASRTU-1 (61781),
     * RS95S/QMR-KWT-2 (67291), UNNE-1B (98205).
     * Replaces the AMSAT live-page fetch: stable on any network, no
     * sync-time dependency, no stale-list or timeout failure modes.
     *
     * BA7OPF additions (2026-10-09):
     *  - RS95S / QMR-KWT-2 (67291): V/U FM transponder 145.920/436.950 CTCSS 67.0
     *    (SatNOGS "QMR-KWT-2 (RS95S)", R4UAB "QMR-KWT-2 (RS95S)", AMSAT "RS95S
     *    (QMR-KWT-2)"; the LoTW alias table maps the tracker name to ARRL's "RS95S").
     *  - UNNE-1B (98205): AMSAT-EA 1.5P PocketQube released from the T-18 OTV
     *    "OSSIE" with a V/U FM repeater ("Voice + (A)FSK").
     *  - IO-86 / LAPAN-A2 (40931): V/U FM voice repeater 145.880/435.880 PL 88.5
     *    (SatNOGS "V/U FM Voice (PL 88.5Hz)"; R4UAB "LAPAN-A2 (IO-86)", SatNOGS
     *    3LE "LAPAN-A2", AMSAT "IO-86"; the LoTW alias table maps LAPAN-A2 to
     *    ARRL's "IO-86"). This fork's first TLE source keeps its elements fresh.
     *  ⚠ 98205 is SatNOGS' analysis number (designator 26227BL) for the freshly
     *    released object — Space-Track assigns the permanent catalogue number later,
     *    so this entry must be re-pointed then; the ephem-watch cron job tracks it.
     *
     * The set is shared: the satellite page / mutual page "AMSAT Live FM" filters
     * and the radar Log page (FM satellites are logged on the fixed nominal
     * voice-repeater pair).
     */
    val amSatFmCatnums = setOf(27607, 25544, 61781, 67291, 98205, 40931)

    /**
     * Hardcoded AMSAT Live linear (SSB/CW) satellites:
     * RS-44 (44909), FO-29 (24278), AO-7 (7530),
     * AO-73 FUNcube-1 (39444), JO-97 JY1SAT (43803).
     */
    val amSatLinearCatnums = setOf(44909, 24278, 7530, 39444, 43803)

    /** Virtual satellite-selection types: transponder/activity filters shown
     *  at the top of the type picker. They resolve to live lists (hardcoded
     *  FM/Linear catnum sets or mode=SSTV radios) instead of persisted
     *  per-type IDs, and are mutually exclusive with the regular TLE-source
     *  types in the picker. */
    val virtualTypeNames = listOf("AMSAT Live FM", "AMSAT Live Linear", "Live SSTV")

    /** Selecting "All" displays every satellite stored locally, regardless of which
     *  enabled TLE source supplied it. The CelesTrak active-group ID cache is not
     *  used to restrict this filter. */
    val allSourceType = "All"
}
