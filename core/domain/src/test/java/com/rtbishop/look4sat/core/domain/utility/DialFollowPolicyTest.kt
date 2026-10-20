package com.rtbishop.look4sat.core.domain.utility

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DialFollowPolicyTest {

    @Test
    fun ignoresDopplerSizedReadbackAndRejectsTheOtherBand() {
        val withinDeadband = DialFollowPolicy.update(
            state = DialFollowState(),
            leg = DialLeg.TX,
            observedFrequencyHz = 145_900_019L,
            commandedFrequencyHz = 145_900_000L,
            nowMillis = 1_000L,
            deadbandHz = 20L,
            settleMillis = 1_500L
        )
        assertNull(withinDeadband.state.activeLeg)

        val wrongBand = DialFollowPolicy.update(
            state = DialFollowState(),
            leg = DialLeg.TX,
            observedFrequencyHz = 435_100_000L,
            commandedFrequencyHz = 145_900_000L,
            nowMillis = 1_000L,
            deadbandHz = 20L,
            settleMillis = 1_500L
        )
        assertEquals(DialFollowRejection.WRONG_BAND, wrongBand.rejection)
        assertNull(wrongBand.state.activeLeg)
    }

    @Test
    fun acceptsOnlyAfterTheConfiguredStableWindow() {
        val started = DialFollowPolicy.update(
            state = DialFollowState(),
            leg = DialLeg.RX,
            observedFrequencyHz = 435_101_000L,
            commandedFrequencyHz = 435_100_000L,
            nowMillis = 1_000L,
            deadbandHz = 20L,
            settleMillis = 1_500L
        )
        val stillSettling = DialFollowPolicy.update(
            state = started.state,
            leg = DialLeg.RX,
            observedFrequencyHz = 435_101_005L,
            commandedFrequencyHz = 435_100_000L,
            nowMillis = 2_499L,
            deadbandHz = 20L,
            settleMillis = 1_500L
        )
        assertNull(stillSettling.acceptedFrequencyHz)

        val accepted = DialFollowPolicy.update(
            state = stillSettling.state,
            leg = DialLeg.RX,
            observedFrequencyHz = 435_101_004L,
            commandedFrequencyHz = 435_100_000L,
            nowMillis = 2_500L,
            deadbandHz = 20L,
            settleMillis = 1_500L
        )
        assertEquals(DialLeg.RX, accepted.acceptedLeg)
        assertEquals(435_101_004L, accepted.acceptedFrequencyHz)
        assertNull(accepted.state.activeLeg)
    }

    @Test
    fun movingDialRestartsTheSettleWindow() {
        val started = DialFollowPolicy.update(
            DialFollowState(), DialLeg.TX, 145_901_000L, 145_900_000L,
            nowMillis = 1_000L, deadbandHz = 20L, settleMillis = 1_500L
        )
        val moved = DialFollowPolicy.update(
            started.state, DialLeg.TX, 145_902_000L, 145_900_000L,
            nowMillis = 2_000L, deadbandHz = 20L, settleMillis = 1_500L
        )
        val tooSoon = DialFollowPolicy.update(
            moved.state, DialLeg.TX, 145_902_000L, 145_900_000L,
            nowMillis = 3_000L, deadbandHz = 20L, settleMillis = 1_500L
        )
        assertNull(tooSoon.acceptedFrequencyHz)
        assertEquals(2_000L, tooSoon.state.stableSinceMillis)
    }

    @Test
    fun zeroSettleAcceptsTheFirstPlausibleManualMove() {
        val result = DialFollowPolicy.update(
            DialFollowState(), DialLeg.TX, 145_901_000L, 145_900_000L,
            nowMillis = 1_000L, deadbandHz = 20L, settleMillis = 0L
        )
        assertEquals(145_901_000L, result.acceptedFrequencyHz)
    }
}
