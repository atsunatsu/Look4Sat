package com.rtbishop.look4sat.core.data.framework.transport

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Explicit, non-exported Android receiver for Android USB host permission results. */
class UsbPermissionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getIntExtra(EXTRA_REQUEST, -1)
        waiters.remove(requestId)?.let {
            if (!it.isCompleted) it.complete(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
        }
    }

    companion object {
        private const val EXTRA_REQUEST = "com.rtbishop.look4sat.USB_REQUEST_ID"
        private val nextId = AtomicInteger(1)
        private val waiters = ConcurrentHashMap<Int, CompletableDeferred<Boolean>>()

        fun register(): Pair<Int, CompletableDeferred<Boolean>> {
            val id = nextId.getAndIncrement()
            val result = CompletableDeferred<Boolean>()
            waiters[id] = result
            return id to result
        }

        fun makeIntent(context: Context, id: Int) =
            Intent(context, UsbPermissionReceiver::class.java).putExtra(EXTRA_REQUEST, id)

        fun unregister(id: Int) { waiters.remove(id) }
    }
}
