/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.model.DataSourcesSettings
import com.rtbishop.look4sat.core.domain.model.DatabaseState
import com.rtbishop.look4sat.core.domain.model.GridQso
import com.rtbishop.look4sat.core.domain.model.LoTWSettings
import com.rtbishop.look4sat.core.domain.model.OtherSettings
import com.rtbishop.look4sat.core.domain.model.PassesSettings
import com.rtbishop.look4sat.core.domain.model.RCSettings
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.model.WavelogUploadSettings
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.control.ControlDiagnosticsBuffer
import com.rtbishop.look4sat.core.domain.control.IControlDiagnostics
import com.rtbishop.look4sat.core.domain.control.NoOpControlDiagnostics
import com.rtbishop.look4sat.core.domain.predict.OrbitalData
import com.rtbishop.look4sat.core.domain.predict.OrbitalObject
import com.rtbishop.look4sat.core.domain.predict.OrbitalPass
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import com.rtbishop.look4sat.core.domain.repository.IRadioController
import com.rtbishop.look4sat.core.domain.repository.ISatelliteRepo
import com.rtbishop.look4sat.core.domain.repository.ISettingsRepo
import com.rtbishop.look4sat.core.domain.repository.PttState
import com.rtbishop.look4sat.core.domain.repository.TrackingPhase
import com.rtbishop.look4sat.core.domain.repository.RemoteRadioState
import com.rtbishop.look4sat.core.domain.repository.RemoteRadioCapabilities
import com.rtbishop.look4sat.core.domain.repository.FrequencyPairResult
import com.rtbishop.look4sat.core.domain.repository.RadioTransmitOwner
import com.rtbishop.look4sat.core.domain.repository.CwTransmitState
import com.rtbishop.look4sat.core.domain.utility.CalculatorTuning
import com.rtbishop.look4sat.core.domain.utility.CalculatorFrequencyAnchor
import com.rtbishop.look4sat.core.domain.utility.frequencies
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RadioTrackingServiceTest {
    @Test fun n76UsesAtomicNativeTrackingAndDoesNotApplyTheL4s250HzQuantizer() = runTest {
        val remote = remoteFixtureState().also { flow ->
            flow.value = flow.value.copy(capabilities = flow.value.capabilities!!.copy(
                cwText = false, voicePtt = false, satelliteProfiles = false, nativeSettings = false))
        }
        val tx = FakeRadioController(remote = remote, pttReadbackSupported = true)
        val fixture = Fixture(backgroundScope, tx, FakeRadioController(),
            radioModel = RadioControlSettings.MODEL_VERO_N76,
            transport = RadioControlSettings.TRANSPORT_BLUETOOTH)
        fixture.transponder = fixture.transponder.copy(uplinkMode = "FM", downlinkMode = "FM")
        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()
        assertTrue(fixture.service.state.value.splitMode)
        assertTrue(fixture.service.state.value.isActive)
        assertTrue(tx.operations.any { it.startsWith("pair:") })
        assertFalse(tx.operations.any { it.startsWith("frequency:") || it.startsWith("tx-frequency:") || it.startsWith("rx-frequency:") })
        val current = fixture.service.state.value
        assertEquals(fixture.position.getDownlinkFreq(current.nominalRxFrequencyHz!!), current.targetRxFrequencyHz)
        assertEquals(fixture.position.getUplinkFreq(current.nominalTxFrequencyHz!!), current.targetTxFrequencyHz)
        fixture.close()
        assertTrue("split:false" in tx.operations)
    }

    @Test fun n76KeepsFmForTelemetrySelectionAndExplicitModeRequests() = runTest {
        val remote = remoteFixtureState().also { flow ->
            flow.value = flow.value.copy(capabilities = flow.value.capabilities!!.copy(
                satelliteProfiles = false, nativeSettings = false))
        }
        val tx = FakeRadioController(remote = remote, pttReadbackSupported = true)
        val fixture = Fixture(backgroundScope, tx, FakeRadioController(),
            radioModel = RadioControlSettings.MODEL_VERO_N76,
            transport = RadioControlSettings.TRANSPORT_BLUETOOTH)
        fixture.service.connectRadios()
        fixture.transponder = fixture.transponder.copy(uplinkLow = null, uplinkHigh = null,
            uplinkMode = null, downlinkMode = "BPSK")
        fixture.service.setTransponder(fixture.transponder)
        runCurrent()
        fixture.startTracking(null)
        runCurrent()
        assertTrue(fixture.service.state.value.isActive)
        fixture.service.setMode("CW", "USB")
        runCurrent()
        assertEquals("FM", fixture.service.state.value.txMode)
        assertEquals("FM", fixture.service.state.value.rxMode)
        assertTrue(tx.operations.filter { it.startsWith("split-modes:") }.all { it == "split-modes:FM:FM" })
        assertNull(fixture.service.state.value.errorMessage)
        fixture.close()
    }

    @Test fun recoveredRemoteErrorClearsThePanelWithoutErasingALaterCommandFailure() = runTest {
        val remote = remoteFixtureState()
        val tx = FakeRadioController(remote = remote, setModeSucceeds = false)
        val fixture = Fixture(backgroundScope, tx, FakeRadioController(),
            radioModel = RadioControlSettings.MODEL_ICOM_IC705,
            transport = RadioControlSettings.TRANSPORT_BLUETOOTH)
        fixture.service.connectRadios()
        runCurrent()
        remote.value = remote.value.copy(error = "Recording source unavailable")
        runCurrent()
        assertEquals(remote.value.error, fixture.service.state.value.errorMessage)
        remote.value = remote.value.copy(error = null, recording = true)
        runCurrent()
        assertNull(fixture.service.state.value.errorMessage)
        assertNull(fixture.service.state.value.lastCommandError)
        remote.value = remote.value.copy(error = "Old recording error")
        runCurrent()
        fixture.service.setMode("FM", "FM")
        runCurrent()
        assertEquals("Radio mode command was not acknowledged", fixture.service.state.value.errorMessage)
        remote.value = remote.value.copy(error = null)
        runCurrent()
        assertEquals("Radio mode command was not acknowledged", fixture.service.state.value.errorMessage)
        fixture.close()
    }

    @Test fun recordingAndExportErrorsDoNotBecomeCatErrorsOrStopTracking() = runTest {
        val remote = remoteFixtureState().also { flow ->
            flow.value = flow.value.copy(isN76 = true, capabilities = flow.value.capabilities!!.copy(
                satelliteProfiles = false, nativeSettings = false))
        }
        val tx = FakeRadioController(remote = remote, pttReadbackSupported = true)
        val fixture = Fixture(backgroundScope, tx, FakeRadioController(),
            radioModel = RadioControlSettings.MODEL_VERO_N76, transport = RadioControlSettings.TRANSPORT_BLUETOOTH)
        fixture.transponder = fixture.transponder.copy(uplinkMode = "FM", downlinkMode = "FM")
        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()
        remote.value = remote.value.copy(recordingError = "Microphone permission required", recordingExportError = "Folder access expired")
        fixture.service.recordingAction(com.rtbishop.look4sat.core.domain.model.RadioRecordingAction.START)
        fixture.service.saveRecording("content://new-provider/recording.wav")
        runCurrent()
        assertTrue(fixture.service.state.value.isActive)
        assertNull(fixture.service.state.value.errorMessage)
        assertEquals("Folder access expired", fixture.service.state.value.remoteRadio?.recordingExportError)
        remote.value = remote.value.copy(recordingError = null, recordingExportError = null)
        runCurrent()
        assertNull(fixture.service.state.value.errorMessage)
        fixture.close()
    }

    @Test fun enablingN76AudioRecreatesTheConnectionOnNextConnect() = runTest {
        val tx = FakeRadioController()
        val fixture = Fixture(backgroundScope, tx, FakeRadioController(),
            radioModel = RadioControlSettings.MODEL_VERO_N76,
            transport = RadioControlSettings.TRANSPORT_BLUETOOTH)
        fixture.service.connectRadios()
        assertTrue(fixture.service.applyRadioControlSettings(fixture.settings.radioControlSettings.value.copy(n76BluetoothAudio = true)))
        assertFalse(fixture.service.state.value.txConnected)
        assertTrue("disconnect" in tx.operations)
        fixture.service.connectRadios()
        assertTrue(fixture.service.state.value.txConnected)
        fixture.close()
    }

    @Test fun n76RejectsUnsupportedUsbBeforeConstructingAnyController() = runTest {
        val tx = FakeRadioController()
        val fixture = Fixture(backgroundScope, tx, FakeRadioController(), radioModel = RadioControlSettings.MODEL_VERO_N76)
        fixture.service.connectRadios()
        assertFalse(fixture.service.state.value.txConnected)
        assertTrue(tx.operations.isEmpty())
        assertEquals("N76 requires Bluetooth", fixture.service.state.value.errorMessage)
        fixture.close()
    }

    @Test fun ic705DopplerChangesBothFrequenciesWithoutSwitchingToTxVfo() = runTest {
        val tx = FakeRadioController()
        val fixture = Fixture(backgroundScope, tx, FakeRadioController(),
            radioModel = RadioControlSettings.MODEL_ICOM_IC705, splitMode = true)
        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()
        assertTrue(fixture.service.state.value.isActive)
        assertTrue(tx.operations.any { it.startsWith("tx-frequency:") })
        tx.operations.clear()
        repeat(4) { index ->
            fixture.position.distanceRate = 1.2 + index * 0.4
            advanceTimeBy(1_100L)
            runCurrent()
        }
        assertTrue(tx.operations.count { it.startsWith("tx-frequency:") } >= 2)
        assertTrue(tx.operations.count { it.startsWith("rx-frequency:") } >= 2)
        assertFalse(tx.operations.any { it.startsWith("vfo:") })
        assertFalse(tx.operations.any { it.startsWith("mode:") || it.startsWith("split-modes:") })
        fixture.close()
    }

    @Test fun ic705ReceiveOnlyTrackingLeavesSplitOffAndOldTxVfoUntouched() = runTest {
        val tx = FakeRadioController()
        val fixture = Fixture(backgroundScope, tx, FakeRadioController(),
            radioModel = RadioControlSettings.MODEL_ICOM_IC705, splitMode = true)
        fixture.transponder = fixture.transponder.copy(uplinkLow = null, uplinkHigh = null)
        fixture.service.connectRadios()
        fixture.startTracking(null)
        runCurrent()
        assertTrue(fixture.service.state.value.isActive)
        assertFalse(tx.operations.contains("split:true"))
        assertFalse(tx.operations.contains("vfo:tx"))
        assertFalse(tx.operations.any { it.startsWith("tx-frequency:") })
        fixture.close()
    }

    private val legacyModels=listOf(RadioControlSettings.MODEL_YAESU_FT817,RadioControlSettings.MODEL_YAESU_FT857,RadioControlSettings.MODEL_YAESU_FTX1,
        RadioControlSettings.MODEL_ICOM_IC705,RadioControlSettings.MODEL_ICOM_IC9700,
        RadioControlSettings.MODEL_ICOM_IC910,RadioControlSettings.MODEL_ICOM_IC820)

    @Test fun connectionSettingsChangeClosesOldControllersAndRequiresExplicitReconnect()=runTest {
        val fixture=Fixture(backgroundScope,FakeRadioController(),FakeRadioController())
        fixture.service.connectRadios();fixture.startTracking();runCurrent()
        assertTrue(fixture.service.applyRadioControlSettings(fixture.settings.radioControlSettings.value.copy(baudRate=38400)))
        assertFalse(fixture.service.state.value.txConnected)
        assertFalse(fixture.service.state.value.rxConnected)
        assertFalse(fixture.service.state.value.isActive)
        assertNull(fixture.service.state.value.nominalRxFrequencyHz)
        fixture.service.connectRadios();fixture.startTracking();runCurrent()
        assertTrue(fixture.service.state.value.isActive);fixture.close()
    }

    @Test fun receiveOnlyConnectionQueriesOnlyTheConfiguredRadio()=runTest {
        val tx=FakeRadioController(pttReadbackSupported=true)
        val rx=FakeRadioController(pttReadbackSupported=true)
        val fixture=Fixture(backgroundScope,tx,rx)
        fixture.settings.radioControlSettings.value=fixture.settings.radioControlSettings.value.copy(txRadioAddress="")
        fixture.transponder=fixture.transponder.copy(uplinkLow=null,uplinkHigh=null)
        fixture.service.connectRadios();fixture.startTracking(null);runCurrent()
        assertTrue(fixture.service.state.value.isActive)
        assertTrue(fixture.service.state.value.catTransmitStateKnown)
        assertNull(fixture.service.state.value.errorMessage)
        rx.physicalPtt=true;advanceTimeBy(1100);runCurrent()
        assertTrue(fixture.service.state.value.frequencyEditingLocked);fixture.close()
    }

    @Test fun noConnectedRadioCannotReportTrackingReady()=runTest {
        val fixture=Fixture(backgroundScope,FakeRadioController(),FakeRadioController())
        fixture.startTracking();runCurrent()
        assertFalse(fixture.service.state.value.isActive)
        assertEquals(TrackingPhase.ERROR,fixture.service.state.value.trackingPhase)
        fixture.close()
    }

    @Test fun independentBasesAndExplicitCalculatorLinkWorkForEveryLegacyModel()=runTest {
        for(model in legacyModels) for(split in listOf(false,true)) {
            val fixture=Fixture(backgroundScope,FakeRadioController(),FakeRadioController(),radioModel=model,splitMode=split)
            fixture.transponder=fixture.transponder.copy(uplinkHigh=fixture.nominalTxHz+10000)
            fixture.service.connectRadios();fixture.startTracking();runCurrent()
            val rx=fixture.service.state.value.nominalRxFrequencyHz
            fixture.service.adjustTxBaseFrequency(1000);runCurrent()
            assertEquals("$model RX must not follow TX",rx,fixture.service.state.value.nominalRxFrequencyHz)
            val tx=fixture.service.state.value.txBaseFrequencyHz
            fixture.service.setRxBaseFrequency(435108000);runCurrent()
            assertEquals(tx,fixture.service.state.value.txBaseFrequencyHz)
            val tuning=CalculatorTuning(fixture.transponder.uuid,CalculatorFrequencyAnchor.GROUND_RX,435105000)
            fixture.service.tuneFromCalculator(fixture.transponder,tuning);runCurrent()
            assertTrue("$model calculator must explicitly link",fixture.service.state.value.calculatorLinked)
            val linkedRx=fixture.service.state.value.nominalRxFrequencyHz
            fixture.service.adjustTxBaseFrequency(500);runCurrent()
            assertFalse(fixture.service.state.value.calculatorLinked)
            assertEquals(linkedRx,fixture.service.state.value.nominalRxFrequencyHz)
            fixture.close()
        }
    }

    @Test fun legacyPhysicalPttAndUnknownStatusFreezeTuningThenResumeAtCurrentTime()=runTest {
        val tx=FakeRadioController(pttReadbackSupported=true)
        val fixture=Fixture(backgroundScope,tx,FakeRadioController(),radioModel=RadioControlSettings.MODEL_ICOM_IC705,splitMode=true)
        fixture.service.connectRadios();fixture.startTracking();runCurrent()
        val base=fixture.service.state.value.txBaseFrequencyHz
        tx.physicalPtt=true;advanceTimeBy(1100);runCurrent()
        assertTrue(fixture.service.state.value.frequencyEditingLocked)
        val writes=tx.operations.size
        fixture.service.adjustTxBaseFrequency(1000);runCurrent();advanceTimeBy(1100);runCurrent()
        assertEquals(writes,tx.operations.size)
        tx.physicalPtt=null;advanceTimeBy(1100);runCurrent()
        assertFalse(fixture.service.state.value.catTransmitStateKnown)
        assertEquals(writes,tx.operations.size)
        tx.physicalPtt=false;advanceTimeBy(1100);runCurrent()
        assertFalse(fixture.service.state.value.frequencyEditingLocked)
        assertTrue(tx.operations.size>writes)
        assertEquals(base,fixture.service.state.value.txBaseFrequencyHz)
        fixture.close()
    }

    @Test fun receiveOnlyAndReconnectionNeverReuseAnOldTransmitTarget()=runTest {
        for(model in legacyModels) {
            val tx=FakeRadioController();val fixture=Fixture(backgroundScope,tx,FakeRadioController(),radioModel=model,splitMode=true)
            fixture.service.connectRadios();fixture.startTracking();runCurrent()
            fixture.service.setRxBaseFrequency(435108000);runCurrent()
            fixture.service.connectRadios();runCurrent()
            assertNull(fixture.service.state.value.nominalRxFrequencyHz)
            fixture.transponder=fixture.transponder.copy(uuid="beacon",uplinkLow=null,uplinkHigh=null,downlinkMode="FM")
            val writes=tx.operations.count {it.startsWith("frequency:") || it.startsWith("tx-frequency:")}
            fixture.startTracking();runCurrent()
            assertNull(fixture.service.state.value.txBaseFrequencyHz)
            fixture.service.setTxBaseFrequency(145905000);runCurrent()
            assertNull(fixture.service.state.value.txBaseFrequencyHz)
            if(!fixture.service.state.value.splitMode)assertEquals(writes,tx.operations.count {it.startsWith("frequency:") || it.startsWith("tx-frequency:")})
            fixture.close()
        }
    }
    @Test fun localPhysicalPttIsReleasedAtLosWithoutASeparateRemoteOwner()=runTest {
        var now=System.currentTimeMillis()
        val remote=remoteFixtureState();val tx=FakeRadioController(remote=remote)
        val fixture=Fixture(backgroundScope,tx,FakeRadioController(),nowProvider={now},
            radioModel=RadioControlSettings.MODEL_QUANSHENG_UVK1,splitMode=true,transport=RadioControlSettings.TRANSPORT_USB)
        fixture.service.connectRadios();fixture.startTracking();runCurrent()
        remote.value=remote.value.copy(txSource=3);runCurrent()
        val releases=tx.operations.count {it=="ptt:off"}
        now=fixture.afterPassStart;advanceTimeBy(1100);runCurrent()
        assertTrue(tx.operations.count {it=="ptt:off"}>releases)
        assertEquals(0,remote.value.txSource);assertFalse(fixture.service.state.value.isActive)
        fixture.close()
    }
    @Test fun voiceTransmissionRejectsBaseModeToneAndCalculatorEditsWithoutPttOffOrQueue()=runTest {
        val remote=remoteFixtureState();val tx=FakeRadioController(remote=remote)
        val fixture=Fixture(backgroundScope,tx,FakeRadioController(),radioModel=RadioControlSettings.MODEL_QUANSHENG_UVK1,
            splitMode=true,transport=RadioControlSettings.TRANSPORT_USB)
        fixture.transponder=fixture.transponder.copy(uplinkMode="FM",downlinkMode="FM")
        fixture.service.connectRadios();fixture.startTracking();runCurrent()
        fixture.service.setVoicePtt(true);runCurrent()
        val before=fixture.service.state.value;val off=tx.operations.count {it=="ptt:off"};val pairs=tx.operations.count {it.startsWith("pair:")}
        fixture.service.adjustTxBaseFrequency(1000);fixture.service.setRxBaseFrequency(435150000)
        fixture.service.setMode("CW","CW");fixture.service.setCtcssTone(88.5)
        runCurrent();advanceTimeBy(1100);runCurrent()
        assertEquals(RadioTransmitOwner.VOICE,fixture.service.state.value.transmitOwner)
        assertEquals(off,tx.operations.count {it=="ptt:off"});assertEquals(pairs,tx.operations.count {it.startsWith("pair:")})
        assertEquals(before.txBaseFrequencyHz,fixture.service.state.value.txBaseFrequencyHz)
        fixture.service.cancelRemoteTransmit();runCurrent()
        assertEquals(before.txBaseFrequencyHz,fixture.service.state.value.txBaseFrequencyHz)
        assertEquals(before.nominalRxFrequencyHz,fixture.service.state.value.nominalRxFrequencyHz)
        fixture.close()
    }
    @Test fun queuedEditCannotOvertakePttArmingAndUnknownStateCannotEdit()=runTest {
        val remote=remoteFixtureState();val tx=FakeRadioController(remote=remote)
        val fixture=Fixture(backgroundScope,tx,FakeRadioController(),radioModel=RadioControlSettings.MODEL_QUANSHENG_UVK1,
            splitMode=true,transport=RadioControlSettings.TRANSPORT_USB)
        fixture.transponder=fixture.transponder.copy(uplinkMode="FM",downlinkMode="FM")
        fixture.service.connectRadios();fixture.startTracking();runCurrent()
        val base=fixture.service.state.value.txBaseFrequencyHz
        remote.value=remote.value.copy(transmitStateKnown=false)
        fixture.service.adjustTxBaseFrequency(1000);runCurrent()
        assertEquals(base,fixture.service.state.value.txBaseFrequencyHz)
        assertEquals("Radio transmit state is unknown", fixture.service.state.value.lastCommandError)
        remote.value=remote.value.copy(transmitStateKnown=true);runCurrent()
        fixture.service.adjustTxBaseFrequency(1000);fixture.service.setVoicePtt(true);runCurrent()
        assertEquals(base,fixture.service.state.value.txBaseFrequencyHz)
        assertEquals(RadioTransmitOwner.VOICE,fixture.service.state.value.transmitOwner)
        fixture.service.cancelRemoteTransmit();runCurrent();fixture.close()
    }
    @Test fun repeatedConnectDuringHandshakeDoesNotQueueAnotherDisconnect() = runTest {
        val started = CompletableDeferred<Unit>()
        val proceed = CompletableDeferred<Unit>()
        val tx = FakeRadioController().apply { beforeConnect = { started.complete(Unit); proceed.await() } }
        val fixture = Fixture(backgroundScope, tx, FakeRadioController())
        val first = async { fixture.service.connectRadios() }
        runCurrent()
        assertTrue(started.isCompleted)
        val duplicate = async { fixture.service.connectRadios() }
        runCurrent()
        assertTrue(duplicate.isCompleted)
        proceed.complete(Unit)
        first.await()
        duplicate.await()
        assertEquals(1, tx.operations.count { it == "connect" })
        assertTrue(fixture.service.state.value.txConnected)
        fixture.close()
    }

    @Test fun disconnectedRemoteDoesNotReportAnActiveTransmission() = runTest {
        val remote = remoteFixtureState()
        val fixture = Fixture(backgroundScope, FakeRadioController(remote = remote), FakeRadioController(),
            radioModel = RadioControlSettings.MODEL_QUANSHENG_UVK1, splitMode = true)
        fixture.service.connectRadios()
        remote.value = remote.value.copy(connected = false, transmitStateKnown = false)
        runCurrent()
        fixture.service.setMode("FM", "FM")
        runCurrent()
        assertEquals("Radio is not connected", fixture.service.state.value.lastCommandError)
        fixture.close()
    }
    @Test fun independentlyEditingEitherBaseKeepsTheOtherAndReceiveOnlyProfileNeverAddsTx()=runTest {
        val remote=remoteFixtureState();val tx=FakeRadioController(remote=remote)
        val fixture=Fixture(backgroundScope,tx,FakeRadioController(),radioModel=RadioControlSettings.MODEL_QUANSHENG_UVK1,
            splitMode=true,transport=RadioControlSettings.TRANSPORT_USB)
        fixture.service.connectRadios();fixture.startTracking();runCurrent()
        val rx=fixture.service.state.value.nominalRxFrequencyHz
        fixture.service.adjustTxBaseFrequency(1000);runCurrent()
        assertEquals(rx,fixture.service.state.value.nominalRxFrequencyHz)
        val newTx=fixture.service.state.value.txBaseFrequencyHz
        fixture.service.setRxBaseFrequency(435150000);runCurrent()
        assertEquals(newTx,fixture.service.state.value.txBaseFrequencyHz)
        assertEquals(435150000L,fixture.service.state.value.nominalRxFrequencyHz)
        fixture.close()
        val secondRemote=remoteFixtureState()
        secondRemote.value=secondRemote.value.copy(capabilities=secondRemote.value.capabilities!!.copy(nativeSettings=true,satelliteProfiles=true),
            nativeProfileValid=true,nativeProfileKey=com.rtbishop.look4sat.core.domain.repository.radioSatelliteProfileKey("TEST","test-transponder"),
            nativeSavedMode=0,nativeBaseFrequencies=com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies(435150000,null))
        val receiver=FakeRadioController(remote=secondRemote)
        val second=Fixture(backgroundScope,receiver,FakeRadioController(),radioModel=RadioControlSettings.MODEL_QUANSHENG_UVK1,
            splitMode=true,transport=RadioControlSettings.TRANSPORT_USB)
        second.transponder=second.transponder.copy(uplinkLow=null,uplinkHigh=null,downlinkMode="FM",uplinkMode="FM")
        second.service.connectRadios();second.startTracking();runCurrent()
        assertNull(second.service.state.value.txBaseFrequencyHz);assertFalse(secondRemote.value.txEnabled)
        second.service.setVoicePtt(true);runCurrent();assertFalse(receiver.operations.contains("ptt:on"));second.close()
    }
    @Test fun satelliteProfileRestoresIndependentBasesForTrackingAndPttPreparation()=runTest {
        val remote=remoteFixtureState()
        val bases=com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies(435150000,145920000)
        remote.value=remote.value.copy(capabilities=remote.value.capabilities!!.copy(nativeSettings=true,satelliteProfiles=true),
            nativeProfileValid=true,nativeProfileKey=com.rtbishop.look4sat.core.domain.repository.radioSatelliteProfileKey("TEST","test-transponder"),
            nativeBaseFrequencies=bases,nativeProfileGeneration=1,settingsSequence=1,nativeSavedMode=0)
        val tx=FakeRadioController(remote=remote)
        val fixture=Fixture(backgroundScope,tx,FakeRadioController(),radioModel=RadioControlSettings.MODEL_QUANSHENG_UVK1,
            splitMode=true,transport=RadioControlSettings.TRANSPORT_USB)
        fixture.transponder=fixture.transponder.copy(uplinkMode="FM",downlinkMode="FM")
        fixture.service.connectRadios();fixture.startTracking();runCurrent()
        assertEquals(145920000L,fixture.service.state.value.txBaseFrequencyHz)
        assertEquals(fixture.position.getDownlinkFreq(bases.rxHz),tx.frequencyHz)
        assertEquals(fixture.position.getUplinkFreq(bases.txHz!!),tx.txFrequencyHz)
        assertTrue(tx.operations.contains("bases:435150000:145920000"))
        fixture.service.setVoicePtt(true);runCurrent()
        assertEquals(fixture.position.getDownlinkFreq(bases.rxHz),tx.frequencyHz)
        assertEquals(1,remote.value.txSource)
        fixture.service.setVoicePtt(false);runCurrent();fixture.close()
    }

    @Test fun nativeSaveUpdatesBothBasesAndPhoneEditsSyncBackToMenu()=runTest {
        val remote=remoteFixtureState()
        remote.value=remote.value.copy(capabilities=remote.value.capabilities!!.copy(nativeSettings=true,satelliteProfiles=true),
            nativeProfileValid=true,nativeProfileKey=com.rtbishop.look4sat.core.domain.repository.radioSatelliteProfileKey("TEST","test-transponder"),
            nativeBaseFrequencies=com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies(435150000,145920000),
            nativeProfileGeneration=1,settingsSequence=1,nativeSavedMode=0)
        val tx=FakeRadioController(remote=remote)
        val fixture=Fixture(backgroundScope,tx,FakeRadioController(),radioModel=RadioControlSettings.MODEL_QUANSHENG_UVK1,
            splitMode=true,transport=RadioControlSettings.TRANSPORT_USB)
        fixture.transponder=fixture.transponder.copy(uplinkMode="FM",downlinkMode="FM")
        fixture.service.connectRadios();fixture.startTracking();runCurrent()
        remote.value=remote.value.copy(nativeProfileGeneration=2,settingsSequence=2,nativeSettingsModified=true,
            nativeBaseFrequencies=com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies(435160000,145925000))
        runCurrent()
        assertEquals(145925000L,fixture.service.state.value.txBaseFrequencyHz)
        assertEquals(fixture.position.getDownlinkFreq(435160000),tx.frequencyHz)
        fixture.service.setTxBaseFrequency(145930000);runCurrent()
        assertEquals(145930000L,remote.value.nativeBaseFrequencies!!.txHz)
        assertEquals(145930000L,fixture.service.state.value.txBaseFrequencyHz)
        fixture.close()
    }
    @Test fun fmMetadataDefaultsSurviveConnectionAndApplyModeAndTxCtcss()=runTest {
        val remote=remoteFixtureState()
        remote.value=remote.value.copy(capabilities=remote.value.capabilities!!.copy(nativeSettings=true),settingsSequence=1)
        val tx=FakeRadioController(remote=remote)
        val fixture=Fixture(backgroundScope,tx,FakeRadioController(),radioModel=RadioControlSettings.MODEL_QUANSHENG_UVK1,
            splitMode=true,transport=RadioControlSettings.TRANSPORT_USB)
        fixture.transponder=fixture.transponder.copy(info="Mode V/U FM Voice CTCSS 67.0 Hz",uplinkMode="FM",downlinkMode="FM")
        fixture.service.setTransponder(fixture.transponder);runCurrent()
        fixture.service.connectRadios();runCurrent()
        assertEquals(67.0,fixture.service.state.value.ctcssTone!!,0.0)
        fixture.startTracking();runCurrent()
        assertTrue(tx.operations.contains("split-modes:FM:FM"))
        assertTrue(tx.operations.contains("tone:67.0"))
        fixture.close()
    }

    @Test fun savedNativeProfileForThisTransponderOverridesOnlyItsDefaults()=runTest {
        val remote=remoteFixtureState()
        remote.value=remote.value.copy(capabilities=remote.value.capabilities!!.copy(nativeSettings=true),settingsSequence=1,
            nativeProfileValid=true,nativeProfileKey=com.rtbishop.look4sat.core.domain.repository.radioContextKey("test-transponder"),
            nativeSavedMode=0,nativeSavedTxCtcssHz=88.5,txCtcssHz=88.5)
        val tx=FakeRadioController(remote=remote)
        val fixture=Fixture(backgroundScope,tx,FakeRadioController(),radioModel=RadioControlSettings.MODEL_QUANSHENG_UVK1,
            splitMode=true,transport=RadioControlSettings.TRANSPORT_USB)
        fixture.transponder=fixture.transponder.copy(info="FM CTCSS 67.0 Hz",uplinkMode="FM",downlinkMode="FM")
        fixture.service.setTransponder(fixture.transponder);runCurrent();fixture.service.connectRadios();runCurrent()
        fixture.startTracking();runCurrent()
        assertTrue(tx.operations.contains("tone:88.5"))
        tx.operations.clear()
        fixture.service.setTransponder(fixture.transponder.copy(uuid="other",info="FM Voice"));runCurrent()
        assertNull(fixture.service.state.value.ctcssTone)
        assertTrue(tx.operations.contains("ctcss:false"))
        fixture.close()
    }
    @Test fun calculatorTxEditImmediatelyAppliesAtomicPairWithoutDoubleDoppler() = runTest {
        val tx=FakeRadioController(remote=remoteFixtureState())
        val fixture=Fixture(backgroundScope,tx,FakeRadioController(),radioModel=RadioControlSettings.MODEL_QUANSHENG_UVK1,
            splitMode=true,transport=RadioControlSettings.TRANSPORT_USB)
        fixture.transponder=fixture.transponder.copy(uplinkHigh=146000000,downlinkHigh=435200000,isInverted=true)
        fixture.service.connectRadios();fixture.startTracking();runCurrent()
        val tuning=CalculatorTuning(fixture.transponder.uuid,CalculatorFrequencyAnchor.GROUND_TX,145950000)
        fixture.service.tuneFromCalculator(fixture.transponder,tuning);runCurrent()
        val expected=tuning.frequencies(fixture.transponder,fixture.position,0)!!
        assertEquals(expected.txHz,tx.txFrequencyHz);assertEquals(expected.rxHz,tx.frequencyHz)
        assertEquals(145950000,tx.txFrequencyHz)
        fixture.service.setTxBaseFrequency(145920000);runCurrent()
        assertEquals(fixture.position.getUplinkFreq(145920000),tx.txFrequencyHz)
        fixture.close()
    }

    @Test fun calculatorRxEditDuringCwIsRejectedWithoutStoppingOrQueuing() = runTest {
        val tx=FakeRadioController(remote=remoteFixtureState())
        val fixture=Fixture(backgroundScope,tx,FakeRadioController(),radioModel=RadioControlSettings.MODEL_QUANSHENG_UVK1,
            splitMode=true,transport=RadioControlSettings.TRANSPORT_USB)
        fixture.transponder=fixture.transponder.copy(uplinkHigh=146000000,downlinkHigh=435200000,isInverted=true)
        fixture.service.connectRadios();fixture.startTracking();runCurrent()
        fixture.service.sendCw("CQ TEST",20);runCurrent()
        val originalRx=tx.frequencyHz
        val writes=tx.operations.count { it.startsWith("pair:") }
        val tuning=CalculatorTuning(fixture.transponder.uuid,CalculatorFrequencyAnchor.GROUND_RX,435150000)
        fixture.service.tuneFromCalculator(fixture.transponder,tuning);runCurrent()
        advanceTimeBy(1100);runCurrent()
        assertEquals(RadioTransmitOwner.CW,fixture.service.state.value.transmitOwner)
        assertEquals(writes,tx.operations.count { it.startsWith("pair:") })
        fixture.service.cancelRemoteTransmit();runCurrent()
        assertEquals(originalRx,tx.frequencyHz)
        fixture.close()
    }

    @Test fun physicalPttStateHoldsTrackingAndReleaseWakesItWithoutDisconnect() = runTest {
        val remote=remoteFixtureState()
        val tx=FakeRadioController(remote=remote)
        val fixture=Fixture(backgroundScope,tx,FakeRadioController(),radioModel=RadioControlSettings.MODEL_QUANSHENG_UVK1,
            splitMode=true,transport=RadioControlSettings.TRANSPORT_USB)
        fixture.service.connectRadios();fixture.startTracking();runCurrent()
        remote.value=remote.value.copy(txSource=3);runCurrent()
        val writes=tx.operations.count { it.startsWith("pair:") }
        advanceTimeBy(1100);runCurrent()
        assertEquals(writes,tx.operations.count { it.startsWith("pair:") })
        assertTrue(fixture.service.state.value.txConnected)
        remote.value=remote.value.copy(txSource=0);runCurrent()
        assertTrue(tx.operations.count { it.startsWith("pair:") }>writes)
        assertTrue(fixture.service.state.value.txConnected)
        fixture.close()
    }


    private fun remoteFixtureState() = MutableStateFlow(RemoteRadioState(
        connected = true, rxFrequencyHz = 435100000L, txFrequencyHz = 145900000L,
        capabilities = RemoteRadioCapabilities(10,92,10,45,1500,18000000,1300000000,15,9,true,true,true,true,false,false)
    ))

    @Test fun l4sCwGapsStillHoldBothFrequenciesUntilCancelled() = runTest {
        val remote = remoteFixtureState()
        val tx = FakeRadioController(remote = remote)
        val fixture = Fixture(backgroundScope,tx,FakeRadioController(),
            radioModel = RadioControlSettings.MODEL_QUANSHENG_UVK1, splitMode = true,
            transport = RadioControlSettings.TRANSPORT_USB)
        fixture.service.connectRadios();fixture.startTracking();runCurrent()
        assertEquals("CW",fixture.service.state.value.txMode)
        fixture.service.sendCw("CQ DE TEST",20);runCurrent()
        assertEquals(RadioTransmitOwner.CW,fixture.service.state.value.transmitOwner)
        val writes = tx.operations.count { it.startsWith("pair:") || it.contains("frequency:") }
        remote.value = remote.value.copy(txSource = 0) // key-up gap, message still sending
        advanceTimeBy(1100);runCurrent()
        assertEquals(writes,tx.operations.count { it.startsWith("pair:") || it.contains("frequency:") })
        fixture.service.cancelRemoteTransmit();runCurrent()
        assertEquals(RadioTransmitOwner.NONE,fixture.service.state.value.transmitOwner)
        assertEquals(PttState.OFF,fixture.service.state.value.pttState)
        assertTrue(tx.operations.count { it.startsWith("pair:") || it.contains("frequency:") } > writes)
        fixture.close()
    }

    @Test fun l4sTouchReleaseBeforeQueuedPressNeverKeysRadio() = runTest {
        val tx = FakeRadioController(remote = remoteFixtureState())
        val fixture = Fixture(backgroundScope,tx,FakeRadioController(),
            radioModel = RadioControlSettings.MODEL_QUANSHENG_UVK1, splitMode = true,
            transport = RadioControlSettings.TRANSPORT_USB)
        fixture.transponder = fixture.transponder.copy(uplinkMode = "FM", downlinkMode = "FM")
        fixture.service.connectRadios();fixture.startTracking();runCurrent()
        fixture.service.setVoicePtt(true);fixture.service.setVoicePtt(false);runCurrent()
        assertFalse(tx.operations.contains("ptt:on"))
        fixture.close()
    }

    @Test
    fun ic820RejectsTwoConnectionsToTheSameRadio() = runTest {
        val tx = FakeRadioController()
        val rx = FakeRadioController()
        val fixture = Fixture(backgroundScope, tx, rx, radioModel = RadioControlSettings.MODEL_ICOM_IC820)
        fixture.settings.radioControlSettings.value = fixture.settings.radioControlSettings.value.copy(rxRadioAddress = "TX")
        fixture.service.connectRadios()
        assertTrue(fixture.service.state.value.errorMessage?.contains("separate TX/RX radios") == true)
        assertFalse(tx.isConnected)
        assertFalse(rx.isConnected)
        fixture.close()
    }

    @Test
    fun diagnosticsCaptureConnectionTrackingAndControlledFailureStages() = runTest {
        val diagnostics = ControlDiagnosticsBuffer(nowMillis = { testScheduler.currentTime })
        val fixture = Fixture(
            backgroundScope,
            FakeRadioController(setModeSucceeds = false),
            FakeRadioController(),
            diagnostics = diagnostics
        )

        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()

        val stages = diagnostics.events.value.map { it.stage }
        assertTrue("connect/start" in stages)
        assertTrue("connect/result" in stages)
        assertTrue("tracking/start" in stages)
        assertTrue("tracking/initialization" in stages)
        fixture.close()
    }
    @Test
    fun radioCommandActorSerializesConcurrentCommands() = runTest {
        var active = 0
        var maximumActive = 0
        val actor = RadioCommandActor(backgroundScope) { _, _ -> }

        List(12) {
            async {
                actor.execute {
                    active++
                    maximumActive = maxOf(maximumActive, active)
                    delay(10L)
                    active--
                }
            }
        }.awaitAll()

        assertEquals(1, maximumActive)
    }

    @Test
    fun txCtcssSequenceIsOneActorCommand() = runTest {
        val actor = RadioCommandActor(backgroundScope) { _, _ -> }
        val delegate = FakeRadioController(ctcssStepDelayMillis = 1L)
        val radio = SerialRadioController(delegate, actor)

        val configuring = async { radio.configureTxCtcss(88.5) }
        runCurrent()
        val competing = async { radio.setFrequency(145_900_000L) }

        assertTrue(configuring.await())
        assertTrue(competing.await())
        assertEquals(
            listOf(
                "vfo:tx",
                "tone:88.5",
                "ctcss:true",
                "vfo:rx",
                "frequency:145900000"
            ),
            delegate.operations
        )
    }

    @Test
    fun pttPermitCapturedBeforeEmergencyStopCannotBeRearmed() = runTest {
        val actor = RadioCommandActor(backgroundScope) { _, _ -> }
        val delegate = FakeRadioController()
        val radio = SerialRadioController(delegate, actor)
        val permit = radio.pttSafetyGeneration()

        radio.invalidatePendingPttOn()
        val result = runCatching { radio.pttOnIfGeneration(permit) }

        assertTrue(result.isFailure)
        assertFalse("ptt:on" in delegate.operations)
    }

    @Test
    fun transponderCanBeSelectedBeforeRadioConnection() = runTest {
        val tx = FakeRadioController()
        val rx = FakeRadioController()
        val fixture = Fixture(backgroundScope, tx, rx)

        fixture.service.setTransponder(fixture.transponder)
        runCurrent()

        assertEquals(fixture.transponder.uuid, fixture.service.state.value.selectedTransponder?.uuid)
        assertEquals(fixture.nominalTxHz, fixture.service.state.value.txBaseFrequencyHz)
        assertTrue(tx.operations.isEmpty())
        assertTrue(rx.operations.isEmpty())
        fixture.close()
    }

    @Test
    fun satelliteCapableRadioEnablesDedicatedModeBeforeMainSubSetup() = runTest {
        val radio = FakeRadioController()
        val fixture = Fixture(
            scope = backgroundScope,
            tx = radio,
            rx = FakeRadioController(),
            radioModel = RadioControlSettings.MODEL_ICOM_IC910,
            splitMode = true,
            duplexMode = RadioControlSettings.DUPLEX_MODE_SATELLITE
        )
        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()

        assertEquals(TrackingPhase.READY, fixture.service.state.value.trackingPhase)
        assertTrue(fixture.service.state.value.satelliteMode)
        assertTrue(radio.operations.indexOf("split:true") < radio.operations.indexOf("vfo:rx"))
        assertTrue(radio.operations.indexOf("split:true") < radio.operations.indexOf("vfo:tx"))
        fixture.close()
    }

    @Test
    fun satelliteCapableRadioCanUseOrdinarySplitFallback() = runTest {
        val radio = FakeRadioController()
        val fixture = Fixture(
            scope = backgroundScope,
            tx = radio,
            rx = FakeRadioController(),
            radioModel = RadioControlSettings.MODEL_ICOM_IC9700,
            splitMode = true,
            duplexMode = RadioControlSettings.DUPLEX_MODE_SPLIT
        )
        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()

        assertEquals(TrackingPhase.READY, fixture.service.state.value.trackingPhase)
        assertFalse(fixture.service.state.value.satelliteMode)
        assertTrue(radio.operations.indexOf("split:true") > radio.operations.indexOf("vfo:tx"))
        fixture.close()
    }

    @Test
    fun failedInitializationNeverPublishesReadyOrActive() = runTest {
        val fixture = Fixture(
            backgroundScope,
            FakeRadioController(setModeSucceeds = false),
            FakeRadioController()
        )
        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()

        assertFalse(fixture.service.state.value.isActive)
        assertEquals(TrackingPhase.ERROR, fixture.service.state.value.trackingPhase)
        fixture.close()
    }

    @Test
    fun trackingOrbitCalculationUsesDisciplinedUtc() = runTest {
        var disciplinedNow = 1_800_000_000_000L
        val fixture = Fixture(
            backgroundScope,
            FakeRadioController(),
            FakeRadioController(),
            nowProvider = { disciplinedNow }
        )
        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()
        assertTrue(disciplinedNow in fixture.satelliteRepo.requestedTimes)

        disciplinedNow += 1_000L
        advanceTimeBy(1_000L)
        runCurrent()
        assertTrue(disciplinedNow in fixture.satelliteRepo.requestedTimes)
        fixture.close()
    }

    @Test
    fun dualTrackingDefaultsToNominalUplinkCenter() = runTest {
        val fixture = Fixture(backgroundScope, FakeRadioController(), FakeRadioController())
        fixture.service.connectRadios()
        fixture.startTracking(null)
        runCurrent()
        assertEquals(fixture.nominalTxHz, fixture.service.state.value.txBaseFrequencyHz)
        fixture.close()
    }

    @Test
    fun missingUplinkModeIsDerivedFromInvertedDownlink() = runTest {
        val fixture = Fixture(backgroundScope, FakeRadioController(), FakeRadioController())
        fixture.transponder = fixture.transponder.copy(uplinkMode = null, isInverted = true)
        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()
        assertEquals("LSB", fixture.service.state.value.txMode)
        fixture.close()
    }

    @Test
    fun rxDialTuningKeepsTxIndependentForBothInversionDirections() = runTest {
        for (split in listOf(false, true)) for (inverted in listOf(false, true)) {
            val tx = FakeRadioController()
            val rx = FakeRadioController()
            val fixture = Fixture(backgroundScope, tx, rx,
                radioModel = RadioControlSettings.MODEL_ICOM_IC705, splitMode = split)
            fixture.transponder = fixture.transponder.copy(
                uplinkHigh = fixture.nominalTxHz + 10_000L, isInverted = inverted,
                uplinkMode = if (inverted) "LSB" else "USB")
            fixture.satelliteRepo.positionAt = { fixture.position.copy(distanceRate = 0.0) }
            fixture.settings.setSatelliteOffset(12_345, "1.25")
            fixture.settings.radioControlSettings.value =
                fixture.settings.radioControlSettings.value.copy(dialSettleMillis = 0L)
            fixture.service.connectRadios()
            fixture.startTracking()
            runCurrent()
            val initialRx = (if (inverted) 435_110_000L else 435_100_000L) + 1_250L
            assertEquals(initialRx, fixture.service.state.value.rxFrequencyHz)
            val receiver = if (split) tx else rx
            receiver.frequencyHz += 1_000L
            advanceTimeBy(3_100L)
            runCurrent()
            assertEquals(fixture.nominalTxHz, fixture.service.state.value.txBaseFrequencyHz)
            assertEquals(initialRx + 1_000L, fixture.service.state.value.rxFrequencyHz)
            fixture.settings.setSatelliteOffset(12_345, "-2")
            advanceTimeBy(1_100L)
            runCurrent()
            assertEquals(initialRx + 1_000L - 3_250L, fixture.service.state.value.rxFrequencyHz)
            fixture.close()
        }
    }

    @Test
    fun dialFollowWaitsForSettleAndRejectsCrossBandReadback() = runTest {
        var now = 1_000_000L
        val tx = FakeRadioController()
        val fixture = Fixture(
            backgroundScope,
            tx,
            FakeRadioController(),
            nowProvider = { now },
            radioModel = RadioControlSettings.MODEL_ICOM_IC705,
            splitMode = true
        )
        fixture.settings.radioControlSettings.value = fixture.settings.radioControlSettings.value.copy(
            dialSettleMillis = 1_500L,
            linearDialDeadbandHz = 20L
        )
        fixture.satelliteRepo.positionAt = { fixture.position.copy(distanceRate = 0.0) }
        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()

        tx.txFrequencyHz = 435_100_000L
        now += 1_000L
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(fixture.nominalTxHz, fixture.service.state.value.txBaseFrequencyHz)

        tx.txFrequencyHz = fixture.nominalTxHz + 1_000L
        now += 1_000L
        advanceTimeBy(1_000L)
        runCurrent()
        now += 1_499L
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(fixture.nominalTxHz, fixture.service.state.value.txBaseFrequencyHz)

        now += 1L
        advanceTimeBy(1_000L)
        runCurrent()
        assertEquals(fixture.nominalTxHz + 1_000L, fixture.service.state.value.txBaseFrequencyHz)
        fixture.close()
    }

    @Test
    fun failedFrequencyWriteKeepsLastAcknowledgedFrequency() = runTest {
        val tx = FakeRadioController()
        val fixture = Fixture(backgroundScope, tx, FakeRadioController())
        fixture.service.connectRadios()
        fixture.startTracking()
        runCurrent()
        val previous = fixture.service.state.value.txFrequencyHz
        tx.frequencySucceeds = false
        fixture.service.adjustTxBaseFrequency(1_000L)
        advanceTimeBy(1_100L)
        runCurrent()
        assertEquals(previous, fixture.service.state.value.txFrequencyHz)
        assertTrue(fixture.service.state.value.lastCommandError?.contains("frequency") == true)
        fixture.close()
    }

    private class Fixture(
        scope: kotlinx.coroutines.CoroutineScope,
        private val tx: FakeRadioController,
        private val rx: FakeRadioController,
        nowProvider: () -> Long = System::currentTimeMillis,
        radioModel: String = RadioControlSettings.MODEL_YAESU_FT817,
        splitMode: Boolean = false,
        duplexMode: String = RadioControlSettings.DUPLEX_MODE_SPLIT,
        transport: String = RadioControlSettings.TRANSPORT_USB,
        diagnostics: IControlDiagnostics = NoOpControlDiagnostics
    ) {
        val nominalTxHz = 145_900_000L
        val position = OrbitalPos(elevation = 0.5, distanceRate = 1.2, aboveHorizon = true)
        val settings = FakeSettingsRepo(radioModel, splitMode, duplexMode, transport)
        val satelliteRepo = FakeSatelliteRepo(position)
        private val now = nowProvider()
        val afterPassStart = now + 180_000L
        private val satellite = OrbitalData(
            name = "TEST",
            epoch = 24_100.0,
            meanmo = 15.0,
            eccn = 0.001,
            incl = 51.6,
            raan = 0.0,
            argper = 0.0,
            meanan = 0.0,
            catnum = 12_345,
            bstar = 0.0
        ).getObject()
        private val pass = OrbitalPass(
            aosTime = now - 60_000L,
            losTime = now + 120_000L,
            orbitalObject = satellite
        )
        var transponder = SatRadio(
            uuid = "test-transponder",
            info = "Linear",
            isAlive = true,
            downlinkLow = 435_100_000L,
            downlinkHigh = 435_110_000L,
            downlinkMode = "USB",
            uplinkLow = nominalTxHz,
            uplinkHigh = nominalTxHz,
            uplinkMode = "USB",
            isInverted = false,
            catnum = pass.catNum
        )
        val service = RadioTrackingService(
            appScope = scope,
            bluetoothManager = null,
            satelliteRepo = satelliteRepo,
            settingsRepo = settings,
            nowMillis = nowProvider,
            controllerFactory = { _, address -> if (address == "TX") tx else rx },
            diagnostics = diagnostics
        )

        fun startTracking(base: Long? = nominalTxHz) {
            service.startTracking(pass, transponder, base)
        }

        suspend fun close() {
            service.stopTracking()
            service.disconnectRadios()
        }
    }
}

