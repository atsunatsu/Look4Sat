package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticsBuffer
import com.rtbishop.look4sat.core.domain.repository.FrequencyPairResult
import com.rtbishop.look4sat.core.domain.repository.RadioTrackingDisplay
import com.rtbishop.look4sat.core.domain.model.N76ControlOptions
import com.rtbishop.look4sat.core.domain.model.N76FirmwareMode
import com.rtbishop.look4sat.core.domain.model.N76Power
import com.rtbishop.look4sat.core.domain.model.RadioRecordingAction
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class N76ControllerTest {
    @Test fun framesMatchGaiaAndDecoderHandlesSplitCoalescedNoiseEchoAndChecksums() {
        assertArrayEquals(byteArrayOf(-1, 1, 0, 1, 0, 2, 0, 4, 3), N76Protocol.frame(4, byteArrayOf(3)))
        val wire = N76Protocol.frame(35, byteArrayOf(0), reply = true)
        val decoder = N76Protocol.Decoder()
        assertTrue(decoder.feed(wire.copyOfRange(0, 5)).isEmpty())
        val packets = decoder.feed(wire.copyOfRange(5, wire.size) + N76Protocol.frame(20))
        assertEquals(listOf(35, 20), packets.map { it.command })
        assertTrue(packets[0].reply)
        assertFalse(packets[1].reply)
        val checked = (wire.copyOf().also { it[2] = 1 } + byteArrayOf(0))
        checked[checked.lastIndex] = checked.dropLast(1).fold(0) { result, b -> result xor (b.toInt() and 255) }.toByte()
        assertEquals(1, decoder.feed(checked).size)
        checked[checked.lastIndex] = (checked.last().toInt() xor 1).toByte()
        assertEquals(1, decoder.feed(checked + byteArrayOf(0, 8, 9) + wire).size)
        assertTrue(decoder.feed(ByteArray(10_000)).isEmpty())
        assertEquals(35, decoder.feed(wire).single().command)
    }

    @Test fun nativeSatelliteParametersUseExactHertzToneAndManufacturerFlags() {
        val pair = N76Protocol.Frequencies(437_797_591, 145_990_803)
        val first = N76Protocol.frequencyParameters(pair, 67.0, 0)
        assertEquals("1a1842d708b3a49300001a2c0a0361a8", first.hex())
        assertEquals("0a03", N76Protocol.frequencyParameters(pair, 67.0, 1).copyOfRange(12, 14).hex())
        assertEquals("0a00", N76Protocol.frequencyParameters(pair, 67.0, 2).copyOfRange(12, 14).hex())
        val receiveOnly = N76Protocol.frequencyParameters(pair.copy(txHz = null), 67.0, 2)
        assertEquals(0, ByteBuffer.wrap(receiveOnly).getInt(4))
        assertEquals(0, ByteBuffer.wrap(receiveOnly).getShort(10).toInt())
    }

    @Test fun nativeDisplayMatchesAzimuthElevationRangeAltitudeAndCountdownScales() {
        val bytes = N76Protocol.satelliteInfo(RadioTrackingDisplay("ISS (ZARYA)", 114.0, -76.0,
            distanceKm = 12795.0, altitudeKm = 432.0, secondsUntilAos = 3090))
        assertEquals("ISS (ZARYA)", bytes.take(11).toByteArray().toString(Charsets.US_ASCII))
        assertEquals("3900b40031fb01b00c12", bytes.copyOfRange(20, 30).hex())
        assertEquals(30, N76Protocol.satelliteInfo(RadioTrackingDisplay("X".repeat(60), 360.0, 90.0)).size)
    }

    @Test fun connectsReadOnlyRegistersWithoutWaitingForAckAndStartsNoTransmitter() = withRadio { radio, wire, _ ->
        assertTrue(radio.isConnected)
        assertFalse(wire.secureFirst)
        assertEquals(listOf(4, 20, 6), wire.commands().take(3))
        assertArrayEquals(byteArrayOf(3), wire.sent.first().payload)
        assertFalse(wire.commands().contains(35))
        assertFalse(radio.pttOn())
        assertFalse(radio.remoteState.value.capabilities!!.cwText)
        assertTrue(radio.remoteState.value.capabilities!!.voicePtt)
    }

    @Test fun readOnlyHandshakeRetriesAndCanUseAlternativeBluetoothSocket() = runBlocking {
        val wire = SimulatedN76().apply { suppressFirstInfo = true }
        withRadio(wire) { radio, _, _ ->
            assertTrue(radio.isConnected)
            assertEquals(2, wire.commands().count { it == 4 })
        }
        val alternative = SimulatedN76().apply { silentUntilAlternative = true }
        withRadio(alternative) { radio, _, _ ->
            assertTrue(radio.isConnected)
            assertEquals(1, alternative.alternatives)
        }
    }

    @Test fun variant259WithoutVfoFlagVerifiesInactiveFrequencyModeAndTracks() = withRadio(
        SimulatedN76().apply { product = 259; vfo = false; frequencyStatus = ByteArray(4) }
    ) { radio, wire, diagnostics ->
        assertEquals(listOf(4, 36, 20, 6), wire.commands().take(4))
        assertTrue(radio.isConnected)
        assertNull(radio.remoteState.value.error)
        assertFalse(wire.commands().any { it in listOf(35, 66, 77) })
        assertTrue(diagnostics.events.value.any { it.stage == "n76/connect/device-info" &&
            "product=259" in it.message && "supportsVfo=false" in it.message })
        ready(radio)
        assertEquals(FrequencyPairResult.APPLIED, radio.setTrackingFrequencies(435_000_000, 145_950_000))
        assertTrue(radio.pttOn())
        assertEquals(FrequencyPairResult.HELD, radio.setTrackingFrequencies(435_001_000, 145_951_000))
        assertTrue(radio.pttOff())
    }

    @Test fun rejectsMalformedInfoAndUnverifiedFrequencyModeWithoutAnyTune() = runBlocking {
        for (wire in listOf(
            SimulatedN76().apply { deviceInfo = ByteArray(9) },
            SimulatedN76().apply { product = 259; vfo = false; reject = 36 },
            SimulatedN76().apply { product = 259; vfo = false; suppress = 36 },
            SimulatedN76().apply { product = 259; vfo = false; frequencyStatus = ByteArray(3) }
        )) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val radio = N76Controller(wire, scope, replyTimeoutMillis = 100)
            try {
                assertFalse(radio.connect())
                assertFalse(wire.commands().contains(35))
                assertFalse(radio.isConnected)
                assertNotNull(radio.remoteState.value.error)
            } finally { radio.disconnect(); scope.cancel() }
        }
    }

    @Test fun trackingSendsInfoThenAtomicPairAndExitsWithoutWritingChannelMemory() = withRadio { radio, wire, _ ->
        ready(radio)
        assertTrue(radio.configureTxCtcss(67.0))
        repeat(3) { index ->
            assertEquals(FrequencyPairResult.APPLIED, radio.setTrackingFrequencies(437_797_591L + index, 145_990_803L - index))
        }
        val tunes = wire.sent.filter { it.command == 35 }
        assertEquals(listOf("0a00", "0a00", "0a00"), tunes.map { it.payload.copyOfRange(12, 14).hex() })
        for (index in wire.sent.indices.filter { wire.sent[it].command == 35 }) assertEquals(77, wire.sent[index - 1].command)
        assertEquals(145_990_801L, radio.remoteState.value.txFrequencyHz)
        assertTrue(radio.setSplitMode(false))
        assertArrayEquals(ByteArray(16), wire.sent.last { it.command == 35 }.payload)
        assertFalse(wire.commands().any { it in listOf(11, 12, 14, 58) })
    }

    @Test fun singleSideEditsPreserveOtherRawFrequencyAndNeverAccumulateCorrection() = withRadio { radio, _, _ ->
        ready(radio)
        assertFalse(radio.setWorkingFrequency(435_000_000))
        assertEquals(FrequencyPairResult.APPLIED, radio.setTrackingFrequencies(435_000_010, 145_950_020))
        repeat(4) {
            assertTrue(radio.setTxVfoFrequency(145_951_030))
            assertEquals(435_000_010L, radio.remoteState.value.rxFrequencyHz)
        }
        repeat(4) {
            assertTrue(radio.setWorkingFrequency(435_002_040))
            assertEquals(145_951_030L, radio.remoteState.value.txFrequencyHz)
        }
    }

    @Test fun localPttHoldsBothFrequenciesAndRejectsToneAndModeChangesWithoutStoppingTx() = withRadio { radio, wire, _ ->
        ready(radio)
        radio.setTrackingFrequencies(435_000_000, 145_950_000)
        wire.transmitting = true
        val count = wire.commands().count { it == 35 }
        assertTrue(radio.readPttState() == true)
        assertEquals(FrequencyPairResult.HELD, radio.setTrackingFrequencies(435_004_000, 145_952_000))
        assertFalse(radio.configureTxCtcss(88.5))
        assertFalse(radio.setMode("FM"))
        assertFalse(radio.pttOff())
        assertEquals(count, wire.commands().count { it == 35 })
        wire.transmitting = false
        assertEquals(FrequencyPairResult.APPLIED, radio.setTrackingFrequencies(435_006_000, 145_954_000))
        assertEquals(435_006_000L, radio.remoteState.value.rxFrequencyHz)
    }

    @Test fun stopDuringPhysicalPttDefersOnlyTeardownUntilRelease() = withRadio { radio, wire, _ ->
        ready(radio)
        radio.setTrackingFrequencies(435_000_000, 145_950_000)
        wire.transmitting = true
        assertTrue(radio.setSplitMode(false))
        assertFalse(wire.sent.last { it.command == 35 }.payload.all { it == 0.toByte() })
        wire.transmitting = false
        delay(650)
        assertArrayEquals(ByteArray(16), wire.sent.last { it.command == 35 }.payload)
        assertNull(radio.remoteState.value.rxFrequencyHz)
    }

    @Test fun receiveOnlyNeverReusesOldUplinkAndContextChangeClearsTargets() = withRadio { radio, wire, _ ->
        ready(radio)
        radio.setTrackingFrequencies(435_000_000, 145_950_000)
        assertEquals(FrequencyPairResult.APPLIED, radio.setTrackingFrequencies(435_100_000, null))
        assertNull(radio.remoteState.value.txFrequencyHz)
        assertFalse(radio.remoteState.value.txEnabled)
        assertFalse(radio.setTxVfoFrequency(145_960_000))
        assertEquals(0, ByteBuffer.wrap(wire.sent.last { it.command == 35 }.payload).getInt(4))
        assertTrue(radio.prepareTrackingContext("another-satellite"))
        assertFalse(radio.setWorkingFrequency(435_200_000))
    }

    @Test fun requestedModesStayFmAndInvalidBandNeverSendsAnRfWrite() = withRadio { radio, wire, _ ->
        ready(radio)
        for (mode in listOf("USB", "LSB", "CW", "NFM", "BPSK")) {
            assertTrue(radio.setMode(mode))
            assertEquals("FM", radio.remoteState.value.rxMode)
            assertEquals("FM", radio.remoteState.value.txMode)
            assertNull(radio.remoteState.value.error)
        }
        assertEquals(FrequencyPairResult.FAILED, radio.setTrackingFrequencies(200_000_000, 145_950_000))
        assertEquals(FrequencyPairResult.FAILED, radio.setTrackingFrequencies(435_000_000, 800_000_000))
        assertFalse(wire.commands().contains(35))
        assertTrue(radio.isConnected)
    }

    @Test fun rejectionPreservesAppliedToneAndLogsDifferentlyFromTimeout() = withRadio { radio, wire, diagnostics ->
        ready(radio)
        radio.configureTxCtcss(67.0)
        radio.setTrackingFrequencies(435_000_000, 145_950_000)
        wire.reject = 35
        assertFalse(radio.configureTxCtcss(88.5))
        assertEquals(67.0, radio.remoteState.value.txCtcssHz!!, 0.0)
        assertTrue(diagnostics.events.value.any { it.stage == "n76/reply/rejected" })
        assertTrue(radio.isConnected)
        wire.reject = null; wire.suppress = 35
        assertEquals(FrequencyPairResult.FAILED, radio.setTrackingFrequencies(435_003_000, 145_953_000))
        assertFalse(radio.isConnected)
        assertTrue(diagnostics.events.value.any { it.stage == "n76/reply/timeout" })
    }

    @Test fun frequencyReadbackIgnoresEchoAndOtherCommandReplies() = withRadio { radio, wire, _ ->
        wire.echo = true
        val result = radio.readFrequencyAndMode()
        assertEquals(435_000_000L to "FM", result)
        assertNull(radio.readTxVfoFrequency())
    }

    @Test fun invalidUiValuesDoNotDisconnectAndPassiveTransportLossReachesUi() = withRadio { radio, wire, _ ->
        assertFalse(radio.configureTxCtcss(Double.NaN))
        assertFalse(radio.configureTxCtcss(999.0))
        assertFalse(radio.updateTrackingDisplay(RadioTrackingDisplay("ISS", Double.NaN, 0.0)))
        assertTrue(radio.isConnected)
        wire.disconnect()
        delay(650)
        assertFalse(radio.remoteState.value.connected)
        assertFalse(radio.remoteState.value.transmitStateKnown)
        assertTrue(radio.remoteState.value.error!!.contains("Bluetooth"))
    }

    @Test fun cancellingTrackingFinishesItsAckBeforeSendingNativeTeardown() = withRadio { radio, wire, _ ->
        ready(radio)
        val sent = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        wire.onFrequencyWrite = { sent.complete(Unit); release.await() }
        val sender = CoroutineScope(currentCoroutineContext()).launch {
            radio.setTrackingFrequencies(435_000_000, 145_950_000)
        }
        sent.await()
        sender.cancel()
        release.complete(Unit)
        sender.join()
        wire.onFrequencyWrite = null
        assertTrue(radio.isConnected)
        assertTrue(radio.setSplitMode(false))
        assertArrayEquals(ByteArray(16), wire.sent.last { it.command == 35 }.payload)
    }

    @Test fun phoneVoicePttIsConfirmedOnCatAndStopsBeforeDopplerResumes() = withRadio(
        audioFactory = { wire -> FakeAudio(wire) }
    ) { radio, wire, _ ->
        ready(radio)
        radio.setTrackingFrequencies(435_000_000, 145_950_000)
        assertTrue(radio.remoteState.value.capabilities!!.voicePtt)
        assertFalse(radio.remoteState.value.capabilities!!.cwText)
        assertTrue(radio.pttOn())
        assertTrue(radio.pttOn())
        assertTrue(wire.transmitting)
        assertEquals(FrequencyPairResult.HELD, radio.setTrackingFrequencies(435_003_000, 145_953_000))
        assertTrue(radio.pttOff())
        assertFalse(wire.transmitting)
        assertEquals(FrequencyPairResult.APPLIED, radio.setTrackingFrequencies(435_005_000, 145_955_000))
        radio.setAudioForeground(false)
        assertFalse(radio.remoteState.value.bluetoothAudioConnected)
        assertTrue(radio.remoteState.value.capabilities!!.voicePtt)
        assertTrue(radio.isConnected)
        radio.setAudioForeground(true)
        assertTrue(radio.remoteState.value.bluetoothAudioConnected)
        assertFalse(wire.transmitting)
    }

    @Test fun cancellationDuringVoiceArmingNeverLeavesAudioTransmitting() = runBlocking {
        val wire = SimulatedN76()
        val start = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val audio = object : FakeAudio(wire) {
            override suspend fun startTransmit(): Boolean {
                super.startTransmit()
                start.complete(Unit)
                delay(10_000)
                return true
            }
        }
        val radio = N76Controller(wire, scope, replyTimeoutMillis = 100, audio = audio)
        try {
            assertTrue(radio.connect()); ready(radio)
            radio.setTrackingFrequencies(435_000_000, 145_950_000)
            val sender = launch { radio.pttOn() }
            start.await(); sender.cancel(); sender.join()
            assertFalse(wire.transmitting)
        } finally { radio.disconnect(); scope.cancel() }
    }

    @Test fun failedOptionalAudioDoesNotDisconnectCatOrClaimVoiceReady() = withRadio(
        audioFactory = { wire -> object : FakeAudio(wire) {
            override suspend fun connect(): Boolean { state.value = N76AudioState(error = "Audio unavailable"); return false }
        } }
    ) { radio, _, _ ->
        assertTrue(radio.isConnected)
        assertTrue(radio.remoteState.value.capabilities!!.voicePtt)
        assertFalse(radio.remoteState.value.bluetoothAudioConnected)
        assertEquals("Audio unavailable", radio.remoteState.value.bluetoothAudioError)
        assertNull(radio.remoteState.value.error)
    }

    @Test fun audioOffUsesDistinctControlPttPressAndReleaseWithoutAnAudioSession() = withRadio { radio, wire, _ ->
        ready(radio)
        radio.setTrackingFrequencies(435_000_000, 145_950_000)
        assertTrue(radio.pttOn())
        assertTrue(wire.transmitting)
        assertFalse(radio.remoteState.value.phoneMicrophone)
        assertFalse(radio.remoteState.value.bluetoothAudioConnected)
        assertEquals(FrequencyPairResult.HELD, radio.setTrackingFrequencies(435_005_000, 145_955_000))
        assertTrue(radio.pttOff())
        assertFalse(wire.transmitting)
        val commands = wire.sent.filter { it.command == 66 }
        assertEquals(listOf("000d", "001a"), commands.map { it.payload.hex() })
        assertFalse(wire.commands().any { it in listOf(33, 34) })
        assertEquals(FrequencyPairResult.APPLIED, radio.setTrackingFrequencies(435_008_000, 145_958_000))
    }

    @Test fun legacyModeForcedTonesAndPowerUseTheReferenceBitFields() = withRadio(
        options = N76ControlOptions(firmwareMode = N76FirmwareMode.EXACT, forceRxCtcss = true, rxCtcssHz = 88.5,
            forceTxCtcss = true, txCtcssHz = 67.0, power = N76Power.LOW)
    ) { radio, wire, _ ->
        ready(radio)
        assertEquals(FrequencyPairResult.APPLIED, radio.setTrackingFrequencies(435_000_000, 145_950_000))
        val payload = wire.sent.last { it.command == 35 }.payload
        assertEquals(14, payload.size)
        assertEquals("22921a2c8301", payload.copyOfRange(8, 14).hex())
        assertFalse(wire.commands().contains(77))
        assertTrue(radio.setSplitMode(false))
        assertArrayEquals(ByteArray(14), wire.sent.last { it.command == 35 }.payload)
    }

    @Test fun monitorTogglesOnlyOnARequestedStateChange() = withRadio { radio, wire, _ ->
        assertTrue(radio.setRadioMonitor(true))
        assertTrue(radio.setRadioMonitor(true))
        assertTrue(radio.remoteState.value.radioMonitor)
        assertTrue(radio.setRadioMonitor(false))
        assertEquals(listOf("000f", "000f"), wire.sent.filter { it.command == 66 }.map { it.payload.hex() })
    }

    @Test fun audioRelayRestoresOnlyItsOwnFlagAndPreservesOtherRadioSettings() = withRadio(
        wire = SimulatedN76().apply { bss[2] = 0 }, audioFactory = { FakeAudio(it) }
    ) { radio, wire, _ ->
        assertTrue(wire.bss[2].toInt() and 2 != 0)
        wire.bss[4] = 0x77
        radio.setAudioForeground(false)
        assertEquals(0, wire.bss[2].toInt() and 2)
        assertEquals(0x77, wire.bss[4].toInt())
        assertTrue(radio.isConnected)
    }

    @Test fun recordingAndPlaybackActionsAreWiredAndAutoRecordStartsOncePerPass() = runBlocking {
        val recorder = FakeRecording()
        withRadio(audioFactory = { FakeAudio(it) }, recorder = recorder,
            options = N76ControlOptions(recordRadio = true, recordOnTrack = true)) { radio, _, _ ->
            ready(radio)
            repeat(3) { radio.setTrackingFrequencies(435_000_000L + it, 145_950_000) }
            assertEquals(1, recorder.starts)
            assertTrue(recorder.state.value.recording)
            assertTrue(radio.recordingAction(RadioRecordingAction.STOP))
            assertFalse(radio.remoteState.value.recording)
            assertEquals("test.wav", radio.remoteState.value.lastRecording)
            assertTrue(radio.recordingAction(RadioRecordingAction.PLAY_LAST))
            assertTrue(radio.remoteState.value.playingRecording)
            assertTrue(radio.recordingAction(RadioRecordingAction.STOP_PLAYBACK))
            assertFalse(radio.remoteState.value.playingRecording)
            assertTrue(radio.setSplitMode(false))
        }
    }

    @Test fun successfulRecordingClearsItsPreviousError() = runBlocking {
        val recorder = FakeRecording().apply { startFailure = "Enable Bluetooth audio to record the radio" }
        withRadio(recorder = recorder) { radio, _, _ ->
            assertFalse(radio.recordingAction(RadioRecordingAction.START))
            assertEquals(recorder.startFailure, radio.remoteState.value.recordingError)
            assertNull(radio.remoteState.value.error)
            recorder.startFailure = null
            assertTrue(radio.recordingAction(RadioRecordingAction.START))
            assertTrue(radio.remoteState.value.recording)
            assertNull(radio.remoteState.value.recordingError)
        }
    }

    @Test fun recordingRecoveryDoesNotEraseASeparateCatFailure() = runBlocking {
        val recorder = FakeRecording().apply { startFailure = "Recording source unavailable" }
        withRadio(recorder = recorder) { radio, _, _ ->
            assertFalse(radio.recordingAction(RadioRecordingAction.START))
            assertFalse(radio.configureTxCtcss(Double.NaN))
            val catError = radio.remoteState.value.error
            assertEquals("Invalid CTCSS tone", catError)
            recorder.startFailure = null
            assertTrue(radio.recordingAction(RadioRecordingAction.START))
            assertEquals(catError, radio.remoteState.value.error)
        }
    }

    @Test fun transientStatusLossHoldsTuningAndRecoversWithoutDisconnectingOrReplayingTargets() = withRadio { radio, wire, diagnostics ->
        ready(radio)
        radio.setTrackingFrequencies(435_000_000, 145_950_000)
        val writes = wire.commands().count { it == 35 }
        wire.suppress = 20
        assertEquals(FrequencyPairResult.HELD, radio.setTrackingFrequencies(435_001_000, 145_951_000))
        assertTrue(radio.isConnected)
        assertFalse(radio.remoteState.value.transmitStateKnown)
        assertEquals(writes, wire.commands().count { it == 35 })
        wire.suppress = null
        assertEquals(FrequencyPairResult.APPLIED, radio.setTrackingFrequencies(435_003_000, 145_953_000))
        assertEquals(435_003_000L, radio.remoteState.value.rxFrequencyHz)
        assertNull(radio.remoteState.value.error)
        assertTrue(diagnostics.events.value.any { it.stage == "n76/status/recovered" })
    }

    @Test fun persistentIdleStatusLossDisconnectsAfterBoundedRecovery() = withRadio { radio, wire, _ ->
        wire.suppress = 20
        repeat(3) { assertNull(radio.readPttState()) }
        assertFalse(radio.isConnected)
        assertFalse(wire.transmitting)
        assertFalse(wire.commands().contains(66))
    }

    @Test fun optionalAudioSettingsTimeoutLeavesCatUsable() = withRadio(
        SimulatedN76().apply { suppress = 33 }, audioFactory = { FakeAudio(it) }
    ) { radio, _, _ ->
        assertTrue(radio.isConnected)
        assertFalse(radio.remoteState.value.bluetoothAudioConnected)
        assertNotNull(radio.remoteState.value.bluetoothAudioError)
        assertNull(radio.remoteState.value.error)
        ready(radio)
        assertEquals(FrequencyPairResult.APPLIED, radio.setTrackingFrequencies(435_000_000, 145_950_000))
        delay(600)
        assertNotNull(radio.remoteState.value.bluetoothAudioError)
        assertNull(radio.remoteState.value.error)
    }

    @Test fun recordingExceptionCannotDisconnectCatAndSaveFailureIsSeparate() = runBlocking {
        val recorder = FakeRecording().apply { throwOnStart = true }
        withRadio(recorder = recorder) { radio, _, _ ->
            ready(radio)
            assertFalse(radio.recordingAction(RadioRecordingAction.START))
            assertTrue(radio.isConnected)
            assertEquals("Disk full", radio.remoteState.value.recordingError)
            assertNull(radio.remoteState.value.error)
            assertEquals(FrequencyPairResult.APPLIED, radio.setTrackingFrequencies(435_000_000, 145_950_000))
            recorder.throwOnStart = false
            assertTrue(radio.recordingAction(RadioRecordingAction.START))
            assertNull(radio.remoteState.value.recordingError)
            recorder.state.value = recorder.state.value.copy(recording = false, lastFile = "local.wav", exportError = "Folder permission expired")
            assertTrue(radio.saveRecording("content://new-provider/recording.wav"))
            assertEquals("content://new-provider/recording.wav", recorder.savedUri)
            assertEquals("local.wav", radio.remoteState.value.lastRecording)
            assertNull(radio.remoteState.value.recordingExportError)
            assertTrue(radio.remoteState.value.recordingExported)
            assertNull(radio.remoteState.value.error)
        }
    }

    @Test fun rejectedControlPttDoesNotQueueAStartOrLeaveAnOwnedTransmission() = withRadio { radio, wire, diagnostics ->
        ready(radio); radio.setTrackingFrequencies(435_000_000, 145_950_000)
        wire.reject = 66
        assertFalse(radio.pttOn())
        assertFalse(wire.transmitting)
        assertTrue(radio.isConnected)
        assertTrue(diagnostics.events.value.any { it.stage == "n76/reply/rejected" })
        wire.reject = null
        assertTrue(radio.pttOn()); assertTrue(radio.pttOff())
        assertFalse(wire.transmitting)
    }

    private suspend fun ready(radio: N76Controller) {
        assertTrue(radio.prepareTrackingContext("satellite-transponder"))
        assertTrue(radio.setSplitModes("FM", "FM"))
        assertTrue(radio.updateTrackingDisplay(RadioTrackingDisplay("ISS (ZARYA)", 114.0, -76.0)))
    }

    private fun withRadio(wire: SimulatedN76 = SimulatedN76(), audioFactory: ((SimulatedN76) -> N76Audio)? = null,
        options: N76ControlOptions = N76ControlOptions(),
        recorder: N76Recording? = null,
        block: suspend (N76Controller, SimulatedN76, ControlDiagnosticsBuffer) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val diagnostics = ControlDiagnosticsBuffer()
        val radio = N76Controller(wire, scope, diagnostics, replyTimeoutMillis = 100, audio = audioFactory?.invoke(wire), options = { options }, recorder = recorder)
        try { assertTrue(radio.connect()); block(radio, wire, diagnostics) }
        finally { radio.disconnect(); scope.cancel() }
    }

    private open class FakeAudio(private val wire: SimulatedN76) : N76Audio {
        override val state = kotlinx.coroutines.flow.MutableStateFlow(N76AudioState())
        override suspend fun connect(): Boolean { state.value = N76AudioState(connected = true); return true }
        override suspend fun startTransmit(): Boolean { wire.transmitting = true; state.value = state.value.copy(transmitting = true); return true }
        override suspend fun stopTransmit(): Boolean { wire.transmitting = false; state.value = state.value.copy(transmitting = false); return true }
        override suspend fun disconnect() { stopTransmit(); state.value = N76AudioState() }
    }

    private class FakeRecording : N76Recording {
        override val state = kotlinx.coroutines.flow.MutableStateFlow(N76RecordingState())
        var starts = 0
        var startFailure: String? = null
        var throwOnStart = false
        var savedUri: String? = null
        override suspend fun start(satelliteName: String, radioAudioAvailable: Boolean): Boolean {
            if (throwOnStart) throw java.io.IOException("Disk full")
            starts++
            state.value = state.value.copy(recording = startFailure == null, error = startFailure)
            return startFailure == null
        }
        override suspend fun stop(): Boolean { state.value = state.value.copy(recording = false,
            lastFile = if (starts > 0) "test.wav" else null); return true }
        override suspend fun playLast(): Boolean { state.value = state.value.copy(playing = true); return true }
        override suspend fun stopPlayback(): Boolean { state.value = state.value.copy(playing = false); return true }
        override fun feedRadio(pcm: ShortArray) = Unit
        override suspend fun save(destinationUri: String): Boolean {
            savedUri = destinationUri
            state.value = state.value.copy(exportError = null, exportedFile = state.value.lastFile)
            return true
        }
    }

    private class SimulatedN76 : RadioTransport {
        override var isConnected = false
        val sent = java.util.Collections.synchronizedList(mutableListOf<N76Protocol.Packet>())
        @Volatile var transmitting = false
        var secureFirst = true
        var product = 2
        var vfo = true
        var deviceInfo: ByteArray? = null
        var frequencyStatus = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(435_000_000).array()
        var echo = false
        var reject: Int? = null
        var suppress: Int? = null
        var suppressFirstInfo = false
        var silentUntilAlternative = false
        var alternatives = 0
        var onFrequencyWrite: (suspend () -> Unit)? = null
        private var input = byteArrayOf()
        var bss = ByteArray(46).also { it[2] = 2 }
        override val canTryAlternativeConnection get() = silentUntilAlternative && alternatives == 0
        override fun configureBluetoothConnection(secureFirst: Boolean, timeoutMillis: Long) { this.secureFirst = secureFirst }
        override suspend fun connect(): Boolean { isConnected = true; return true }
        override suspend fun connectAlternative(): Boolean { alternatives++; return true }
        override suspend fun disconnect() { isConnected = false; input = byteArrayOf() }
        fun commands() = sent.map { it.command }

        override suspend fun write(bytes: ByteArray): Boolean {
            if (!isConnected) return false
            val packet = N76Protocol.Decoder().feed(bytes).single()
            sent += packet
            if (packet.command == 35) onFrequencyWrite?.invoke()
            if (echo) input += bytes
            if (packet.command == 6 || packet.command == suppress || (silentUntilAlternative && alternatives == 0)) return true
            if (packet.command == 4 && suppressFirstInfo) { suppressFirstInfo = false; return true }
            if (packet.command == 66 && reject != 66) {
                if (packet.payload.contentEquals(byteArrayOf(0, 0x0d))) transmitting = true
                if (packet.payload.contentEquals(byteArrayOf(0, 0x1a))) transmitting = false
            }
            if (packet.command == 34 && reject != 34) bss = packet.payload.copyOf()
            val data = when (packet.command) {
                4 -> deviceInfo ?: byteArrayOf(0, (product shr 8).toByte(), product.toByte(), 1, 0, 137.toByte(), 0, if (vfo) 2 else 0, 32, 0)
                20 -> byteArrayOf(if (transmitting) 0xc0.toByte() else 0x80.toByte(), 0, 0, 0)
                36 -> frequencyStatus
                33 -> bss.copyOf()
                else -> byteArrayOf()
            }
            input += N76Protocol.frame(packet.command, byteArrayOf(if (reject == packet.command) 1 else 0) + data, reply = true)
            return true
        }
        override suspend fun readAvailable(maxBytes: Int): ByteArray {
            // Fragment every response, including coalesced echo + reply.
            val count = minOf(maxBytes, 7, input.size)
            val result = input.copyOfRange(0, count)
            input = input.copyOfRange(count, input.size)
            return result
        }
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }
}
