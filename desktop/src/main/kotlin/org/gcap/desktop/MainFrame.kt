package org.gcap.desktop

import org.gcap.core.AllXgvShpExporter
import org.gcap.core.AllXgvSummaryExporter
import org.gcap.core.GroundCall
import org.gcap.core.OdsExporter
import org.gcap.core.QaSummaryExporter
import org.gcap.core.SampleNaming
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.GridLayout
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * The one desktop window. Each Android Activity becomes a page swapped into the
 * centre pane; navigation is by method call rather than an Intent.
 */
class MainFrame(
    val repo: SampleRepo,
    val settings: DesktopSettings,
) : JFrame("Ground Call Capture - GCap Linux Desktop") {

    private val content = JPanel(BorderLayout())
    private val status = JLabel()
    private var current: JComponent? = null

    init {
        defaultCloseOperation = EXIT_ON_CLOSE
        addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowClosing(e: java.awt.event.WindowEvent) {
                // Let the open page flush unsaved edits (autosave may be off).
                (current as? Page)?.onHide()
            }
        })
        setSize(1000, 760)
        setLocationRelativeTo(null)
        content.background = Gc.GRAY_LBL
        add(content, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)
        minimumSize = Dimension(760, 560)
        showMenu()
    }

    fun refreshStatus() {
        status.text = "Folder: ${repo.storage.describe()}  (${repo.samples().size} samples)"
    }

    private fun showPage(component: JComponent) {
        (current as? Page)?.onHide()
        current = component
        content.removeAll()
        content.add(component, BorderLayout.CENTER)
        content.revalidate()
        content.repaint()
        (component as? Page)?.onShow()
        refreshStatus()
    }

    fun showMenu() = showPage(MainMenuPanel(this))
    fun showSummary(id: String) = showPage(SummaryPanel(this, id))
    fun showPoints(id: String) = showPage(PointsPanel(this, id))
    fun showComments(id: String) = showPage(CommentsPanel(this, id))
    fun showSettings() = showPage(SettingsPanel(this))
    fun showGpsMap(sampleId: String? = null) = showPage(GpsMapPanel(this, sampleId))
    fun showLayers() = showPage(LayerSettingsPanel(this))

    fun chooseDataFolder() {
        val chooser = javax.swing.JFileChooser(repo.storage.folder).apply {
            fileSelectionMode = javax.swing.JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "Choose ground-call data folder"
        }
        if (chooser.showOpenDialog(this) == javax.swing.JFileChooser.APPROVE_OPTION) {
            repo.setFolder(chooser.selectedFile)
            refreshStatus()
        }
    }

    fun newCall() {
        val input = JTextField()
        val pane = JPanel(GridLayout(2, 1, 4, 4)).apply {
            add(JLabel("Photo id shown on your camera file name"))
            add(input)
        }
        while (true) {
            val ok = JOptionPane.showConfirmDialog(
                this, pane, "New Ground Call", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE,
            )
            if (ok != JOptionPane.OK_OPTION) return
            val id = input.text.trim()
            when {
                id.isEmpty() -> warn("Enter a name first")
                !id.matches(Regex("[A-Za-z0-9]{1,6}")) -> warn("Letters/digits only (max 6)")
                repo.sampleExists(id) -> warn("Sample $id already exists")
                else -> {
                    val gc = GroundCall(
                        version = "v5.1.1 6/8/26", sampleId = id, sampleType = "mp",
                        entryDate = SimpleDateFormat("M/d/yy").format(Date()),
                    )
                    repo.save(gc)
                    showSummary(id)
                    return
                }
            }
        }
    }

    fun openSample() {
        val samples = repo.samples()
        if (samples.isEmpty()) {
            JOptionPane.showMessageDialog(
                this,
                "Folder: ${repo.storage.describe()}\n\nPut .dat files there, or choose " +
                    "'Data folder...' to point the app at your Groundcalls folder.",
                "No samples", JOptionPane.WARNING_MESSAGE,
            )
            return
        }
        val names = samples.map {
            val n = it.nPoints
            "${it.sampleId}.dat   (${n} pt${if (n == 1) "" else "s"}, ${it.trees.size} trees)"
        }.toTypedArray()
        val pick = JOptionPane.showInputDialog(
            this, "Open sample:", "Open sample",
            JOptionPane.PLAIN_MESSAGE, null, names, names.firstOrNull(),
        ) as? String ?: return
        val gc = samples[names.indexOf(pick)] ?: return
        val choice = JOptionPane.showOptionDialog(
            this, "Sample ${gc.sampleId}\nWhat do you want to do with this sample?",
            "Sample ${gc.sampleId}", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
            null, arrayOf("OPEN", "Save As", "Cancel"), "OPEN",
        )
        when (choice) {
            0 -> showSummary(gc.sampleId)
            1 -> saveAs(gc)
        }
    }

    fun saveAs(gc: GroundCall) {
        val raw = JOptionPane.showInputDialog(
            this,
            "Enter the new sample name (e.g. 0508, 0508-, 0508b)",
            gc.sampleId,
        ) ?: return
        val p = SampleNaming.plan(gc, { repo.sampleExists(it) }, raw)
        if (!p.canSave) { warn(p.error ?: "Cannot save"); return }
        if (p.unusual) warn("Unusual name")
        val renamed = gc.copy(sampleId = p.name)
        repo.save(renamed)
        showSummary(p.name)
    }

    fun exports() {
        val options = arrayOf(
            "1. XGVs_SUMMARY_for_SHP", "2. XGV_SUMMARY_for_QA",
            "3. CREATE_ALL_XGV_SHP", "4. CREATE_INDIVIDUAL_ODSs",
        )
        val which = JOptionPane.showOptionDialog(
            this, "Select an export:", "CREATE_XGVs_SUMMARY",
            JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, options, options[0],
        )
        when (which) {
            0 -> runExport {
                val (filename, _) = AllXgvSummaryExporter.generate(repo.storage, repo.samples())
                "Created $filename in XGV_ODS_XLSX"
            }
            1 -> runExport {
                val (filename, _) = QaSummaryExporter.generate(repo.storage, repo.samples())
                "Created $filename in XGV_ODS_XLSX"
            }
            2 -> {
                val crew = JOptionPane.showInputDialog(this, "Enter crew abbreviation (e.g. vri):", "vri")
                    ?: return
                runExport {
                    val (fileName, count) = AllXgvShpExporter.generate(
                        repo.storage, repo.samples(), crew.trim().ifEmpty { "vri" },
                    )
                    "Created $fileName with $count points"
                }
            }
            3 -> runExport {
                var n = 0
                for (gc in repo.samples()) if (OdsExporter.write(gc, repo.storage)) n++
                "Created $n individual ODS+XLSX in XGV_ODS_XLSX"
            }
        }
    }

    private fun runExport(block: () -> String) {
        try {
            val msg = block()
            JOptionPane.showMessageDialog(this, msg)
        } catch (e: Exception) {
            JOptionPane.showMessageDialog(this, "Failed: ${e.message}", "Error", JOptionPane.ERROR_MESSAGE)
        }
    }

    fun about() {
        JOptionPane.showMessageDialog(
            this,
            "Linux desktop port of the GCap v5.1.1 ground-call data collector " +
                "(BC VRI calibration). Writes byte-compatible .dat files.\n\n" +
                "Slots: # Sp DBH TCls CCls Hgt Age Dist Slope; up to 40 trees/point; " +
                "1..n points; BAF 3-30 or fixed radius.\n\n" +
                "Extended fields (BAF, VC, CC, snags, clinometer angles) live in a " +
                "sidecar <id>.gc.json beside the .dat.",
            "About Ground Call Capture", JOptionPane.INFORMATION_MESSAGE,
        )
    }

    private fun warn(msg: String) = JOptionPane.showMessageDialog(this, msg, "Notice", JOptionPane.WARNING_MESSAGE)
}

