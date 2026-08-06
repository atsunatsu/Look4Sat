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
package com.rtbishop.look4sat.core.presentation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed class Screen(val iconResId: Int, val titleResId: Int, val screenId: String) : NavKey {

    @Serializable
    data object Satellites : Screen(R.drawable.ic_satellites, R.string.nav_sat, "Satellites")

    @Serializable
    data object Passes : Screen(R.drawable.ic_passes, R.string.nav_pass, "Passes")

    @Serializable
    data object Radar : Screen(R.drawable.ic_radar, R.string.nav_radar, "Radar")

    @Serializable
    data object Map : Screen(R.drawable.ic_map, R.string.nav_map, "Map")

    @Serializable
    data object Mutual : Screen(R.drawable.ic_match, R.string.nav_mutual, "Mutual")

    @Serializable
    data object Roaming : Screen(R.drawable.ic_roaming, R.string.nav_roaming, "Roaming")

    @Serializable
    data object CwDecode : Screen(R.drawable.ic_cw, R.string.nav_cw, "CwDecode")

    @Serializable
    data object WavelogLog : Screen(R.drawable.ic_log, R.string.nav_log, "WavelogLog")

    @Serializable
    data object AmSat : Screen(R.drawable.ic_satellites, R.string.nav_amsat, "AMSAT")

    @Serializable
    data object Settings : Screen(R.drawable.ic_settings, R.string.nav_prefs, "Settings")
}

// UI 设置: 默认主菜单页面顺序(底部栏 5 槽)
val defaultScreenOrder = listOf("Satellites", "Passes", "Radar", "Map", "Settings")

// UI 设置: 默认更多菜单顺序(进「更多」的页面)
val defaultSubMenuOrder = listOf("Mutual", "Roaming", "CwDecode", "WavelogLog", "AMSAT")

@Serializable
data object RadarDestination : NavKey

interface IDeeplinkMatcher {
    fun match(deeplink: String): NavKey?
}

object PassDetailsMatcher : IDeeplinkMatcher {
    val passDetailsRegex = """https://github.com/rt-bishop/Look4Sat/passes/(.*)""".toRegex()

    override fun match(deeplink: String): NavKey? {
        val passMatch = passDetailsRegex.find(deeplink)
        passMatch?.let { match ->
            val passId = match.groupValues[1]
            if (passId.isNotEmpty()) return RadarDestination
        }
        return null
    }
}

class DeeplinkResolver(private val fallbackDestination: NavKey = Screen.Passes) {

    private val matchers: List<IDeeplinkMatcher> = listOf(PassDetailsMatcher)

    fun resolve(deeplink: String): NavKey {
        matchers.forEach { it.match(deeplink)?.let { match -> return match } }
        return fallbackDestination
    }
}