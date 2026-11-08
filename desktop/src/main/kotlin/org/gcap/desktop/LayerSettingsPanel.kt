package org.gcap.desktop

import org.gcap.core.ShpFormat
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.GridLayout
import java.io.File
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField

/**
 * Desktop Layer settings page: one block per GPS_MAP shapefile controlling how
 * it is drawn. Saved to layer_settings.json in the data folder, so phone and
 * desktop agree.
 */
class LayerSettingsPanel(private val frame: MainFrame) : JPanel(BorderLayout()), Page {

    private class Row(val name: String, val isPoint: Boolean, val fields: List<String>, var spec: LayerSpec)

    private val rowsList = mutableListOf<Row>()
    private val list = JPanel()
    private val dir: File get() = File(frame.repo.storage.folder, "GPS_MAP")

    init {
        border = BorderFactory.createEmptyBorder(6, 6, 6, 6)
        val top = JPanel(BorderLayout())
        top.add(JLabel("Layer settings").apply { font = Gc.titleFont }, BorderLayout.WEST)
        val btns = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        btns.add(Gc.button("Save", Gc.ORANGE_BT) { save() })
        btns.add(Gc.button("Cancel") { frame.showGpsMap() })
        top.add(btns, BorderLayout.SOUTH)
        add(top, BorderLayout.NORTH)
        list.layout = BoxLayout(list, BoxLayout.Y_AXIS)
        add(JScrollPane(list), BorderLayout.CENTER)
        loadRows()
        redraw()
    }

    override fun onShow() = Unit

    private fun loadRows() {
        val saved = LayerSpecs.parse(runCatching { File(frame.repo.storage.folder, LayerSpecs.FILE).readText() }.getOrNull())
        dir.mkdirs()
        val all = dir.listFiles().orEmpty()
        val stems = all.filter { it.name.endsWith(".shp", true) }.map { it.name.substringBeforeLast('.') }.distinct()
        val ordered = saved.map { it.name }.filter { n -> stems.any { it.equals(n, true) } } +
            stems.filter { st -> saved.none { it.name.equals(st, true) } }
        rowsList.clear()
        for (name in ordered) {
            val shp = all.firstOrNull { it.name.substringBeforeLast('.').equals(name, true) && it.name.endsWith(".shp", true) } ?: continue
            val head = runCatching { shp.readBytes() }.getOrNull()
            val shape = head?.let { runCatching { ShpFormat.shpHeader(it) }.getOrNull() }
            val isPoint = shape == null || shape.shapeType in listOf(
                ShpFormat.TYPE_POINT, ShpFormat.TYPE_POINTZ, ShpFormat.TYPE_MULTIPOINT, ShpFormat.TYPE_MULTIPOINTZ)
            val dbf = all.firstOrNull { it.name.substringBeforeLast('.').equals(name, true) && it.name.endsWith(".dbf", true) }
            val fields = dbf?.let { runCatching { ShpFormat.dbfHeader(it.readBytes()).fields.map { f -> f.name } }.getOrNull() }.orEmpty()
            val spec = saved.firstOrNull { it.name.equals(name, true) }
                ?: LayerSpecs.defaults().firstOrNull { it.name.equals(name, true) }
                ?: LayerSpec(name, symbol = if (isPoint) "circle" else "single",
                    color = if (isPoint) "orange" else "violet", size = if (isPoint) 6.0 else 2.0)
            rowsList.add(Row(name, isPoint, fields, spec.copy(name = name)))
        }
    }

    private fun redraw() {
        list.removeAll()
        if (rowsList.isEmpty()) {
            list.add(JLabel("No shapefiles in the GPS_MAP folder yet."))
        }
        for ((i, row) in rowsList.withIndex()) list.add(rowView(i, row))
        list.revalidate(); list.repaint()
    }

