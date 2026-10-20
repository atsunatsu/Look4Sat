package com.rtbishop.look4sat.core.data.repository

import android.content.SharedPreferences
import androidx.core.content.edit
import com.rtbishop.look4sat.core.domain.repository.CwTransmitPresets

internal fun readRemoteCwPresets(preferences: SharedPreferences): List<String> =
    CwTransmitPresets.defaults.mapIndexed { index,default ->
        runCatching {CwTransmitPresets.normalize(preferences.getString("remoteCwPreset$index",default) ?: default)}.getOrDefault(default)
    }

internal fun writeRemoteCwPreset(preferences: SharedPreferences,index: Int,text: String) {
    require(index in 0..7)
    val value=CwTransmitPresets.normalize(text)
    preferences.edit {putString("remoteCwPreset$index",value)}
}
