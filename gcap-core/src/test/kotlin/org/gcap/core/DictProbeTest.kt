package org.gcap.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

class DictProbeTest {
    @Test
    fun theNewDictionariesLoadInOrder() {
        assertEquals(listOf("1","2","3","4","5","6","7"), FieldChecks.smrCodes)
        assertEquals(listOf("A","B","C","D"), FieldChecks.snrCodes)
        assertEquals(listOf("N","A","F","B","V"), FieldChecks.mpCodes)
    }
}

class CodeAcceptsTest {
    @Test
    fun smrAcceptsOnlyRegimesAndBlank() {
        assertTrue(FieldChecks.smrAccepts("1"))
        assertTrue(FieldChecks.smrAccepts("7"))
        assertTrue(FieldChecks.smrAccepts(" 3 "))
        assertTrue(FieldChecks.smrAccepts(""))
        assertFalse(FieldChecks.smrAccepts("0"))
        assertFalse(FieldChecks.smrAccepts("8"))
        assertFalse(FieldChecks.smrAccepts("3.5"))
    }

    @Test
    fun snrAndMpAcceptOnlyTheirOwnCodes() {
        for (c in listOf("A", "B", "C", "D", "a", "")) assertTrue(FieldChecks.snrAccepts(c))
        assertFalse(FieldChecks.snrAccepts("E"))
        for (c in listOf("N", "A", "F", "B", "V", "a", "f", "")) assertTrue(FieldChecks.mpAccepts(c))
        assertFalse(FieldChecks.mpAccepts("AF"), "A and F are separate codes, not one")
        assertFalse(FieldChecks.mpAccepts("X"))
        // SNR and MP must not accept each other's codes
        assertFalse(FieldChecks.snrAccepts("AF"))
        assertFalse(FieldChecks.snrAccepts("N"))
        assertFalse(FieldChecks.mpAccepts("C"))
    }
}
