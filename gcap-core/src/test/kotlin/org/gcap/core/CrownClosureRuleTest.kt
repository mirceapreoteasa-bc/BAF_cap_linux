package org.gcap.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CrownClosureRuleTest {
    @Test
    fun crownClosureAcceptsOnlyFivetoNinetyFive() {
        assertEquals(FieldChecks.Level.ERROR, FieldChecks.crownClosure(4).level)
        assertEquals(FieldChecks.Level.OK, FieldChecks.crownClosure(5).level)
        assertEquals(FieldChecks.Level.OK, FieldChecks.crownClosure(95).level)
        assertEquals(FieldChecks.Level.ERROR, FieldChecks.crownClosure(96).level)
        assertEquals(FieldChecks.Level.OK, FieldChecks.crownClosure(null).level)
    }

    @Test
    fun snagsAcceptsZeroToTwoThousand() {
        assertEquals(FieldChecks.Level.OK, FieldChecks.snagsCount(0).level)
        assertEquals(FieldChecks.Level.OK, FieldChecks.snagsCount(2000).level)
        assertEquals(FieldChecks.Level.ERROR, FieldChecks.snagsCount(2001).level)
        assertEquals(FieldChecks.Level.ERROR, FieldChecks.snagsCount(-1).level)
    }
}
