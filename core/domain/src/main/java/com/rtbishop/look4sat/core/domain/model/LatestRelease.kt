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

/** Latest release info fetched from the fork's GitHub releases API. */
data class LatestRelease(
    val versionTag: String, // e.g. "v4.4.6-ba7opf.6"
    val title: String,      // release name, may be empty
    val body: String,       // release notes / update description
    val apkUrl: String?     // first .apk asset download URL, null if absent
)

/** Which update endpoint to use. AUTO retries other endpoints when one fails. */
enum class UpdateSource {
    AUTO, GITHUB, GHFAST, LLKK, MOEYY;

    companion object {
        fun fromStored(value: String?): UpdateSource =
            entries.firstOrNull { it.name == value } ?: AUTO
    }
}
