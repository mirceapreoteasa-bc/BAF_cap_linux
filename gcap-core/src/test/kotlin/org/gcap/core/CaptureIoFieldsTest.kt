package org.gcap.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CaptureIoFieldsTest {

    private fun header() = TestUtil.minimalHeader()

    private fun base(): GroundCall = GroundCall(sampleId = "x", headerLines = header())

    @Test
    fun fullCaptureMetaAndHeightsRoundTrip() {
        val tree = Tree(
            number = 1, species = "FDC", dbhCm = 20.0, dbhRaw = "20", point = 1,
            hgtTopDeg = 12.5, hgtBotDeg = -3.25, hgtSDistM = 18.0, hgtCorM = 0.75,
        )
        val points = listOf(
            UtmPoint("P1", 100.0, 200.0, "10N", 55.5, 49.1, -122.3, "Field", 123456789L),
            UtmPoint("P2", 101.0, 201.0, "10N", 56.6, 49.2, -122.4, "Intended", 223456789L),
        )
        val tie = UtmPoint("Tie_Point", 102.0, 202.0, "10N", 57.7, 49.3, -122.5, "Field", 323456789L)
        val meta = CaptureMeta(
            baf = 12.0, radiusM = 3.5, conflictChoice = "BAF",
            snags = mapOf(1 to 5, 2 to 4), deadStems = mapOf(1 to 2),
            ocularComments = "plc70 fdc30",
            ocularSpeciesCodes = listOf("PL", "FD"),
            ocularSpeciesPcts = listOf(70, 30),
            callType = "XGV-3", location = "Coast", utmZone = "10N",
            coordSource = "Field", poly = "P1", photo = "IMG1",
            utmPoints = points, utmChosenIndex = 1, utmTiePoint = tie,
            use3rdAsSecond = true,
        )
        val gc = base().copy(trees = listOf(tree), capture = meta)
        val merged = CaptureIo.merge(
            base().copy(trees = listOf(tree)),
            CaptureIo.toJson(gc).toByteArray(Charsets.UTF_8),
        )
        assertEquals(meta, merged.capture)
        assertEquals(12.5, merged.trees.single().hgtTopDeg)
        assertEquals(-3.25, merged.trees.single().hgtBotDeg)
        assertEquals(18.0, merged.trees.single().hgtSDistM)
        assertEquals(0.75, merged.trees.single().hgtCorM)
    }

    @Test
    fun explicitEmptyOcularCommentStaysEmpty() {
        val gc = base().copy(compositionRaw = "ba60 hw35 ss5")
        val merged = CaptureIo.merge(gc, """{"sampleId":"x","ocularComments":""}""".toByteArray())
        assertEquals("", merged.capture.ocularComments)
    }

    @Test
    fun sidecarWithoutOcularKeyStillUsesComposition() {
        val gc = base().copy(compositionRaw = "ba60 hw35 ss5")
        val merged = CaptureIo.merge(gc, """{"sampleId":"x"}""".toByteArray())
        assertEquals("ba60 hw35 ss5", merged.capture.ocularComments)
    }

    @Test
    fun partialSidecarKeepsBaseStringFields() {
        val gc = base().copy(capture = CaptureMeta(
            callType = "XGV-3", location = "Coast", utmZone = "10N",
            coordSource = "Field", poly = "P1", photo = "IMG1",
        ))
        val merged = CaptureIo.merge(gc, """{"sampleId":"x"}""".toByteArray())
        assertEquals("XGV-3", merged.capture.callType)
        assertEquals("Coast", merged.capture.location)
        assertEquals("10N", merged.capture.utmZone)
        assertEquals("Field", merged.capture.coordSource)
        assertEquals("P1", merged.capture.poly)
        assertEquals("IMG1", merged.capture.photo)
    }

    @Test
    fun tiePointKeepsElevationAndCoordinates() {
        val tie = UtmPoint("Tie_Point", 102.0, 202.0, "10N", 57.7, 49.3, -122.5, "Field", 323456789L)
        val gc = base().copy(capture = CaptureMeta(utmTiePoint = tie))
        val merged = CaptureIo.merge(base(), CaptureIo.toJson(gc).toByteArray(Charsets.UTF_8))
        val back = merged.capture.utmTiePoint
        assertNotNull(back)
        assertEquals(tie, back)
    }

    @Test
    fun useThirdAsSecondCanBeTurnedOff() {
        val on = base().copy(capture = CaptureMeta(use3rdAsSecond = true))
        val mergedOn = CaptureIo.merge(base(), CaptureIo.toJson(on).toByteArray(Charsets.UTF_8))
        assertTrue(mergedOn.capture.use3rdAsSecond)
        val mergedOff = CaptureIo.merge(mergedOn, """{"sampleId":"x"}""".toByteArray())
        assertFalse(mergedOff.capture.use3rdAsSecond)
    }
}
