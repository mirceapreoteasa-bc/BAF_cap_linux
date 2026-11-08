package org.gcap.desktop

import org.gcap.core.FieldChecks
import org.gcap.core.GcapCalc
import org.gcap.core.GroundCall
import org.gcap.core.Tree
import java.awt.BorderLayout
import java.awt.GridLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField

/**
 * Desktop Summary page: the 6-column crew SummaryODS grid. Red/gray = labels,
 * green = editable, blue = derived, yellow = actions.
 */
class SummaryPanel(
    private val frame: MainFrame,
    private val sampleId: String,
) : JPanel(BorderLayout()), Page {

    private val repo = frame.repo
    private var gc: GroundCall = repo.byId(sampleId) ?: run {
        javax.swing.SwingUtilities.invokeLater { frame.showMenu() }
        GroundCall(sampleId = sampleId)
    }
    private val form = JPanel()
    private val messages = JLabel(" ", JLabel.CENTER)
    private val autosave = Autosave(repo) { save(quiet = true) }

    private class Binding(
        val read: () -> String,
        val apply: (String) -> Unit,
        val start: String,
    )
    private val bindings = mutableListOf<Binding>()

    init {
        border = javax.swing.BorderFactory.createEmptyBorder(8, 8, 8, 8)
        val top = JPanel(BorderLayout())
        top.add(JLabel("Summary $sampleId").apply { font = Gc.titleFont }, BorderLayout.WEST)
        top.add(Gc.button("Exit", Gc.ORANGE_BT) { frame.showMenu() }, BorderLayout.EAST)
        add(top, BorderLayout.NORTH)
        form.layout = javax.swing.BoxLayout(form, javax.swing.BoxLayout.Y_AXIS)
        add(JScrollPane(form), BorderLayout.CENTER)
        add(messages.apply { isOpaque = true }, BorderLayout.SOUTH)
        build()
        checkConflict()
    }

    override fun onShow() {
        gc = repo.byId(sampleId) ?: gc
        build()
        autosave.start()
    }

    override fun onHide() = autosave.stop()

    private fun eco(key: String): String = (gc.ecology[key] as? String).orEmpty()

    private fun ecoList(key: String, i: Int): String =
        (gc.ecology[key] as? List<*>)?.getOrNull(i)?.toString().orEmpty()

    private fun onEco(key: String): (String) -> Unit = { v -> gc = gc.copy(ecology = gc.ecology + (key to v)) }

    private fun onEcoListItem(key: String, i: Int): (String) -> Unit = { v ->
        val cur = (gc.ecology[key] as? List<*>)?.map { it?.toString().orEmpty() } ?: List(4) { "" }
        val updated = cur.toMutableList()
        while (updated.size <= i) updated.add("")
        updated[i] = v
        gc = gc.copy(ecology = gc.ecology + (key to updated))
    }

    private fun updateCapture(updater: (org.gcap.core.CaptureMeta) -> org.gcap.core.CaptureMeta) {
        val newCap = updater(gc.capture)
        val hasBoth = newCap.baf != null && newCap.baf!! > 0.0 && newCap.radiusM != null && newCap.radiusM!! > 0.0
        val resolved = if (!hasBoth) newCap.copy(conflictChoice = "") else newCap
        gc = gc.copy(capture = resolved)
        // The BAF/RADIUS notice is about these fields, so it must follow them as they are
        // typed rather than wait for the next full rebuild (which only happens on Save or
        // leaving the page). Rebuilding here would take the caret out of the field.
        refreshNotice()
    }

    /** The notice line for the current BAF/RADIUS state; empty when there is nothing to say. */
    private fun noticeText(): String {
        val hasBoth = gc.capture.baf != null && gc.capture.baf!! > 0.0 &&
            gc.capture.radiusM != null && gc.capture.radiusM!! > 0.0
        val bafIssue = gc.capture.baf?.let { FieldChecks.bafApproved(it) }
        val radiusIssue = gc.capture.radiusM?.let { FieldChecks.radiusApproved(it) }
        val warn = !GcapCalc.hasFixedRadius(gc) && GcapCalc.bafFor(gc) == null
        return when {
            hasBoth && gc.capture.conflictChoice == "BAF" -> "please delete RADIUS"
            hasBoth && gc.capture.conflictChoice == "RADIUS" -> "please delete BAF"
            hasBoth -> "Both BAF and RADIUS entered - choose BAF or RADIUS"
            radiusIssue != null && radiusIssue.level == FieldChecks.Level.RED -> radiusIssue.message
            bafIssue != null && bafIssue.level == FieldChecks.Level.RED -> bafIssue.message
            warn -> "BAF is empty  -  BA and density not calculated"
            else -> ""
        }
    }

    private fun refreshNotice() {
        val msg = noticeText()
        messages.text = if (msg.isEmpty()) " " else msg
        messages.background = if (msg.isNotEmpty()) Gc.NOTICE else java.awt.Color(0, 0, 0, 0)
        messages.isOpaque = msg.isNotEmpty()
    }

    private fun checkConflict() {
        val hasBoth = gc.capture.baf != null && gc.capture.baf!! > 0.0 &&
            gc.capture.radiusM != null && gc.capture.radiusM!! > 0.0
        if (hasBoth && gc.capture.conflictChoice.isEmpty()) {
            val choice = JOptionPane.showOptionDialog(
                this, "Both BAF and RADIUS are entered. Do you use BAF or fixed RADIUS?",
                "Plot Type Conflict", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE,
                null, arrayOf("1. BAF", "2. RADIUS"), "1. BAF",
            )
            gc = gc.copy(capture = gc.capture.copy(conflictChoice = if (choice == 1) "RADIUS" else "BAF"))
            repo.save(gc)
            build()
        }
    }

    private fun build() {
        bindings.clear()
        form.removeAll()

        fun row(vararg cells: java.awt.Component) {
            val r = JPanel(GridLayout(1, 6, 3, 3))
            for (i in 0 until 6) r.add(if (i < cells.size) cells[i] else Gc.spacer())
            r.maximumSize = java.awt.Dimension(Int.MAX_VALUE, r.preferredSize.height.coerceAtLeast(28))
            form.add(r)
        }

        fun boundField(text: String, live: Boolean = false, onValue: (String) -> Unit): JTextField {
            val f = Gc.field(text)
            bindings += Binding({ f.text }, onValue, text)
            if (live) {
                f.document.addDocumentListener(object : javax.swing.event.DocumentListener {
                    override fun insertUpdate(e: javax.swing.event.DocumentEvent) { onValue(f.text) }
                    override fun removeUpdate(e: javax.swing.event.DocumentEvent) { onValue(f.text) }
                    override fun changedUpdate(e: javax.swing.event.DocumentEvent) { onValue(f.text) }
                })
            }
            return f
        }

        fun picker(text: String, values: List<String>, onValue: (String) -> Unit): JComboBox<String> {
            val cb = Gc.combo(values, text)
            bindings += Binding({ cb.selectedItem?.toString().orEmpty() }, onValue, text.trim())
            return cb
        }

        val seAccept = frame.settings.seAccept
        val spmAccept = frame.settings.spmAccept
        val nvAccept = frame.settings.nvAccept
        val heAccept = frame.settings.heAccept

        row(
            Gc.grayLabel("Gcall"), Gc.blueValue(gc.sampleId), Gc.spacer(),
            Gc.grayLabel("SE"), picker(eco("line8"), FieldChecks.seCodes.toList().sorted(), onEco("line8")),
            Gc.redLabel("BAF"),
        )
        row(
            Gc.grayLabel("Poly"),
            boundField(gc.capture.poly) { gc = gc.copy(capture = gc.capture.copy(poly = it)) },
            Gc.spacer(),
            Gc.grayLabel("SPM"), picker(eco("line9"), FieldChecks.spmCodes.toList().sorted(), onEco("line9")),
            boundField(Gc.fmt(gc.capture.baf), live = true) { updateCapture { c -> c.copy(baf = it.toDoubleOrNull()) } },
        )
        row(
            Gc.grayLabel("Map"), Gc.blueValue(gc.mapSheet),
            Gc.button("GPS") { frame.showGpsMap(gc.sampleId) },
            Gc.grayLabel("SMR"), picker(eco("smr"), FieldChecks.smrCodes, onEco("smr")),
            Gc.redLabel("RADIUS"),
        )
        row(
            Gc.grayLabel("Crew"),
            boundField(gc.sampleType) { gc = gc.copy(sampleType = it) },
            Gc.spacer(),
            Gc.grayLabel("SNR"), picker(eco("snr"), FieldChecks.snrCodes, onEco("snr")),
            boundField(Gc.fmt(gc.capture.radiusM), live = true) { updateCapture { c -> c.copy(radiusM = it.toDoubleOrNull()) } },
        )
        row(
            Gc.grayLabel("Date"), Gc.blueValue(gc.entryDate), Gc.spacer(),
            Gc.grayLabel("MP"), picker(eco("line12"), FieldChecks.mpCodes, onEco("line12")),
            Gc.spacer(),
        )

        refreshNotice()

        row(Gc.grayLabel("Photo"), Gc.blueValue(gc.capture.photo), Gc.spacer())

        row(Gc.spacer(), Gc.redLabel("NV"), Gc.redLabel("HE"), Gc.redLabel("SH"), Gc.spacer(),
            Gc.button("Save As", Gc.ORANGE_BT) { saveAs() })
        row(
            Gc.grayLabel("CT"),
            picker(eco("landcover"), FieldChecks.nvCodes.toList().sorted(), onEco("landcover")),
            picker(eco("surface"), FieldChecks.heCodes.toList().sorted(), onEco("surface")),
            boundField(ecoList("cover_bytes", 1)) { v -> onEcoListItem("cover_bytes", 1)(v) },
            Gc.redLabel("BR"),
            Gc.button("Exit w/o Save", Gc.ORANGE_BT) { frame.showMenu() },
        )
        row(
            Gc.grayLabel("%"),
            boundField(eco("slope")) { onEco("slope")(it) },
            boundField(ecoList("cover_bytes", 0)) { v -> onEcoListItem("cover_bytes", 0)(v) },
            boundField(ecoList("cover_bytes", 2)) { v -> onEcoListItem("cover_bytes", 2)(v) },
            boundField(ecoList("cover_bytes", 3)) { v -> onEcoListItem("cover_bytes", 3)(v) },
            Gc.spacer(),
        )

        row(
            Gc.button("MAIN") { save(); exportIndividual(); frame.showMenu() },
            Gc.button("Add Pt") {
                val p = gc.nPoints + 1
                gc = gc.copy(trees = gc.trees + Tree(number = 1, point = p, measured = true, dbhRaw = ""))
                save()
                info("Point P$p added - enter trees")
            },
            Gc.button("Edit Pt") { frame.showPoints(gc.sampleId) },
            Gc.button("SAVE", Gc.ORANGE_BT) { save() },
            Gc.button("Comments") { frame.showComments(gc.sampleId) },
            Gc.button("Save & EXIT", Gc.ORANGE_BT) { save(); exportIndividual(); frame.showMenu() },
        )

        row(Gc.grayLabel("Layer"), Gc.blueValue("1"), Gc.spacer(), Gc.redLabel("Vert Compl"),
            picker(ecoList("layer_counts", 0), listOf("1", "2", "3", "4", "5")) { v -> setForAllPoints("layer_counts", v) },
            Gc.spacer())

        val hasFixed = GcapCalc.hasFixedRadius(gc)
        val activeBaf = GcapCalc.bafFor(gc)
        val noBasis = !hasFixed && activeBaf == null
        val activeRadius = if (hasFixed) gc.capture.radiusM else null
        val inTrees: List<Tree>? = when {
            hasFixed -> gc.trees.filter { it.isLive && it.dbhCm != null }
            noBasis -> null
            else -> GcapCalc.inTrees(gc.trees, activeBaf!!)
        }
        row(Gc.redLabel("Points"), Gc.blueValue(gc.nPoints.toString()),
            Gc.redLabel("Trees"), Gc.blueValue(inTrees?.size?.toString() ?: ""),
            Gc.redLabel("CC"), ccField())
        val ocular = if (gc.capture.ocularComments.isBlank()) "N" else "Y"
        val outTrees = if (hasFixed || inTrees == null) 0 else gc.liveTrees.size - inTrees.size
        row(Gc.grayLabel("Snags"), snagsField(), Gc.redLabel("Dead trees"), Gc.blueValue(gc.deadTrees.size.toString()),
            Gc.redLabel("Ocular"), Gc.blueValue(ocular))
        if (outTrees > 0) {
            row(Gc.spacer(), Gc.spacer(), Gc.redLabel("Out trees"), Gc.blueValue(outTrees.toString()))
        }

        val comp = if (noBasis) emptyList() else GcapCalc.reorderForDisplay(
            GcapCalc.speciesComposition(gc.trees, useBasalArea = hasFixed, baf = if (hasFixed) null else activeBaf).toList(),
            gc.capture.use3rdAsSecond,
        ).take(5)
        val codes = comp.map { it.first }
        val pcts = comp.map { "%.0f".format(it.second) }
        val ages = GcapCalc.meanTotalAgeBySpecies(gc.trees)
        val hgts = GcapCalc.meanHeightBySpecies(gc.trees)
        fun ageView(i: Int): String = codes.getOrNull(i)?.let { ages[it] }?.let { Math.round(it).toString() } ?: ""
        fun hgtView(i: Int): String = codes.getOrNull(i)?.let { hgts[it]?.let(Gc::fmt) } ?: ""

        row(Gc.grayLabel("Sp"), Gc.blueValue(codes.getOrNull(0).orEmpty()), Gc.blueValue(codes.getOrNull(1).orEmpty()),
            Gc.blueValue(codes.getOrNull(2).orEmpty()), Gc.blueValue(codes.getOrNull(3).orEmpty()),
            Gc.blueValue(codes.getOrNull(4).orEmpty()))
        row(Gc.grayLabel("%"), Gc.blueValue(pcts.getOrNull(0).orEmpty()), Gc.blueValue(pcts.getOrNull(1).orEmpty()),
            Gc.blueValue(pcts.getOrNull(2).orEmpty()), Gc.blueValue(pcts.getOrNull(3).orEmpty()),
            Gc.blueValue(pcts.getOrNull(4).orEmpty()))
        val calc = GcapCalc.summarizeByPoint(gc, baf = activeBaf, radiusM = activeRadius)
        val density = calc.points.mapNotNull { s -> s.stemsHa?.let { Math.round(it) } }
            .takeIf { it.isNotEmpty() }?.let { Math.round(it.average()).toInt() }
        row(Gc.grayLabel("Age"), Gc.blueValue(ageView(0)), Gc.blueValue(ageView(1)), Gc.blueValue(ageView(2)),
            Gc.grayLabel("BA"), Gc.blueValue(calc.baHaM2?.let(Gc::fmt) ?: ""))
        row(Gc.grayLabel("Hgt"), Gc.blueValue(hgtView(0)), Gc.blueValue(hgtView(1)), Gc.blueValue(hgtView(2)),
            Gc.grayLabel("Density"), Gc.blueValue(density?.toString() ?: ""))

        form.revalidate()
        form.repaint()
    }

    private fun snagsField(): JTextField {
        val shown = gc.capture.snagsValue?.toString().orEmpty()
        return Gc.field(shown).also { f ->
            bindings += Binding({ f.text }, { v ->
                val t = v.trim()
                val n = t.toIntOrNull()
                if (t.isNotEmpty() && (n == null || FieldChecks.snagsCount(n).level != FieldChecks.Level.OK)) {
                    warn("Snags must be a whole number 0 to 2000")
                } else {
                    gc = gc.copy(capture = gc.capture.copy(snags = if (n == null) emptyMap() else mapOf(1 to n)))
                }
            }, shown)
        }
    }

    private fun ccField(): JTextField {
        val shown = ecoList("layer_cover_a", 0)
        return Gc.field(shown).also { f ->
            bindings += Binding({ f.text }, { v ->
                val t = v.trim()
                val n = t.toIntOrNull()
                if (t.isNotEmpty() && (n == null || FieldChecks.crownClosure(n).level != FieldChecks.Level.OK)) {
                    warn("Crown closure must be a whole number ${FieldChecks.CC_MIN} to ${FieldChecks.CC_MAX}")
                } else {
                    setForAllPoints("layer_cover_a", t)
                }
            }, shown)
        }
    }

    private fun setForAllPoints(key: String, v: String) {
        for (i in 0 until 3) onEcoListItem(key, i)(v)
    }

    private fun commitFieldEdits() {
        val pending = bindings.map { b -> Triple(b, b.read().trim(), b.start.trim()) }
        for ((b, now, before) in pending) if (now != before) b.apply(now)
    }

    private fun save(quiet: Boolean = false) {
        commitFieldEdits()
        repo.save(gc)
        build()
        if (!quiet) info("Saved ${gc.sampleId}.dat")
    }

    fun exportIndividual() {
        org.gcap.core.OdsExporter.write(gc, repo.storage)
    }

    private fun saveAs() {
        commitFieldEdits()
        frame.saveAs(gc)
    }

    private fun info(msg: String) = JOptionPane.showMessageDialog(this, msg)
    private fun warn(msg: String) = JOptionPane.showMessageDialog(this, msg, "Notice", JOptionPane.WARNING_MESSAGE)
}
