package com.rtbishop.look4sat.core.domain.repository

import com.rtbishop.look4sat.core.domain.model.LatestRelease
import java.io.File

interface IUpdateRepository {
    /** Fetch the latest release of the fork repo from GitHub; null on failure. */
    suspend fun getLatestRelease(): LatestRelease?

    /** Download the release APK from [url] into [dest]; true on success. */
    suspend fun downloadApk(url: String, dest: File): Boolean
}
