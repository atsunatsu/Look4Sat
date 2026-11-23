package com.rtbishop.look4sat.core.data.framework

import com.rtbishop.look4sat.core.domain.ft4.Ft4CatWindow
import com.rtbishop.look4sat.core.domain.time.IDisciplinedClock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException

internal class Ft4CatBudget(
    private val window: Ft4CatWindow?,
    private val clock: IDisciplinedClock?
) : AbstractCoroutineContextElement(Key) {
    private val monotonicDeadline = clock?.snapshot()?.let { snapshot ->
        snapshot.monotonicNanos + (checkNotNull(window).deadlineUtcMillis - snapshot.utcMillis) * 1_000_000L
    }

    fun remainingMillis(): Long {
        if (window == null || clock == null || monotonicDeadline == null) return Long.MAX_VALUE
        val snapshot = clock.snapshot()
        return minOf(window.remainingMillis(snapshot.utcMillis),
            ((monotonicDeadline - snapshot.monotonicNanos) / 1_000_000L).coerceAtLeast(0L))
    }

    fun checkBeforeCommand() {
        if (remainingMillis() == 0L) throw Ft4CatWindowExpired()
    }

    companion object Key : CoroutineContext.Key<Ft4CatBudget> {
        val cleanup = Ft4CatBudget(null, null)
    }
}

internal class Ft4CatWindowExpired : CancellationException("FT4 CAT window closed")

internal class TimedRadioTransport(private val transport: RadioTransport) : RadioTransport by transport {
    override suspend fun write(bytes: ByteArray): Boolean {
        kotlin.coroutines.coroutineContext[Ft4CatBudget]?.checkBeforeCommand()
        return transport.write(bytes)
    }
}
