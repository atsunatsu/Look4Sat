package com.rtbishop.look4sat.feature.mutual

import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.NearEarthObject
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.predict.OrbitalObject
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import kotlin.math.PI

/**
 * Deterministic orbital test fixtures: real ISS TLE elements (2024-03-09,
 * same source as DataParserTest) so SGP4 propagation produces genuine
 * visibility windows instead of fabricated numbers.
 */
object TestOrbits {

    /** ISS (ZARYA) from the 2024-03-09 TLE used in DataParserTest. */
    val ISS: OrbitalObject = NearEarthObject(
        OrbitalData(
            name = "ISS (ZARYA)",
            epoch = 24069.23963816,
            meanmo = 15.49756209,
            eccn = 0.0005741,
            incl = 51.6418,
            raan = 90.7424,
            argper = 343.9724,
            meanan = 92.8274,
            catnum = 25544,
            bstar = 0.00025016
        )
    )

    /** Guangzhou (default BA7OPF grid OL62/OL63 area). */
    val GUANGZHOU = GeoPos(23.13, 113.26)

    /** Shenzhen, ~100 km from Guangzhou — nearly every LEO pass is mutual. */
    val SHENZHEN = GeoPos(22.54, 114.06)

    /** New York — on the opposite side of the globe, never mutual with GZ. */
    val NEW_YORK = GeoPos(40.71, -74.01)

    /**
     * Second LEO with the same ISS elements but a different argument of
     * perigee, so its visibility windows land at different times. Gives tests
     * two distinct passes to exercise ordering/aggregation logic.
     */
    val ISS_VARIANT: OrbitalObject = NearEarthObject(
        OrbitalData(
            name = "ISS-V2",
            epoch = 24069.23963816,
            meanmo = 15.49756209,
            eccn = 0.0005741,
            incl = 51.6418,
            raan = 90.7424,
            argper = 100.0,
            meanan = 92.8274,
            catnum = 25545,
            bstar = 0.00025016
        )
    )

    /**
     * Coarse pass-window search (30s steps) producing OrbitalPass entries with
     * honest AOS/LOS times. Mirrors the coarse phase of SatelliteRepo.getLeoPass;
     * the ViewModel under test re-refines the edges to ~1s itself.
     */
    fun findPassWindows(
        sat: OrbitalObject = ISS,
        pos: GeoPos = GUANGZHOU,
        time: Long = System.currentTimeMillis(),
        hoursAhead: Int = 12
    ): List<OrbitalPass> {
        if (!sat.willBeSeen(pos)) return emptyList()
        val passes = mutableListOf<OrbitalPass>()
        val step = 30_000L
        val end = time + hoursAhead * 3600_000L
        var t = time
        var inWindow = false
        var aos = 0L
        var maxElev = 0.0
        while (t < end) {
            val elev = sat.getElevation(pos, t) * 180.0 / PI
            if (elev > 0.0 && !inWindow) {
                inWindow = true
                aos = t
                maxElev = 0.0
            }
            if (inWindow && elev > maxElev) maxElev = elev
            if (elev <= 0.0 && inWindow) {
                inWindow = false
                passes.add(
                    OrbitalPass(
                        aosTime = aos,
                        losTime = t,
                        maxElevation = maxElev,
                        orbitalObject = sat
                    )
                )
            }
            t += step
        }
        return passes
    }
}