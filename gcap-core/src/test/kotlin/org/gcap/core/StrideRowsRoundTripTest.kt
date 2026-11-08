package org.gcap.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class StrideRowsRoundTripTest {
    private val src = File(System.getProperty("user.home"), "VRI PHOTOINTERPRETATION/SITE_TOOLS_SOFTWARE/GCap_for_android/Groundcalls/024.dat")

    @Test
    fun vertComplAndCrownClosureWriteToAllThreePointRows() {
        org.junit.jupiter.api.Assumptions.assumeTrue(src.isFile, "crew sample not present")
        val gc = DatParser.parseText(src.readText(Charsets.ISO_8859_1))
        val eco = gc.ecology + ("layer_counts" to listOf("3", "3", "3", "")) + ("layer_cover_a" to listOf("60", "60", "60", ""))
        val text = DatWriter.render(gc.copy(ecology = eco))
        val back = DatParser.parseText(text)
        assertEquals("3", (back.ecology["layer_counts"] as List<*>)[0])
        assertEquals("3", (back.ecology["layer_counts"] as List<*>)[2])
        assertEquals("60", (back.ecology["layer_cover_a"] as List<*>)[0])
        assertEquals("60", (back.ecology["layer_cover_a"] as List<*>)[2])
    }

    /** A stub file that stops short of the header layout must still be saveable.
     *  The per-point cells sit at header lines 52..102, so with only 36 lines
     *  there was nowhere to put them and saving threw IndexOutOfBounds. */
    @Test
    fun aStubFileIsPaddedSoThePointCellsStillFit() {
        val short = (1..36).joinToString("\r\n") { i ->
            when (i) {
                1 -> "v5.1.1 6/8/26"
                3 -> "STUB1"
                6 -> "mp"
                24 -> "7"
                else -> ""
            }
        }
        val gc = DatParser.parseText(short)
        assertTrue(gc.headerLines!!.size < 149, "the stub really is short")

        val edited = gc.copy(
            ecology = gc.ecology +
                ("layer_counts" to listOf("4", "", "")) +
                ("layer_cover_a" to listOf("55", "", "")),
            capture = gc.capture.copy(snags = mapOf(1 to 6)),
        )
        val lines = DatWriter.render(edited).split("\r\n")

        assertTrue(lines.size >= 149, "padded to the full header, got ${lines.size} lines")
        assertEquals("STUB1", lines[2], "the sample's own header is preserved")
        assertEquals("7", lines[23], "its BAF is preserved")
        assertEquals("4", lines[51].trim(), "Vert Compl reached row 52")
        assertEquals("55", lines[66].trim(), "CC reached row 67")
        assertEquals("6", lines[81].trim(), "snags reached row 82")

        val back = DatParser.parseText(lines.joinToString("\r\n"))
        assertEquals("STUB1", back.sampleId)
        assertEquals(6, back.capture.snagsValue)
        assertEquals(7.0, back.capture.baf!!, 1e-9)
    }
}
