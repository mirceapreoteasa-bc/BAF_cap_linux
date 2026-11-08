package org.gcap.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AddedSpeciesTest {
    @Test
    fun aSpeciesAddedInSettingsIsAcceptedAndOtherwiseNot() {
        val before = FieldChecks.extraSpecies
        try {
            FieldChecks.extraSpecies = setOf("zq")
            assertTrue(FieldChecks.isKnownSpecies("ZQ"))
            assertFalse(FieldChecks.isKnownSpecies("ZR"))
        } finally {
            FieldChecks.extraSpecies = before
        }
    }
}
