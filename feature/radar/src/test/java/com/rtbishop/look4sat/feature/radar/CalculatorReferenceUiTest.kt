package com.rtbishop.look4sat.feature.radar

import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.rtbishop.look4sat.core.domain.model.SatRadio
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import com.rtbishop.look4sat.core.domain.utility.CalculatorFrequencyAnchor
import com.rtbishop.look4sat.core.domain.utility.CalculatorReference
import com.rtbishop.look4sat.core.domain.utility.CalculatorTuning
import com.rtbishop.look4sat.core.domain.utility.frequencies
import com.rtbishop.look4sat.core.presentation.MainTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[34],qualifiers="en-w411dp-h891dp-port")
class CalculatorReferenceUiTest {
    @get:Rule val compose=createComposeRule()
    private val radio=SatRadio("linear","Linear Transponder",true,435000000,435100000,"USB",
        145900000,146000000,"USB",true,12345)

    private fun exercise(nominal: Boolean) {
        val reference=mutableStateOf(CalculatorReference(145962000,435057000,2500))
        val position=mutableStateOf(OrbitalPos())
        val tunings=mutableListOf<CalculatorTuning>()
        var pair=0L to 0L
        compose.setContent {
            MainTheme(isDarkTheme=nominal) {
                DopplerFrequencyCalculator(radio,position.value,offsetKHz="2.5",
                    initialTxBaseHz=reference.value.txHz,reference=reference.value,
                    displayNominalFrequencies=nominal,onFrequencyChange={tx,rx->pair=tx to rx},
                    onTuningChange={tunings+=it})
            }
        }
        val format=if(nominal) "%.4f" else "%.6f"
        fun assertFrequency(tag: String, hz: Long) {
            compose.onNodeWithTag(tag).assertTextEquals(String.format(java.util.Locale.ENGLISH,format,hz/1e6))
        }
        assertFrequency("calculatorTxFrequency",145962000)
        assertFrequency("calculatorRxFrequency",435057000)
        compose.runOnIdle { reference.value=CalculatorReference(145965000,435059000,2500) }
        assertFrequency("calculatorTxFrequency",145965000)
        assertFrequency("calculatorRxFrequency",435059000)
        compose.runOnIdle { assertTrue(tunings.isEmpty()) }
        compose.onAllNodesWithText("+").onFirst().performClick()
        compose.runOnIdle {
            val tuning=tunings.single()
            assertEquals(reference.value,tuning.reference)
            if(nominal) assertEquals(CalculatorFrequencyAnchor.SATELLITE,tuning.anchor)
            val expected=tuning.frequencies(radio,position.value,2500)!!
            assertEquals(expected.txHz,pair.first)
            assertTrue(kotlin.math.abs(expected.rxHz-pair.second)<=2)
        }
        if(nominal) {
            val chosen=pair.first
            compose.runOnIdle { position.value=OrbitalPos(distanceRate=7.0) }
            assertFrequency("calculatorTxFrequency",chosen)
            compose.waitUntil(timeoutMillis=3000) {
                pair.first==tunings.single().frequencies(radio,position.value,2500)!!.txHz
            }
            compose.runOnIdle {
                val expected=tunings.single().frequencies(radio,position.value,2500)!!
                assertEquals(expected.txHz,pair.first)
                assertTrue(kotlin.math.abs(expected.rxHz-pair.second)<=2)
            }
        }
    }

    @Test fun trackingCalculatorUpdatesBothReferencesWithoutAutomaticallyRetuning()=exercise(false)
    @Test fun cwCalculatorUsesTheSameReferencesAndKeepsNominalTuningAcrossDopplerChanges()=exercise(true)

    @Test fun cwStepSelectorSupportsQuarterKilohertzAndSnapsTheVisibleFrequency() {
        val step = mutableLongStateOf(1_000L)
        val reference = CalculatorReference(145962000, 435057000)
        compose.setContent {
            MainTheme(isDarkTheme = true) {
                DopplerFrequencyCalculator(
                    radio, OrbitalPos(), initialTxBaseHz = reference.txHz,
                    reference = reference, displayNominalFrequencies = true,
                    frequencyStepHz = step.longValue,
                    onFrequencyStepChange = { step.longValue = it }
                )
            }
        }
        compose.onNodeWithTag("calculatorTxFrequency").assertTextEquals("145.962")
        compose.onNodeWithTag("cwCalculatorStepSelector").performClick()
        compose.onNodeWithTag("cwCalculatorStepOption250").performClick()
        compose.onNodeWithTag("calculatorTxFrequency").assertTextEquals("145.96200")
        compose.onAllNodesWithText("+").onFirst().performClick()
        compose.onNodeWithTag("calculatorTxFrequency").assertTextEquals("145.96225")
        compose.onNodeWithTag("cwCalculatorStepSelector").performClick()
        compose.onNodeWithTag("cwCalculatorStepOption5000").performClick()
        compose.onNodeWithTag("calculatorTxFrequency").assertTextEquals("145.960")
        compose.runOnIdle { assertEquals(5_000L, step.longValue) }
    }

