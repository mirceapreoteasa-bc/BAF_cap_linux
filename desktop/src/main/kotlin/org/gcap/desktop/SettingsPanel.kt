package org.gcap.desktop

import org.gcap.core.FieldChecks
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.GridLayout
import java.util.Properties
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField

/**
 * Desktop Settings page: mirrors Templates/Settings.ods. Lists merge over the
 * built-in rules; ranges replace the built-in single range. Edits take effect
 * immediately; DISCARD_CHANGES puts back the snapshot taken when the page
 * opened, SAVE_CHANGES just leaves them in place.
 */
class SettingsPanel(private val frame: MainFrame) : JPanel(BorderLayout()), Page {

    private val settings = frame.settings
    private val snapshot: Properties = settings.snapshot()
    private val col = JPanel()

    init {
        border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
        val top = JPanel(BorderLayout())
        top.add(JLabel("Settings").apply { font = Gc.titleFont }, BorderLayout.WEST)
        top.add(Gc.button("Back") { frame.showMenu() }, BorderLayout.EAST)
        add(top, BorderLayout.NORTH)
        col.layout = BoxLayout(col, BoxLayout.Y_AXIS)
        add(JScrollPane(col), BorderLayout.CENTER)
        build()
    }

    private fun build() {
        col.removeAll()
        col.add(JLabel("Mirrors Templates/Settings.ods. Autosave off when blank; 20..120 s accepted. " +
            "Lists are merged over the built-in rules."))

        addRow("Layer settings",
            frame.repo.storage.readBytes(LayerSpecs.FILE)?.toString(Charsets.UTF_8)
                ?.let { LayerSpecs.parse(it) }?.let { if (it.isEmpty()) "built-in look" else "${it.size} layers saved" }
                ?: "built-in look") { frame.showLayers() }

        addRow("Autosave edits every", settings.autosaveSeconds?.let { "every $it s" } ?: "off  (20..120 s)") {
            val inp = JTextField(settings.autosaveSeconds?.toString() ?: "")
            val ok = JOptionPane.showConfirmDialog(this, inp,
                "Autosave edits every (seconds) - blank turns it off, 20..120 accepted",
                JOptionPane.OK_CANCEL_OPTION)
            if (ok == JOptionPane.OK_OPTION) {
                val typed = inp.text.trim()
                val n = typed.toIntOrNull()
                when {
                    typed.isEmpty() -> settings.setAutosave(null)
                    n == null -> warn("Autosave needs a whole number of seconds")
                    n < 20 || n > 120 -> {
                        val c = n.coerceIn(20, 120); settings.setAutosave(c)
                        warn("Autosave set to $c s (accepted 20..120)")
                    }
                    else -> settings.setAutosave(n)
                }
                build()
            }
        }

        addRow("accepted BAF list", "none added; BAF accepted range ${FieldChecks.BAF_MIN} .. ${FieldChecks.BAF_MAX}  (tap to edit)") {
            openList("accepted BAF list", settings.bafExtra) { settings.setBafExtra(it) }
        }
        addRow("accepted NV list (non-vegetated)", listText(FieldChecks.nvCodes, settings.nvAccept)) {
            openList("accepted NV list (non-vegetated)", settings.nvAccept) { settings.setNvAccept(it) }
        }
        addRow("accepted HE list (herb)", listText(FieldChecks.heCodes, settings.heAccept)) {
            openList("accepted HE list (herb)", settings.heAccept) { settings.setHeAccept(it) }
        }
        addRow("accepted SE list (surface expression)", listText(FieldChecks.seCodes, settings.seAccept)) {
            openList("accepted SE list (surface expression)", settings.seAccept) { settings.setSeAccept(it) }
        }
        addRow("accepted SPM list (site position meso)", listText(FieldChecks.spmCodes, settings.spmAccept)) {
            openList("accepted SPM list (site position meso)", settings.spmAccept) { settings.setSpmAccept(it) }
        }
        addRow("accepted species list", listText(FieldChecks.speciesCodes, settings.speciesExtra)) {
            openList("accepted species list", settings.speciesExtra) { settings.setSpeciesAccept(it) }
        }

        val dbhCur = settings.dbhUser?.let { "[${it.start}..${it.endInclusive}]" } ?: "[]"
        addRow("accepted DBH range",
            rangeDisplay(dbhCur, "built-in ${FieldChecks.DBH_MIN} .. ${FieldChecks.DBH_MAX} cm; warnings below ${FieldChecks.DBH_WARN_SMALL} and above ${FieldChecks.DBH_WARN_LARGE} cm")) {
            openRange("accepted DBH range", dbhCur) { a, b ->
                settings.setDbhUser(a.toDoubleOrNull()?.let { x -> b.toDoubleOrNull()?.let { y -> x..y } })
            }
        }
        val ageCur = settings.ageUser?.let { "[${it.first}..${it.last}]" } ?: "[]"
        addRow("accepted Age range", rangeDisplay(ageCur, "built-in ${FieldChecks.AGE_MIN} .. ${FieldChecks.AGE_MAX} years")) {
            openRange("accepted Age range", ageCur) { a, b ->
                settings.setAgeUser(a.toIntOrNull()?.let { x -> b.toIntOrNull()?.let { y -> x..y } })
            }
        }
        val hgtCur = settings.hgtUser?.let { "[${it.start}..${it.endInclusive}]" } ?: "[]"
        addRow("accepted Hgt range", rangeDisplay(hgtCur, "built-in ${FieldChecks.HGT_MIN} .. ${FieldChecks.HGT_MAX} m")) {
            openRange("accepted Hgt range", hgtCur) { a, b ->
                settings.setHgtUser(a.toDoubleOrNull()?.let { x -> b.toDoubleOrNull()?.let { y -> x..y } })
            }
        }

        col.add(Box.createVerticalStrut(10))
        col.add(Gc.button("Restore defaults", Gc.ORANGE_BT) {
            settings.restoreDefaults()
            build()
        }.apply { alignmentX = java.awt.Component.LEFT_ALIGNMENT })
        col.add(Box.createVerticalStrut(6))
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        buttons.add(Gc.button("DISCARD_CHANGES", Gc.ORANGE_BT) {
            settings.restore(snapshot); build()
        })
        buttons.add(Gc.button("SAVE_CHANGES", Gc.ORANGE_BT) { frame.showMenu() })
        buttons.alignmentX = java.awt.Component.LEFT_ALIGNMENT
        col.add(buttons)

        col.revalidate(); col.repaint()
    }

