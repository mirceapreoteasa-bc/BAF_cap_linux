package org.gcap.desktop

import org.gcap.core.FieldChecks
import org.gcap.core.GcapCalc
import org.gcap.core.GroundCall
import org.gcap.core.SpeciesSiteIdx
import org.gcap.core.Tree
import org.gcap.core.Y2bhTable
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JSplitPane
import javax.swing.JTextField

/**
 * Desktop tree-entry grid (the "Edit point" screen): one row per tree, point
 * tabs, in/out tint, and the right-hand height + age calculators.
 */
class PointsPanel(
    private val frame: MainFrame,
    private val sampleId: String,
) : JPanel(BorderLayout()), Page {

    private val repo = frame.repo
    private var gc: GroundCall = repo.byId(sampleId) ?: GroundCall(sampleId = sampleId)
    private var currentPoint = 1

    private val grid = JPanel()
    private val header = JPanel()
    private val tabs = JPanel(FlowLayout(FlowLayout.LEFT, 3, 3))
    private val messages = JLabel(" ", JLabel.LEFT)
    private val banner = JLabel()

    private val rowCells = mutableMapOf<String, List<JComponent>>()
    private val numberLabels = mutableMapOf<String, JLabel>()
    private val glyphLabels = mutableMapOf<String, JLabel>()
    private var highlighted: Int? = null
    private val autosave = Autosave(repo) { save(quiet = true) }

    private class Binding(val read: () -> String, val apply: (String) -> Unit, val start: String)
    private val bindings = mutableListOf<Binding>()

    private val hTop = Gc.field("")
    private val hBot = Gc.field("")
    private val hSD = Gc.field("")
    private val hCor = Gc.field("")
    private val hHeight = Gc.blueValue("—")
    private val aCore = Gc.field("")
    private val aCor = Gc.blueValue("—")
    private val aTotal = Gc.blueValue("—")
    private val aSI = Gc.blueValue("—")
    private val panelTitle = JLabel("Tap Age cell", JLabel.CENTER)
    private val agedCount = JLabel("Aged 0/$MAX_AGED_TREES")
    private var ageTarget: Int? = null
    private val columns = listOf("#", "Sp", "DBH", "TCls", "CCls", "Hgt", "Age", "Dist", "Slope")

    init {
        border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
        currentPoint = 1.coerceIn(1, gc.nPoints.coerceAtLeast(1))

        val top = JPanel(BorderLayout())
        top.add(JLabel("Sample ${gc.sampleId}").apply { font = Gc.titleFont }, BorderLayout.WEST)
        top.add(tabs, BorderLayout.CENTER)
        top.add(Gc.button("Exit", Gc.ORANGE_BT) { frame.showSummary(gc.sampleId) }, BorderLayout.EAST)
        add(top, BorderLayout.NORTH)

        grid.layout = BoxLayout(grid, BoxLayout.Y_AXIS)
        header.layout = GridLayout(1, 9, 1, 1)
        val center = JPanel(BorderLayout())
        center.add(header, BorderLayout.NORTH)
        center.add(JScrollPane(grid), BorderLayout.CENTER)

        val right = JPanel()
        right.layout = BoxLayout(right, BoxLayout.Y_AXIS)
        right.border = BorderFactory.createEmptyBorder(0, 8, 0, 0)
        buildRightPanel(right)

        val split = JSplitPane(JSplitPane.HORIZONTAL_SPLIT, center, right)
        split.resizeWeight = 0.72
        add(split, BorderLayout.CENTER)

        val bottom = JPanel(BorderLayout())
        bottom.add(banner, BorderLayout.CENTER)
        val btns = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        btns.add(Gc.button("Add tree") { addTree() })
        btns.add(Gc.button("Delete tree") { deleteTree() })
        btns.add(Gc.button("Delete point") { deletePoint() })
        btns.add(Gc.button("Summary") { save(); frame.showSummary(gc.sampleId) })
        btns.add(Gc.button("Comments") { frame.showComments(gc.sampleId) })
        btns.add(Gc.button("SAVE", Gc.ORANGE_BT) { save() })
        btns.add(Gc.button("Save & Exit", Gc.ORANGE_BT) { save(); exportIndividual(); frame.showMenu() })
        btns.add(Gc.button("Exit w/o Save", Gc.ORANGE_BT) { frame.showSummary(gc.sampleId) })
        bottom.add(btns, BorderLayout.NORTH)
        bottom.add(messages.apply {
            isOpaque = true
            border = BorderFactory.createEmptyBorder(4, 4, 4, 4)
        }, BorderLayout.SOUTH)
        add(bottom, BorderLayout.SOUTH)

        hTop.document.addDocumentListener(simple { recomputeHeight() })
        hBot.document.addDocumentListener(simple { recomputeHeight() })
        hSD.document.addDocumentListener(simple { recomputeHeight() })
        hCor.document.addDocumentListener(simple { recomputeHeight() })
        aCore.document.addDocumentListener(simple { recomputeAge() })

        buildHeader()
        setupPointTabs()
        rebuildGrid()
    }

    override fun onShow() {
        gc = repo.byId(sampleId) ?: gc
        setupPointTabs(); rebuildGrid()
        autosave.start()
    }

    override fun onHide() = autosave.stop()

    private fun buildHeader() {
        header.removeAll()
        for (c in columns) header.add(JLabel(c, JLabel.CENTER).apply {
            background = Color(0xF2, 0xF2, 0xF2); isOpaque = true; font = Gc.boldFont
            border = Gc.cellBorder(3)
        })
    }

    private fun setupPointTabs() {
        tabs.removeAll()
        for (p in 1..gc.nPoints.coerceAtLeast(1)) {
            val sel = p == currentPoint
            tabs.add(Gc.button("P$p", if (sel) Color(0x00, 0x66, 0xCC) else Color(0xED, 0xED, 0xED)) {
                currentPoint = p; resetTargets(); setupPointTabs(); rebuildGrid()
            }.apply { if (sel) foreground = Color.WHITE })
        }
        tabs.add(Gc.button("Add point", Color(0xED, 0xED, 0xED)) {
            currentPoint = gc.nPoints + 1; resetTargets(); setupPointTabs(); rebuildGrid()
        })
        tabs.revalidate(); tabs.repaint()
    }

    private fun key(t: Tree) = "${t.point}.${t.number}"

    private fun rebuildGrid() {
        bindings.clear(); rowCells.clear(); numberLabels.clear(); glyphLabels.clear()
        highlighted = null
        grid.removeAll()
        val trees = gc.trees.filter { it.point == currentPoint }.sortedBy { it.number }
        for (t in trees) grid.add(buildRow(t))
        val addRow = JPanel(GridLayout(1, 9, 1, 1)).apply {
            add(JLabel("+", JLabel.CENTER).apply { font = Gc.titleFont })
            for (i in 1 until 9) add(JLabel(""))
            border = BorderFactory.createEmptyBorder(4, 0, 4, 0)
        }
        addRow.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) { addTree() }
        })
        grid.add(addRow)
        val dead = trees.count { it.isDead }
        banner.text = "Point $currentPoint  ·  ${trees.size} trees" + if (dead > 0) "  ·  $dead dead" else ""
        refreshMessages()
        grid.revalidate(); grid.repaint()
    }

    private fun refreshMessages() {
        val msg = FieldChecks.sample(gc)
            .filter { it.level == FieldChecks.Level.RED || it.level == FieldChecks.Level.ERROR }
            .joinToString("  ·  ") { it.message }
        messages.text = if (msg.isEmpty()) " " else msg
        messages.background = if (msg.isNotEmpty()) Gc.NOTICE else Color(0, 0, 0, 0)
        messages.isOpaque = msg.isNotEmpty()
    }

    private fun buildRow(t: Tree): JPanel {
        val row = JPanel(GridLayout(1, 9, 1, 1))
        row.background = if (highlighted == t.number) Color(0xE0, 0xEC, 0xFF) else Color.WHITE
        row.isOpaque = true
        val k = key(t)

        val num = JLabel(t.number.toString(), JLabel.CENTER).apply { font = Gc.boldFont }
        val glyph = JLabel("", JLabel.CENTER).apply { font = Gc.boldFont }
        val numWrap = JPanel(FlowLayout(FlowLayout.CENTER, 0, 0)).apply {
            add(num); add(glyph)
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    highlighted = t.number
                    showInOutMessage(t.number)
                    row.background = Color(0xE0, 0xEC, 0xFF)
                }
            })
        }
        numberLabels[k] = num
        glyphLabels[k] = glyph
        row.add(numWrap)

        val cells = mutableListOf<JComponent>()
        fun textCell(col: Int, text: String, apply: (String) -> Unit) {
            val f = Gc.field(text)
            bindings += Binding({ f.text }, apply, text)
            f.addFocusListener(object : java.awt.event.FocusAdapter() {
                override fun focusLost(e: java.awt.event.FocusEvent) {
                    val s = f.text.trim()
                    refusal(col, s)?.let { msg ->
                        warn(msg)
                        f.text = currentValue(col, t.number)
                    }
                    apply(if (refusal(col, s) == null) s else currentValue(col, t.number))
                    recolor(col, f)
                }
                override fun focusGained(e: java.awt.event.FocusEvent) {
                    if (col == 5) armAge(t.number)
                }
            })
            cells.add(f); row.add(f)
        }

        textCell(0, t.species) { applyCell(t.number, 0, it) }
        textCell(1, dbhTextOf(t)) { applyCell(t.number, 1, it) }

        val tcls = Gc.combo(listOf("4"), t.tcls)
        tcls.addActionListener { applyCell(t.number, 2, tcls.selectedItem?.toString().orEmpty()) }
        bindings += Binding({ tcls.selectedItem?.toString().orEmpty() }, { applyCell(t.number, 2, it) }, t.tcls.trim())
        cells.add(tcls); row.add(tcls)

        val ccls = Gc.combo(listOf("D", "C", "H", "I"), t.crownClass)
        ccls.addActionListener { applyCell(t.number, 3, ccls.selectedItem?.toString().orEmpty()) }
        bindings += Binding({ ccls.selectedItem?.toString().orEmpty() }, { applyCell(t.number, 3, it) }, t.crownClass.trim())
        cells.add(ccls); row.add(ccls)

        textCell(4, Gc.fmt(t.heightM)) { applyCell(t.number, 4, it) }
        textCell(5, t.shownAge?.toString().orEmpty()) { applyCell(t.number, 5, it) }
        textCell(6, Gc.fmt(t.distM)) { applyCell(t.number, 6, it) }
        textCell(7, Gc.fmt(t.slopeDeg)) { applyCell(t.number, 7, it) }

        rowCells[k] = cells
        tintInOut(t.number)
        return row
    }

    private fun currentValue(col: Int, number: Int): String {
        val t = gc.trees.firstOrNull { it.point == currentPoint && it.number == number } ?: return ""
        return when (col) {
            0 -> t.species
            1 -> dbhTextOf(t)
            2 -> t.tcls
            3 -> t.crownClass
            4 -> Gc.fmt(t.heightM)
            5 -> t.shownAge?.toString().orEmpty()
            6 -> Gc.fmt(t.distM)
            7 -> Gc.fmt(t.slopeDeg)
            else -> ""
        }
    }

    private fun refusal(col: Int, text: String): String? {
        val t = text.trim()
        if (t.isEmpty()) return null
        return when (col) {
            1 -> {
                val v = t.toDoubleOrNull() ?: return "DBH must be a number"
                val b = FieldChecks.dbhBounds
                when {
                    v > b.endInclusive -> "DBH is too large, it exceeds ${one(b.endInclusive)} cm!"
                    v < b.start -> "DBH is too small, it is under ${one(b.start)} cm!"
                    else -> null
                }
            }
            4 -> {
                val v = t.toDoubleOrNull() ?: return "Height must be a number"
                val b = FieldChecks.hgtBounds
                when {
                    v > b.endInclusive -> "Height is too large, it exceeds ${one(b.endInclusive)} m!"
                    v < b.start -> "Height is too small, it is under ${one(b.start)} m!"
                    else -> null
                }
            }
            5 -> {
                val v = t.toIntOrNull() ?: return "Age must be a whole number of years"
                val b = FieldChecks.ageBounds
                when {
                    v > b.last -> "Age is too large, it exceeds ${b.last} years!"
                    v < b.first -> "Age is too small, it is under ${b.first} years!"
                    else -> null
                }
            }
            else -> null
        }
    }

    private fun applyCell(number: Int, col: Int, raw: String) {
        val t = gc.trees.firstOrNull { it.point == currentPoint && it.number == number } ?: return
        val s = raw.trim()
        if (refusal(col, s) != null) return
        val updated = when (col) {
            0 -> t.copy(species = s.uppercase().take(4))
            1 -> t.copy(dbhCm = s.toDoubleOrNull(), dbhRaw = s)
            2 -> t.copy(tcls = s)
            3 -> t.copy(crownClass = s.lowercase())
            4 -> t.copy(heightM = s.toDoubleOrNull())
            5 -> t.copy(ageCore = s.toIntOrNull(), ageCor = null)
            6 -> t.copy(distM = s.toDoubleOrNull())
            7 -> t.copy(slopeDeg = s.toDoubleOrNull())
            else -> t
        }
        if (updated != t) {
            gc = gc.copy(trees = gc.trees.map {
                if (it.point == currentPoint && it.number == number) updated else it
            })
        }
    }

    private fun recolor(col: Int, comp: JComponent) {
        val text = when (comp) {
            is JTextField -> comp.text
            is JComboBox<*> -> comp.selectedItem?.toString().orEmpty()
            else -> ""
        }
        val lvl = when (col) {
            0 -> FieldChecks.species(text)
            1 -> FieldChecks.dbhText(text)
            2 -> FieldChecks.treeClass(text)
            3 -> FieldChecks.crownClass(text.trim().lowercase())
            4 -> FieldChecks.height(text.toDoubleOrNull())
            5 -> FieldChecks.ageCore(text.toIntOrNull())
            6 -> FieldChecks.dist(text.toDoubleOrNull())
            7 -> FieldChecks.slope(text.toDoubleOrNull())
            else -> FieldChecks.Level.OK
        }
        val bg = when (lvl) {
            FieldChecks.Level.ERROR -> Color(0xFF, 0xC8, 0xC8)
            FieldChecks.Level.RED -> Color(0xFF, 0xF6, 0xE5)
            else -> Gc.GREEN_ED
        }
        comp.background = bg
    }

    private fun tintInOut(number: Int) {
        val t = gc.trees.firstOrNull { it.point == currentPoint && it.number == number } ?: return
        val k = key(t)
        val num = numberLabels[k] ?: return
        val glyph = glyphLabels[k] ?: return
        val cells = rowCells[k] ?: return
        val default = Color.BLACK
        if (!t.isLive || (t.distM == null && !t.isOutNumber)) {
            num.foreground = default; glyph.text = ""; return
        }
        val baf = GcapCalc.bafFor(gc) ?: run { num.foreground = default; glyph.text = ""; return }
        val inTree = GcapCalc.isIn(t, baf)
        val color = if (inTree) Color(0x00, 0x77, 0x00) else Color(0xCC, 0x00, 0x00)
        num.foreground = color
        glyph.text = if (inTree) "<" else ">"
        glyph.foreground = color
    }

    private fun showInOutMessage(number: Int) {
        val t = gc.trees.firstOrNull { it.point == currentPoint && it.number == number } ?: return
        val sp = t.species.ifBlank { "tree" }
        val baf = GcapCalc.bafFor(gc)
        val msg = when {
            baf == null -> "Tree $number ($sp): no BAF entered, so it cannot be told yet whether it falls inside the plot"
            !t.isLive -> "Tree $number ($sp) is DEAD - not counted"
            t.isOutNumber -> "Tree $number ($sp) is an OUT tree (91-99) - age/height only"
            t.dbhCm == null -> "Tree $number ($sp) OUT - no DBH"
            t.distM == null -> "Tree $number ($sp) assumed IN - no distance (Dist) recorded"
            else -> {
                val margin = GcapCalc.marginCm(t, baf)
                if (GcapCalc.isIn(t, baf)) "Tree $number ($sp) IN by ${fmt1(margin ?: 0.0)} cm"
                else "Tree $number ($sp) OUT by ${fmt1(-(margin ?: 0.0))} cm"
            }
        }
        info(msg)
    }

    // ---- right calculators -------------------------------------------------

    private fun buildRightPanel(right: JPanel) {
        right.add(panelTitle.apply {
            background = Color(0xDD, 0xDD, 0xDD); isOpaque = true; font = Gc.boldFont
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
        })
        right.add(Box.createVerticalStrut(6))
        fun title(t: String) = JLabel(t).apply {
            background = Color(0xF2, 0xF2, 0xF2); isOpaque = true; font = Gc.boldFont
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
        }
        fun prow(label: String, value: JComponent) = JPanel(BorderLayout()).apply {
            add(JLabel(label).apply {
                background = Color(0xFF, 0x00, 0x00); foreground = Color.WHITE; isOpaque = true
                font = Gc.boldFont; preferredSize = Dimension(64, 24)
            }, BorderLayout.WEST)
            add(value, BorderLayout.CENTER)
            maximumSize = Dimension(Int.MAX_VALUE, 26)
            alignmentX = java.awt.Component.LEFT_ALIGNMENT
        }
        right.add(title("Height"))
        right.add(prow("Top", hTop))
        right.add(prow("Bot", hBot))
        right.add(prow("SDist", hSD))
        right.add(prow("Cor", hCor))
        right.add(prow("Height", hHeight))
        right.add(Box.createVerticalStrut(6))
        right.add(title("Age"))
        right.add(prow("Age_1.3", aCore))
        right.add(prow("Cor", aCor))
        right.add(prow("Total age", aTotal))
        right.add(prow("Site index", aSI))
        right.add(agedCount)
        right.add(Box.createVerticalGlue())
    }

    private fun updateAgedCount() {
        agedCount.text = "Aged ${gc.trees.count { it.ageCore != null }}/$MAX_AGED_TREES"
    }

    private fun armAge(number: Int) {
        val t = gc.trees.firstOrNull { it.point == currentPoint && it.number == number } ?: return
        ageTarget = number
        aCore.text = t.ageCore?.toString() ?: ""
        panelTitle.text = "Age → Tree $number (P$currentPoint)"
        recomputeAge()
    }

    private fun recomputeHeight() {
        val tv = hTop.text.toDoubleOrNull()
        val bv = hBot.text.toDoubleOrNull()
        val sv = hSD.text.toDoubleOrNull()
        if (tv == null || bv == null || sv == null) { hHeight.text = "—"; return }
        val cv = hCor.text.toDoubleOrNull() ?: 0.0
        hHeight.text = fmt1(GcapCalc.heightFromSloped(sv, tv, bv, cv))
    }

    private fun recomputeAge() {
        val n = ageTarget ?: return
        val t = gc.trees.firstOrNull { it.point == currentPoint && it.number == n } ?: return
        val core = aCore.text.toIntOrNull()
        aCor.text = "—"; aTotal.text = "—"; aSI.text = "—"
        if (core == null) { updateAgedCount(); return }
        val h = t.heightM
        if (h == null || h <= 1.3) { warn("Enter the tree Height first (H > 1.3 m)"); return }
        val already = t.ageCore != null
        val counted = gc.trees.count { it.ageCore != null }
        if (!already && counted >= MAX_AGED_TREES) { warn("Only $MAX_AGED_TREES aged trees per sample"); return }
        val (estSI, ytbh) = ageCorrection(core, h, t.species)
        aSI.text = if (estSI != null) fmt1(estSI) else "—"
        val totalR = Math.round(core + ytbh).toInt()
        aCor.text = fmt1(ytbh)
        aTotal.text = totalR.toString()
        writeCell(n, 5, totalR.toString()) { it.copy(ageCore = core, ageCor = ytbh) }
        updateAgedCount()
    }

    private fun ageCorrection(core: Int, h: Double, species: String): Pair<Double?, Double> {
        val ybhProxy = GcapCalc.yearsToBreastHeight(core, h)!!
        val guidance = SpeciesSiteIdx.guidance(species)
        if (guidance == null) return null to ybhProxy
        val index = SpeciesSiteIdx.indexFor(species)
        val si1 = (50.0 * h / core).coerceIn(4.0, 60.0)
        val cor1 = Y2bhTable.get(index, si1).takeIf { it > 0.0 } ?: ybhProxy
        val roughTotal = core + cor1
        val estSI = (50.0 * h / roughTotal).coerceIn(4.0, 60.0)
        val ytbh = Y2bhTable.get(index, estSI).takeIf { it > 0.0 } ?: ybhProxy
        return estSI to ytbh
    }

    private fun flushAgedRows() {
        for (t in gc.trees) {
            if (t.point != currentPoint) continue
            val core = t.ageCore ?: continue
            val h = t.heightM ?: continue
            if (h <= 1.3) continue
            val (_, ytbh) = ageCorrection(core, h, t.species)
            val totalR = Math.round(core + ytbh).toInt()
            writeCell(t.number, 5, totalR.toString()) { it.copy(ageCore = core, ageCor = ytbh) }
        }
    }

    private fun writeCell(number: Int, idx: Int, text: String, upd: (Tree) -> Tree) {
        val t = gc.trees.firstOrNull { it.point == currentPoint && it.number == number } ?: return
        val cells = rowCells[key(t)] ?: return
        val cell = cells.getOrNull(idx) ?: return
        gc = gc.copy(trees = gc.trees.map { if (it.point == currentPoint && it.number == number) upd(it) else it })
        if (cell is JTextField && cell.text != text) {
            cell.text = text
        } else if (cell is JComboBox<*> && cell.selectedItem?.toString() != text) {
            cell.selectedItem = text
        }
    }

    // ---- actions -----------------------------------------------------------

    private fun addTree() {
        val next = (gc.trees.filter { it.point == currentPoint }.maxOfOrNull { it.number } ?: 0) + 1
        gc = gc.copy(trees = gc.trees + Tree(number = next, point = currentPoint, measured = true, dbhRaw = ""))
        rebuildGrid()
    }

    private fun deleteTree() {
        val n = highlighted
        if (n == null) { warn("Tap a tree row first"); return }
        val t = gc.trees.firstOrNull { it.point == currentPoint && it.number == n } ?: return
        val label = t.species.ifBlank { "(unspec.)" }
        val ok = JOptionPane.showConfirmDialog(
            this, "Removes this tree and its data.", "Delete tree $n ($label) from P$currentPoint?",
            JOptionPane.OK_CANCEL_OPTION,
        )
        if (ok == JOptionPane.OK_OPTION) {
            gc = gc.copy(trees = gc.trees.filterNot { it.point == currentPoint && it.number == n })
            if (ageTarget == n) ageTarget = null
            rebuildGrid()
        }
    }

    private fun deletePoint() {
        if (gc.nPoints <= 1) { warn("Sample needs >= 1 point"); return }
        val ok = JOptionPane.showConfirmDialog(
            this, "Removes point $currentPoint; later points re-number down.", "Delete point P$currentPoint?",
            JOptionPane.OK_CANCEL_OPTION,
        )
        if (ok != JOptionPane.OK_OPTION) return
        val keep = gc.trees.filter { it.point != currentPoint }
        gc = gc.copy(trees = keep.map { if (it.point < currentPoint) it else it.copy(point = it.point - 1) })
        currentPoint = currentPoint.coerceAtMost(gc.nPoints.coerceAtLeast(1))
        resetTargets(); setupPointTabs(); rebuildGrid()
    }

    private fun resetTargets() {
        ageTarget = null
        panelTitle.text = "Tap Age cell"
        aCore.text = ""
        aTotal.text = "—"
        updateAgedCount()
    }

    private fun save(quiet: Boolean = false) {
        commitAll()
        val conflicts = findLegacyConflicts(gc.trees)
        if (conflicts.isEmpty()) {
            repo.save(gc); if (!quiet) info("Saved ${gc.sampleId}.dat")
            return
        }
        if (resolveLegacy(conflicts, 0)) {
            repo.save(gc)
            rebuildGrid()
            if (!quiet) info("Saved ${gc.sampleId}.dat")
        }
    }

    private fun resolveLegacy(list: List<LegacyConflict>, idx: Int): Boolean {
        if (idx >= list.size) return true
        val c = list[idx]
        val choice = JOptionPane.showOptionDialog(
            this,
            "What letter to use for CCls at Tree number ${c.number} at Point ${c.point}?",
            "CCls conflict", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
            null, arrayOf("Existing: ${c.existing}", "Incoming: ${c.incoming}"), "Existing: ${c.existing}",
        )
        if (choice < 0) return false
        val letter = if (choice == 1) c.incoming else c.existing
        gc = gc.copy(trees = applyCrownChoice(gc.trees, c, letter))
        val del = JOptionPane.showConfirmDialog(
            this,
            "Should letter \"${c.incoming}\" be deleted from tree number ${c.number} at Point ${c.point}?",
            "Delete TCls letter?", JOptionPane.YES_NO_OPTION,
        )
        if (del != JOptionPane.YES_OPTION) return false
        gc = gc.copy(trees = applyDeleteTcls(gc.trees, c))
        return resolveLegacy(list, idx + 1)
    }

    private fun exportIndividual() {
        org.gcap.core.OdsExporter.write(gc, repo.storage)
    }

    private fun commitAll() {
        val pending = bindings.map { Triple(it, it.read().trim(), it.start.trim()) }
        var changed = false
        for ((b, now, before) in pending) if (now != before) { b.apply(now); changed = true }
        return
    }

    private fun dbhTextOf(t: Tree): String = (t.dbhRaw ?: "").ifEmpty { Gc.fmt(t.dbhCm) }

    private fun one(v: Double): String =
        if (v == Math.floor(v) && !v.isInfinite()) v.toLong().toString() else v.toString()

    private fun fmt1(v: Double): String {
        val r = Math.round(v * 10) / 10.0
        return if (r == r.toInt().toDouble()) r.toInt().toString() else r.toString()
    }

    private fun info(msg: String) = JOptionPane.showMessageDialog(this, msg)
    private fun warn(msg: String) = JOptionPane.showMessageDialog(this, msg, "Notice", JOptionPane.WARNING_MESSAGE)

    private fun simple(f: () -> Unit) = object : javax.swing.event.DocumentListener {
        override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = f()
        override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = f()
        override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = f()
    }

    companion object {
        private const val MAX_AGED_TREES = 6
    }
}
