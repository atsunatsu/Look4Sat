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
import com.rtbishop.look4sat.core.domain.model.UpdateSource
import java.io.File

interface IUpdateRepository {
    /** Fetch the latest release of the fork repo from GitHub; null on failure. */
    suspend fun getLatestRelease(): LatestRelease?

    /** Download the release APK from [url] into [dest]; true on success. */
    suspend fun downloadApk(url: String, dest: File): Boolean

    /** Preferred endpoint, with transparent fallback only in AUTO mode. */
    suspend fun getLatestRelease(
        source: UpdateSource,
        onSourceChanged: (UpdateSource) -> Unit
    ): LatestRelease? = getLatestRelease()

    /** Validate each downloaded candidate before allowing the installer to see it. */
    suspend fun downloadApk(
        release: LatestRelease,
        dest: File,
        source: UpdateSource,
        preferred: UpdateSource?,
        onSourceChanged: (UpdateSource) -> Unit,
        validate: (File) -> Boolean
    ): Boolean = release.apkUrl?.let { downloadApk(it, dest) } ?: false
}