    private fun addRow(title: String, value: String, onTap: () -> Unit) {
        val line = JPanel(BorderLayout(8, 0))
        line.add(Gc.button(title) { onTap() }, BorderLayout.WEST)
        line.add(JLabel("<html><body style='width:420px'>${value.replace("\n", "<br>")}</body></html>").apply {
            background = Color(0x10, 0x31, 0x4F); foreground = Color(0xFF, 0xE4, 0x5C); isOpaque = true
            border = Gc.cellBorder(6)
        }, BorderLayout.CENTER)
        line.maximumSize = java.awt.Dimension(Int.MAX_VALUE, 56)
        line.alignmentX = java.awt.Component.LEFT_ALIGNMENT
        col.add(line)
        col.add(Box.createVerticalStrut(4))
    }

    private fun listText(builtIn: Set<String>, added: Set<String>): String {
        val all = (builtIn + added.map { it.trim().uppercase() }).filter { it.isNotBlank() }.sorted()
        val head = if (added.isEmpty()) "built-in, ${builtIn.size} codes" else "built-in ${builtIn.size} + ${added.size} added"
        return "$head: " + all.joinToString(", ")
    }

    private fun rangeText(current: String): String {
        val parts = current.removePrefix("[").removeSuffix("]").split("..")
        val mn = parts.getOrNull(0)?.trim().orEmpty()
        val mx = parts.getOrNull(1)?.trim().orEmpty()
        return if (mn.isEmpty() && mx.isEmpty()) "built-in" else "$mn .. $mx"
    }

    private fun rangeDisplay(current: String, builtIn: String): String {
        val user = rangeText(current)
        return if (user == "built-in") builtIn
        else "$builtIn\nyour entry $user (saved)"
    }

    private fun openList(title: String, current: Set<String>, onSave: (Set<String>) -> Unit) {
        val area = JTextArea(current.sorted().joinToString("\n"), 8, 24)
        val ok = JOptionPane.showConfirmDialog(
            this, JScrollPane(area),
            "Edit $title - one code per line (merged over the built-in list). Blank = built-in only.",
            JOptionPane.OK_CANCEL_OPTION,
        )
        if (ok == JOptionPane.OK_OPTION) {
            onSave(area.text.lineSequence().map { it.trim().uppercase() }.filter { it.isNotEmpty() }.toSet())
            build()
        }
    }

    private fun openRange(title: String, current: String, onSave: (String, String) -> Unit) {
        val parts = current.removePrefix("[").removeSuffix("]").split("..")
        val e1 = JTextField(parts.getOrNull(0)?.trim().orEmpty())
        val e2 = JTextField(parts.getOrNull(1)?.trim().orEmpty())
        val row = JPanel(GridLayout(2, 2, 4, 4)).apply {
            add(JLabel("min")); add(JLabel("max")); add(e1); add(e2)
        }
        val ok = JOptionPane.showConfirmDialog(
            this, row,
            "Edit $title - one accepted min..max range. Leaving a side blank keeps the built-in rule.",
            JOptionPane.OK_CANCEL_OPTION,
        )
        if (ok == JOptionPane.OK_OPTION) {
            onSave(e1.text.trim(), e2.text.trim())
            build()
        }
    }

    private fun warn(msg: String) = JOptionPane.showMessageDialog(this, msg, "Notice", JOptionPane.WARNING_MESSAGE)
}
