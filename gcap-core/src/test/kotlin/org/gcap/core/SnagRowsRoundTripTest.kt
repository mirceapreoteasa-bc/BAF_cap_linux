package org.gcap.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.io.File

class SnagRowsRoundTripTest {
    @Test
    fun snagsLandOnFileRows82_85_88AndReadBack() {
        val src = File(System.getProperty("user.home"), "VRI PHOTOINTERPRETATION/SITE_TOOLS_SOFTWARE/GCap_for_android/Groundcalls/024.dat")
        org.junit.jupiter.api.Assumptions.assumeTrue(src.isFile, "crew sample not present")
        val gc = DatParser.parseText(src.readText(Charsets.ISO_8859_1))
        val edited = gc.copy(capture = gc.capture.copy(snags = mapOf(1 to 7, 2 to 7, 3 to 7)))
        val text = DatWriter.render(edited)
        assertEquals(listOf(81, 84, 87), DatWriter.SNAG_CELLS)
        val back = DatParser.parseText(text)
        assertEquals(mapOf(1 to 7, 2 to 7, 3 to 7), back.capture.snags)
    }

    @Test
    fun crewSampleReadsItsOwnSnagRows() {
        val src = File(System.getProperty("user.home"), "VRI PHOTOINTERPRETATION/SITE_TOOLS_SOFTWARE/GCap_for_android/Groundcalls/024.dat")
        org.junit.jupiter.api.Assumptions.assumeTrue(src.isFile, "crew sample not present")
        val gc = DatParser.parseText(src.readText(Charsets.ISO_8859_1))
        assertEquals(mapOf(1 to 5, 2 to 5, 3 to 5), gc.capture.snags)
    }

    /** A snag count entered on a brand-new sample, before any point exists, used
     *  to be thrown away: it was fanned out across the points that already
     *  existed, and a new sample has none. It must survive, and must then appear
     *  on every point once the sample grows. */
    @Test
    fun snagsEnteredBeforeAnyPointStillReachEveryRow() {
        val empty = GroundCall(sampleId = "zz", capture = CaptureMeta())
        assertEquals(0, empty.points().size)
        // what the Summary field now stores
        val entered = empty.copy(capture = empty.capture.copy(snags = mapOf(1 to 7)))
        assertEquals(7, entered.capture.snagsValue)

        val text = DatWriter.render(entered)
        val lines = text.split("\n")
        for (row in listOf(81, 84, 87)) {
            assertEquals(7, lines.getOrElse(row) { "" }.trim().toIntOrNull(), "row $row")
        }
        assertEquals(7, DatParser.parseText(text).capture.snagsValue)
    }

    /** Per-point entries are still honoured: a file that genuinely records
     *  different counts per point must not be flattened onto the first one. */
    @Test
    fun perPointSnagsAreNotOverwrittenByTheFallback() {
        val gc = GroundCall(
            sampleId = "zz",
            capture = CaptureMeta(snags = mapOf(1 to 3, 2 to 5, 3 to 9)),
        )
        val lines = DatWriter.render(gc).split("\n")
        assertEquals(3, lines[81].trim().toIntOrNull())
        assertEquals(5, lines[84].trim().toIntOrNull())
        assertEquals(9, lines[87].trim().toIntOrNull())
        assertEquals(3, gc.capture.snagsValue)
    }
}
