package com.rtbishop.look4sat.core.domain.repository

import com.rtbishop.look4sat.core.domain.model.WavelogUploadSettings

/**
 * Verify the selected station's current server metadata before preparing an upload.
 * A missing ID must not silently route contacts to a different station profile.
 */
suspend fun refreshWavelogStationForUpload(
    wavelogRepo: IWavelogUploadRepository,
    settingsRepo: ISettingsRepo,
    selected: WavelogUploadSettings
): WavelogUploadSettings? {
    val probe = wavelogRepo.probe(selected.url, selected.apiKey) ?: return null
    val profile = probe.stations.firstOrNull { it.id == selected.stationId } ?: return null
    val current = settingsRepo.wavelogUploadSettings.value
    if (current.url != selected.url || current.apiKey != selected.apiKey ||
        current.stationId != selected.stationId
    ) return null

    settingsRepo.setWavelogStations(probe.stations)
    val refreshed = current.withStationProfile(profile)
    if (refreshed != current) settingsRepo.updateWavelogUploadSettings(refreshed)
    return refreshed
}
