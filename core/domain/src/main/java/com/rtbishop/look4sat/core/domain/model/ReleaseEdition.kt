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
package com.rtbishop.look4sat.core.domain.model

/**
 * The two APKs published for one release tag. Both editions share the applicationId,
 * the signing key and the versionCode, which is exactly what lets the in-app switcher
 * install the other edition as a plain update over the same app identity (and lets the
 * user switch back later).
 */
enum class ReleaseEdition(val label: String, val assetSuffix: String) {
    BASIC("basic", ""),
    JSUENHANCED("jsuenhanced", "-jsuenhanced");

    companion object {
        /**
         * Release asset name for an edition, e.g.
         * `Look4Sat-4.4.8-ba7opf.13.apk` / `Look4Sat-4.4.8-ba7opf.13-jsuenhanced.apk`.
         */
        fun assetName(edition: ReleaseEdition, versionTag: String): String =
            "Look4Sat-${versionTag.removePrefix("v")}${edition.assetSuffix}.apk"
    }
}
