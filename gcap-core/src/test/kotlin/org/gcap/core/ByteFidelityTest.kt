package org.gcap.core

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class ByteFidelityTest {
    @Test
    fun diffAll() {
        for (f in File(System.getProperty("user.home"), "GroundCallsCapture511/Groundcalls").listFiles { it.name.endsWith(".dat") }!!.sortedBy { it.name }) {
            val original = f.readBytes()
            val gc = DatParser.parseText(original.toString(Charsets.ISO_8859_1))
            val rend = DatWriter.render(gc).toByteArray(Charsets.ISO_8859_1)
            val lo = original.toString(Charsets.ISO_8859_1).split("\r\n")
            val lr = rend.toString(Charsets.ISO_8859_1).split("\r\n")
            if (lo.size != lr.size) println("${f.name}: lines ${lo.size} vs ${lr.size}")
            assertEquals(lo.size, lr.size, "${f.name}: line count must not change")
            var healed = 0
            var caseOnly = 0
            val healedTargets = mutableSetOf<Int>()
            for (i in lo.indices) {
                val a = lo[i]
                val b = lr[i]
                if (a == b) continue
                if (i in healedTargets) continue // receiving side of a counted move
                println("${f.name} L$i orig=$a rend=$b")
                // Allowed drift class 1: crew case rules (letter case only).
                if (a.equals(b, ignoreCase = true) && a.length == b.length) {
                    caseOnly++
                    continue
                }
                // Allowed drift class 2: legacy crown healing — a lone crown
                // letter in the TCls slot is cleared (10 lines down is the
                // CCls slot): either moved there when blank, or dropped when
                // CCls already holds the authoritative value.
                if (a.length == 1 && a.lowercase() in FieldChecks.CROWN_CLASS_LETTERS && b.isEmpty()) {
                    val loCrown = lo.getOrNull(i + 10).orEmpty()
                    val lrCrown = lr.getOrNull(i + 10).orEmpty()
                    val moved = loCrown.isEmpty() && lrCrown == a.lowercase()
                    val dropped = loCrown.isNotEmpty() && lrCrown.equals(loCrown, ignoreCase = true) &&
                        lrCrown.length == loCrown.length
                    if (moved || dropped) {
                        healed++
                        healedTargets.add(i + 10)
                        continue
                    }
                }
                throw AssertionError("${f.name} L$i: unexpected drift orig='$a' rend='$b'")
            }
            if (f.name in setOf("054.dat", "308.dat")) {
                println("${f.name}: $healed legacy crown letters healed")
            } else {
                assertEquals(0, healed, "${f.name}: only 054/308 carry legacy slot-3 letters")
            }
            // Full byte equality holds everywhere except documented drifts:
            // 307.dat's 'H'->'h' case rule and 054/308's healed letters.
            if (healed == 0 && caseOnly == 0) assertArrayEquals(original, rend)
        }
    }
}
