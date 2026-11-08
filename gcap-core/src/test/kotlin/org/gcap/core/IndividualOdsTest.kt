package org.gcap.core

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Pins the individual <id>.ods geometry: Summary top-left, Comments
 * top-right, GPS block under Comments, Point1..3 stacked below Summary.
 */
class IndividualOdsTest {

    private fun sample(): GroundCall {
        val header = MutableList(149) { "" }
        header[141] = "10"
        return GroundCall(
            sampleId = "024",
            mapSheet = "092K096",
            sampleType = "mp",
            entryDate = "6/2/26",
            ecology = linkedMapOf<String, Any?>(
                "line8" to "N",
                "line9" to "L",
                "smr" to "5",
                "snr" to "D",
                "line12" to "N",
                "landcover" to "",
                "slope" to "0",
                "surface" to "HF",
                "cover_bytes" to listOf("10", "2.5", "35", "0"),
                "line21" to "0",
                "layer_counts" to listOf("2", "2", "2"),
                "layer_cover_a" to listOf("40", "40", "40"),
                "layer_cover_b" to listOf("5", "5", "5"),
                "layer_cover_c" to listOf("5", "4", "5"),
            ),
            comment = "@e to points 1",
            compositionRaw = "act 100",
            easting = 364828.576,
            northing = 5647861.527,
            trees = listOf(
                Tree(number = 1, point = 1, species = "ACT", dbhCm = 45.5, crownClass = "d",
                    heightM = 34.8, ageCore = 42, ageCor = 2.5, distM = 10.0, slopeDeg = 5.0, measured = true),
                Tree(number = 1, point = 2, species = "ACT", dbhCm = 30.8, crownClass = "c", measured = true),
            ),
            capture = CaptureMeta(
                baf = 12.0,
                utmZone = "10N",
                utmPoints = listOf(
                    UtmPoint(id = "P1", easting = 364828.576, northing = 5647861.527, zone = "10N", elevM = 61.9),
                    UtmPoint(id = "P2", easting = 364900.0, northing = 5647900.0, zone = "10N"),
                ),
                utmChosenIndex = 0,
            ),
            headerLines = header,
        )
    }

    @Test
    fun `six pages compose on one sheet with the right geometry`() {
        val sheet = OdsExporter.individualSheet(sample())
        assertEquals("024", sheet.title)
        assertEquals(13, sheet.colWidths.size)
        val rows = sheet.rows
        // Summary block top-left.
        assertEquals("Gcall", rows[0][0].text)
        assertEquals("024", rows[0][1].text)
        // Comments block top-right (col 7).
        assertEquals("Comments", rows[0][7].text)
        assertEquals("@e to points 1", rows[0][8].text)
        assertEquals("Ocular", rows[1][7].text)
        // GPS block at row 9, col 7: header, chosen, ids, x/y/z.
        val g = 9
        assertEquals("GPS", rows[g][7].text)
        assertEquals("024", rows[g][8].text)
        assertEquals("Map_Tile", rows[g][9].text)
        assertEquals("092K096", rows[g][10].text)
        assertEquals("UtmZone", rows[g][11].text)
        assertEquals("10N", rows[g][12].text)
        assertEquals("Chosen", rows[g + 1][7].text)
        assertEquals("P1", rows[g + 1][8].text)
        assertEquals("", rows[g + 2][7].text)
        assertEquals("P1", rows[g + 2][8].text)
        assertEquals("P2", rows[g + 2][9].text)
        assertEquals("x", rows[g + 3][7].text)
        assertEquals("364828.576", rows[g + 3][8].text)
        assertEquals("y", rows[g + 4][7].text)
        assertEquals("5647861.527", rows[g + 4][8].text)
        assertEquals("z", rows[g + 5][7].text)
        assertEquals("61.9", rows[g + 5][8].text)
        // Point blocks stacked below Summary + one blank separator row
        // (row 18 when no Out-trees row, 19 with it — both fine).
        val p1 = rows.indexOfFirst { it[0].text.startsWith("Point 1") }
        assertTrue(p1 >= 18, "Point 1 must start below the Summary/GPS zone (got $p1)")
        assertTrue(rows[p1 - 1].all { it.text.isEmpty() }, "blank separator above Point 1")
        val head = rows[p1 + 1].take(11).map { it.text }
        assertEquals(
            listOf("#", "Sp", "DBH", "TCls", "CCls", "Hgt", "Age", "Dist", "Slope", "Age_1.3", "Age_Correction"),
            head,
        )
        val trow = rows[p1 + 2].take(11).map { it.text }
        assertEquals("1", trow[0])
        assertEquals("ACT", trow[1])
        assertEquals("45.5", trow[2])
        assertEquals("d", trow[4])
        assertEquals("34.8", trow[5])
        assertEquals("10", trow[7])
        assertEquals("5", trow[8])
        assertEquals("42", trow[9])
        assertEquals("2.5", trow[10])
        // Every row spans the full 13-column grid.
        assertTrue(rows.all { it.size == 13 }, "all rows must be 13 cells wide")
    }
}
