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
package com.rtbishop.look4sat.core.domain.repository

import com.rtbishop.look4sat.core.domain.model.LatestRelease
import com.rtbishop.look4sat.core.domain.model.ReleaseEdition
import java.io.File

interface IUpdateRepository {
    /** Fetch the latest release of the fork repo from GitHub; null on failure. */
    suspend fun getLatestRelease(): LatestRelease?

    /**
     * Download the [edition]'s APK from [url] into [dest]; true on success.
     * The archive is validated against the installed app (package, signature, sizes and,
     * for the enhanced edition, the FT4 native core) before it is reported as usable, and
     * the same versionCode is accepted so the user can switch editions without a downgrade.
     */
    suspend fun downloadApk(url: String, edition: ReleaseEdition, dest: File): Boolean
}