private class FakeRadioController(
    private val pttOnSucceeds: Boolean = true,
    private val setModeSucceeds: Boolean = true,
    private val ctcssStepDelayMillis: Long = 0L,
    private val remote: MutableStateFlow<RemoteRadioState>? = null,
    private val pttReadbackSupported:Boolean=false
) : IRadioController {
    override val supportsPttReadback get()=pttReadbackSupported
    var physicalPtt:Boolean?=false
    override suspend fun readPttState()=physicalPtt
    override val remoteState get() = remote
    override val supportsAtomicPair get() = remote != null
    override suspend fun updateTrackingBases(bases: com.rtbishop.look4sat.core.domain.repository.RadioBaseFrequencies):Boolean {
        if(remote?.value?.capabilities?.satelliteProfiles==true) {
            operations+="bases:${bases.rxHz}:${bases.txHz}"
            remote.value=remote.value.copy(nativeBaseFrequencies=bases)
        }
        return true
    }
    override suspend fun setTrackingFrequencies(rxHz: Long, txHz: Long?): FrequencyPairResult {
        operations += "pair:$rxHz:$txHz"
        if (remote?.value?.txSource != 0) return FrequencyPairResult.FAILED
        frequencyHz = rxHz; txFrequencyHz = txHz ?: 0
        remote.value = remote.value.copy(rxFrequencyHz = rxHz, txFrequencyHz = txHz, txEnabled = txHz != null)
        return FrequencyPairResult.APPLIED
    }
    override suspend fun startCw(text: String, wpm: Int): Boolean {
        operations += "cw:$text:$wpm"
        remote?.value = remote.value.copy(txSource = 2, cwState = CwTransmitState.SENDING)
        return true
    }
    val operations = mutableListOf<String>()
    var frequencyHz = 0L
    var txFrequencyHz = 0L
    var mode = "USB"
    var modeReadbackOverride: String? = null
    var frequencySucceeds = true
    var frequencyReadbackOffset = 0L
    var beforeReadTx: (suspend () -> Unit)? = null
    var beforeSetFrequency: (suspend () -> Unit)? = null
    override var isConnected: Boolean = false
    var beforeConnect: (suspend () -> Unit)? = null

    override suspend fun connect(): Boolean {
        operations += "connect"
        beforeConnect?.invoke()
        isConnected = true
        return true
    }

    override suspend fun disconnect() {
        operations += "disconnect"
        isConnected = false
    }

    override suspend fun setFrequency(frequencyHz: Long): Boolean {
        beforeSetFrequency?.invoke()
        operations += "frequency:$frequencyHz"
        if (!frequencySucceeds) return false
        this.frequencyHz = frequencyHz
        return true
    }

    override suspend fun setMode(mode: String): Boolean {
        operations += "mode:$mode"
        if (setModeSucceeds) this.mode = mode
        return setModeSucceeds
    }

    override suspend fun setDataMode(enabled: Boolean, baseMode: String): Boolean {
        operations += "data:$enabled:$baseMode"
        return true
    }

    override suspend fun setTxDataMode(enabled: Boolean, baseMode: String): Boolean {
        operations += "tx-data:$enabled:$baseMode"
        return true
    }

    override suspend fun setCtcssMode(enabled: Boolean): Boolean {
        operations += "ctcss:$enabled"
        if (ctcssStepDelayMillis > 0L) delay(ctcssStepDelayMillis)
        return true
    }

    override suspend fun setCtcssTone(toneHz: Double): Boolean {
        operations += "tone:$toneHz"
        if (ctcssStepDelayMillis > 0L) delay(ctcssStepDelayMillis)
        return true
    }
    override suspend fun readFrequencyAndMode(): Pair<Long, String> =
        (frequencyHz + frequencyReadbackOffset) to (modeReadbackOverride ?: mode)
    override suspend fun readTxVfoFrequency(): Long {
        beforeReadTx?.invoke()
        return txFrequencyHz + frequencyReadbackOffset
    }

    override suspend fun setBand(frequencyHz: Long): Boolean {
        operations += "band:$frequencyHz"
        return true
    }

    override suspend fun setVfo(vfoA: Boolean): Boolean {
        operations += "vfo:${if (vfoA) "rx" else "tx"}"
        if (ctcssStepDelayMillis > 0L) delay(ctcssStepDelayMillis)
        return true
    }

    override suspend fun setSplitMode(enabled: Boolean): Boolean {
        operations += "split:$enabled"
        return true
    }

    override suspend fun setSplitModes(rxMode: String?, txMode: String?): Boolean {
        operations += "split-modes:$rxMode:$txMode"
        remote?.value = remote.value.copy(rxMode = rxMode ?: "FM", txMode = txMode ?: "FM")
        return true
    }

    override suspend fun setWorkingFrequency(frequencyHz: Long): Boolean {
        operations += "rx-frequency:$frequencyHz"
        this.frequencyHz = frequencyHz
        return true
    }

    override suspend fun setTxVfoFrequency(frequencyHz: Long): Boolean {
        operations += "tx-frequency:$frequencyHz"
        txFrequencyHz = frequencyHz
        return true
    }

    override suspend fun pttOn(): Boolean {
        operations += "ptt:on"
        if (pttOnSucceeds) remote?.value = remote.value.copy(txSource = 1)
        return pttOnSucceeds
    }

    override suspend fun pttOff(): Boolean {
        operations += "ptt:off"
        remote?.value = remote.value.copy(txSource = 0, cwState = CwTransmitState.CANCELLED)
        return true
    }
}

