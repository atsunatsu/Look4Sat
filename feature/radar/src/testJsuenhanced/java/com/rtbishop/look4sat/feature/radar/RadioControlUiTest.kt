package com.rtbishop.look4sat.feature.radar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioControlUiTest {
    @Test fun frequenciesUseUnambiguousMhzWithAllSixDecimalPlaces() {
        assertEquals("145.950000", referenceFrequencyText(145_950_000L))
        assertEquals("435.850010", referenceFrequencyText(435_850_010L))
        assertEquals("0.000001", referenceFrequencyText(1L))
        assertEquals("9999.999999", referenceFrequencyText(9_999_999_999L))
    }

    @Test fun txShortcutDoesNotChangeRxOrTheOriginalDraft() {
        val draft = ReferenceFrequencyDraft("435.850000", "145.950000")
        val adjusted = draft.adjusted(ReferenceTarget.TX, 1_000L, false)
        assertEquals("435.850000", adjusted?.rx)
        assertEquals("145.951000", adjusted?.tx)
        assertEquals("145.950000", draft.tx)
    }

    @Test fun rxShortcutDoesNotChangeTx() {
        val draft = ReferenceFrequencyDraft("435.850000", "145.950000")
        val adjusted = draft.adjusted(ReferenceTarget.RX, -100L, true)
        assertEquals("435.849900", adjusted?.rx)
        assertEquals(draft.tx, adjusted?.tx)
    }

    @Test fun referenceParsingKeepsExistingLegacyAndL4sLimits() {
        assertEquals(1L, parseReferenceFrequency("0.000001", false))
        assertEquals(1_300_000_000L, parseReferenceFrequency("1300", true))
        assertNull(parseReferenceFrequency("17.999999", true))
        assertNull(parseReferenceFrequency("1300.000001", true))
        assertNull(parseReferenceFrequency("10000", false))
        assertNull(parseReferenceFrequency("145.9500001", false))
        assertNull(parseReferenceFrequency("", false))
        assertNull(parseReferenceFrequency("bad", false))
    }

    @Test fun shortcutsRejectInvalidOrOutOfRangeDrafts() {
        assertNull(ReferenceFrequencyDraft("bad", "145.950000").adjusted(ReferenceTarget.RX, 100L, false))
        assertNull(ReferenceFrequencyDraft("18.000000", "145.950000").adjusted(ReferenceTarget.RX, -100L, true))
        assertNull(ReferenceFrequencyDraft("435.850000", "9999.999999").adjusted(ReferenceTarget.TX, 100L, false))
    }

    @Test fun receiveOnlySubmissionNeverReusesAHiddenTxValue() {
        val draft = ReferenceFrequencyDraft("435.850000", "old or invalid TX")
        val bases = draft.bases(false, txEnabled = false)
        assertEquals(435_850_000L, bases?.rxHz)
        assertNull(bases?.txHz)
        assertNull(draft.bases(false, txEnabled = true))
    }

    @Test fun bothReferenceFieldsMustBeValidBeforePairSubmission() {
        val draft = ReferenceFrequencyDraft("435.850000", "145.950000")
        assertEquals(435_850_000L, draft.bases(false, true)?.rxHz)
        assertEquals(145_950_000L, draft.bases(false, true)?.txHz)
        assertNull(draft.copy(rx = "invalid").bases(false, true))
        assertNull(draft.copy(tx = "").bases(false, true))
    }

    @Test fun smallWindowsAndLargeFontsUseTwoShortcutColumnsAndStackActions() {
        assertEquals(3, referenceAdjustmentColumns(360f, 1f))
        assertEquals(2, referenceAdjustmentColumns(240f, 1f))
        assertEquals(2, referenceAdjustmentColumns(360f, 1.5f))
        assertFalse(stackRadioActionButtons(360f, 1f))
        assertTrue(stackRadioActionButtons(240f, 1f))
        assertTrue(stackRadioActionButtons(360f, 1.5f))
    }

    @Test fun runtimeModeTakesPriorityOverTheTransponderDefaultForToneVisibility() {
        assertFalse(showTxToneControl(true, "USB", "FM"))
        assertTrue(showTxToneControl(true, "NFM", "USB"))
        assertTrue(showTxToneControl(true, "FM-N", "USB"))
        assertTrue(showTxToneControl(true, null, "FM"))
        assertTrue(showTxToneControl(true, "", "FMN"))
        assertFalse(showTxToneControl(false, "FM", "FM"))
        assertFalse(showTxToneControl(true, null, "CW"))
    }
}
