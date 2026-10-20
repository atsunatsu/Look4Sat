package com.rtbishop.look4sat.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class N76OptionsTest {
    @Test fun intervalEditorAcceptsOnlySupportedQuarterSecondSteps() {
        assertEquals(250L, n76PollMillis("250"))
        assertEquals(1000L, n76PollMillis("1000"))
        assertEquals(15000L, n76PollMillis("15000"))
        listOf("", "abc", "0", "249", "251", "15001", "-1000", "1.5").forEach { assertNull(n76PollMillis(it)) }
    }
}
