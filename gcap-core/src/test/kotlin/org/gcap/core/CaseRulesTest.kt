package org.gcap.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Crew case rules enforced by DatWriter.render (single choke point for all
 * .dat saves): version/crew/comment/composition/crown lower-case, everything
 * else (mapsheet, eco codes, species) UPPER-case. SampleId stays byte-exact
 * (it drives the file name: "0508b" must never become "0508B").
 */
class CaseRulesTest {

    private fun mixedCaseSample() = GroundCall(
        sampleId = "0508b",
        mapSheet = "092k096",
        sampleType = "MP",
        entryDate = "6/5/26",
        ecology = linkedMapOf<String, Any?>(
            "line8" to "n",
            "line9" to "c",
            "smr" to "2",
            "snr" to "b",
            "line12" to "n",
            "landcover" to "br",
            "slope" to "15",
            "surface" to "hg",
            "cover_bytes" to listOf("15", "0", "0", "15"),
            "line21" to "0",
        ),
        comment = "@E To Points 1 GPS 1",
        compositionRaw = "ACT 100 Dr MB Cw SS",
        easting = 355919.214,
        northing = 5671762.398,
        trees = listOf(
            Tree(number = 1, point = 1, species = "act", dbhCm = 38.0, crownClass = "H", measured = true),
        ),
        capture = CaptureMeta(baf = 12.0),
        headerLines = null, // new file: blank template, version line already lower-case
    )

    private fun renderLines(gc: GroundCall): List<String> =
        DatWriter.render(gc).split("\r\n")

    @Test
    fun `header fields follow the case rules`() {
        val l = renderLines(mixedCaseSample())
        assertEquals("v5.1.1 6/8/26", l[0]) // template version tag stays lower-case
        assertEquals("0508b", l[2]) // sampleId byte-exact (file name!)
        assertEquals("092K096", l[4]) // mapsheet UPPER
        assertEquals("mp", l[5]) // crew lower
        assertEquals("N", l[8])
        assertEquals("C", l[9])
        assertEquals("B", l[11])
        assertEquals("BR", l[13]) // non-veg UPPER
        assertEquals("HG", l[15]) // herb UPPER
        assertEquals("@e to points 1 gps 1", l[111]) // comment lower
        assertEquals("act 100 dr mb cw ss", l[126]) // composition lower
    }

    @Test
    fun `tree fields follow the case rules`() {
        val l = renderLines(mixedCaseSample())
        // Tree zone starts at header line index 149: rec offsets 1 (species), 13 (crown).
        assertEquals("ACT", l[150]) // species UPPER
        assertEquals("h", l[162]) // crown lower
    }

    @Test
    fun `legacy crown letter in tcls slot migrates to ccls`() {
        val gc = mixedCaseSample().copy(
            trees = listOf(
                Tree(number = 1, point = 1, species = "FDC", dbhCm = 25.2,
                    slots = listOf("C", "", "", "15.2", "", "117", "11.5", "", "", ""),
                    crownClass = "", measured = true),
            ),
        )
        val l = renderLines(gc)
        assertEquals("", l[152]) // TCls slot cleared
        assertEquals("c", l[162]) // CCls receives it, lower-case rule applied
    }

    @Test
    fun `real dead marker and filled crown are never clobbered`() {
        val gc = mixedCaseSample().copy(
            trees = listOf(
                Tree(number = 1, point = 1, species = "FDC", dbhCm = 25.2, tcls = "4",
                    slots = listOf("C", "", "", "15.2", "", "117", "11.5", "", "", ""),
                    crownClass = "H", measured = true),
            ),
        )
        val l = renderLines(gc)
        assertEquals("4", l[152]) // dead marker preserved
        assertEquals("h", l[162]) // existing crown kept (lower-cased), legacy ignored
    }

    @Test
    fun `already conformant files round-trip byte-exact`() {
        val l = renderLines(mixedCaseSample())
        val gc2 = DatParser.parseText(l.joinToString("\r\n"))
        // Re-render must be identical: rules are idempotent.
        assertEquals(l, DatWriter.render(gc2).split("\r\n"))
    }
}
