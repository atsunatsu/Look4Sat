package com.rtbishop.look4sat.core.domain.utility

/**
 * Compares two version strings of the form "<major>.<minor>.<patch>[-<build>]" used by the
 * BA7OPF fork releases (e.g. "4.4.6-ba7opf.6" or "v4.4.6-ba7opf.7").
 *
 * The comparison is done on the numeric version segments first; when the base versions are
 * equal, the trailing build number (if any) decides. This lets a "4.4.6-ba7opf.7" release be
 * detected as newer than "4.4.6-ba7opf.6" while "4.5.0" trumps any "4.4.x" build.
 */
object VersionComparator {

    /** True when [candidate] is a newer version than [current]. */
    fun isNewer(candidate: String, current: String): Boolean {
        val cand = parse(candidate)
        val curr = parse(current)
        val maxLen = maxOf(cand.first.size, curr.first.size)
        for (i in 0 until maxLen) {
            val c = cand.first.getOrElse(i) { 0 }
            val k = curr.first.getOrElse(i) { 0 }
            if (c != k) return c > k
        }
        return cand.second > curr.second
    }

    /**
     * Parses a version string into (numeric base segments, trailing build number).
     * "v4.4.6-ba7opf.7" -> ([4,4,6], 7); "4.4.6" -> ([4,4,6], 0); "4.4.6-ba7opf" -> ([4,4,6], 0).
     */
    fun parse(version: String): Pair<List<Int>, Int> {
        val cleaned = version.trim().removePrefix("v")
        val dashIndex = cleaned.indexOf('-')
        val basePart = if (dashIndex >= 0) cleaned.substring(0, dashIndex) else cleaned
        val suffixPart = if (dashIndex >= 0) cleaned.substring(dashIndex + 1) else ""
        val base = basePart.split('.').mapNotNull { it.toIntOrNull() }
        // The build number is the trailing numeric segment of the suffix (e.g. "ba7opf.7" -> 7).
        val build = suffixPart
            .split('.')
            .lastOrNull()
            ?.toIntOrNull()
            ?: 0
        return base to build
    }
}
