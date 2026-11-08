package org.gcap.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A saved Settings range is a project rule on top of the manual's limits, so it
 * narrows them and never widens them. Widening would let back in the typos the
 * built-in bands exist to catch.
 */
class UserRangeBoundsTest {

    @AfterEach
    fun reset() {
        FieldChecks.dbhUserRange = null
        FieldChecks.hgtUserRange = null
        FieldChecks.ageUserRange = null
    }

    @Test
    fun `no user range leaves the built-in limits alone`() {
        assertDbh(FieldChecks.DBH_MIN, FieldChecks.DBH_MAX, FieldChecks.dbhBounds)
        assertHgt(FieldChecks.HGT_MIN, FieldChecks.HGT_MAX, FieldChecks.hgtBounds)
        assertEquals(FieldChecks.AGE_MIN..FieldChecks.AGE_MAX, FieldChecks.ageBounds)
    }

    @Test
    fun `a narrower range narrows the enforced bounds`() {
        FieldChecks.dbhUserRange = 20.0..150.0
        assertDbh(20.0, 150.0, FieldChecks.dbhBounds)
        // inside the project rule but outside the manual's warning bands
        assertTrue(FieldChecks.dbh(200.0).level == FieldChecks.Level.ERROR)
        assertTrue(FieldChecks.dbh(5.0).level == FieldChecks.Level.ERROR)
        // inside both
        assertTrue(FieldChecks.dbh(40.0).level == FieldChecks.Level.OK)
    }

    @Test
    fun `a wider range cannot loosen the built-in limits`() {
        FieldChecks.dbhUserRange = 0.0..5000.0
        assertDbh(FieldChecks.DBH_MIN, FieldChecks.DBH_MAX, FieldChecks.dbhBounds)
        assertTrue(FieldChecks.dbh(3555.0).level == FieldChecks.Level.ERROR)
        assertTrue(FieldChecks.dbh(0.5).level == FieldChecks.Level.ERROR)

        FieldChecks.hgtUserRange = 0.0..1000.0
        assertHgt(FieldChecks.HGT_MIN, FieldChecks.HGT_MAX, FieldChecks.hgtBounds)

        FieldChecks.ageUserRange = 0..9999
        assertEquals(FieldChecks.AGE_MIN..FieldChecks.AGE_MAX, FieldChecks.ageBounds)
    }

    @Test
    fun `a partly outside range is intersected, not rejected`() {
        FieldChecks.dbhUserRange = 100.0..900.0
        // 700 is the built-in ceiling, so the effective top is 700 not 900
        assertDbh(100.0, FieldChecks.DBH_MAX, FieldChecks.dbhBounds)
        assertTrue(FieldChecks.dbh(800.0).level == FieldChecks.Level.ERROR)
        assertTrue(FieldChecks.dbh(150.0).level == FieldChecks.Level.OK)
    }

    @Test
    fun `a range entirely outside the manual falls back to the manual`() {
        FieldChecks.dbhUserRange = 2000.0..3000.0
        assertDbh(FieldChecks.DBH_MIN, FieldChecks.DBH_MAX, FieldChecks.dbhBounds)
    }

    @Test
    fun `the saved phone setting narrows dbh as the crew configured it`() {
        // the BV5200 ships dbh 7.4..999: the manual caps it at 700
        FieldChecks.dbhUserRange = 7.4..999.0
        assertDbh(7.4, 700.0, FieldChecks.dbhBounds)
        assertTrue(FieldChecks.dbh(650.0).level == FieldChecks.Level.RED)
        assertTrue(FieldChecks.dbh(750.0).level == FieldChecks.Level.ERROR)
        assertTrue(FieldChecks.dbh(7.0).level == FieldChecks.Level.ERROR)
    }

    @Test
    fun `height and age honour their own ranges`() {
        FieldChecks.hgtUserRange = 10.0..30.0
        assertHgt(10.0, 30.0, FieldChecks.hgtBounds)
        assertTrue(FieldChecks.height(40.0).level == FieldChecks.Level.ERROR)
        assertTrue(FieldChecks.height(20.0).level == FieldChecks.Level.OK)

        FieldChecks.ageUserRange = 10..80
        assertEquals(10..80, FieldChecks.ageBounds)
        assertTrue(FieldChecks.ageCore(5).level == FieldChecks.Level.ERROR)
        assertTrue(FieldChecks.ageCore(40).level == FieldChecks.Level.OK)
    }


    private fun assertDbh(lo: Double, hi: Double, actual: ClosedRange<Double>) {
        assertEquals(lo, actual.start, 1e-9, "DBH lower bound")
        assertEquals(hi, actual.endInclusive, 1e-9, "DBH upper bound")
    }

    private fun assertHgt(lo: Double, hi: Double, actual: ClosedRange<Double>) {
        assertEquals(lo, actual.start, 1e-9, "Hgt lower bound")
        assertEquals(hi, actual.endInclusive, 1e-9, "Hgt upper bound")
    }

    @Test
    fun `messages name the bound that was actually hit`() {
        FieldChecks.dbhUserRange = 20.0..150.0
        assertEquals("DBH must be 20..150 cm", FieldChecks.dbh(300.0).message)
        FieldChecks.hgtUserRange = 10.0..30.0
        assertEquals("Hgt must be 10..30 m", FieldChecks.height(60.0).message)
        FieldChecks.ageUserRange = 10..80
        assertEquals("Age count out of range (10..80)", FieldChecks.ageCore(200).message)
    }
}
