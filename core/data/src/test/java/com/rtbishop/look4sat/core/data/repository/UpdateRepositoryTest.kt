/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.domain.model.ReleaseEdition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateRepositoryTest {
    private val tag = "v4.4.8-ba7opf.13"
    private val downloadBase = "https://github.com/atsunatsu/Look4Sat/releases/download/$tag/"

    @Test
    fun parsesEveryPublishedEditionFromOneReleasePage() {
        val html = """
            <meta content='https://github.com/atsunatsu/Look4Sat/releases/tag/$tag' property='og:url'>
            <title>Release $tag · atsunatsu/Look4Sat</title>
            <div class="markdown-body"><p>Dual edition</p></div>
            <a href="/atsunatsu/Look4Sat/releases/download/$tag/Look4Sat-${tag.removePrefix("v")}.apk">basic</a>
            <a href="/atsunatsu/Look4Sat/releases/download/$tag/Look4Sat-${tag.removePrefix("v")}-jsuenhanced.apk">jsuenhanced</a>
            <a href="/atsunatsu/Look4Sat/releases/download/$tag/Look4Sat-${tag.removePrefix("v")}-release.aab">aab</a>
        """.trimIndent()
        val release = parseReleasePage(html, UPDATE_MIRRORS.first())!!
        assertEquals(tag, release.versionTag)
        assertEquals("Dual edition", release.body)
        assertEquals(
            "https://ghfast.top/${downloadBase}Look4Sat-${tag.removePrefix("v")}.apk",
            release.urlOf(ReleaseEdition.BASIC)
        )
        assertEquals(
            "https://ghfast.top/${downloadBase}Look4Sat-${tag.removePrefix("v")}-jsuenhanced.apk",
            release.urlOf(ReleaseEdition.JSUENHANCED)
        )
    }

    @Test
    fun publishesOnlyTheEditionsThatWereUploaded() {
        val html = """
            <meta content='https://github.com/atsunatsu/Look4Sat/releases/tag/$tag' property='og:url'>
            <a href="/atsunatsu/Look4Sat/releases/download/$tag/Look4Sat-${tag.removePrefix("v")}.apk">basic</a>
        """.trimIndent()
        val release = parseReleasePage(html, "")!!
        assertEquals(setOf(ReleaseEdition.BASIC), release.assetUrls.keys)
        assertNull(release.urlOf(ReleaseEdition.JSUENHANCED))
    }

    @Test
    fun doesNotInventApkNameWhenNoAssetWasPublished() {
        val html = """<meta property="og:url" content="https://github.com/atsunatsu/Look4Sat/releases/tag/$tag">"""
        assertEquals(false, parseReleasePage(html, "")?.hasAssets)
    }

    @Test
    fun ignoresAssetsFromOtherRepositories() {
        val html = """
            <meta content='https://github.com/atsunatsu/Look4Sat/releases/tag/$tag' property='og:url'>
            <a href="https://github.com/attacker/repo/releases/download/$tag/Look4Sat-${tag.removePrefix("v")}.apk">fake</a>
        """.trimIndent()
        assertEquals(false, parseReleasePage(html, "")?.hasAssets)
    }

    @Test
    fun rejectsUntrustedMirrorAndNestedExternalDownload() {
        val own = "${downloadBase}Look4Sat-${tag.removePrefix("v")}.apk"
        assertEquals(false, isTrustedDownloadUrl("https://evil.example/" + own))
        assertEquals(false, isTrustedDownloadUrl("https://ghfast.top/https://github.com/attacker/repo/releases/download/$tag/app.apk"))
        assertEquals(true, isTrustedDownloadUrl("https://ghfast.top/" + own))
        assertEquals(false, isTrustedDownloadUrl(own + "?redirect=https://evil.example"))
    }

    @Test
    fun assetNamesAreDerivedFromTheTagAndEdition() {
        assertEquals("Look4Sat-4.4.8-ba7opf.13.apk", ReleaseEdition.assetName(ReleaseEdition.BASIC, tag))
        assertEquals("Look4Sat-4.4.8-ba7opf.13-jsuenhanced.apk", ReleaseEdition.assetName(ReleaseEdition.JSUENHANCED, tag))
    }
}
