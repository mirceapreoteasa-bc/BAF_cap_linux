package org.gcap.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

class DatRoundTripTest {

    private val samplesDir = System.getProperty("samples.dir")
        ?: "${System.getProperty("user.home")}/GroundCallsCapture511/Groundcalls"

    private fun sampleFiles(): List<File> {
        val dir = File(samplesDir)
        assertTrue(dir.isDirectory, "samples dir not found: $samplesDir")
        return dir.listFiles { f -> f.name.endsWith(".dat") }!!.sortedBy { it.name }
    }

    @Test
    fun parsesAllSamplesWithExpectedPointCounts() {
        for (f in sampleFiles()) {
            val gc = DatParser.parseFile(f.absolutePath)
            assertEquals(3, gc.nPoints, "${f.name}: expected 3 points")
            val lines = f.readBytes().toString(Charsets.ISO_8859_1).split("\r\n")
            // header per-point count cells L96/99/102 = "1-based" parse counts
            for (i in 0 until gc.nPoints) {
                val headerCount = lines[96 + i * 3].toIntOrNull() ?: 0
                val parsedCount = gc.points()[i + 1]?.size ?: 0
                assertEquals(headerCount, parsedCount,
                    "${f.name}: header L${96 + i * 3} count != parsed point ${i + 1} count")
            }
        }
    }

    @Test
    fun roundTripPreservesTreesAndHeader() {
        for (f in sampleFiles()) {
            val gc = DatParser.parseFile(f.absolutePath)
            val rendered = DatWriter.render(gc)
            val gc2 = DatParser.parseText(rendered)

            assertEquals(gc.sampleId, gc2.sampleId, "${f.name}: sample id")
            assertEquals(gc.entryDate, gc2.entryDate, "${f.name}: entry date")
            assertEquals(gc.comment, gc2.comment, "${f.name}: comment")
            assertEquals(gc.compositionRaw, gc2.compositionRaw, "${f.name}: composition")
            assertEquals(gc.nPoints, gc2.nPoints, "${f.name}: n points")

            val p1 = gc.points()
            val p2 = gc2.points()
            assertEquals(p1.keys, p2.keys, "${f.name}: point ids")
            for (p in p1.keys) {
                val a = p1[p]!!
                val b = p2[p]!!
                assertEquals(a.size, b.size, "${f.name}: point $p tree count")
                for (i in a.indices) {
                    assertEquals(a[i].number, b[i].number, "${f.name}: tree number")
                    assertEquals(a[i].species, b[i].species, "${f.name}: species")
                    assertEquals(a[i].dbhCm, b[i].dbhCm, "${f.name}: dbh")
                    // Crew case rules: crown normalizes to lower-case on save
                    // (307.dat carries a legacy uppercase 'H'); a lone crown
                    // letter mistyped in the TCls slot (054/308) is cleared,
                    // moving to CCls only when that is blank (CCls wins).
                    val legacy = a[i].slots.getOrNull(0)
                        ?.takeIf { it.length == 1 && it.lowercase() in FieldChecks.CROWN_CLASS_LETTERS }
                        ?.lowercase()
                    val expectedCrown =
                        if (a[i].tcls.isEmpty() && legacy != null && a[i].crownClass.isEmpty()) legacy
                        else a[i].crownClass.lowercase()
                    assertEquals(expectedCrown, b[i].crownClass, "${f.name}: crown")
                    assertEquals(a[i].measured, b[i].measured, "${f.name}: measured")
                }
            }
        }
    }

