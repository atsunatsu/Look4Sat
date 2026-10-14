package com.rtbishop.look4sat.feature.radar

import com.rtbishop.look4sat.core.domain.repository.PttState
import com.rtbishop.look4sat.core.domain.repository.RadioTransmitOwner
import com.rtbishop.look4sat.core.domain.repository.RemoteRadioCapabilities
import com.rtbishop.look4sat.core.domain.repository.RemoteRadioState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class L4sCwAvailabilityTest {
    @Test fun legacyModelsUseTheSameFrequencyEditingLock() {
        val legacy=RadioControlSubState(isL4sModel=false,isTracking=true,isReady=true)
        assertFalse(legacy.frequencyEditingLocked)
        assertTrue(legacy.copy(serviceEditingLocked=true).frequencyEditingLocked)
        assertTrue(legacy.copy(pttState=PttState.ARMING).frequencyEditingLocked)
        assertTrue(legacy.copy(pttState=PttState.ON).frequencyEditingLocked)
        assertTrue(legacy.copy(transmitOwner=RadioTransmitOwner.FT4).frequencyEditingLocked)
    }
    private val capabilities = RemoteRadioCapabilities(10,92,10,45,1500,18000000,1300000000,15,9,
        true,true,true,true,false,false)
    private val ready = RadioControlSubState(isL4sModel=true, isReady=true,
        txPanel=RadioPanelState("TX",mode="CW"),
        remoteRadio=RemoteRadioState(connected=true,capabilities=capabilities,txEnabled=true))

    @Test fun transmissionNeedsConnectedReadyCwRadioAndConfirmedPttOff() {
        assertTrue(ready.canSendL4sCw())
        assertFalse(ready.copy(remoteRadio=null).canSendL4sCw())
        assertFalse(ready.copy(remoteRadio=ready.remoteRadio!!.copy(connected=false)).canSendL4sCw())
        assertFalse(ready.copy(isReady=false).canSendL4sCw())
        assertFalse(ready.copy(remoteRadio=ready.remoteRadio!!.copy(txEnabled=false)).canSendL4sCw())
        assertFalse(ready.copy(txPanel=RadioPanelState("TX",mode="FM")).canSendL4sCw())
        assertFalse(ready.copy(pttState=PttState.ERROR).canSendL4sCw())
        assertFalse(ready.copy(remoteRadio=ready.remoteRadio!!.copy(txSource=3)).canSendL4sCw())
    }

    @Test fun noFirmwareCapabilityOrAnyTransmitOwnerBlocksSend() {
        assertFalse(ready.copy(remoteRadio=ready.remoteRadio!!.copy(capabilities=null)).canSendL4sCw())
        assertFalse(ready.copy(remoteRadio=ready.remoteRadio!!.copy(capabilities=capabilities.copy(cwText=false))).canSendL4sCw())
        for(owner in listOf(RadioTransmitOwner.VOICE,RadioTransmitOwner.CW,RadioTransmitOwner.FT4)) {
            assertFalse(ready.copy(transmitOwner=owner).canSendL4sCw())
        }
    }
}
