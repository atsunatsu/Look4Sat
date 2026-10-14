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

/** Latest release info fetched from the fork's GitHub releases page. */
data class LatestRelease(
    val versionTag: String, // e.g. "v4.4.8-ba7opf.13"
    val title: String,      // release name, may be empty
    val body: String,       // release notes / update description
    /** Download URL per published edition; a missing key means that edition is not published. */
    val assetUrls: Map<ReleaseEdition, String> = emptyMap()
) {
    fun urlOf(edition: ReleaseEdition): String? = assetUrls[edition]

    /** True when at least one edition of this release is installable. */
    val hasAssets: Boolean get() = assetUrls.isNotEmpty()
}