    private fun rowView(idx: Int, row: Row): JPanel {
        val box = JPanel(BorderLayout())
        box.border = BorderFactory.createEmptyBorder(8, 0, 12, 0)
        box.maximumSize = java.awt.Dimension(Int.MAX_VALUE, 220)

        val head = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        head.add(JLabel("${idx + 1}. ${row.name}   (${if (row.isPoint) "point" else "line"})").apply { font = Gc.boldFont })
        val show = JCheckBox("Show", row.spec.visible)
        show.addActionListener { row.spec = row.spec.copy(visible = show.isSelected) }
        head.add(show)
        head.add(Gc.button("Up") { moveRow(idx, -1) })
        head.add(Gc.button("Down") { moveRow(idx, 1) })

        val fieldsPanel = JPanel(GridLayout(0, 2, 6, 4))
        val symbols = if (row.isPoint) LayerSpecs.POINT_SYMBOLS else LayerSpecs.LINE_SYMBOLS
        val symbolCombo = JComboBox(symbols.toTypedArray()).apply {
            selectedItem = row.spec.symbol.takeIf { it in symbols } ?: symbols.first()
            addActionListener { row.spec = row.spec.copy(symbol = selectedItem?.toString() ?: symbols.first()) }
        }
        fieldsPanel.add(JLabel("Symbol")); fieldsPanel.add(symbolCombo)

        val colourCombo = JComboBox(LayerSpecs.COLOURS.keys.toTypedArray()).apply {
            selectedItem = row.spec.color
            addActionListener { row.spec = row.spec.copy(color = selectedItem?.toString() ?: "orange") }
        }
        fieldsPanel.add(JLabel("Colour")); fieldsPanel.add(colourCombo)

        val sizeField = JTextField(row.spec.size.toString())
        sizeField.document.addDocumentListener(simple {
            sizeField.text.toDoubleOrNull()?.let { v -> row.spec = row.spec.copy(size = v.coerceIn(0.5, 40.0)) }
        })
        fieldsPanel.add(JLabel(if (row.isPoint) "Size (px)" else "Line width (px)")); fieldsPanel.add(sizeField)

        val labelNames = listOf("(none)") + row.fields
        val labelCombo = JComboBox(labelNames.toTypedArray()).apply {
            selectedItem = row.spec.label.takeIf { it in labelNames } ?: "(none)"
            addActionListener {
                val v = selectedItem?.toString().orEmpty()
                row.spec = row.spec.copy(label = if (v == "(none)") "" else v)
            }
        }
        fieldsPanel.add(JLabel("Label field")); fieldsPanel.add(labelCombo)

        val labelSizeField = JTextField(row.spec.labelSize.toString())
        labelSizeField.document.addDocumentListener(simple {
            labelSizeField.text.toDoubleOrNull()?.let { v -> row.spec = row.spec.copy(labelSize = v.coerceIn(3.0, 30.0)) }
        })
        fieldsPanel.add(JLabel("Label size (pt)")); fieldsPanel.add(labelSizeField)

        box.add(head, BorderLayout.NORTH)
        box.add(fieldsPanel, BorderLayout.CENTER)
        return box
    }

    private fun moveRow(idx: Int, delta: Int) {
        val j = idx + delta
        if (j !in rowsList.indices) return
        val t = rowsList[idx]; rowsList[idx] = rowsList[j]; rowsList[j] = t
        redraw()
    }

    private fun save() {
        val specs = rowsList.map { it.spec }
        val ok = frame.repo.storage.writeBytes(LayerSpecs.FILE, LayerSpecs.write(specs).toByteArray(Charsets.UTF_8))
        if (ok) {
            JOptionPane.showMessageDialog(this, "Layer settings saved (${specs.size} layers)")
            frame.showGpsMap()
        } else {
            JOptionPane.showMessageDialog(this, "Could not save layer_settings.json - check the data folder",
                "Error", JOptionPane.ERROR_MESSAGE)
        }
    }

    private fun simple(f: () -> Unit) = object : javax.swing.event.DocumentListener {
        override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = f()
        override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = f()
        override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = f()
    }
}
