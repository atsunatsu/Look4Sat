package com.rtbishop.look4sat.feature.radar

import org.junit.Assert.*
import org.junit.Test

class CwConsoleStateTest {
    @Test fun normalHeaderKeepsContactsLeftButSmallWindowsAndLargeFontsRemainReadable() {
        assertFalse(cwConsoleHeaderStacked(336f, 1f))
        assertTrue(cwConsoleHeaderStacked(280f, 1f))
        assertTrue(cwConsoleHeaderStacked(336f, 1.8f))
    }
    @Test fun closingOnlyCancelsCwNotAnotherTransmitOwner() {
        val idle = RadioControlSubState()
        assertFalse(idle.shouldStopConsoleCw())
        for (owner in listOf(com.rtbishop.look4sat.core.domain.repository.RadioTransmitOwner.VOICE)) {
            assertFalse(idle.copy(transmitOwner = owner).shouldStopConsoleCw())
            assertFalse(idle.copy(transmitOwner = owner,
                remoteRadio = com.rtbishop.look4sat.core.domain.repository.RemoteRadioState(txSource = 2)).shouldStopConsoleCw())
        }
        assertTrue(idle.copy(transmitOwner = com.rtbishop.look4sat.core.domain.repository.RadioTransmitOwner.CW).shouldStopConsoleCw())
        assertTrue(idle.copy(remoteRadio = com.rtbishop.look4sat.core.domain.repository.RemoteRadioState(txSource = 2)).shouldStopConsoleCw())
    }

    @Test fun narrowScreensAndLargeFontsUseTwoColumnsWithoutLosingAnyPreset() {
        assertEquals(4, cwConsolePresetColumns(400f, 1f))
        assertEquals(2, cwConsolePresetColumns(320f, 1f))
        assertEquals(2, cwConsolePresetColumns(400f, 1.8f))
        for (columns in listOf(2, 4)) {
            assertEquals((0..3).toList(), (0 until 4 step columns).flatMap { (it until it + columns).toList() })
        }
    }
    @Test fun callsignCandidatesKeepOffsetsAndPortablePrefixesAndSuffixes() {
        val text = "CQ DE bg5jsu/p DL/BA7OPF TNX 599 PM01AB RR73 73"
        val candidates = cwCallsignCandidates(text)
        assertEquals(listOf("BG5JSU/P", "DL/BA7OPF"), candidates.map { it.callsign })
        candidates.forEach { assertEquals(it.callsign, text.substring(it.start, it.end).uppercase()) }
    }

    @Test fun punctuationDoesNotBecomePartOfTheSharedCallsign() {
        assertEquals(listOf("F4HWN", "BA7OPF"), cwCallsignCandidates("(F4HWN)? BA7OPF,").map { it.callsign })
    }

    @Test fun ordinaryWordsReportsAndGridLocatorsAreNotCallsignCandidates() {
        assertTrue(cwCallsignCandidates("CQ DE TNX 599 73 RR73 PM01 PM01AB").isEmpty())
    }

    @Test fun closingPreservesTheDraftAndANewSessionUsesDefaults() {
        val open = CwConsoleState(true, CwConsoleDraft("CQ DE BA7OPF", "BA7OPF/P", "579"))
        assertEquals(open.draft, open.copy(visible = false).draft)
        assertEquals(CwConsoleDraft(), CwConsoleState().draft)
    }

    @Test fun defaultCwCallsignFollowsTheActiveUploadDestination() {
        val log = LogUiState(certificateCallsign = "BA7OPF", wavelogCallsign = "BH3HCF", wavelogMode = true)
        assertEquals("BH3HCF", CwConsoleDraft().myCallsign ?: log.ownCallsign)
        assertEquals("BA7OPF", CwConsoleDraft().myCallsign ?: log.copy(wavelogMode = false).ownCallsign)
        assertEquals("BH3HCF/P", CwConsoleDraft(myCallsign = "BH3HCF/P").myCallsign ?: log.ownCallsign)
    }

    @Test fun ownCallsignOverrideCanBeExplicitlyClearedWithoutChangingTheSource() {
        val source = "BA7OPF"
        assertEquals(source, CwConsoleDraft().myCallsign ?: source)
        assertEquals("", CwConsoleDraft(myCallsign = "").myCallsign ?: source)
        assertEquals("BA7OPF/P", CwConsoleDraft(myCallsign = "BA7OPF/P").myCallsign ?: source)
        assertEquals("BA7OPF", source)
    }
}