private class FakeSatelliteRepo(private val position: OrbitalPos) : ISatelliteRepo {
    var positionAt: ((Long) -> OrbitalPos)? = null
    val requestedTimes = mutableListOf<Long>()
    override val satellites = MutableStateFlow<List<OrbitalObject>>(emptyList())
    override val passes = MutableStateFlow<List<OrbitalPass>>(emptyList())
    override val isCalculating = MutableStateFlow(false)
    override val selectedPass = MutableStateFlow(0 to 0L)
    override fun selectPass(catNum: Int, aosTime: Long) = Unit
    override suspend fun initRepository() = Unit
    override suspend fun calculatePasses(
        time: Long,
        hoursAhead: Int,
        minElevation: Double,
        aosStartMinute: Int,
        aosEndMinute: Int,
        invertAosTimeWindow: Boolean,
        modes: List<String>
    ) = Unit

    override suspend fun nominalFrequencies(name: String): com.rtbishop.look4sat.core.domain.model.NominalFrequencies? = null

    override suspend fun getPosition(sat: OrbitalObject, pos: GeoPos, time: Long): OrbitalPos {
        requestedTimes += time
        return (positionAt?.invoke(time) ?: position).copy(time = time)
    }
    override suspend fun getTrack(sat: OrbitalObject, pos: GeoPos, start: Long, end: Long) = emptyList<OrbitalPos>()
    override suspend fun getRadios(
        sat: OrbitalObject,
        pos: GeoPos,
        radios: List<SatRadio>,
        time: Long
    ) = radios
    override suspend fun getRadiosWithId(id: Int) = emptyList<SatRadio>()
    override suspend fun getSatelliteIdsWithModes(modes: List<String>) = emptyList<Int>()
    override suspend fun getSatelliteIdsWithModesAndUplink(modes: List<String>) = emptyList<Int>()
    override suspend fun getSatelliteIdsWithModesAndAmateur(modes: List<String>) = emptyList<Int>()
}

