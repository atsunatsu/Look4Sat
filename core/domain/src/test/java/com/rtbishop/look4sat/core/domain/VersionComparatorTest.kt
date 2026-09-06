package com.rtbishop.look4sat.core.domain

import com.rtbishop.look4sat.core.domain.utility.VersionComparator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparatorTest {

    @Test
    fun `newer build number wins when base version equal`() {
        assertTrue(VersionComparator.isNewer("4.4.6-ba7opf.7", "4.4.6-ba7opf.6"))
        assertTrue(VersionComparator.isNewer("v4.4.6-ba7opf.6", "4.4.6-ba7opf.5"))
    }

    @Test
    fun `newer base version wins regardless of build number`() {
        assertTrue(VersionComparator.isNewer("4.5.0", "4.4.6-ba7opf.99"))
        assertTrue(VersionComparator.isNewer("4.4.7-ba7opf.1", "4.4.6-ba7opf.99"))
    }

    @Test
    fun `older versions are not newer`() {
        assertFalse(VersionComparator.isNewer("4.4.6-ba7opf.5", "4.4.6-ba7opf.6"))
        assertFalse(VersionComparator.isNewer("4.4.5", "4.4.6-ba7opf.1"))
        assertFalse(VersionComparator.isNewer("4.4.6-ba7opf.6", "4.4.6-ba7opf.6"))
    }

    @Test
    fun `v prefix is ignored`() {
        assertTrue(VersionComparator.isNewer("v4.4.7", "4.4.6"))
        assertFalse(VersionComparator.isNewer("v4.4.6", "4.4.6-ba7opf.1"))
    }

    @Test
    fun `non numeric suffix without number treated as zero build`() {
        assertTrue(VersionComparator.isNewer("4.4.6-ba7opf.1", "4.4.6-ba7opf"))
        assertFalse(VersionComparator.isNewer("4.4.6-ba7opf", "4.4.6-ba7opf.1"))
    }

    @Test
    fun `multi level suffixes compare correctly`() {
        // The .9.1 hotfix case that exposed the old single-build-number logic.
        assertTrue(VersionComparator.isNewer("4.4.6-ba7opf.9.1", "4.4.6-ba7opf.9"))
        assertTrue(VersionComparator.isNewer("v4.4.6-ba7opf.9.1", "4.4.6-ba7opf.8"))
        assertFalse(VersionComparator.isNewer("4.4.6-ba7opf.9", "4.4.6-ba7opf.9.1"))
        assertTrue(VersionComparator.isNewer("4.4.6-ba7opf.10", "4.4.6-ba7opf.9.1"))
    }

    @Test
    fun `parse extracts base and suffix segments`() {
        assertEquals(listOf(4, 4, 6) to listOf(7), VersionComparator.parse("v4.4.6-ba7opf.7"))
        assertEquals(listOf(4, 4, 6) to listOf(9, 1), VersionComparator.parse("v4.4.6-ba7opf.9.1"))
        assertEquals(listOf(4, 4, 6) to emptyList<Int>(), VersionComparator.parse("4.4.6"))
        assertEquals(listOf(4, 4, 6) to emptyList<Int>(), VersionComparator.parse("4.4.6-ba7opf"))
    }
}
