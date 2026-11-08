package org.gcap.core

import java.util.Locale
import kotlin.io.path.Path
import kotlin.io.path.writeText

/**
 * Writes ground call data back to GCap v5.1.1 `.dat` text format (CRLF, one
 * value per line). The 148-line header is an exact template (from a reference
 * file) overlaid with the capture fields; tree records are 16 lines each,
 * grouped by point, with the typed fields overlaid onto their canonical slots
 * so untouched data round-trips byte-exactly.
 */
object DatWriter {

    /** Reference header template lines 0..148, loaded from resources. */
    private val headerTemplate: List<String> by lazy {
        val raw = DatWriter::class.java.classLoader
            ?.getResourceAsStream("org/gcap/header_clean.txt")
            ?.readBytes()
            ?.toString(Charsets.ISO_8859_1)
            ?: error("missing header_clean.txt resource")
        require(!raw.isEmpty()) { "header template loaded empty" }
        raw.split("\n")
    }

    init {
        require(headerTemplate.size == 149) { "header template must be 149 lines" }
    }

    // Header line indices for the variable capture fields.
    private const val LINE_SAMPLE_ID = 2
    private const val LINE_MAP_SHEET = 4
    private const val LINE_SAMPLE_TYPE = 5
    private const val LINE_ENTRY_DATE = 6
    private const val LINE_BAF = 23
    private const val LINE_COMMENT = 111
    private const val LINE_COMPOSITION = 126
    private const val LINE_EASTING = 142
    private const val LINE_NORTHING = 143
    private const val LINE_ELEVATION = 144
    // Dat line 142 (index 141) carries the UTM zone number ("10" in every
    // observed file). Old files inherit it via headerLines; new files start
    // from a blank template, so it must be written explicitly (see below).
    private const val LINE_UTM_ZONE = 141
    private const val LINE_FIRST_MARKER = 148

    // Per-point header cells: L66/69/72 = cover A %, L81/84/87 = cover B %,
    // L96/99/102 = per-point tree counts (stride 3).
    private val COVER_A = listOf(66, 69, 72)
    private val COVER_B = listOf(81, 84, 87)
    private val COUNTS = listOf(96, 99, 102)

    /**
     * Snags per point. Zero-based header indices: file rows 82, 85 and 88 as numbered in
     * the editor and the crew's own files. Real samples carry the same value in all
     * three, so each point's count goes into its own cell.
     */
    val SNAG_CELLS = listOf(81, 84, 87)

    /**
     * Vertical complexity and crown closure, one cell per point, the same stride-3 layout
     * the crew's files use (1-based rows 52/55/58 and 67/70/73). The Summary sets one
     * value and it is written to all three, so nothing reads as averaged or added.
     */
    val VC_CELLS = listOf(51, 54, 57)
    val CC_CELLS = listOf(66, 69, 72)

    private fun formatNumber(v: Double): String {
        val l = v.toLong()
        return if (v == l.toDouble()) l.toString() else v.toString()
    }

    private fun formatDbh(dbhCm: Double?): String {
        if (dbhCm == null) return ""
        val l = dbhCm.toLong()
        return if (dbhCm == l.toDouble()) l.toString() else dbhCm.toString()
    }

