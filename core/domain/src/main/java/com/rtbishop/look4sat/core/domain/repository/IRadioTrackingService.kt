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
package com.rtbishop.look4sat.core.domain.repository

import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.utility.CalculatorTuning
import com.rtbishop.look4sat.core.domain.predict.OrbitalObject
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import kotlinx.coroutines.flow.StateFlow

enum class PttState {
    OFF,
    ARMING,
    ON,
    ERROR
}

enum class TrackingPhase {
    IDLE,
    INITIALIZING,
    READY,
    ERROR
}

data class RadioTrackingState(
    val isActive: Boolean = false,
    val trackingPhase: TrackingPhase = TrackingPhase.IDLE,
    val txConnected: Boolean = false,
    val rxConnected: Boolean = false,
    val txFrequencyHz: Long? = null,
    val rxFrequencyHz: Long? = null,
    val txMode: String? = null,
    val rxMode: String? = null,
    val ctcssTone: Double? = null,
    val txBaseFrequencyHz: Long? = null,
    val selectedTransponder: SatRadio? = null,
    val currentPass: OrbitalPass? = null,
    val azimuth: Double = 0.0,
    val elevation: Double = 0.0,
    val distance: Double = 0.0,
    val errorMessage: String? = null,
    val pttState: PttState = PttState.OFF,
    val commandBusy: Boolean = false,
    val lastCommandError: String? = null,
    val splitMode: Boolean = false,
    val satelliteMode: Boolean = false,
    val nominalTxFrequencyHz: Long? = null,
    val nominalRxFrequencyHz: Long? = null,
    val txDopplerCorrectionHz: Long? = null,
    val rxDopplerCorrectionHz: Long? = null,
    val physicalConnectionCount: Int = 0,
    val remoteRadio: RemoteRadioState? = null,
    val transmitOwner: RadioTransmitOwner = RadioTransmitOwner.NONE,
    val targetTxFrequencyHz: Long? = null,
    val targetRxFrequencyHz: Long? = null,
    val cwMessagePosition: Int = 0,
    val cwMessageLength: Int = 0,
    val calculatorLinked:Boolean=false,
    val referenceDirty:Boolean=false,
    val catTransmitStateKnown: Boolean = true,
    val catTransmitting: Boolean = false
) {
    val frequencyEditingLocked get()=trackingPhase==TrackingPhase.INITIALIZING ||
        !catTransmitStateKnown || catTransmitting || pttState!=PttState.OFF || transmitOwner!=RadioTransmitOwner.NONE ||
        remoteRadio?.txSource?.let {it!=0}==true || remoteRadio?.nativeMenuOpen==true || remoteRadio?.profileStore?.busy==true ||
        remoteRadio?.handsetMacroStatus?.busy==true || remoteRadio?.let {!it.connected || !it.transmitStateKnown}==true
}

interface IRadioTrackingService {
    val state: StateFlow<RadioTrackingState>
    val profileManagerState:StateFlow<RadioProfileManagerState> get()=EmptyRadioProfileManagerState

    suspend fun connectRadios()
    suspend fun disconnectRadios()
    fun startTracking(pass: OrbitalPass, transponder: SatRadio, txBaseFreqHz: Long?)
    fun stopTracking()
    fun setTransponder(transponder: SatRadio)
    fun setTxBaseFrequency(frequencyHz: Long)
    fun adjustTxBaseFrequency(deltaHz: Long)
    fun setRxBaseFrequency(frequencyHz: Long) = Unit
    fun setBaseFrequencies(bases:RadioBaseFrequencies)=Unit
    fun tuneFromCalculator(transponder: SatRadio, tuning: CalculatorTuning) = Unit
    fun setCtcssTone(toneHz: Double?)
    fun setMode(txMode: String, rxMode: String)
    fun setVoicePtt(enabled: Boolean) = Unit
    fun sendCw(text: String, wpm: Int) = Unit
    fun setNativeCwOptions(options: NativeCwOptions) = Unit
    fun syncHandsetCwPresets() = Unit
    fun cancelHandsetCwSync() = Unit
    fun refreshNativeDiagnostics() = Unit
    fun cancelRemoteTransmit() = Unit
    suspend fun refreshRadioProfiles():Boolean=false
    suspend fun requestDeleteRadioProfile(slot:Int):Boolean=false
    suspend fun prepareRadioProfileImport(json:String):Boolean=false
    suspend fun stageRadioProfileImport(overwrite:Set<String>):Boolean=false
    suspend fun exportRadioProfiles():String?=null
    suspend fun cancelRadioProfileOperation()=Unit
    fun setRadioProfileAlias(key:String,alias:String)=Unit
    suspend fun applyRadioControlSettings(settings:com.rtbishop.look4sat.core.domain.model.RadioControlSettings):Boolean=false
    suspend fun setAudioForeground(enabled: Boolean) = Unit
    fun setRadioMonitor(enabled: Boolean) = Unit
    fun recordingAction(action: com.rtbishop.look4sat.core.domain.model.RadioRecordingAction) = Unit
    fun saveRecording(destinationUri: String) = Unit
}

private val EmptyRadioProfileManagerState:StateFlow<RadioProfileManagerState> = kotlinx.coroutines.flow.MutableStateFlow(RadioProfileManagerState())
