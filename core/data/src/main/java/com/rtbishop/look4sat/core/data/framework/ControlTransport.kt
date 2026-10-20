/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.data.framework

/**
 * A connected byte stream used by radio-control protocols.
 *
 * CAT controllers own protocol framing and command serialization; transports
 * only manage connection lifecycle and bytes. Boolean failures keep the existing CAT
 * contract stable while the higher-level service exposes typed state to the UI.
 */
interface ControlTransport {
    val isConnected: Boolean

    suspend fun connect(): Boolean

    suspend fun disconnect()

    suspend fun write(bytes: ByteArray): Boolean

    suspend fun readAvailable(maxBytes: Int): ByteArray
}
