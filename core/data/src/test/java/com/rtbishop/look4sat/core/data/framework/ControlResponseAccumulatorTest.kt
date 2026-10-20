package com.rtbishop.look4sat.core.data.framework

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ControlResponseAccumulatorTest {

    @Test
    fun combinesFragmentsUntilTheResponseHasBeenQuietLongEnough() = runTest {
        var now = 0L
        val transport = ScriptedControlTransport(
            listOf(
                "AZ=12".encodeToByteArray(),
                ByteArray(0),
                "3 EL=45".encodeToByteArray(),
                ByteArray(0),
                ByteArray(0),
                ByteArray(0)
            )
        )
        val accumulator = ControlResponseAccumulator(
            nowMillis = { now },
            waitMillis = { now += it }
        )

        val result = accumulator.readUntilQuiet(
            transport = transport,
            maxBytes = 128,
            timeoutMillis = 500,
            quietMillis = 60,
            pollMillis = 20
        )

        assertArrayEquals("AZ=123 EL=45".encodeToByteArray(), result)
    }

    @Test
    fun capsAccumulatedDataAtTheRequestedMaximum() = runTest {
        var now = 0L
        val accumulator = ControlResponseAccumulator(
            nowMillis = { now },
            waitMillis = { now += it }
        )
        val result = accumulator.readUntilQuiet(
            transport = ScriptedControlTransport(listOf("12345".encodeToByteArray(), "67890".encodeToByteArray())),
            maxBytes = 7,
            timeoutMillis = 500,
            quietMillis = 40,
            pollMillis = 20
        )

        assertArrayEquals("1234567".encodeToByteArray(), result)
    }

    @Test
    fun returnsEmptyWhenNoBytesArriveBeforeTheDeadline() = runTest {
        var now = 0L
        val accumulator = ControlResponseAccumulator(
            nowMillis = { now },
            waitMillis = { now += it }
        )

        val result = accumulator.readUntilQuiet(
            transport = ScriptedControlTransport(emptyList()),
            maxBytes = 32,
            timeoutMillis = 100,
            quietMillis = 40,
            pollMillis = 20
        )

        assertArrayEquals(ByteArray(0), result)
    }
}

private class ScriptedControlTransport(chunks: List<ByteArray>) : ControlTransport {
    private val responses = ArrayDeque(chunks)
    override val isConnected: Boolean = true

    override suspend fun connect(): Boolean = true

    override suspend fun disconnect() = Unit

    override suspend fun write(bytes: ByteArray): Boolean = true

    override suspend fun readAvailable(maxBytes: Int): ByteArray {
        val next = responses.removeFirstOrNull() ?: return ByteArray(0)
        return next.copyOf(minOf(next.size, maxBytes))
    }
}
