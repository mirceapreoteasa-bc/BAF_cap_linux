package org.gcap.desktop

import org.gcap.core.GroundCall
import org.gcap.core.NtsSheets
import org.gcap.core.OdsExporter
import org.gcap.core.ShpAuxiliaryExporter
import org.gcap.core.ShpFormat
import org.gcap.core.UtmPoint
import org.gcap.core.UtmProj
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextField
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Desktop port of the Android GpsActivity: one combined GPS + GPS_MAP screen.
 *
 * Left column: the 5 x 15 readout grid (Gcall, capture P1/P2/P3, the UTM
 * capture fields, the sample readouts and the Use/Save/Summary actions).
 * Right: the GPS_MAP canvas with the Android 15-button map-function bar, which
 * includes functional shapefile editing of the XGV_MP point layer.
 */
class GpsMapPanel(
    private val frame: MainFrame,
    private val sampleId: String?,
) : JPanel(BorderLayout()), Page {

    private val repo = frame.repo
    private var gc: GroundCall? = sampleId?.let { repo.byId(it) }

    private val pointIds = listOf("P1", "P2", "P3")
    private val pointUtmX = mutableMapOf<String, JTextField>()
    private val pointUtmY = mutableMapOf<String, JTextField>()
    private val pointElev = mutableMapOf<String, JTextField>()
    private val tieUtmX = mutableListOf<JTextField>()
    private val tieUtmY = mutableListOf<JTextField>()
    private val tieElev = mutableListOf<JTextField>()

    private val mapsheetV = blueValue("")
    private val satV = blueValue("")
    private val precV = blueValue("")
    private val dateV = blueValue("")
    private val timeV = blueValue("")
    private val zoneV = blueValue("")
    private val elevV = blueValue("")

    private val status = JLabel("Starting GPS…").apply {
        font = Font("SansSerif", Font.PLAIN, 11)
        border = BorderFactory.createEmptyBorder(2, 6, 2, 6)
    }

    private var chosenUse = -1
    private var latestFix: Fix? = null

    private val canvas = MapCanvas()

    private var mapStarted = false
    private var editing = false
    private var addArm = false
    private var selectArm = false
    private var mapShowP1P2P3 = true
    private var dirty = false

    private val editableName = "XGV_MP"
    private var layerSpecs: List<LayerSpec> = emptyList()

    private val addedPoints = mutableListOf<EditPoint>()
    private val deletedIdx = mutableSetOf<Int>()
    private var origEditable: List<ShpFormat.Shape> = emptyList()
    private var editableLabels: List<String> = emptyList()
    private var lastLoadedE = Double.NEGATIVE_INFINITY
    private var lastLoadedN = Double.NEGATIVE_INFINITY

    private var client: GpsdClient? = null
    private val autosave = Autosave(repo) { save(silent = true) }
    private val useBtns = mutableListOf<JButton>()

    data class EditPoint(val e: Double, val n: Double, val name: String)

    private val mapDir: File get() = File(repo.storage.folder, "GPS_MAP")

    init {
        border = BorderFactory.createEmptyBorder(4, 4, 4, 4)

        val split = JPanel(BorderLayout())
        split.add(buildGrid(), BorderLayout.WEST)

        val mapPanel = JPanel(BorderLayout())
        mapPanel.add(buildMapBar(), BorderLayout.NORTH)
        canvas.onMapTap = { e, n ->
            when {
                addArm -> placePoint(e, n)
                selectArm -> selectFeatureAt(e, n)
            }
        }
        mapPanel.add(canvas, BorderLayout.CENTER)
        split.add(mapPanel, BorderLayout.CENTER)

        add(split, BorderLayout.CENTER)
        add(status, BorderLayout.SOUTH)

        gc?.let { prefill(it) }
        refreshUseButtons()
    }

    // ---- layout ------------------------------------------------------------

    private fun buildGrid(): JPanel {
        val top = JPanel().apply {
            layout = GridLayout(12, 1, 0, 2)
            preferredSize = Dimension(660, 0)
            border = BorderFactory.createEmptyBorder(0, 0, 0, 4)
        }

        // R1  Gcall | call name | | Save &Exit | EXIT
        top.add(row(
            red("Gcall"), blueValue(gc?.sampleId ?: "—"), spacer(),
            actBtn("Save &Exit", ORANGE_BT) { saveExit() }, actBtn("EXIT", ORANGE_BT) { exit() },
        ))
        // R2  Collect GPS to capture | P1 | P2 | P3
        top.add(row(
            red("<html>Collect GPS<br>to capture</html>"),
            actBtn("P1", YELLOW_BT) { collect(0) },
            actBtn("P2", YELLOW_BT) { collect(1) },
            actBtn("P3", YELLOW_BT) { collect(2) },
            spacer(),
        ))
        // R3..R11 point fields + readouts
        top.add(row(red("P1_x"), pointField(pointUtmX, "P1"), spacer(), red("Mapsheet"), mapsheetV))
        top.add(row(red("P1_y"), pointField(pointUtmY, "P1"), spacer(), red("Satellites"), satV))
        top.add(row(red("P1_z"), pointField(pointElev, "P1"), spacer(), red("Precision"), precV))
        top.add(row(red("P2_x"), pointField(pointUtmX, "P2"), spacer(), red("Date"), dateV))
        top.add(row(red("P2_y"), pointField(pointUtmY, "P2"), spacer(), red("Time"), timeV))
        top.add(row(red("P2_z"), pointField(pointElev, "P2"), spacer(), red("UTM Zone"), zoneV))
        top.add(row(red("P3_x"), pointField(pointUtmX, "P3"), spacer(), red("Elevation"), elevV))
        top.add(row(red("P3_y"), pointField(pointUtmY, "P3"), spacer(), spacer(), spacer()))
        top.add(row(red("P3_z"), pointField(pointElev, "P3"), spacer(), spacer(), spacer()))
        // R12 Use_P1 | Use_P2 | Use_P3 | Save_coordinates | Summary
        top.add(row(
            useBtn("Use_P1_xy", 0), useBtn("Use_P2_xy", 1), useBtn("Use_P3_xy", 2),
            actBtn("Save_coordinates", ORANGE_BT) { saveWithSheetCheck { } },
            actBtn("Summary", YELLOW_BT) { saveSummary() },
        ))
        return top
    }

    private fun buildMapBar(): JPanel {
        val labels = arrayOf(
            "Start_MAP", "Start Editing", "Save Edits", "Stop Editing", "Add_Point",
            "Add layer", "Remove layer", "Available layers", "Select feature", "Delete_Point",
            "Close_MAP", "Zoom_IN", "Zoom_OUT", "Unselect feature", "Show P1P2P3",
        )
        val actions: List<() -> Unit> = listOf(
            { startMap() }, { startEditing() }, { saveEdits() }, { stopEditing() },
            { armAddPoint() }, { pickLayer() }, { removeLayer() }, { listLayers() },
            { armSelect() }, { deletePoint() },
            { closeMap() }, { zoomIn() }, { zoomOut() }, { unselect() }, { toggleP1P2P3() },
        )
        val bar = JPanel(GridLayout(3, 5, 2, 1))
        val special = setOf(0, 1, 2, 3, 10) // Start_MAP, Start/Stop Editing, Save Edits, Close_MAP
        for (i in labels.indices) bar.add(mapBtn(labels[i], if (i in special) ORANGE_BT else YELLOW_BT, actions[i]))
        return bar
    }

    private fun row(vararg cells: JComponent): JPanel = JPanel(GridLayout(1, 5, 2, 1)).apply {
        for (c in cells) add(c)
    }

    private fun red(text: String): JLabel = JLabel(text).apply {
        font = Font("SansSerif", Font.BOLD, 11)
        foreground = Color(0xCC, 0x00, 0x00)
        verticalAlignment = JLabel.CENTER
    }

    private fun blueValue(text: String): JLabel = JLabel(text).apply {
        background = BLUE
        isOpaque = true
        font = Font("SansSerif", Font.PLAIN, 11)
        border = Gc.cellBorder(4)
    }

    private fun spacer(): JComponent = JPanel().apply { isOpaque = false }

    private fun actBtn(text: String, bg: Color, onClick: () -> Unit): JButton =
        Gc.button(text, bg, onClick).apply {
            font = Font("SansSerif", Font.PLAIN, 11)
            margin = java.awt.Insets(1, 2, 1, 2)
        }

    private fun useBtn(text: String, idx: Int): JButton = actBtn(text, YELLOW_BT) {
        chosenUse = idx
        refreshUseButtons()
    }.also { useBtns.add(it) }

    private fun mapBtn(text: String, bg: Color = YELLOW_BT, onClick: () -> Unit): JButton = Gc.button(text, bg, onClick).apply {
        font = Font("SansSerif", Font.PLAIN, 11)
        margin = java.awt.Insets(1, 1, 1, 1)
    }

    private fun numField(): JTextField = JTextField().apply {
        background = BLUE
        font = Font("SansSerif", Font.PLAIN, 11)
        border = Gc.cellBorder(3)
        columns = 8
    }

    private fun pointField(map: MutableMap<String, JTextField>, id: String): JTextField =
        numField().also { map[id] = it }

    // ---- page lifecycle ----------------------------------------------------

    override fun onShow() {
        client = GpsdClient(
            onFix = { fix -> SwingUtilities.invokeLater { onFix(fix) } },
            onStatus = { msg -> SwingUtilities.invokeLater { status.text = msg } },
        ).also { it.start() }
        autosave.start()
    }

    override fun onHide() {
        autosave.stop()
        client?.stop(); client = null
    }

    // ---- GPS ---------------------------------------------------------------

    private fun onFix(fix: Fix) {
        latestFix = fix
        if (fix.mode < 2 || fix.lat == null || fix.lon == null) {
            status.text = "No fix yet (mode ${fix.mode})"
            return
        }
        val z = meridianZone(fix.lon)
        val (e, n) = UtmProj.latLonToUtm(fix.lat, fix.lon, z)
        updateReadouts(fix, "${z}${if (fix.lat >= 0) "N" else "S"}")
        status.text = "Fix ok · ${fix.timeIso ?: ""}"
        if (mapStarted) {
            canvas.centerE = e; canvas.centerN = n
            canvas.setGpsMarker(e, n, fix.accuracyM ?: 0.0, true)
            loadViewport(false)
            refreshP1P2P3()
        }
    }

    private fun prefill(gc: GroundCall) {
        for ((n, pid) in pointIds.withIndex()) {
            val prev = gc.capture.utmPoints.getOrNull(n)?.takeIf { it.id == pid }
            pointUtmX[pid]!!.text = prev?.easting?.let { fmt(it) }.orEmpty()
            pointUtmY[pid]!!.text = prev?.northing?.let { fmt(it) }.orEmpty()
            pointElev[pid]!!.text = prev?.elevM?.let { fmt(it) }.orEmpty()
        }
        val tie = gc.capture.utmTiePoint
        if (tie != null) {
            tieUtmX.getOrNull(0)?.let { it.text = tie.easting?.let { e -> fmt(e) }.orEmpty() }
            tieUtmY.getOrNull(0)?.let { it.text = tie.northing?.let { n -> fmt(n) }.orEmpty() }
            tieElev.getOrNull(0)?.let { it.text = tie.elevM?.let { e -> fmt(e) }.orEmpty() }
        }
        zoneV.text = gc.capture.utmZone
        if (gc.mapSheet.isNotBlank()) mapsheetV.text = gc.mapSheet
        elevV.text = gc.capture.utmPoints.lastOrNull()?.elevM?.let { fmt(it) }.orEmpty()
        chosenUse = gc.capture.utmChosenIndex ?: pointIds.indices.lastOrNull() ?: -1
    }

    private fun collect(n: Int) {
        if (gc == null) { warn("Open a sample first"); return }
        val fix = latestFix ?: run { warn("No fix yet - wait for a position"); return }
        val lat = fix.lat ?: run { warn("No fix yet - wait for a position"); return }
        val lon = fix.lon ?: run { warn("No fix yet - wait for a position"); return }
        val z = meridianZone(lon)
        val (e, nN) = UtmProj.latLonToUtm(lat, lon, z)
        val pid = pointIds[n]
        pointUtmX[pid]!!.text = fmt(e)
        pointUtmY[pid]!!.text = fmt(nN)
        pointElev[pid]!!.text = fix.altM?.let { "%.1f".format(it) } ?: ""
        updateReadouts(fix, "${z}${if (lat >= 0) "N" else "S"}")
        refreshP1P2P3()
        toast("Captured $pid")
    }

    private fun updateReadouts(fix: Fix, zone: String) {
        satV.text = "0"
        precV.text = fix.accuracyM?.let { "±${it.toInt()} m" } ?: "-"
        val now = Date()
        dateV.text = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
        timeV.text = SimpleDateFormat("HH:mm:ss", Locale.US).format(now)
        zoneV.text = zone
        elevV.text = fix.altM?.let { "%.0f".format(it) } ?: ""
    }

    private fun meridianZone(lon: Double): Int =
        (Math.floor((lon + 180.0) / 6.0).toInt() + 1).coerceIn(1, 60)

    private fun fmt(v: Double): String = String.format(Locale.US, "%.3f", v).trimEnd('0').trimEnd('.')

    // ---- save --------------------------------------------------------------

    private fun buildGc(): GroundCall? {
        val base = gc ?: return null
        val formZone = zoneV.text.trim()
        val points = pointIds.mapNotNull { pid ->
            val xRaw = pointUtmX[pid]?.text?.toString().orEmpty()
            val yRaw = pointUtmY[pid]?.text?.toString().orEmpty()
            if (xRaw.isBlank() && yRaw.isBlank()) null
            else UtmPoint(
                id = pid,
                easting = xRaw.toDoubleOrNull(),
                northing = yRaw.toDoubleOrNull(),
                zone = formZone,
                elevM = pointElev[pid]?.text?.toString()?.toDoubleOrNull(),
            )
        }
        val tie = UtmPoint(
            id = "Tie_Point",
            easting = tieUtmX.getOrNull(0)?.text?.toString()?.toDoubleOrNull(),
            northing = tieUtmY.getOrNull(0)?.text?.toString()?.toDoubleOrNull(),
            elevM = tieElev.getOrNull(0)?.text?.toString()?.toDoubleOrNull(),
        ).takeIf { it.easting != null || it.northing != null }

        val chosenIdx = chosenUse.takeIf { it in points.indices } ?: points.indices.lastOrNull() ?: -1
        val chosen = points.getOrNull(chosenIdx)
        return base.copy(
            easting = chosen?.easting,
            northing = chosen?.northing,
            elevation = chosen?.elevM,
            capture = base.capture.copy(
                utmPoints = points,
                utmChosenIndex = if (chosenIdx in points.indices) chosenIdx else null,
                utmTiePoint = tie,
                utmZone = formZone,
            ),
        )
    }

    private fun save(silent: Boolean = false) {
        val g = buildGc() ?: return
        repo.save(g); gc = g
        if (!silent) toast("Saved ${g.sampleId}.dat")
    }

    private fun saveWithSheetCheck(after: () -> Unit) {
        val g = buildGc() ?: run { after(); return }
        val chosen = g.capture.utmChosenIndex?.let { g.capture.utmPoints.getOrNull(it) }
            ?: g.capture.utmPoints.lastOrNull()
        val newSheet = sheetFor(chosen?.easting, chosen?.northing, chosen?.zone, g)
        if (newSheet != null && newSheet != g.mapSheet) {
            val choice = JOptionPane.showOptionDialog(
                this,
                "New coordinates fall in mapsheet $newSheet (was ${g.mapSheet.ifEmpty { "(blank)" }}).\n" +
                    "Update the mapsheet in the .dat file?",
                "Mapsheet changed?", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
                null, arrayOf("Update", "Keep old", "Cancel"), "Update",
            )
            when (choice) {
                0 -> {
                    val updated = g.copy(mapSheet = newSheet)
                    mapsheetV.text = newSheet
                    repo.save(updated); gc = updated
                    toast("Saved ${updated.sampleId}.dat ($newSheet)")
                    after()
                }
                1 -> { repo.save(g); gc = g; toast("Saved ${g.sampleId}.dat"); after() }
            }
        } else {
            repo.save(g); gc = g
            toast("Saved ${g.sampleId}.dat")
            after()
        }
    }

    private fun zoneFor(g: GroundCall, pointZone: String): Int? {
        val digits = pointZone.filter { it.isDigit() }.takeIf { it.isNotEmpty() }
            ?: g.capture.utmZone.filter { it.isDigit() }.takeIf { it.isNotEmpty() }
            ?: g.headerLines?.getOrNull(141)?.filter { it.isDigit() }?.takeIf { it.isNotEmpty() }
            ?: return null
        return digits.toIntOrNull()
    }

    private fun sheetFor(e: Double?, n: Double?, pointZone: String?, g: GroundCall): String? {
        if (e == null || n == null) return null
        val zone = zoneFor(g, pointZone.orEmpty()) ?: return null
        return try { NtsSheets.mapsheetFor(e, n, zone) } catch (ex: Exception) { null }
    }

    private fun saveExit() {
        saveWithSheetCheck {
            if (editing) commitEdits()
            repo.samples().firstOrNull { it.sampleId == sampleId }?.let { OdsExporter.write(it, repo.storage) }
            frame.showMenu()
        }
    }

    private fun exit() {
        saveWithSheetCheck {
            if (editing) commitEdits()
            repo.samples().firstOrNull { it.sampleId == sampleId }?.let { OdsExporter.write(it, repo.storage) }
            frame.showMenu()
        }
    }

    private fun saveSummary() {
        save()
        if (sampleId != null) frame.showSummary(sampleId) else frame.showMenu()
    }

    // ---- GPS_MAP -----------------------------------------------------------

    private fun listMapFiles(): List<String> {
        mapDir.mkdirs()
        return mapDir.listFiles { f -> f.isFile }?.map { it.name }.orEmpty()
    }

    private fun readMapFile(name: String): ByteArray? =
        File(mapDir, name).takeIf { it.exists() }?.let { runCatching { it.readBytes() }.getOrNull() }

    private fun overwriteMapFile(name: String, bytes: ByteArray): Boolean = try {
        val f = File(mapDir, name); f.parentFile?.mkdirs(); f.writeBytes(bytes); true
    } catch (e: Exception) { false }

    private fun stem(name: String): String = name.substringBeforeLast('.')

    private fun shpFileFor(name: String): String? {
        val list = listMapFiles()
        return list.firstOrNull { stem(it).equals(name, true) && it.endsWith(".shp", true) }
            ?: list.firstOrNull { it.contains(name, true) && it.endsWith(".shp", true) }
    }

    private fun listFileFor(shpName: String, ext: String): String =
        listMapFiles().firstOrNull {
            it.equals(shpName.replace(".shp", ext, ignoreCase = true), ignoreCase = true)
        } ?: shpName.replace(".shp", ext, ignoreCase = true)

    private fun defaultLayers(): List<MapLayer> =
        LayerSpecs.defaults().map { d ->
            val style = styleFor(d.name)
            val kind = if (LayerSpecs.isPointSymbol(style.symbol)) MapLayer.Kind.POINT else MapLayer.Kind.LINE
            MapLayer(d.name, LayerSpecs.awtColorOf(style.color), style.size.toFloat(), kind, d.name == editableName)
                .also { applyStyle(it, style) }
        }

    private fun styleFor(name: String): LayerSpec = LayerSpecs.styleFor(name, layerSpecs)

    private fun applyStyle(layer: MapLayer, spec: LayerSpec) {
        layer.visible = spec.visible
        layer.symbol = spec.symbol
        layer.color = LayerSpecs.awtColorOf(spec.color)
        layer.sizeDp = spec.size
        layer.widthPx = spec.size.toFloat()
        layer.labelSizeSp = spec.labelSize
        layer.labelField = spec.label.takeIf { it.isNotBlank() }
        layer.labelColor = LayerSpecs.labelColor(spec.label)
    }

    private fun loadLayerSpecs() {
        layerSpecs = LayerSpecs.parse(
            runCatching { File(repo.storage.folder, LayerSpecs.FILE).readText() }.getOrNull(),
        )
    }

    private fun fieldLabels(shpFile: String, field: String): List<String> {
        val dbf = readMapFile(listFileFor(shpFile, ".dbf")) ?: return emptyList()
        return try {
            val fields = ShpFormat.dbfHeader(dbf).fields
            val idx = fields.indexOfFirst { it.name.equals(field, ignoreCase = true) }
            if (idx < 0) emptyList()
            else ShpFormat.readDbfRows(dbf).map { it.values.getOrNull(idx) ?: "" }
        } catch (e: Exception) { emptyList() }
    }

    private fun startMap() {
        if (mapStarted) { toast("GPS_MAP already started"); return }
        mapStarted = true
        canvas.started = true
        lastLoadedE = Double.NEGATIVE_INFINITY; lastLoadedN = Double.NEGATIVE_INFINITY
        loadLayerSpecs()
        for (d in defaultLayers()) {
            val f = shpFileFor(d.name) ?: continue
            d.file = f
            canvas.addLayer(d)
        }
        canvas.editableLayerName = editableName
        val c = sampleCenter()
            ?: latestFix?.let { UtmProj.latLonToUtm(it.lat ?: 0.0, it.lon ?: 0.0, meridianZone(it.lon ?: 0.0)) }
            ?: (0.0 to 0.0)
        canvas.centerE = c.first; canvas.centerN = c.second
        loadViewport(true)
        loadEditable()
        refreshP1P2P3()
        toast("GPS_MAP started")
    }

    private fun sampleCenter(): Pair<Double, Double>? {
        for (pid in pointIds) {
            val ex = pointUtmX[pid]?.text?.toString()?.toDoubleOrNull()
            val ny = pointUtmY[pid]?.text?.toString()?.toDoubleOrNull()
            if (ex != null && ny != null) return ex to ny
        }
        val tx = tieUtmX.getOrNull(0)?.text?.toString()?.toDoubleOrNull()
        val ty = tieUtmY.getOrNull(0)?.text?.toString()?.toDoubleOrNull()
        if (tx != null && ty != null) return tx to ty
        return null
    }

    private fun shutdownMap() {
        mapStarted = false
        canvas.started = false
        canvas.editing = false
        canvas.layers.clear()
        canvas.selected = null
        canvas.setP1P2P3(emptyList())
        editing = false; addArm = false; selectArm = false
        addedPoints.clear(); deletedIdx.clear(); dirty = false
        canvas.repaint()
    }

    private fun closeMap() {
        if (editing && dirty) promptSaveEdits { shutdownMap() } else shutdownMap()
    }

    private fun zoomIn() {
        if (!mapStarted) { toast("Start_MAP first"); return }
        canvas.scaleDenominator = if (canvas.scaleDenominator <= 2500) 2500 else canvas.scaleDenominator / 2
        loadViewport(true)
    }

    private fun zoomOut() {
        if (!mapStarted) { toast("Start_MAP first"); return }
        canvas.scaleDenominator = minOf(canvas.scaleDenominator * 2, 40000)
        loadViewport(true)
    }

    private fun toggleP1P2P3() {
        mapShowP1P2P3 = !mapShowP1P2P3
        canvas.showP1P2P3 = mapShowP1P2P3
        refreshP1P2P3()
        toast(if (mapShowP1P2P3) "Show P1P2P3 on" else "Show P1P2P3 off")
    }

    private fun refreshP1P2P3() {
        val markers = ArrayList<Triple<String, Double, Double>>()
        if (mapShowP1P2P3) for (pid in pointIds) {
            val ex = pointUtmX[pid]?.text?.toString()?.toDoubleOrNull()
            val ny = pointUtmY[pid]?.text?.toString()?.toDoubleOrNull()
            if (ex != null && ny != null) markers.add(Triple(pid, ex, ny))
        }
        canvas.setP1P2P3(markers)
    }

    private fun loadViewport(force: Boolean) {
        if (!mapStarted) return
        val (w, h) = canvas.windowMeters()
        val cx = canvas.centerE; val cy = canvas.centerN
        if (!force && Math.abs(cx - lastLoadedE) < w * 0.4 && Math.abs(cy - lastLoadedN) < h * 0.4) return
        lastLoadedE = cx; lastLoadedN = cy
        val x0 = cx - w / 2 - 50; val x1 = cx + w / 2 + 50
        val y0 = cy - h / 2 - 50; val y1 = cy + h / 2 + 50
        for (layer in canvas.layers.filter { it.name != editableName }) {
            val bytes = readMapFile(layer.file) ?: continue
            val shapes = runCatching { ShpFormat.readShp(bytes, x0, y0, x1, y1) }.getOrDefault(emptyList())
            layer.shapes = if (layer.labelField != null) {
                val labels = fieldLabels(layer.file, layer.labelField!!)
                if (labels.isEmpty()) shapes
                else shapes.map { it.copy(label = labels.getOrNull(it.recordIndex).orEmpty()) }
            } else shapes
        }
        canvas.repaint()
    }

    // ---- editing -----------------------------------------------------------

    private fun loadEditable() {
        val file = shpFileFor(editableName) ?: return
        val bytes = readMapFile(file) ?: return
        origEditable = runCatching { ShpFormat.readShp(bytes, -1e18, -1e18, 1e18, 1e18) }.getOrDefault(emptyList())
        val db = readMapFile(listFileFor(file, ".dbf"))
        editableLabels = if (db != null) {
            try {
                val fields = ShpFormat.dbfHeader(db).fields
                val idx = fields.indexOfFirst { it.name.equals("GCALL_ID", ignoreCase = true) }
                if (idx < 0) emptyList() else ShpFormat.readDbfRows(db).map { it.values.getOrNull(idx) ?: "" }
            } catch (e: Exception) { emptyList() }
        } else emptyList()
        deletedIdx.clear(); addedPoints.clear(); dirty = false
        reloadEditableView()
    }

    private fun editableDisplayShapes(): List<ShpFormat.Shape> {
        val out = ArrayList<ShpFormat.Shape>()
        val wantLabel = styleFor(editableName).label.isNotBlank()
        for ((i, s) in origEditable.withIndex()) if (i !in deletedIdx) {
            val lbl = if (!wantLabel) ""
            else editableLabels.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() } ?: "XGV_MP_${i + 1}"
            out.add(s.copy(label = lbl))
        }
        var idx = origEditable.size
        for (p in addedPoints) {
            out.add(ShpFormat.Shape(idx++, ShpFormat.TYPE_POINT, listOf(listOf(doubleArrayOf(p.e, p.n))), p.e, p.n, p.e, p.n, p.name))
        }
        return out
    }

    private fun reloadEditableView() {
        canvas.layers.firstOrNull { it.name == editableName }?.shapes = editableDisplayShapes()
        canvas.repaint()
    }

    private fun currentEditPoints(): List<EditPoint> {
        val out = ArrayList<EditPoint>()
        for ((i, s) in origEditable.withIndex()) if (i !in deletedIdx) {
            val c = s.parts.firstOrNull()?.firstOrNull() ?: continue
            val lbl = editableLabels.getOrNull(i)?.trim()?.takeIf { it.isNotEmpty() } ?: "XGV_MP_${i + 1}"
            out.add(EditPoint(c[0], c[1], lbl))
        }
        for (p in addedPoints) {
            if (out.none { it.name == p.name && Math.abs(it.e - p.e) < 0.01 && Math.abs(it.n - p.n) < 0.01 }) out.add(p)
        }
        return out
    }

    private fun startEditing() {
        if (!mapStarted) { toast("Start_MAP first"); return }
        if (canvas.layers.none { it.name == editableName }) { toast("$editableName not loaded - see Available layers"); return }
        if (editing) { toast("Already editing"); return }
        editing = true; addArm = false; selectArm = false
        canvas.editing = true
        toast("Editing $editableName")
    }

    private fun saveEdits() {
        if (!editing) { toast("Start Editing first"); return }
        commitEdits()
    }

    private fun commitEdits() {
        val pts = currentEditPoints()
        val shpN = shpFileFor(editableName) ?: run { toast("Editable shapefile not found"); return }
        val shxN = listFileFor(shpN, ".shx")
        val dbfN = listFileFor(shpN, ".dbf")
        val dbfOrig = readMapFile(dbfN) ?: run { toast("Editable DBF missing"); return }
        val coords = pts.map { doubleArrayOf(it.e, it.n) }
        val okShp = overwriteMapFile(shpN, ShpFormat.buildPointShp(coords))
        val okShx = overwriteMapFile(shxN, ShpFormat.buildPointShx(coords))
        val newDbf = ShpFormat.buildDbf(dbfOrig, pts.map { it.name }, "GCALL_ID")
        val okDbf = overwriteMapFile(dbfN, newDbf)
        // Use the on-disk stem, not a lowercased one: a case-sensitive filing system would
        // otherwise leave xgv_mp.prj/.cpg beside XGV_MP.*, and the next reader may take the
        // wrong sidecar.
        ShpAuxiliaryExporter.writeAuxFiles(repo.storage, stem(shpN))
        if (okShp && okShx && okDbf) {
            origEditable = pts.mapIndexed { i, p ->
                ShpFormat.Shape(i, ShpFormat.TYPE_POINT, listOf(listOf(doubleArrayOf(p.e, p.n))), p.e, p.n, p.e, p.n, p.name)
            }
            editableLabels = pts.map { it.name }
            dirty = false; addedPoints.clear(); deletedIdx.clear()
            reloadEditableView()
            toast("Edits saved to $editableName")
        } else {
            toast("Save failed - check folder write permission")
        }
    }

    private fun stopEditing() {
        if (!editing) { toast("Not editing"); return }
        promptSaveEdits { }
    }

    private fun stopEditingNow() {
        editing = false; addArm = false; selectArm = false
        canvas.editing = false
        toast("Editing stopped")
    }

    private fun promptSaveEdits(after: () -> Unit) {
        val choice = JOptionPane.showOptionDialog(
            this,
            "Stop editing the $editableName shapefile? Save the last edits, discard them, or cancel.",
            "Save edits?", JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE,
            null, arrayOf("Yes, save", "No, discard", "Cancel"), "Yes, save",
        )
        when (choice) {
            0 -> { commitEdits(); stopEditingNow(); after() }
            1 -> { loadEditable(); stopEditingNow(); after() }
        }
    }

    private fun armAddPoint() {
        if (!mapStarted) { toast("Start_MAP first"); return }
        if (!editing) { toast("Start Editing first"); return }
        addArm = true; selectArm = false
        toast("Click the map to place the point")
    }

    private val maxFixAgeMs = 60_000L

    private fun placePoint(e: Double, n: Double) {
        addArm = false
        val fix = latestFix
        if (fix == null) { warn("No GPS fix yet - wait for a position before adding a point."); return }
        val ageMs = (System.currentTimeMillis() - fix.atMs).coerceAtLeast(0)
        if (ageMs > maxFixAgeMs) { warn("GPS fix is ${ageMs / 1000} s old - wait for a current position."); return }
        val input = JTextField(gc?.sampleId.orEmpty()).apply { columns = 12 }
        val msg = JPanel(BorderLayout(4, 4)).apply {
            add(JLabel("Name stored in $editableName.dbf (GCALL_ID)"), BorderLayout.NORTH)
            add(input, BorderLayout.CENTER)
        }
        val ok = JOptionPane.showConfirmDialog(this, msg, "Add point", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
        if (ok != JOptionPane.OK_OPTION) return
        val name = input.text.trim().ifEmpty { gc?.sampleId.orEmpty() }
        if (addedPoints.any { it.name == name && Math.abs(it.e - e) < 0.01 && Math.abs(it.n - n) < 0.01 }) {
            toast("Point $name is already queued"); return
        }
        addedPoints.add(EditPoint(e, n, name))
        dirty = true
        reloadEditableView()
        toast("Point added: $name")
    }

    private fun armSelect() {
        if (!mapStarted) { toast("Start_MAP first"); return }
        selectArm = true; addArm = false
        toast("Click the feature to select it")
    }

    private fun selectAt(e: Double, n: Double): Pair<Int, ShpFormat.Shape>? {
        val (w, h) = canvas.windowMeters()
        val mppxE = w / canvas.width.coerceAtLeast(1)
        val mppxN = h / canvas.height.coerceAtLeast(1)
        var best: Pair<Int, ShpFormat.Shape>? = null
        var bestPx = 28.0
        for (li in canvas.layers.indices.reversed()) {
            val layer = canvas.layers[li]
            if (!layer.visible) continue
            for (s in layer.shapes) {
                val de = maxOf(s.xmin - e, 0.0, e - s.xmax)
                val dn = maxOf(s.ymin - n, 0.0, n - s.ymax)
                val dpx = Math.hypot(de / mppxE, dn / mppxN)
                if (dpx > bestPx) continue
                for (part in s.parts) for (pt in part) {
                    val px = Math.hypot((pt[0] - e) / mppxE, (pt[1] - n) / mppxN)
                    if (px < bestPx) { bestPx = px; best = li to s }
                }
            }
        }
        return best
    }

    private fun selectFeatureAt(e: Double, n: Double) {
        selectArm = false
        val hit = selectAt(e, n) ?: run { toast("No feature nearby"); return }
        canvas.selected = hit
        val layer = canvas.layers.getOrNull(hit.first)
        val name = layer?.name ?: ""
        val npts = hit.second.parts.sumOf { it.size }
        toast("Selected $name ($npts pts)")
    }

    private fun deletePoint() {
        if (!editing) { toast("Start Editing first"); return }
        val sel = canvas.selected ?: run { toast("Select a point first"); return }
        val layer = canvas.layers.getOrNull(sel.first)
        if (layer == null || layer.name != editableName) { toast("Only points in $editableName can be deleted"); return }
        val shape = sel.second
        var origIdx = -1
        for ((i, s) in origEditable.withIndex())
            if (i !in deletedIdx && s.recordIndex == shape.recordIndex) { origIdx = i; break }
        if (origIdx >= 0) {
            deletedIdx.add(origIdx); dirty = true
        } else {
            val c = shape.parts.firstOrNull()?.firstOrNull()
            val hit = c?.let { addedPoints.firstOrNull { p -> p.e == it[0] && p.n == it[1] } }
            if (hit != null) { addedPoints.remove(hit); dirty = true }
        }
        canvas.selected = null
        reloadEditableView()
        toast("Point deleted")
    }

    private fun unselect() {
        addArm = false; selectArm = false
        canvas.selected = null
        toast("Selection cleared")
    }

    private fun listLayers() {
        val all = listMapFiles().filter { it.endsWith(".shp", true) }.map { stem(it) }.distinct().sorted()
        if (all.isEmpty()) { toast("No shapefiles in the GPS_MAP folder"); return }
        val boxes = all.map { name -> JCheckBox(name, canvas.layers.any { it.name == name }) }
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            for (b in boxes) add(b)
        }
        val ok = JOptionPane.showConfirmDialog(
            this, JScrollPane(panel).apply { preferredSize = Dimension(260, 200) },
            "Available layers in GPS_MAP", JOptionPane.OK_CANCEL_OPTION,
        )
        if (ok != JOptionPane.OK_OPTION) return
        for (i in all.indices) {
            val name = all[i]
            if (boxes[i].isSelected && canvas.layers.none { it.name == name }) addDefaultLayer(name)
            if (!boxes[i].isSelected) canvas.removeLayer(name)
        }
        canvas.repaint()
    }

    private fun addDefaultLayer(name: String) {
        val file = shpFileFor(name) ?: return
        val raw = readMapFile(file)
        val info = if (raw != null) runCatching { ShpFormat.shpHeader(raw) }.getOrNull() else null
        val isPoint = info == null || info.shapeType == ShpFormat.TYPE_POINT ||
            info.shapeType == ShpFormat.TYPE_POINTZ || info.shapeType == ShpFormat.TYPE_MULTIPOINT ||
            info.shapeType == ShpFormat.TYPE_MULTIPOINTZ
        val base = layerSpecs.firstOrNull { it.name.equals(name, true) }
            ?: LayerSpec(
                name,
                symbol = if (isPoint) "circle" else "single",
                color = if (isPoint) "orange" else "violet",
                size = if (isPoint) 6.0 else 2.0,
            )
        val spec = base.copy(visible = true)
        val layer = MapLayer(
            name, LayerSpecs.awtColorOf(spec.color), spec.size.toFloat(),
            if (isPoint) MapLayer.Kind.POINT else MapLayer.Kind.LINE, name == editableName,
        )
        applyStyle(layer, spec)
        layer.file = file
        canvas.addLayer(layer)
        if (name == editableName) loadEditable()
    }

    private fun removeLayer() {
        if (canvas.layers.isEmpty()) { toast("No layers loaded"); return }
        val names = canvas.layers.map { it.name }.toTypedArray()
        val pick = JOptionPane.showInputDialog(this, "Remove layer:", "Remove layer",
            JOptionPane.PLAIN_MESSAGE, null, names, names.firstOrNull()) as? String ?: return
        canvas.removeLayer(pick)
        toast("Removed $pick")
    }

    private fun pickLayer() {
        val chooser = javax.swing.JFileChooser(repo.storage.folder).apply {
            fileSelectionMode = javax.swing.JFileChooser.FILES_ONLY
            dialogTitle = "Add layer"
            fileFilter = FileNameExtensionFilter("Shapefiles (*.shp)", "shp")
        }
        if (chooser.showOpenDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) return
        val f = chooser.selectedFile
        val bytes = runCatching { f.readBytes() }.getOrNull() ?: run { toast("Could not read the layer"); return }
        addLayerFromNameBytes(f.name.substringBeforeLast('.'), bytes)
    }

    private fun addLayerFromNameBytes(name: String, bytes: ByteArray) {
        if (!mapStarted) { toast("Start_MAP first"); return }
        val info = runCatching { ShpFormat.shpHeader(bytes) }.getOrNull() ?: return
        val isPoint = info.shapeType == ShpFormat.TYPE_POINT || info.shapeType == ShpFormat.TYPE_POINTZ ||
            info.shapeType == ShpFormat.TYPE_MULTIPOINT || info.shapeType == ShpFormat.TYPE_MULTIPOINTZ
        val spec = styleFor(name)
        val layer = MapLayer(
            name, LayerSpecs.awtColorOf(spec.color), spec.size.toFloat(),
            if (isPoint) MapLayer.Kind.POINT else MapLayer.Kind.LINE, false,
        ).also { applyStyle(it, spec) }
        val (w, h) = canvas.windowMeters()
        layer.shapes = runCatching {
            ShpFormat.readShp(bytes, canvas.centerE - w / 2, canvas.centerN - h / 2,
                canvas.centerE + w / 2, canvas.centerN + h / 2)
        }.getOrDefault(emptyList())
        canvas.addLayer(layer)
        toast("Added layer $name")
    }

    // ---- misc --------------------------------------------------------------

    private fun refreshUseButtons() {
        val names = arrayOf("Use_P1_xy", "Use_P2_xy", "Use_P3_xy")
        for ((i, b) in useBtns.withIndex()) {
            val used = chosenUse == i
            b.background = if (used) Color(0x00, 0x66, 0xCC) else YELLOW_BT
            b.foreground = if (used) Color.WHITE else Color.BLACK
            b.text = names[i]
        }
    }

    private fun toast(msg: String) { status.text = msg }

    private fun warn(msg: String) = JOptionPane.showMessageDialog(this, msg, "Notice", JOptionPane.WARNING_MESSAGE)

    companion object {
        private val YELLOW_BT = Gc.YELLOW_BT
        private val ORANGE_BT = Gc.ORANGE_BT
        private val BLUE = Color(0x72, 0x9F, 0xCF)
    }
}