    /** Render a GroundCall to GCap .dat text. File text ends with CRLF. */
    fun render(gc: GroundCall): String {
        // Inherit the source file's own header (edits) or use the canonical
        // reference template (new samples). A source file shorter than the
        // template is padded out to the full layout first: the per-point cells at
        // header lines 52..102 have nowhere to go otherwise, so saving a stub
        // threw IndexOutOfBounds and the cells below it could not be written.
        val inherited = gc.headerLines
        val base = when {
            inherited == null -> headerTemplate
            inherited.size >= headerTemplate.size -> inherited
            else -> inherited + List(headerTemplate.size - inherited.size) { "" }
        }
        val h = base.toMutableList()

        // null = the model has no opinion (leave the inherited header value);
        // "" = a value the crew cleared and that must actually clear the line.
        fun set(i: Int, v: String?) {
            if (v != null) h[i] = v
        }

        if (gc.sampleId.isNotEmpty()) set(LINE_SAMPLE_ID, gc.sampleId)
        // Case rules (crew convention): mapsheet + eco code fields UPPER-case,
        // crew/comment/composition lower-case. SampleId stays byte-exact: it
        // drives the file name, so "0508b" must never become "0508B" on save.
        set(LINE_MAP_SHEET, gc.mapSheet.uppercase(Locale.US))
        set(LINE_SAMPLE_TYPE, gc.sampleType.lowercase(Locale.US))
        set(LINE_ENTRY_DATE, gc.entryDate)

        val eco = gc.ecology
        val ecoLines = listOf(8, 9, 10, 11, 12, 13, 14, 15, 21)
        val ecoKeys = listOf(
            "line8", "line9", "smr", "snr", "line12", "landcover",
            "slope", "surface", "line21",
        )
        for ((ln, key) in ecoLines.zip(ecoKeys)) {
            (eco[key] as? String)?.let { set(ln, it.uppercase(Locale.US)) }
        }
        // Cover/by-layer percents live at header lines 16-19 (cover_bytes).
        (eco["cover_bytes"] as? List<*>)?.forEachIndexed { idx, v ->
            set(16 + idx, v?.toString())
        }

        // BAF lives at header line 23 (editor line 24), confirmed by crew.
        if (gc.capture.baf != null) set(LINE_BAF, formatNumber(gc.capture.baf))
        else h[LINE_BAF] = ""

        // The comment is free text and must be clearable: writing it only when
        // non-empty left the previous value inherited from the source header,
        // so clearing the Comments field never persisted.
        h[LINE_COMMENT] = gc.comment.lowercase(Locale.US)
        set(LINE_COMPOSITION, gc.compositionRaw.lowercase(Locale.US))
        // UTM zone digits ("10N" -> "10"): chosen point first, else capture zone.
        // Edits keep the old value via headerLines; new files get it written here
        // so their layout matches the old files (zone/easting/northing lines).
        val zoneDigits = (gc.capture.utmChosenIndex?.let { gc.capture.utmPoints.getOrNull(it) }?.zone
            ?: gc.capture.utmPoints.lastOrNull()?.zone
            ?: gc.capture.utmZone).filter { it.isDigit() }
        if (zoneDigits.isNotEmpty()) set(LINE_UTM_ZONE, zoneDigits)
        // Deleting the GPS points must clear the pen-eligible coordinates too;
        // null here means "no coordinates", not "leave the old ones".
        h[LINE_EASTING] = gc.easting?.let { formatNumber(it) } ?: ""
        h[LINE_NORTHING] = gc.northing?.let { formatNumber(it) } ?: ""
        h[LINE_ELEVATION] = gc.elevation?.let { formatNumber(it) } ?: ""

        // per-point header cells (stride 3): count for each present point;
        // cells for missing points are blanked so a reduced file (after point
        // deletion) does not carry stale counts for points that no longer exist.
        val pts = gc.points()
        val n = pts.size
        for (i in COUNTS.indices) {
            h[COUNTS[i]] = pts[i + 1]?.size?.toString() ?: ""
        }
        for (i in SNAG_CELLS.indices) {
            // A point with no entry of its own still gets the sample value, so a
            // snag count entered before the points existed is not lost.
            h[SNAG_CELLS[i]] = (gc.capture.snags[i + 1] ?: gc.capture.snagsValue)?.toString() ?: ""
        }
        for (i in VC_CELLS.indices) {
            h[VC_CELLS[i]] = (gc.ecology["layer_counts"] as? List<*>)?.getOrNull(i)?.toString().orEmpty()
        }
        for (i in CC_CELLS.indices) {
            h[CC_CELLS[i]] = (gc.ecology["layer_cover_a"] as? List<*>)?.getOrNull(i)?.toString().orEmpty()
        }

        // tree records: contiguous 16-line blocks following header line 148
        // (line 148 always holds the literal '1'). Point groups are marked only
        // by tree numbers restarting at 1; no separator lines exist. GCap
        // quirk: the measured flag is written when a next record closes the
        // tree, so the LAST record of the file has no flag line.
        val out = h.toMutableList()
        out[LINE_FIRST_MARKER] = "1"
        val allTrees = (1..n).flatMap { pts[it] ?: emptyList() }
        for ((idx, t) in allTrees.withIndex()) {
            val isLast = idx == allTrees.size - 1
            val rec = MutableList(16) { "" }
            rec[0] = t.number.toString()
            rec[1] = t.species.uppercase(Locale.US)
            rec[2] = t.dbhRaw ?: formatDbh(t.dbhCm)
            val slots = t.slots.take(10)
            for (i in slots.indices) rec[3 + i] = slots[i]
            // Legacy healing: a lone crown-class letter mistyped into the TCls
            // slot (054/308-era files) is an error — the CCls column is
            // authoritative. Clear it whenever TCls is otherwise empty, and
            // move it to CCls when that is blank (lower-case rule applies).
            // Never touches a real "4" dead marker.
            val legacyCrown = rec[3].takeIf { it.length == 1 && it.lowercase(Locale.US) in FieldChecks.CROWN_CLASS_LETTERS }
            if (t.tcls.isEmpty() && legacyCrown != null) {
                rec[3] = ""
                if (t.crownClass.isEmpty() && rec[13].isBlank()) {
                    rec[13] = legacyCrown.lowercase(Locale.US)
                }
            }
            // typed overlays preserve the original slot text while the value is
            // unchanged (e.g. "27.0" stays "27.0"), and write canonical text
            // only for genuinely new/changed values.
            overlayTyped(rec, 3, t.tcls.takeIf { it.isNotEmpty() }, { it }, slots)
            overlayTyped(rec, 7, t.heightM, ::formatNumber, slots)
            overlayTyped(rec, 8, t.distM, ::formatNumber, slots)
            overlayTyped(rec, 9, t.ageCore, { it.toString() }, slots)
            overlayTyped(rec, 10, t.ageCor, ::formatNumber, slots)
            overlayTyped(rec, 11, t.slopeDeg, ::formatNumber, slots)
            // Crown class is always modelled (any letter). When the crew clears
            // it, rec[13] stays blank (it is not seeded from the raw slots), so
            // leaving it alone clears the inherited letter. A legacy letter
            // moved into rec[13] by the block above is kept.
            if (t.crownClass.isNotEmpty()) rec[13] = t.crownClass.lowercase(Locale.US)
            rec[14] = ""
            if (!isLast) rec[15] = if (t.measured) "1" else ""
            // GCap quirk: the last record of the file has no measured-flag line.
            out.addAll(rec.take(if (isLast) 15 else 16))
        }
        return out.joinToString("\r\n") + "\r\n"
    }

    /** Overlay a typed slot value, preserving the original raw text when the
     *  value is unchanged, and only writing canonical formatting otherwise. */
    private inline fun <T> overlayTyped(
        rec: MutableList<String>,
        offset: Int,
        value: T?,
        format: (T) -> String,
        rawSlots: List<String>,
    ) {
        val raw = rawSlots.getOrNull(offset - 3)
        if (value == null) {
            // A blank cell in the editor parses to null. Clear the inherited
            // numeric value (this is how a tree stops being dead, or loses a
            // height/distance/age/slope). Non-numeric legacy text was never
            // modelled, so leave it untouched.
            if (raw == null || raw.isEmpty() || raw.toDoubleOrNull() != null) {
                rec[offset] = ""
            }
            return
        }
        val canon = format(value)
        if (raw != null && raw.isNotEmpty() && raw != canon && raw.toDoubleOrNull()?.let {
                it == (value as? Number)?.toDouble()
            } == true) {
            rec[offset] = raw        // untouched: keep original formatting
        } else {
            rec[offset] = canon
        }
    }

    fun write(gc: GroundCall, path: String) {
        Path(path).writeText(render(gc), Charsets.ISO_8859_1)
    }
}