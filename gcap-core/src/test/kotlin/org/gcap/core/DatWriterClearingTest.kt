package org.gcap.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DatWriterClearingTest {

    @Test
    fun clearedHeaderValuesAndCoordinatesAreWrittenEmpty() {
        val header = TestUtil.minimalHeader().toMutableList()
        header[5] = "mp"
        header[8] = "N"
        header[14] = "12"
        header[16] = "10"; header[17] = "2.5"; header[18] = "35"; header[19] = "0"
        header[111] = "old comment"
        header[142] = "364828.5"; header[143] = "5647861.5"; header[144] = "61.9"
        val gc = GroundCall(
            sampleId = "x", sampleType = "mp", comment = "old comment",
            ecology = mapOf(
                "line8" to "N", "slope" to "12",
                "cover_bytes" to listOf("10", "2.5", "35", "0"),
            ),
            easting = 364828.5, northing = 5647861.5, elevation = 61.9,
            headerLines = header,
        )
        val cleared = gc.copy(
            sampleType = "", comment = "",
            ecology = gc.ecology + mapOf(
                "line8" to "", "slope" to "",
                "cover_bytes" to listOf("", "", "", ""),
            ),
            easting = null, northing = null, elevation = null,
        )
        val lines = DatWriter.render(cleared).split("\r\n")
        for (i in listOf(5, 8, 14, 16, 17, 18, 19, 111, 142, 143, 144)) {
            assertEquals("", lines[i], "line $i should clear")
        }
    }

    @Test
    fun clearedTreeFieldsClearTheRecordAndTreeGoesLive() {
        val t = Tree(
            number = 1, species = "FDC", dbhCm = 20.0, dbhRaw = "20",
            point = 1, tcls = "4", crownClass = "c",
            heightM = 15.2, distM = 8.0, ageCore = 117, ageCor = 11.5, slopeDeg = 5.0,
            slots = listOf("4", "", "", "", "15.2", "8", "117", "11.5", "5", ""),
        )
        val gc = GroundCall(
            sampleId = "x", trees = listOf(t), headerLines = TestUtil.minimalHeader(),
        )
        val cleared = gc.copy(trees = listOf(t.copy(
            tcls = "", crownClass = "",
            heightM = null, distM = null, ageCore = null, ageCor = null, slopeDeg = null,
        )))
        val lines = DatWriter.render(cleared).split("\r\n")
        // First record starts at line 149: number, species, dbh, TCls, ..., Hgt,
        // Dist, Age, AgeCor, Slope, spare, CCls, blank, measured.
        for (off in listOf(3, 7, 8, 9, 10, 11, 13)) {
            assertEquals("", lines[149 + off], "tree offset $off should clear")
        }
        assertEquals("FDC", lines[150])
        assertEquals("20", lines[151])

        val back = DatParser.parseText(DatWriter.render(cleared)).trees.single()
        assertTrue(back.isLive)
        assertEquals("", back.tcls)
        assertNull(back.heightM)
        assertEquals("", back.crownClass)
    }

    @Test
    fun nonNumericLegacySlotTextIsPreserved() {
        val t = Tree(
            number = 1, species = "FDC", dbhCm = 20.0, dbhRaw = "20",
            slots = listOf("", "", "", "", "n/a", "", "", "", "", ""),
        )
        val gc = GroundCall(
            sampleId = "x", trees = listOf(t), headerLines = TestUtil.minimalHeader(),
        )
        assertEquals("n/a", DatWriter.render(gc).split("\r\n")[156])
    }
}
