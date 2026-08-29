package com.rtbishop.look4sat.core.domain.model

/** Latest release info fetched from the fork's GitHub releases API. */
data class LatestRelease(
    val versionTag: String, // e.g. "v4.4.6-ba7opf.6"
    val title: String,      // release name, may be empty
    val body: String,       // release notes / update description
    val apkUrl: String?     // first .apk asset download URL, null if absent
)