/** A page can react when it is swapped in or out (start/stop autosave, gpsd). */
interface Page {
    fun onShow() {}
    fun onHide() {}
}

/** The main menu: New Call / Open Sample / Settings / Data folder / Exports / GPS / Map / About. */
class MainMenuPanel(private val frame: MainFrame) : JPanel(), Page {

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = javax.swing.BorderFactory.createEmptyBorder(20, 24, 20, 24)
        background = java.awt.Color.WHITE

        add(JLabel("Ground Call Capture").apply { font = Gc.titleFont; alignmentX = java.awt.Component.LEFT_ALIGNMENT })
        add(JLabel("BC VRI field data capture  ·  .dat editor").apply {
            foreground = java.awt.Color(0x66, 0x66, 0x66); alignmentX = java.awt.Component.LEFT_ALIGNMENT
        })
        add(Box.createVerticalStrut(16))

        fun addBtn(label: String, bg: java.awt.Color = Gc.MENU_YELLOW, onClick: () -> Unit) {
            add(Gc.menuButton(label, bg, onClick))
            add(Box.createVerticalStrut(8))
        }

        addBtn("New Call…") { frame.newCall() }
        addBtn("Open Sample…") { frame.openSample() }
        addBtn("Settings…") { frame.showSettings() }
        addBtn("Data folder…") { frame.chooseDataFolder() }
        addBtn("CREATE_XGVs_SUMMARY…") { frame.exports() }
        addBtn("GPS / Map…") { frame.showGpsMap() }
        addBtn("Layer settings…") { frame.showLayers() }
        addBtn("About…") { frame.about() }
        addBtn("Exit", Gc.ORANGE_BT) { System.exit(0) }

        add(Box.createVerticalStrut(12))
        val path = JLabel("Folder: ${frame.repo.storage.describe()}")
            .apply { font = java.awt.Font("SansSerif", java.awt.Font.PLAIN, 11); alignmentX = java.awt.Component.LEFT_ALIGNMENT }
        add(path)

        // list of samples underneath, so the landing page is useful
        add(Box.createVerticalStrut(10))
        val list = javax.swing.JList(frame.repo.samples().map { gc ->
            val n = gc.nPoints
            "${gc.sampleId}.dat   (${n} pt${if (n == 1) "" else "s"}, ${gc.trees.size} trees)"
        }.toTypedArray())
        list.addListSelectionListener {
            if (!it.valueIsAdjusting && list.selectedIndex >= 0) {
                frame.showSummary(frame.repo.samples()[list.selectedIndex].sampleId)
            }
        }
        add(JScrollPane(list).apply { alignmentX = LEFT_ALIGNMENT })
    }
}