    @Test fun cwOffsetStepperChangesOffsetAndRecalculatesTheDownlink() {
        val offset = mutableStateOf("2.5")
        val pair = mutableStateOf(0L to 0L)
        compose.setContent {
            MainTheme(isDarkTheme = true) {
                DopplerFrequencyCalculator(
                    radio, OrbitalPos(),
                    offsetKHz = offset.value,
                    onOffsetChange = { offset.value = it },
                    initialTxBaseHz = 145962000,
                    reference = CalculatorReference(145962000, 435057000, 2500),
                    displayNominalFrequencies = true,
                    onFrequencyChange = { tx, rx -> pair.value = tx to rx },
                    onFrequencyStepChange = {}
                )
            }
        }
        compose.onNodeWithTag("cwCalculatorOffsetInput").assertTextEquals("2.5")
        compose.onNodeWithTag("cwCalculatorOffsetIncrease").performClick()
        compose.onNodeWithTag("cwCalculatorOffsetInput").assertTextEquals("2.6")
        compose.onNodeWithTag("calculatorRxFrequency").assertTextEquals("435.0571")
        compose.waitUntil(timeoutMillis = 3_000) { pair.value.second == 435057100L }
        compose.runOnIdle { assertEquals(435057100L, pair.value.second) }

        compose.onNodeWithTag("cwCalculatorOffsetDecrease").performClick()
        compose.onNodeWithTag("cwCalculatorOffsetInput").assertTextEquals("2.5")
        compose.onNodeWithTag("calculatorRxFrequency").assertTextEquals("435.0570")
        compose.waitUntil(timeoutMillis = 3_000) { pair.value.second == 435057000L }
    }

    @Test fun offsetStepperUsesExactTenthKilohertzStepsAndClampsAtTheLimits() {
        assertEquals("0.1", adjustCalculatorOffset("", increase = true))
        assertEquals("-0.1", adjustCalculatorOffset("", increase = false))
        assertEquals("2.6", adjustCalculatorOffset("2.5", increase = true))
        assertEquals("2.4", adjustCalculatorOffset("2.5", increase = false))
        assertEquals("10000", adjustCalculatorOffset("9999.9", increase = true))
        assertEquals("-10000", adjustCalculatorOffset("-9999.9", increase = false))
    }

    @Test fun calculatorStepRoundingSupportsEveryCwStep() {
        for ((step, _) in CW_CALCULATOR_FREQUENCY_STEPS) {
            val snapped = snapCalculatorFrequency(145_962_310L, step)
            assertEquals(0L, snapped % step)
            assertTrue(kotlin.math.abs(snapped - 145_962_310L) <= step / 2)
        }
    }

    @Test fun reopeningTrackingCalculatorAfterEditingBasesDoesNotRestoreTheOldFrequencies() {
        val visible=mutableStateOf(true)
        val reference=mutableStateOf(CalculatorReference(145962000,435057000))
        compose.setContent {
            val holder=rememberSaveableStateHolder()
            MainTheme(isDarkTheme=false) {
                if(visible.value) holder.SaveableStateProvider("calculator") {
                    DopplerFrequencyCalculator(radio,OrbitalPos(),reference=reference.value,
                        initialTxBaseHz=reference.value.txHz)
                }
            }
        }
        compose.onNodeWithTag("calculatorTxFrequency").assertTextEquals("145.962000")
        compose.onAllNodesWithText("+").onFirst().performClick()
        compose.onNodeWithTag("calculatorTxFrequency").assertTextEquals("145.963000")
        compose.runOnIdle { visible.value=false }
        compose.runOnIdle {
            reference.value=CalculatorReference(145965000,435059000)
            visible.value=true
        }
        compose.onNodeWithTag("calculatorTxFrequency").assertTextEquals("145.965000")
        compose.onNodeWithTag("calculatorRxFrequency").assertTextEquals("435.059000")
    }
}
