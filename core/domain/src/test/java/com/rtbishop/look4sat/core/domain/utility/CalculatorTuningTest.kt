package com.rtbishop.look4sat.core.domain.utility

import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import org.junit.Assert.*
import org.junit.Test

class CalculatorTuningTest {
    private val transponder = SatRadio(uuid="linear",info="Linear Transponder",isAlive=true,
        downlinkLow=435000000,downlinkHigh=435100000,downlinkMode="USB",
        uplinkLow=145900000,uplinkHigh=146000000,uplinkMode="USB",isInverted=true,catnum=12345)

    @Test fun groundTxIsNotDopplerCorrectedTwice() {
        val tuning=CalculatorTuning("linear",CalculatorFrequencyAnchor.GROUND_TX,145950000)
        for(rate in listOf(-7.0,0.0,7.0)) {
            val position=OrbitalPos(distanceRate=rate)
            val pair=tuning.frequencies(transponder,position,2500)!!
            assertEquals(145950000,pair.txHz)
            assertEquals(DopplerFrequencyCalculator.computeDownlinkFromUplinkWithOffset(145950000,transponder,position,2500),pair.rxHz)
        }
    }

    @Test fun groundRxKeepsReceiverFixedAndTracksInvertedUplink() {
        val tuning=CalculatorTuning("linear",CalculatorFrequencyAnchor.GROUND_RX,435050000)
        for(rate in listOf(-7.0,0.0,7.0)) {
            val position=OrbitalPos(distanceRate=rate)
            val pair=tuning.frequencies(transponder,position,-1500)!!
            assertEquals(435050000,pair.rxHz)
            assertEquals(DopplerFrequencyCalculator.computeUplinkFromDownlinkWithOffset(435050000,transponder,position,-1500),pair.txHz)
        }
    }

    @Test fun passbandAnchorUsesFreshTimeEvenWhenCalculatorPageIsClosed() {
        val tuning=CalculatorTuning("linear",CalculatorFrequencyAnchor.SATELLITE,145940000)
        val first=tuning.frequencies(transponder,OrbitalPos(distanceRate=-3.0),0)!!
        val later=tuning.frequencies(transponder,OrbitalPos(distanceRate=3.0),0)!!
        assertEquals(145940000,first.nominalTxHz)
        assertEquals(145940000,later.nominalTxHz)
        assertNotEquals(first.txHz,later.txHz)
        assertNotEquals(first.rxHz,later.rxHz)
    }

    @Test fun invalidOrDifferentTransponderTuningCannotApply() {
        assertNull(CalculatorTuning("other",CalculatorFrequencyAnchor.GROUND_TX,145950000).frequencies(transponder,OrbitalPos(),0))
        assertNull(CalculatorTuning("linear",CalculatorFrequencyAnchor.GROUND_TX,0).frequencies(transponder,OrbitalPos(),0))
        assertNull(CalculatorTuning("linear",CalculatorFrequencyAnchor.SATELLITE,145950000)
            .frequencies(transponder.copy(uplinkHigh=transponder.uplinkLow),OrbitalPos(),0))
    }

    @Test fun tuningOutsideTheMovingPassbandIsClamped() {
        val pair=CalculatorTuning("linear",CalculatorFrequencyAnchor.GROUND_RX,1).frequencies(transponder,OrbitalPos(),0)!!
        assertEquals(435000000,pair.rxHz)
        assertEquals(146000000,pair.txHz)
    }
}
