package org.gcap.desktop

import org.gcap.core.FieldChecks
import org.gcap.core.Tree

/**
 * Desktop port of the Android LegacyHealing logic (crown-class letters only),
 * so TCls/CCls conflicts found in 054/308-era files get the same interactive
 * resolution on save.
 */
data class LegacyConflict(
    val point: Int,
    val number: Int,
    val incoming: String,
    val existing: String,
)

fun findLegacyConflicts(trees: List<Tree>): List<LegacyConflict> =
    trees.mapNotNull { t ->
        if (t.tcls.isNotEmpty()) return@mapNotNull null
        val slot = t.slots.getOrNull(0) ?: return@mapNotNull null
        if (slot.length != 1) return@mapNotNull null
        val incoming = slot.lowercase()
        if (incoming !in FieldChecks.CROWN_CLASS_LETTERS) return@mapNotNull null
        val existing = t.crownClass.lowercase()
        if (existing.isEmpty() || existing == incoming) return@mapNotNull null
        LegacyConflict(t.point, t.number, incoming, existing)
    }

fun applyCrownChoice(trees: List<Tree>, c: LegacyConflict, letter: String): List<Tree> =
    trees.map { t ->
        if (t.point == c.point && t.number == c.number) t.copy(crownClass = letter)
        else t
    }

fun applyDeleteTcls(trees: List<Tree>, c: LegacyConflict): List<Tree> =
    trees.map { t ->
        if (t.point == c.point && t.number == c.number) {
            val slots = t.slots.toMutableList()
            if (slots.isNotEmpty()) slots[0] = ""
            t.copy(slots = slots)
        } else t
    }
