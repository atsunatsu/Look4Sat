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
        // parse each source on its own: the type filters need every source's catnums, while the
        // stored elements are merged across sources below by freshness.
        val parsedPerSource = tleResults.map { (rawUrl, result) ->
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
        importedTypeIds.forEach { (type, ids) -> settingsRepo.setSatelliteTypeIds(type, ids.distinct()) }
        val importedRadios = radioResults.flatMap { (rawUrl, result) ->
            val normUrl = normalizeUrl(rawUrl)
            result.stream?.let { dataParser.parseJSONStream(unwrapIfZipped(normUrl, it)) }.orEmpty()
        }.filter { it.uuid.isNotBlank() }.distinctBy { it.uuid }
        // insert parsed data into the database.
        // Transceivers are a full snapshot: sources publish active entries only, so a retired
        // transceiver simply disappears from the feed and has to be dropped locally as well.
        // Manually imported ones (isCustom) are kept: no source can refresh them, so nothing
        // would bring them back if they were replaced here.
        if (importedRadios.isNotEmpty()) {
            localSource.deleteManagedRadios()
            localSource.insertRadios(importedRadios)
        }
        // Elements always come from the freshest source for every satellite; only
        // elements newer than the stored ones are written back.
        insertFresherEntries(mergeEntries(parsedPerSource))
        // keep the previous timestamp when every source failed, so the next launch retries
        val hasFetchedData = parsedPerSource.any { entries -> entries.isNotEmpty() } || importedRadios.isNotEmpty()
        val previousTimestamp = settingsRepo.databaseState.value.updateTimestamp
        if (hasFetchedData) pruneStaleEntries()
        setUpdateSuccessful(if (hasFetchedData) System.currentTimeMillis() else previousTimestamp)
    }

    override suspend fun clearAllData() = withContext(dispatcher) {
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
