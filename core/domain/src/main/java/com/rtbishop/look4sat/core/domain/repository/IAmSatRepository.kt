package com.rtbishop.look4sat.core.domain.repository

import com.rtbishop.look4sat.core.domain.model.AmSatReportSubmission
import com.rtbishop.look4sat.core.domain.model.AmSatReportSubmitResult
import com.rtbishop.look4sat.core.domain.model.SatStatusPage

/** AMSAT satellite status data source */
interface IAmSatRepository {
    /** Cached AMSAT status page for the current foreground session, if any. */
    fun getCachedStatus(): SatStatusPage?

    /** Fetch and parse the AMSAT status page; null on failure. */
    suspend fun fetchStatus(forceRefresh: Boolean = false): SatStatusPage?

    /** Clear the foreground-session status cache. */
    fun clearStatusCache()

    /** Submit a public AMSAT satellite status report. */
    suspend fun submitReport(submission: AmSatReportSubmission): AmSatReportSubmitResult
}