    @Test
    fun deletedPointsClearStaleHeaderCountsAndRecords() {
        val f = sampleFiles().first { it.name == "054.dat" }
        val gc = DatParser.parseFile(f.absolutePath)
        // delete point 3, then point 2 (mimicking the app's deletePoint())
        val once = gc.trees.filter { it.point != 3 }
            .map { if (it.point < 3) it else it.copy(point = it.point - 1) }
        val twice = once.filter { it.point != 2 }
            .map { if (it.point < 2) it else it.copy(point = it.point - 1) }
        val rendered = DatWriter.render(gc.copy(trees = twice))
        val lines = rendered.split("\r\n")
        // header L96/99/102 must be honest: 8 present trees, stale cells cleared
        assertEquals("8", lines[96])
        assertEquals("", lines[99])
        assertEquals("", lines[102])
        val reparsed = DatParser.parseText(rendered)
        assertEquals(1, reparsed.nPoints)
        assertEquals(8, reparsed.trees.size)
        assertTrue(reparsed.trees.all { it.point == 1 })
        // record zone = 149 header lines + 8 records*16 - 1 (no final flag line)
        assertEquals(277, lines.size)
    }

    @Test
    fun newCoordinatesOverwriteOldValuesOnSave() {
        // 024.dat carries old values (zone 10 / 364828.576 / 5647861.527 / 61.9).
        // Saving fresh P1 coords (what GpsActivity.buildGc produces) must
        // replace every one of them, mirroring the mapsheet update behavior.
        val gc = DatParser.parseFile(sampleFiles().first { it.name == "024.dat" }.absolutePath)
        assertEquals(364828.576, gc.easting!!, 1e-6)
        val updated = gc.copy(
            easting = 365271.29,
            northing = 5646932.826,
            elevation = 100.0,
            capture = gc.capture.copy(
                utmZone = "10N",
                utmPoints = listOf(UtmPoint(id = "P1", easting = 365271.29, northing = 5646932.826, zone = "10N")),
                utmChosenIndex = 0,
            ),
        )
        val lines = DatWriter.render(updated).split("\r\n")
        assertEquals("10", lines[141], "dat line 142 zone overwritten")
        assertEquals("365271.29", lines[142], "dat line 143 easting overwritten")
        assertEquals("5646932.826", lines[143], "dat line 144 northing overwritten")
        assertEquals("100", lines[144], "dat line 145 elevation overwritten")
        val back = DatParser.parseText(lines.joinToString("\r\n"))
        assertEquals(365271.29, back.easting!!, 1e-6)
        assertEquals(5646932.826, back.northing!!, 1e-6)
    }

    @Test
    fun editWithoutNewCoordsPreservesZoneCoordsAndSheet() {
        // Home age edit: only a tree age changes, no coordinates collected.
        // Zone (142), easting (143), northing (144), elevation (145) and the
        // mapsheet (5) must survive the save byte-exactly.
        val f = sampleFiles().first { it.name == "024.dat" }
        val orig = f.readBytes().toString(Charsets.ISO_8859_1).split("\r\n")
        val gc = DatParser.parseFile(f.absolutePath)
        val aged = gc.trees.mapIndexed { i, t -> if (i == 0) t.copy(ageCore = 999) else t }
        val lines = DatWriter.render(gc.copy(trees = aged)).split("\r\n")
        assertEquals(orig[141], lines[141], "dat line 142 zone preserved")
        assertEquals(orig[142], lines[142], "dat line 143 easting preserved")
        assertEquals(orig[143], lines[143], "dat line 144 northing preserved")
        assertEquals(orig[144], lines[144], "dat line 145 elevation preserved")
        assertEquals(orig[4], lines[4], "dat line 5 mapsheet preserved")
    }

    @Test
    fun coordinatesRoundTrip() {
        val f = sampleFiles().first { it.name == "304.dat" }
        val gc = DatParser.parseFile(f.absolutePath)
        assertNotNull(gc.easting)
        assertEquals(363510.275, gc.easting!!, 1e-6)
        assertEquals(5653662.79, gc.northing!!, 1e-6)
        assertEquals(31.5, gc.elevation!!, 1e-6)
        val gc2 = DatParser.parseText(DatWriter.render(gc))
        assertEquals(gc.easting, gc2.easting)
        assertEquals(gc.northing, gc2.northing)
        assertEquals(gc.elevation, gc2.elevation)
    }
}