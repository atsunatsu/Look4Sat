package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.control.isProlificSerialAdapter
import org.junit.Assert.*
import org.junit.Test

class ProlificSerialTest {
    private data class Request(val type: Int, val request: Int, val value: Int, val index: Int, val data: ByteArray?)
    private val descriptors = ByteArray(18).also { it[2] = 0; it[3] = 2; it[7] = 64; it[13] = 6 }
    private fun configure(
        product: Int = 0x23A3, stop: Int = 1, failAt: Int = -1,
        rts: Boolean = false, dtr: Boolean = false
    ): Pair<Boolean, List<Request>> {
        val calls = mutableListOf<Request>()
        val ok = configureProlificSerial(product, 0, descriptors, 1200, stop, rts, dtr) { type, request, value, index, data ->
            calls += Request(type, request, value, index, data?.copyOf())
            if (calls.lastIndex == failAt) -1 else data?.size ?: 0
        }
        return ok to calls
    }

    @Test fun reported067b23a3AndKnownSerialProductsAreRecognizedWithoutClaimingAllProlificDevices() {
        for (product in listOf(0x2303, 0x23A3, 0x23B3, 0x23C3, 0x23D3, 0x23E3, 0x23F3))
            assertTrue(isProlificSerialAdapter(0x067B, product))
        assertFalse(isProlificSerialAdapter(0x067B, 0x9999))
        assertFalse(isProlificSerialAdapter(0x9999, 0x23A3))
    }

    @Test fun hxnResetsPipesDisablesHandshakeAndUses1200Baud8n1WithoutLegacyRequests() {
        val (ok, calls) = configure()
        assertTrue(ok)
        assertEquals(listOf(0x80, 0x22, 0x80, 0x20, 0x80), calls.map { it.request })
        assertEquals(7, calls[0].value); assertEquals(3, calls[0].index)
        assertEquals(0, calls[1].value) // Both CAT modem lines default to deasserted.
        assertEquals(0x0A, calls[2].value); assertEquals(0xFF, calls[2].index)
        assertArrayEquals(byteArrayOf(0xB0.toByte(), 4, 0, 0, 0, 0, 8), calls.first { it.request == 0x20 }.data)
    }

    @Test fun prolificHonorsAllConfiguredCatRtsDtrCombinations() {
        for (rts in listOf(false, true)) for (dtr in listOf(false, true)) {
            val (ok, calls) = configure(rts = rts, dtr = dtr)
            assertTrue(ok)
            val state = (if (rts) 2 else 0) or (if (dtr) 1 else 0)
            assertEquals(state, calls.single { it.request == 0x22 }.value)
        }
    }

    @Test fun everyFailedControlRequestStopsConfiguration() {
        for (index in configure().second.indices) {
            val (ok, calls) = configure(failAt = index)
            assertFalse(ok); assertEquals(index + 1, calls.size)
        }
    }

    @Test fun legacy2303DoesNotUseHxnResetAndAcceptsTwoStopBits() {
        val (ok, calls) = configure(product = 0x2303, stop = 2)
        assertTrue(ok)
        // A USB 2.0 HXN device retaining PID 2303 also needs the modern requests.
        assertEquals(0x80, calls.first().request)
        assertEquals(2.toByte(), calls.first { it.request == 0x20 }.data!![4])
        val legacyCalls = mutableListOf<Int>()
        assertTrue(configureProlificSerial(0x2303, 0, descriptors.copyOf().also { it[3] = 1 }, 9600, 1) {
                _, request, _, _, data -> legacyCalls += request; data?.size ?: 0
        })
        assertTrue(legacyCalls.contains(1)); assertFalse(legacyCalls.contains(0x80))
    }

    @Test fun invalidDescriptorOrRateDoesNotIssueControls() {
        for (raw in listOf(ByteArray(0), ByteArray(13))) assertFalse(configureProlificSerial(0x23A3, 0, raw, 1200, 1) {
                _, _, _, _, _ -> fail("Unexpected control transfer"); 0
        })
        assertFalse(configureProlificSerial(0x23A3, 0, descriptors, 12345, 1) {
                _, _, _, _, _ -> fail("Unexpected control transfer"); 0
        })
    }
}