private class FakeSettingsRepo(
    radioModel: String = RadioControlSettings.MODEL_YAESU_FT817,
    splitMode: Boolean = false,
    duplexMode: String = RadioControlSettings.DUPLEX_MODE_SPLIT,
    transport: String = RadioControlSettings.TRANSPORT_USB
) : ISettingsRepo {
    override val appVersionName = "test"
    override val selectedIds = MutableStateFlow<List<Int>>(emptyList())
    override val selectedTypes = MutableStateFlow<List<String>>(emptyList())
    override val passesSettings = MutableStateFlow(
        PassesSettings(hoursAhead = 24, minElevation = 0.0, selectedModes = emptyList())
    )
    override val stationPosition = MutableStateFlow(GeoPos(0.0, 0.0, qthLocator = "AA00"))
    override val databaseState = MutableStateFlow(DatabaseState(0, 0, 0L))
    override val rcSettings = MutableStateFlow(
        RCSettings(false, "", "", "", false, "", "", "", 0L, false, "", "", "", false, "", "")
    )
    override val otherSettings = MutableStateFlow(
        OtherSettings(
            stateOfAutoUpdate = false,
            stateOfSensors = false,
            stateOfSweep = false,
            stateOfUtc = false,
            stateOfLightTheme = false,
            stateOfNightMode = false,
            stateOfMapGrid = false,
            shouldSeeWarning = false,
            shouldSeeWhatsNew = false
        )
    )
    override val dataSourcesSettings = MutableStateFlow(DataSourcesSettings(emptyList(), emptyList()))
    override val dataSourcesStatus = MutableStateFlow<Map<String, Int>>(emptyMap())
    override val radioControlSettings = MutableStateFlow(
        RadioControlSettings(
            enabled = true,
            radioModel = radioModel,
            txRadioAddress = "TX",
            rxRadioAddress = "RX",
            txRadioName = "TX",
            rxRadioName = "RX",
            baudRate = 9_600,
            splitMode = splitMode,
            duplexMode = duplexMode,
            catTransport = transport
        )
    )

    override fun setSelectedIds(ids: List<Int>) = Unit
    override fun setSelectedTypes(types: List<String>) = Unit
    override fun setPassesSettings(settings: PassesSettings) = Unit
    override fun setStationPosition(latitude: Double, longitude: Double, altitude: Double) = true
    override fun setStationPosition() = true
    override fun setStationPosition(locator: String) = true
    override fun getCurrentGrid(): String? = stationPosition.value.qthLocator
    override fun getSatelliteTypesIds(types: List<String>) = emptyList<Int>()
    override fun setSatelliteTypeIds(type: String, ids: List<Int>) = Unit
    override fun updateDatabaseState(state: DatabaseState) { databaseState.value = state }
    override fun updateRCSettings(settings: RCSettings) { rcSettings.value = settings }
    override fun updateOtherSettings(transform: (OtherSettings) -> OtherSettings) {
        otherSettings.value = transform(otherSettings.value)
    }
    override fun updateDataSourcesSettings(settings: DataSourcesSettings) {
        dataSourcesSettings.value = settings
    }
    override fun updateDataSourcesStatus(status: Map<String, Int>) {
        dataSourcesStatus.value = status
    }
    override fun updateRadioControlSettings(settings: RadioControlSettings) {
        radioControlSettings.value = settings
    }
    private val offsets = mutableMapOf<Int, String>()
    override fun getSatelliteOffset(catnum: Int) = offsets[catnum].orEmpty()
    override fun setSatelliteOffset(catnum: Int, offset: String) { offsets[catnum] = offset }
    override fun getSatelliteMode(catnum: Int) = ""
    override fun setSatelliteMode(catnum: Int, mode: String) = Unit
    override fun getAmSatCallsign() = ""
    override fun setAmSatCallsign(callsign: String) = Unit
    override val wavelogUploadSettings = MutableStateFlow(WavelogUploadSettings())
    override fun updateWavelogUploadSettings(settings: WavelogUploadSettings) = Unit
    override fun getWavelogStations(): List<com.rtbishop.look4sat.core.domain.repository.WavelogStationInfo> = emptyList()
    override fun setWavelogStations(stations: List<com.rtbishop.look4sat.core.domain.repository.WavelogStationInfo>) = Unit
    override fun getWavelogSyncCursors(): Map<String, Long> = emptyMap()
    override fun setWavelogSyncCursors(cursors: Map<String, Long>) = Unit
    override fun getWavelogSyncUrl(): String = ""
    override fun setWavelogSyncUrl(url: String) = Unit
    override fun getLastWavelogSyncEpochMs(): Long = 0L
    override fun setLastWavelogSyncEpochMs(value: Long) = Unit
    override fun getLastWavelogFullSyncEpochMs(): Long = 0L
    override fun setLastWavelogFullSyncEpochMs(value: Long) = Unit
    override fun getWorkedGrids() = emptySet<String>()
    override fun setWorkedGrids(grids: Set<String>) = Unit
    override fun getWorkedGridQsos() = emptyMap<String, List<GridQso>>()
    override fun setWorkedGridQsos(qsos: Map<String, List<GridQso>>) = Unit
    override fun getRoamedGrids() = emptySet<String>()
    override fun setRoamedGrids(grids: Set<String>) = Unit
    private var vuccStationIds = emptySet<String>()
    override fun getVuccSelectedStationIds() = vuccStationIds
    override fun setVuccSelectedStationIds(ids: Set<String>) { vuccStationIds = ids }
    override val lotwSettings = MutableStateFlow(LoTWSettings())
    override fun updateLoTWSettings(settings: LoTWSettings) = Unit
    override fun getLastLotwSyncDate() = ""
    override fun setLastLotwSyncDate(date: String) = Unit
    override fun getLastLotwSyncCallsign() = ""
    override fun setLastLotwSyncCallsign(callsign: String) = Unit
    override fun getMarkedGridStations() =
        emptyMap<String, List<com.rtbishop.look4sat.core.domain.model.MarkedStation>>()
    override fun setMarkedGridStations(
        stations: Map<String, List<com.rtbishop.look4sat.core.domain.model.MarkedStation>>
    ) = Unit
}
