package org.gcap.desktop

import org.gcap.core.FieldChecks
import org.gcap.core.GcapCalc
import org.gcap.core.GroundCall
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.event.DocumentListener

/**
 * Desktop Comments page: printed comment, ocular comment, and the 8x2 ocular
 * species grid (codes + cover %, descending).
 */
class CommentsPanel(
    private val frame: MainFrame,
    private val sampleId: String,
) : JPanel(BorderLayout()), Page {

    private val repo = frame.repo
    private var gc: GroundCall = repo.byId(sampleId) ?: GroundCall(sampleId = sampleId)
    private val comments = JTextArea(gc.comment, 5, 40)
    private val ocular = JTextArea(gc.capture.ocularComments, 3, 40)
    private val spFields = List(8) { JTextField() }
    private val pctFields = List(8) { JTextField() }
    private val autosave = Autosave(repo) { persistSilent() }
    private var dirty = false
    private var speciesDirty = false
    private var loading = false

    init {
        border = BorderFactory.createEmptyBorder(8, 8, 8, 8)
        val top = JPanel(BorderLayout())
        top.add(JLabel("Comments ${gc.sampleId}").apply { font = Gc.titleFont }, BorderLayout.WEST)
        val useThird = Gc.button(useThirdLabel()) {}
        useThird.addActionListener {
            persistSilent(force = true)
            gc = gc.copy(capture = gc.capture.copy(use3rdAsSecond = !gc.capture.use3rdAsSecond))
            repo.save(gc)
            useThird.text = useThirdLabel()
        }
        top.add(JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            add(useThird)
            add(Gc.button("Point1") { frame.showPoints(gc.sampleId) })
            add(Gc.button("Summary") { frame.showSummary(gc.sampleId) })
        }, BorderLayout.EAST)
        add(top, BorderLayout.NORTH)

        val body = JPanel()
        body.layout = BoxLayout(body, BoxLayout.Y_AXIS)
        body.add(JLabel("Comments (<=350, no commas)"))
        body.add(JScrollPane(comments).apply {
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, 120)
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
        })
        body.add(Box.createVerticalStrut(8))
        body.add(JLabel("Ocular comment (<=100, no commas)"))
        body.add(JScrollPane(ocular).apply {
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, 80)
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
        })
        body.add(Box.createVerticalStrut(10))
        body.add(JLabel("Ocular species (codes + cover %, largest first)"))

        val grid = JPanel(GridLayout(3, 9, 4, 4))
        grid.add(JLabel(""))
        for (i in 1..8) grid.add(JLabel("S$i", JLabel.CENTER).apply { font = Gc.boldFont })
        grid.add(JLabel("Sp", JLabel.CENTER).apply { font = Gc.boldFont })
        for (f in spFields) grid.add(f)
        grid.add(JLabel("%", JLabel.CENTER).apply { font = Gc.boldFont })
        for (f in pctFields) grid.add(f)
        body.add(grid)
        add(JScrollPane(body), BorderLayout.CENTER)

        loadSpeciesGrid()
        comments.document.addDocumentListener(simple { dirty = true })
        ocular.document.addDocumentListener(simple { dirty = true })
        val gridListener = simple {
            if (!loading) {
                dirty = true
                speciesDirty = true
                updateOcularSpecies()
            }
        }
        for (f in pctFields) f.document.addDocumentListener(gridListener)
        for (f in spFields) f.document.addDocumentListener(gridListener)
        updateOcularSpecies()

        val bottom = JPanel(FlowLayout(FlowLayout.RIGHT))
        bottom.add(Gc.button("Save", Gc.ORANGE_BT) { save() })
        add(bottom, BorderLayout.SOUTH)
    }

    override fun onShow() {
        gc = repo.byId(sampleId) ?: gc
        autosave.start()
    }

    override fun onHide() {
        persistSilent()
        autosave.stop()
    }

    private fun useThirdLabel() =
        if (gc.capture.use3rdAsSecond) "Use 3dr Species: ON" else "Use 3dr Species: OFF"

    private fun loadSpeciesGrid() {
        val storedCodes = gc.capture.ocularSpeciesCodes
        val storedPcts = gc.capture.ocularSpeciesPcts
        val codes: List<String>
        val pcts: List<String>
        if (storedCodes.isNotEmpty() || storedPcts.isNotEmpty()) {
            codes = storedCodes
            pcts = storedPcts.map { it.toString() }
        } else {
            val hasFixed = GcapCalc.hasFixedRadius(gc)
            val activeBaf = GcapCalc.bafFor(gc)
            val comp = if (!hasFixed && activeBaf == null) emptyList() else
                GcapCalc.speciesComposition(gc.trees, useBasalArea = hasFixed, baf = if (hasFixed) null else activeBaf).toList()
            // Editable grid stays in descending cover order; the "use 3rd as 2nd"
            // flag only reorders the Summary/export composition.
            codes = comp.map { it.first }
            pcts = comp.map {
                it.second.let { d -> if (d == Math.floor(d)) d.toInt().toString() else d.toString() }
            }
        }
        loading = true
        try {
            for (i in 0 until 8) {
                spFields[i].text = codes.getOrNull(i) ?: ""
                pctFields[i].text = pcts.getOrNull(i) ?: ""
            }
        } finally {
            loading = false
        }
    }

    private fun ocularSpeciesFromUi(): Pair<List<String>, List<Int>> {
        val codes = mutableListOf<String>()
        val pcts = mutableListOf<Int>()
        for (i in 0 until 8) {
            val sp = spFields[i].text.trim().uppercase()
            val pct = pctFields[i].text.trim().toIntOrNull()
            if (sp.isNotEmpty() && pct != null) { codes += sp; pcts += pct }
        }
        return codes to pcts
    }

    /** Ordering takes the fixed 8 slots, with nulls for unused ones. */
    private fun pctSlots(pcts: List<Int>): List<Int?> =
        pcts + List((8 - pcts.size).coerceAtLeast(0)) { null }

    private fun updateOcularSpecies() {
        val (_, pcts) = ocularSpeciesFromUi()
        val badOrder = FieldChecks.ocularSpeciesOrdering(pctSlots(pcts)).level == FieldChecks.Level.ERROR
        for (i in 0 until 8) {
            val pv = pctFields[i].text.trim().toIntOrNull()
            val badRange = pv?.let { FieldChecks.ocularSpeciesPct(it).level == FieldChecks.Level.ERROR } ?: false
            pctFields[i].background = if (badRange || badOrder) Color(0xFF, 0x66, 0x66) else Gc.GREEN_ED
        }
    }

    private fun persistSilent(force: Boolean = false) {
        if (!dirty && !force) return
        var capture = gc.capture.copy(ocularComments = ocular.text)
        if (speciesDirty) {
            val (codes, pcts) = ocularSpeciesFromUi()
            capture = capture.copy(ocularSpeciesCodes = codes, ocularSpeciesPcts = pcts)
        }
        gc = gc.copy(comment = comments.text, capture = capture)
        repo.save(gc)
        dirty = false
        speciesDirty = false
    }

    private fun save() {
        val c = comments.text
        val o = ocular.text
        if (FieldChecks.comments(c).level == FieldChecks.Level.ERROR ||
            FieldChecks.ocular(o).level == FieldChecks.Level.ERROR
        ) {
            warn("Check length rules (350 / 100, no commas)")
            return
        }
        val (_, pcts) = ocularSpeciesFromUi()
        if (FieldChecks.ocularSpeciesOrdering(pctSlots(pcts)).level == FieldChecks.Level.ERROR) {
            warn("Ocular species cover-% must descend (largest first)")
            return
        }
        persistSilent(force = true)
        JOptionPane.showMessageDialog(this, "Saved")
    }

    private fun warn(msg: String) = JOptionPane.showMessageDialog(this, msg, "Notice", JOptionPane.WARNING_MESSAGE)

    private fun simple(f: () -> Unit) = object : DocumentListener {
        override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = f()
        override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = f()
        override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = f()
    }
}
