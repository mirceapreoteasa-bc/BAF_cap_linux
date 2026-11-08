package org.gcap.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class OcularGridRoundTripTest {
    @Test
    fun ocularGridSurvivesTheSidecar() {
        val gc = GroundCall(
            sampleId = "zz",
            capture = CaptureMeta(
                ocularComments = "gap",
                ocularSpeciesCodes = listOf("PL", "FD", "AT"),
                ocularSpeciesPcts = listOf(60, 30, 10),
            ),
        )
        val back = CaptureIo.merge(gc, CaptureIo.toJson(gc).toByteArray(Charsets.UTF_8))
        assertEquals(listOf("PL", "FD", "AT"), back.capture.ocularSpeciesCodes)
        assertEquals(listOf(60, 30, 10), back.capture.ocularSpeciesPcts)
        assertEquals("gap", back.capture.ocularComments)
    }

    @Test
    fun missingGridStaysEmpty() {
        val gc = GroundCall(sampleId = "zz", capture = CaptureMeta(ocularComments = "gap"))
        val back = CaptureIo.merge(gc, CaptureIo.toJson(gc).toByteArray(Charsets.UTF_8))
        assertEquals(emptyList<String>(), back.capture.ocularSpeciesCodes)
        assertEquals(emptyList<Int>(), back.capture.ocularSpeciesPcts)
    }
}
