/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package com.rtbishop.look4sat.core.domain.model

import java.util.Locale

/**
 * Conservative *home callsign allocation* lookup for Indonesia, sourced from
 * IIHG's WAIPA PREFIXES + SUFFIXES table:
 * https://iihg.net/portfolio/waipa-award/
 *
 * This is only a fallback when a QSO has neither a usable STATE nor a precise
 * geographic match. Portable/special-event calls are intentionally excluded:
 * a home prefix must never override the QSO's operating location.
 *
 * Some IIHG ranges overlap (e.g. KI/KU, PA/PP/PS/PT). Only a UNIQUE matching
 * province is accepted; no guessed province is added to the 38-province award.
 * The table itself is 37 provinces; PD has no distinct callsign allocation.
 */
object WaipaCallsignLookup {

    private data class Rule(
        val code: String,
        val area: Char,
        val prefixLetters: String,
        val ranges: List<Pair<String, String>>
    ) {
        fun matches(prefixLetter: Char, digit: Char, suffix: String): Boolean =
            digit == area && prefixLetter in prefixLetters && ranges.any { (first, last) ->
                suffix.length == first.length && suffix >= first && suffix <= last
            }
    }

    // code | call-area digit | YB..YG's second letter | IIHG suffix ranges.
    // Literal lexicographic ranges implement single-, two- and three-letter
    // suffixes, including their separate Zxx club-station allocations.
    private val rules = """
        AC|6|BCDEFG|A-G AA-GZ ZA-ZG AAA-GZZ ZAA-ZGZ
        BA|9|BCDEFG|A-F AA-FZ ZA-ZF AAA-FZZ ZAA-ZFZ
        BB|4|EFG|F-L FA-LZ ZF-ZL FAA-LZZ ZFA-ZLZ
        BE|4|BCDEFG|M-R MA-RZ ZM-ZR MAA-RZZ ZMA-ZRZ
        BT|1|BCDEFG|R-Y RA-YZ ZR-ZZ RAA-YZZ ZRA-ZZZ
        GO|8|EFG|Q-U QA-UZ ZQ-ZU QAA-UZZ ZQA-ZUZ
        JA|4|BCDEFG|A-E ZA-ZE AAA-EZZ ZAA-ZEZ
        JB|1|BCDEFG|A-Q AA-QZ ZA-ZQ AAA-PZZ ZAA-ZQZ
        JI|3|BCDEFG|A-Z AA-ZZ AAA-ZZZ ZA-ZZ ZAA-ZZZ
        JK|0|BCDEFG|A-Z AA-ZZ AAA-ZZZ ZA-ZZ ZAA-ZZZ
        JT|2|BCDEFG|A-T AA-TZ ZA-ZT AAA-TZZ ZAA-ZTZ
        KB|7|BCDEFG|A-G AA-GZ ZA-ZG AAA-GZZ ZAA-ZGZ
        KI|7|BCDEFG|U-Y UA-YZ ZU-ZZ UAA-YZZ ZUA-ZZZ
        KR|5|EFG|N-Y NA-YZ ZN-ZY NAA-YZZ ZNA-ZYZ
        KS|7|BCDEFG|H-N HA-NZ ZH-ZN HAA-NZZ ZHA-ZNZ
        KT|7|BCDEFG|O-T OA-TZ ZO-ZT OAA-TZZ ZOA-ZTZ
        KU|7|BCDEFG|U-Y UA-YZ ZU-ZZ UAA-YZZ ZUA-ZZZ
        LA|4|BCDEFG|S-Y SA-YZ ZS-ZZ SAA-YZZ ZSA-ZZZ
        MA|8|BCD|V-Y VA-YZ ZV-ZY VAA-YZZ ZVA-ZYZ
        MU|8|EFG|V-Y VA-YZ ZV-ZY VAA-YZZ ZVA-ZYZ
        NB|9|BCDEFG|G-K GA-KZ ZG-ZK GAA-KZZ ZGA-ZKZ
        NT|9|BCDEFG|L-P LA-PZ ZL-ZP LAA-PZZ ZLA-ZPZ
        PA|9|BCDEFG|U-Y UA-YZ ZU-ZZ UAA-YZZ ZUA-ZZZ
        PB|9|BCDEFG|Q-T QA-TZ ZQ-ZT QAA-TZZ ZQA-ZTZ
        PP|9|BCDEFG|U-Y UA-YZ ZU-ZZ UAA-YZZ ZUA-ZZZ
        PS|9|BCDEFG|U-Y UA-YZ ZU-ZZ UAA-YZZ ZUA-ZZZ
        PT|9|BCDEFG|U-Y UA-YZ ZU-ZZ UAA-YZZ ZUA-ZZZ
        RI|5|BCD|N-Y NA-YZ ZN-ZY NAA-YZZ ZNA-ZYZ
        SA|8|BCD|Q-U QA-UZ ZQ-ZU QAA-UZZ ZQA-ZUZ
        SB|5|BCDEFG|A-M AA-MZ ZA-ZM RAA-UZZ ZAA-ZMZ
        SG|8|BCDEFG|I-L IA-LZ ZI-ZL IAA-LZZ ZIA-ZLZ
        SN|8|BCD|A-H AA-HZ ZA-ZH AAA-HZZ ZAA-ZHZ
        SR|8|EFG|A-H AA-HZ ZA-ZH AAA-HZZ ZAA-ZHZ
        SS|4|BCD|F-L FA-LZ ZF-ZL FAA-LZZ ZFA-ZLZ
        ST|8|BCDEFG|M-P MA-PZ ZM-ZP MAA-PZZ ZMA-ZPZ
        SU|6|BCDEFG|H-Z HA-ZZ ZH-ZZ HAA-HZZ ZHA-ZZZ
        YO|2|BCDEFG|U-Y UA-YZ ZU-ZZ UAA-YZZ ZUA-ZZZ
    """.trimIndent().lines().map { line ->
        val fields = line.split('|')
        val ranges = fields[3].split(' ').map { text ->
            val pair = text.split('-')
            pair[0] to pair[1]
        }
        Rule(fields[0], fields[1].single(), fields[2], ranges)
    }

    /** All IIHG province allocations compatible with the complete callsign. */
    fun candidates(call: String): Set<String> {
        val normalized = call.trim().uppercase(Locale.US)
        // /P, /M, /MM, /3, /QRP, foreign guest calls, etc. are location-dependent.
        if ('/' in normalized || normalized.length !in 4..7) return emptySet()
        if (normalized.length < 4 ||
            normalized[0] != 'Y' || normalized[1] !in "BCDEFG" ||
            normalized[2] !in '0'..'9' || !normalized.drop(3).all { it in 'A'..'Z' }
        ) return emptySet()
        val suffix = normalized.substring(3)
        return rules.asSequence()
            .filter { it.matches(normalized[1], normalized[2], suffix) }
            .map { it.code }
            .toSet()
    }

    /** Returns a province only when exactly one IIHG rule matches the whole call. */
    fun province(call: String): String? = candidates(call).singleOrNull()
}
