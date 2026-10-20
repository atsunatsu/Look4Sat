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

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbCdcDescriptorTest {
    @Test
    fun parsesUnionAndInterfaceAssociationDescriptors() {
        val descriptors = byteArrayOf(
            5, 0x24, 0x06, 2, 3,
            8, 0x0b, 4, 2, 2, 2, 1, 0
        )

        val associations = parseCdcInterfaceAssociations(descriptors)

        assertEquals(setOf(3), associations[2])
        assertEquals(setOf(5), associations[4])
    }

    @Test
    fun malformedDescriptorDoesNotReadPastBuffer() {
        val descriptors = byteArrayOf(9, 0x24, 0x06, 2, 3)

        assertEquals(emptyMap<Int, Set<Int>>(), parseCdcInterfaceAssociations(descriptors))
    }
}
