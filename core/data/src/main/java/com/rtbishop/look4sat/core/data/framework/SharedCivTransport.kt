package com.rtbishop.look4sat.core.data.framework

/** Both logical CI-V endpoints run on the same command actor. */
internal class SharedCivTransport(private val transport: RadioTransport) {
    private var owners = 0

    fun endpoint(): RadioTransport = object : RadioTransport by transport {
        private var joined = false
        override val isConnected get() = joined && transport.isConnected

        override suspend fun connect(): Boolean {
            if (isConnected) return true
            if (!transport.isConnected && !transport.connect()) return false
            if (!joined) {
                joined = true
                owners++
            }
            return true
        }

        override suspend fun disconnect() {
            if (!joined) return
            joined = false
            if (--owners == 0) transport.disconnect()
        }
    }
}
