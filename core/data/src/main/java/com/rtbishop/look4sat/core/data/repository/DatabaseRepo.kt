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
package com.rtbishop.look4sat.core.data.repository

import com.rtbishop.look4sat.core.domain.model.DatabaseState
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.repository.IDatabaseRepo
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.source.ILocalSource
import com.rtbishop.look4sat.core.domain.source.IRemoteSource
import com.rtbishop.look4sat.core.domain.source.NetworkResult
import com.rtbishop.look4sat.core.domain.source.Sources
import com.rtbishop.look4sat.core.domain.utility.DataParser
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.util.zip.ZipInputStream

class DatabaseRepo(
    private val dispatcher: CoroutineDispatcher,
    private val dataParser: DataParser,
    private val localSource: ILocalSource,
    private val remoteSource: IRemoteSource,
    private val settingsRepo: ISettingsRepo
) : IDatabaseRepo {

    private val customSourceType = "Other"

    override suspend fun updateTLEFromFile(uri: String): Int = withContext(dispatcher) {
        var importedCount = 0
        remoteSource.getFileStream(uri)?.let { stream ->
            val entries = mergeEntries(listOf(parseSatelliteStream(uri, unwrapIfZipped(uri, stream))))
            insertFresherEntries(entries)
            settingsRepo.setSatelliteTypeIds(customSourceType, entries.map { it.catnum })
            importedCount = entries.size
        }
        setUpdateSuccessful(System.currentTimeMillis())
        importedCount
    }

    override suspend fun updateTransceiversFromFile(uri: String): Int = withContext(dispatcher) {
        var importedCount = 0
        remoteSource.getFileStream(uri)?.let { stream ->
            val transceivers = dataParser.parseJSONStream(unwrapIfZipped(uri, stream))
            localSource.insertRadios(transceivers, isCustom = true)
            importedCount = transceivers.size
        }
        setUpdateSuccessful(System.currentTimeMillis())
        importedCount
    }

    override suspend fun updateFromRemote() = withContext(dispatcher) {
        val settings = settingsRepo.dataSourcesSettings.value
        // Keep the raw URL as the status key (matches what the settings UI
        // displays) while requesting with the normalized URL.
        val tleUrls = settings.satelliteUrls
            .filterIndexed { i, url -> url.isNotBlank() && settings.isSatelliteEnabled(i) }
            .map { it to normalizeUrl(it) }
            .distinctBy { it.second }
        val radioUrls = settings.transceiversUrls
            .filterIndexed { i, url -> url.isNotBlank() && settings.isTransceiverEnabled(i) }
            .map { it to normalizeUrl(it) }
            .distinctBy { it.second }
        val builtinTypesByUrl = Sources.satelliteDataUrls
            .filterValues { it.isNotBlank() }
            .mapValues { normalizeUrl(it.value) }
            .entries
            .associate { (type, url) -> url to type }
        val importedTypeIds = mutableMapOf<String, MutableList<Int>>()
        // launch all network requests concurrently, keeping the raw url as key for status reporting
        val tleJobs = tleUrls.map { (raw, norm) -> async { raw to remoteSource.getNetworkStream(norm) } }
        val radioJobs = radioUrls.map { (raw, norm) -> async { raw to remoteSource.getNetworkStream(norm) } }
        val tleResults = tleJobs.awaitAll()
        val radioResults = radioJobs.awaitAll()
        // report the HTTP status code of every source (200, 404, ...)
        settingsRepo.updateDataSourcesStatus(
            (tleResults + radioResults).associate { (url, result) -> url to result.code }
        )
        // SupGP is not a normal single-epoch feed: preserve all six-hour fits
        // separately, so mergeEntries() cannot collapse them to one future TLE.
        val supGpResult = tleResults.firstOrNull { (url, _) -> Sources.isIssSupGpUrl(url) }
        val acceptedSupGp = supGpResult?.let { (url, result) ->
            processIssSupGp(url, result)
        }.orEmpty()
        if (acceptedSupGp.isNotEmpty()) {
            importedTypeIds.getOrPut("ISS SupGP") { mutableListOf() }.add(Sources.ISS_CATNUM)
        }
        val nowDaynum = OrbitalData.timeToDaynum(System.currentTimeMillis())
        // Parse each ordinary source on its own for its type filter and GP merge.
        val parsedPerSource = tleResults.filterNot { (url, _) ->
            Sources.isIssSupGpUrl(url)
        }.map { (rawUrl, result) ->
            val normUrl = normalizeUrl(rawUrl)
            val entries = result.stream?.let { parseSatelliteStream(normUrl, unwrapIfZipped(normUrl, it)) }.orEmpty()
            // Keep the last good classification on HTTP errors and empty/invalid feeds.
            // Replacing it with an empty list hides otherwise stored satellites.
            if (entries.isNotEmpty()) {
                val type = builtinTypesByUrl[normUrl] ?: customSourceType
                importedTypeIds.getOrPut(type) { mutableListOf() }.addAll(entries.map { it.catnum })
            }
            entries
        }
        // Keep GP independent when it is available. On SupGP-only installs,
        // refresh the minimal database ISS record too, so it never ages out
        // while the supplemental cache is still being updated.
        val gpWasDownloaded = parsedPerSource.any { entries ->
            entries.any { it.catnum == Sources.ISS_CATNUM }
        }
        val hasOrdinarySource = tleUrls.any { (url, _) -> !Sources.isIssSupGpUrl(url) }
        val storedIssEpoch = localSource.getEntriesEpochs()[Sources.ISS_CATNUM]
            ?.let { OrbitalData.epochToDaynum(it) }
        val hasRecentStoredFallback = storedIssEpoch != null &&
            nowDaynum - storedIssEpoch <= 7.0
        val useSupGpFallback = !gpWasDownloaded &&
            (!hasOrdinarySource || !hasRecentStoredFallback)
        val supGpFallback = if (useSupGpFallback)
            acceptedSupGp.lastOrNull { it.epochDaynum <= nowDaynum }?.copy(name = "ISS (ZARYA)")
        else null
        importedTypeIds.forEach { (type, ids) -> settingsRepo.setSatelliteTypeIds(type, ids.distinct()) }
        // A partial or invalid transceiver download is NOT a complete snapshot.
        // Upsert healthy feeds without deleting radios from the failed sources.
        // A full refresh may prune retired entries only when every configured
        // source returned valid, non-empty data. Custom records are never pruned.
        val radioSnapshots = radioResults.map { (rawUrl, result) ->
            val normUrl = normalizeUrl(rawUrl)
            val rows = if (result.code in 200..299) {
                result.stream?.let { dataParser.parseJSONStream(unwrapIfZipped(normUrl, it)) }.orEmpty()
                    .filter { it.uuid.isNotBlank() }
            } else emptyList()
            (result.code in 200..299 && rows.isNotEmpty()) to rows
        }
        val importedRadios = radioSnapshots.flatMap { it.second }.distinctBy { it.uuid }
        val completeRadioSnapshot = radioSnapshots.isNotEmpty() && radioSnapshots.all { it.first }
        if (importedRadios.isNotEmpty()) {
            if (completeRadioSnapshot) localSource.deleteManagedRadios()
            localSource.insertRadios(importedRadios)
        }
        // Elements always come from the freshest source for every satellite; only
        // elements newer than the stored ones are written back.
        insertFresherEntries(mergeEntries(parsedPerSource +
            listOfNotNull(supGpFallback?.let { listOf(it) })))
        // keep the previous timestamp when every source failed, so the next launch retries
        val hasFetchedData = parsedPerSource.any { entries -> entries.isNotEmpty() } ||
            importedRadios.isNotEmpty() || acceptedSupGp.isNotEmpty()
        val previousTimestamp = settingsRepo.databaseState.value.updateTimestamp
        if (hasFetchedData) pruneStaleEntries()
        setUpdateSuccessful(if (hasFetchedData) System.currentTimeMillis() else previousTimestamp)
    }

    /**
     * Refresh only ISS SupGP (called at app launch when the dedicated 24-hour
     * check is due). Never advance DatabaseState.updateTimestamp here: the
     * existing TLE/OMM/transceiver refresh must remain on its 48-hour cycle.
     */
    override suspend fun updateIssSupGp() = withContext(dispatcher) {
        val sources = settingsRepo.dataSourcesSettings.value
        val rawUrl = sources.satelliteUrls.indices.firstOrNull { index ->
            sources.isSatelliteEnabled(index) &&
                Sources.isIssSupGpUrl(sources.satelliteUrls[index])
        }?.let { sources.satelliteUrls[it] } ?: return@withContext

        val response = remoteSource.getNetworkStream(normalizeUrl(rawUrl))
        settingsRepo.updateDataSourcesStatus(
            settingsRepo.dataSourcesStatus.value + (rawUrl to response.code)
        )
        val acceptedSupGp = processIssSupGp(rawUrl, response)
        if (acceptedSupGp.isEmpty()) return@withContext

        settingsRepo.setSatelliteTypeIds("ISS SupGP", listOf(Sources.ISS_CATNUM))
        // When an install has only SupGP enabled, keep an ISS entry in the
        // ordinary satellite table so selection and GP fallback still work.
        val hasOtherSource = sources.satelliteUrls.indices.any { index ->
            sources.isSatelliteEnabled(index) &&
                !Sources.isIssSupGpUrl(sources.satelliteUrls[index])
        }
        val hasIss = localSource.getEntriesEpochs().containsKey(Sources.ISS_CATNUM)
        if (!hasOtherSource || !hasIss) {
            val now = OrbitalData.timeToDaynum(System.currentTimeMillis())
            val current = acceptedSupGp.lastOrNull { it.epochDaynum <= now }
            if (current != null) insertFresherEntries(listOf(current.copy(name = "ISS (ZARYA)")))
        }
        // setIssSupGpSegments() emits issSupGpCacheRevision so selected ISS
        // objects, pass predictions and tracking refresh without touching GP time.
    }

    /**
     * Validates a complete ISS prediction before committing to the offline
     * cache. Failures preserve the last known good segments.
     */
    private suspend fun processIssSupGp(
        rawUrl: String, result: NetworkResult
    ): List<OrbitalData> {
        // Limit automated retries even after a bad HTTP response. The manual
        // Update button is unaffected and can force a check at any time.
        settingsRepo.setIssSupGpLastCheckMillis(System.currentTimeMillis())
        if (result.code !in 200..299) {
            result.stream?.close()
            return emptyList()
        }
        val parsed = try {
            result.stream?.use { dataParser.parseTLEStream(it) }
                .orEmpty()
                .filter { it.catnum == Sources.ISS_CATNUM }
                .distinctBy { it.epochDaynum }
                .sortedBy { it.epochDaynum }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }

        val now = OrbitalData.timeToDaynum(System.currentTimeMillis())
        val valid = parsed.size in 4..100 &&
            parsed.first().epochDaynum <= now &&
            parsed.last().epochDaynum >= now &&
            parsed.all { it.meanmo > 0.0 && it.epochDaynum.isFinite() } &&
            parsed.zipWithNext().all { (first, second) ->
                val gap = second.epochDaynum - first.epochDaynum
                gap > 0.0 && gap <= 0.30
            }
        if (!valid) {
            settingsRepo.updateDataSourcesStatus(
                settingsRepo.dataSourcesStatus.value + (rawUrl to 422)
            )
            return emptyList()
        }
        val previousLast = settingsRepo.getIssSupGpSegments().lastOrNull()?.epochDaynum
        if (previousLast != null && parsed.last().epochDaynum < previousLast) {
            return emptyList() // Do not roll back a newer offline prediction.
        }
        settingsRepo.setIssSupGpSegments(parsed)
        return parsed
    }

    override suspend fun clearAllData() = withContext(dispatcher) {
        settingsRepo.setIssSupGpSegments(emptyList())
        settingsRepo.setIssSupGpLastCheckMillis(0L)
        localSource.deleteEntries()
        localSource.deleteRadios()
        setUpdateSuccessful(0L)
    }

    private fun normalizeUrl(url: String): String =
        if (url.startsWith("http", ignoreCase = true)) url else "https://$url"

    private suspend fun parseSatelliteStream(url: String, stream: InputStream): List<OrbitalData> {
        val bufferedStream = stream.buffered()
        return when {
            hasCsvHint(url) || looksLikeCsv(bufferedStream) -> dataParser.parseCSVStream(bufferedStream)
            else -> dataParser.parseTLEStream(bufferedStream)
        }
    }

    private fun hasCsvHint(url: String): Boolean {
        return url.contains("FORMAT=csv", ignoreCase = true) ||
            url.endsWith(".csv", ignoreCase = true) ||
            url.endsWith(".csv.zip", ignoreCase = true)
    }

    private fun looksLikeCsv(stream: InputStream): Boolean {
        if (!stream.markSupported()) return false
        stream.mark(4096)
        val preview = ByteArray(4096)
        val length = stream.read(preview)
        stream.reset()
        if (length <= 0) return false
        val line = preview.decodeToString(0, length).lineSequence().firstOrNull()?.trim().orEmpty()
        return line.contains("OBJECT_NAME", ignoreCase = true) ||
            line.contains("NORAD_CAT_ID", ignoreCase = true) ||
            line.count { it == ',' } >= 4
    }

    /**
     * Merges the data of every source: orbital elements always come from the set with the newest
     * epoch, while the name comes from the first source that provides it. Source order is thus a
     * naming preference only, which also keeps names stable when sources leapfrog each other.
     */
    private fun mergeEntries(sourceEntries: List<List<OrbitalData>>): List<OrbitalData> {
        val preferredNames = mutableMapOf<Int, String>()
        val freshestEntries = mutableMapOf<Int, OrbitalData>()
        sourceEntries.forEach { entries ->
            entries.forEach { entry ->
                val name = entry.name.trim()
                if (name.isNotBlank()) preferredNames.getOrPut(entry.catnum) { name }
                val current = freshestEntries[entry.catnum]
                if (current == null || entry.epochDaynum > current.epochDaynum) {
                    freshestEntries[entry.catnum] = entry
                }
            }
        }
        return freshestEntries.values.map { entry ->
            val name = preferredNames[entry.catnum]
            if (name == null || name == entry.name) entry else entry.copy(name = name)
        }
    }

    /** Stores the entries that are newer than the ones already saved, renaming the rest in place. */
    private suspend fun insertFresherEntries(entries: List<OrbitalData>) {
        val storedEpochs = localSource.getEntriesEpochs()
        val (fresherEntries, staleEntries) = entries.partition { entry ->
            val storedEpoch = storedEpochs[entry.catnum]
            storedEpoch == null || entry.epochDaynum > OrbitalData.epochToDaynum(storedEpoch)
        }
        localSource.insertEntries(fresherEntries)
        // a reordered source list has to rename satellites right away, even the ones holding
        // elements that are newer than the ones just parsed
        if (staleEntries.isNotEmpty()) {
            val storedNames = localSource.getEntriesNames()
            val renamedEntries = staleEntries.filter { entry -> entry.name != storedNames[entry.catnum] }
            if (renamedEntries.isNotEmpty()) {
                localSource.renameEntries(renamedEntries.associate { it.catnum to it.name })
            }
        }
    }

    /**
     * Drops satellites that no enabled source has refreshed for a month: they either decayed or
     * disappeared from every catalog. Manually imported data ages out the same way, and every
     * source republishes active satellites well within that window.
     */
    private suspend fun pruneStaleEntries() {
        val currentDaynum = OrbitalData.timeToDaynum(System.currentTimeMillis())
        val staleIds = localSource.getEntriesEpochs()
            .filterValues { epoch -> currentDaynum - OrbitalData.epochToDaynum(epoch) > 30.0 }
            .keys.toList()
        if (staleIds.isNotEmpty()) localSource.deleteEntriesWithIds(staleIds)
    }

    private suspend fun setUpdateSuccessful(timestamp: Long) {
        settingsRepo.updateDatabaseState(
            DatabaseState(localSource.getRadiosTotal(), localSource.getEntriesTotal(), timestamp)
        )
    }

    private fun unwrapIfZipped(url: String, stream: InputStream): InputStream =
        if (url.endsWith(".zip", ignoreCase = true)) ZipInputStream(stream).apply { nextEntry } else stream
}
