package com.rtbishop.look4sat.feature.settings

import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.SaverScope
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UsbPermissionRequestTest {
    @Test fun pttGrantIsBoundToItsOwnPortSelectionAndRejectsCatSelectionChanges() {
        val ptt = PendingUsbSelection("ptt", "PTT", "9:2:3:100:200", "IC-820", "token")
        assertTrue(ptt.accepts("token", "ptt", "IC-820", true))
        assertFalse(ptt.accepts("token", "tx", "IC-820", true))
        assertFalse(ptt.accepts("previous", "ptt", "IC-820", true))
    }
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(UsbManager::class.java)
    private val device = Shadow.newInstanceOf(UsbDevice::class.java).apply {
        ReflectionHelpers.setField(this, "mName", "/dev/bus/usb/001/002")
        ReflectionHelpers.setField(this, "mVendorId", 0x36b7)
        ReflectionHelpers.setField(this, "mProductId", 0xffff)
    }
    private fun selection() = PendingUsbSelection("tx", "UV-K1",
        "${device.deviceId}:0:1:${device.vendorId}:${device.productId}", "UV-K1")

    @Test fun mutablePackageScopedCallbackReceivesSystemGrantExtrasOnFirstRequest() {
        val selection = selection()
        val pending = usbPermissionIntent(context, "${context.packageName}.USB_CAT_PERMISSION", selection)
        val shadow = shadowOf(pending)
        assertFalse(shadow.isImmutable)
        assertEquals(context.packageName, shadow.savedIntent.`package`)
        assertEquals(selection.token, shadow.savedIntent.data?.lastPathSegment)
        var granted = false
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(context, receiver,
            android.content.IntentFilter(shadow.savedIntent.action).apply { addDataScheme("look4sat-usb") },
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            pending.send(context, 0, Intent().putExtra(UsbManager.EXTRA_PERMISSION_GRANTED, true))
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertTrue(granted)
        } finally { context.unregisterReceiver(receiver); pending.cancel() }
    }

    @Test fun firstGrantCompletesSelectionWithoutASecondDeviceClick() {
        val selection = selection()
        shadowOf(manager).addOrUpdateUsbDevice(device, false)
        assertNull(authorizedUsbDevice(manager, selection))
        shadowOf(manager).addOrUpdateUsbDevice(device, true)
        assertEquals(device, authorizedUsbDevice(manager, selection))
    }

    @Test fun denialAndDetachmentNeverApplyASelection() {
        val selection = selection()
        shadowOf(manager).addOrUpdateUsbDevice(device, false)
        assertNull(authorizedUsbDevice(manager, selection))
        shadowOf(manager).addOrUpdateUsbDevice(device, true)
        shadowOf(manager).removeUsbDevice(device)
        assertNull(authorizedUsbDevice(manager, selection))
    }

    @Test fun staleResultsDoNotOverwriteAnotherTargetModelOrTransport() {
        val selection = selection()
        assertTrue(selection.accepts(selection.token, "tx", "UV-K1", true))
        assertFalse(selection.accepts("old", "tx", "UV-K1", true))
        assertFalse(selection.accepts(selection.token, "rx", "UV-K1", true))
        assertFalse(selection.accepts(selection.token, "tx", "IC-705", true))
        assertFalse(selection.accepts(selection.token, "tx", "UV-K1", false))
        assertFalse(selection.copy(address = "999:0:1:14007:65535").matches(device))
        assertFalse(selection.copy(address = "${device.deviceId}:0:1:1027:65535").matches(device))
    }

    @Test fun rotationRetainsPendingSelectionAndItsRequestIdentity() {
        val selection = selection()
        val scope = SaverScope { true }
        val saved = with(pendingUsbSelectionSaver) { scope.save(mutableStateOf(selection)) }!!
        assertEquals(selection, pendingUsbSelectionSaver.restore(saved)?.value)
    }

    @Test fun rapidRequestsHaveDistinctPendingIntents() {
        val first = usbPermissionIntent(context, "USB_CAT_PERMISSION", selection())
        val second = usbPermissionIntent(context, "USB_CAT_PERMISSION", selection())
        assertNotEquals(first, second)
        first.cancel(); second.cancel()
    }
}
