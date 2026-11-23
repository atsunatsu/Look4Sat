package com.rtbishop.look4sat.feature.settings

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.rtbishop.look4sat.core.domain.model.RadioControlSettings
import com.rtbishop.look4sat.core.presentation.MainTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "en-w411dp-h891dp-port")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RadioConfigurationSaveTest {
    @Test fun selectingFtx1KeepsTheBluetoothSerialTransport() {
        show(initial.copy(enabled = true, radioModel = RadioControlSettings.MODEL_YAESU_FT817,
            txRadioAddress = "00:11:22:33:44:55",
            catTransport = RadioControlSettings.TRANSPORT_BLUETOOTH))
        compose.onNodeWithText(RadioControlSettings.MODEL_YAESU_FTX1).performScrollTo().performClick()
        compose.onNodeWithText("Accept").performClick()
        compose.runOnIdle {
            assertEquals(RadioControlSettings.TRANSPORT_BLUETOOTH, saved.single().catTransport)
            assertEquals(RadioControlSettings.MODEL_YAESU_FTX1, saved.single().radioModel)
        }
    }
    @Test fun usbPttSelectionIsSavedIndependentlyOfCatHandshake() {
        show(initial.copy(catTransport = RadioControlSettings.TRANSPORT_USB))
        compose.onNodeWithText("USB CAT port").performScrollTo().performClick()
        compose.onNodeWithText("Accept").performClick()
        compose.runOnIdle {
            val result = saved.single()
            assertTrue(result.usbPtt.enabled)
            assertEquals(com.rtbishop.look4sat.core.domain.model.UsbPttPort.CAT, result.usbPtt.port)
            assertFalse(result.catRts)
            assertFalse(result.catDtr)
        }
    }
    @get:Rule val compose = createComposeRule()
    private val saved = mutableListOf<RadioControlSettings>()
    private var dismissed = false
    private val initial = RadioControlSettings(false, RadioControlSettings.MODEL_ICOM_IC820, "", "", "", "", 1200)
    private fun show(settings: RadioControlSettings) {
        compose.setContent { MainTheme { RadioControlDialog(settings, { dismissed = true }, saved::add) } }
    }

    @Test fun badTcpConfigurationOffersModifyOrIgnoreAndSaveInsteadOfSilentlyRejectingAccept() {
        show(initial.copy(enabled = true, radioModel = RadioControlSettings.MODEL_ICOM_IC820,
            catTransport = "TCP", txRadioAddress = "bad-host", civAddress = 0x42))
        compose.onNodeWithText("Accept").assertIsEnabled().performClick()
        compose.onNodeWithText("Radio configuration error").assertIsDisplayed()
        compose.onNodeWithText("Modify").performClick()
        compose.runOnIdle { assertTrue(saved.isEmpty()); assertFalse(dismissed) }
        compose.onNodeWithText("Accept").performClick()
        compose.onNodeWithText("Ignore and save").performClick()
        compose.runOnIdle { assertEquals("bad-host", saved.single().txRadioAddress); assertTrue(dismissed) }
    }

    @Test fun disabledCatSavesDespiteMissingUsbDevice() {
        show(initial.copy(enabled = false, radioModel = RadioControlSettings.MODEL_QUANSHENG_UVK1,
            catTransport = "USB"))
        compose.onNodeWithText("Accept").performClick()
        compose.runOnIdle { assertFalse(saved.single().enabled); assertTrue(dismissed) }
    }

    @Test fun ic820AndLegacyYaesuExposeSingleRadioModeWithoutClaimingAutomaticSplitCapability() {
        show(initial.copy(enabled = true, radioModel = RadioControlSettings.MODEL_ICOM_IC820,
            catTransport = "USB", splitMode = true))
        compose.onNodeWithText("Single-radio control").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Preset the radio’s TX/RX mode on the radio. Software does not enable split or satellite mode.")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Satellite profiles").assertDoesNotExist()
    }
}
