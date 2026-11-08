package org.gcap.desktop

import org.gcap.core.CaptureIo
import org.gcap.core.DatParser
import org.gcap.core.DatWriter
import org.gcap.core.FieldChecks
import org.gcap.core.GCapStorage
import org.gcap.core.GroundCall
import java.io.File
import java.util.Properties

/**
 * Persistent desktop configuration. The one thing the user must choose on first
 * run is the data folder; it is remembered here so later runs open the same
 * place. Kept under ~/.config/gcap so a portable install and a packaged .deb
 * share the same settings.
 */
object Config {
    val configDir: File = File(File(System.getProperty("user.home"), ".config"), "gcap")

    private val file = File(configDir, "desktop.properties")
    private val props = Properties()

    init {
        configDir.mkdirs()
        if (file.exists()) {
            runCatching { file.inputStream().use { props.load(it) } }
        }
    }

    fun dataFolder(): File? =
        props.getProperty("data_folder")?.takeIf { it.isNotBlank() }?.let { File(it) }

    fun setDataFolder(dir: File) {
        props.setProperty("data_folder", dir.absolutePath)
        save()
    }

    private fun save() {
        runCatching { file.outputStream().use { props.store(it, "GCap desktop configuration") } }
    }
}

/**
 * Desktop port of the Android settings store (mirror of Templates/Settings.ods).
 * Values live in ~/.config/gcap/settings.properties; an empty value clears the
 * key so the built-in rule stays authoritative. Ranges are stored as two
 * numbers; discrete lists as comma-joined strings.
 */
class DesktopSettings {

    private val file = File(Config.configDir, "settings.properties")
    private val p = Properties()

    var autosaveSeconds: Int? = null
        private set
    var bafExtra: Set<String> = emptySet()
        private set
    var nvAccept: Set<String> = emptySet()
        private set
    var heAccept: Set<String> = emptySet()
        private set
    var seAccept: Set<String> = emptySet()
        private set
    var spmAccept: Set<String> = emptySet()
        private set
    var speciesExtra: Set<String> = emptySet()
        private set
    var dbhUser: ClosedRange<Double>? = null
        private set
    var hgtUser: ClosedRange<Double>? = null
        private set
    var ageUser: IntRange? = null
        private set
    var bafUser: ClosedRange<Double>? = null
        private set

    init {
        if (file.exists()) runCatching { file.inputStream().use { p.load(it) } }
        readAll()
    }

    private fun str(key: String): Set<String> =
        p.getProperty(key)?.split(',')?.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }?.toSet()
            ?: emptySet()

    private fun dblRange(key: String): ClosedRange<Double>? {
        val mn = p.getProperty("${key}_min")?.toDoubleOrNull() ?: return null
        val mx = p.getProperty("${key}_max")?.toDoubleOrNull() ?: return null
        return mn..mx
    }

    private fun intRange(key: String): IntRange? {
        val mn = p.getProperty("${key}_min")?.toIntOrNull() ?: return null
        val mx = p.getProperty("${key}_max")?.toIntOrNull() ?: return null
        return mn..mx
    }

    private fun readAll() {
        autosaveSeconds = p.getProperty("autosave_seconds")?.toIntOrNull()?.takeIf { it in 20..120 }
        bafExtra = str("baf_extra")
        nvAccept = str("nv_accept")
        heAccept = str("he_accept")
        seAccept = str("se_accept")
        spmAccept = str("spm_accept")
        speciesExtra = str("species_extra")
        dbhUser = dblRange("dbh")
        hgtUser = dblRange("hgt")
        ageUser = intRange("age")
        bafUser = dblRange("baf_range")
        applyToFieldChecks()
    }

    private fun putRange(key: String, r: ClosedRange<Double>?) {
        if (r == null) {
            p.remove("${key}_min"); p.remove("${key}_max")
        } else {
            p.setProperty("${key}_min", r.start.toString())
            p.setProperty("${key}_max", r.endInclusive.toString())
        }
    }

    private fun putIntRange(key: String, r: IntRange?) {
        if (r == null) {
            p.remove("${key}_min"); p.remove("${key}_max")
        } else {
            p.setProperty("${key}_min", r.first.toString())
            p.setProperty("${key}_max", r.last.toString())
        }
    }

    private fun putSet(key: String, v: Set<String>) {
        if (v.isEmpty()) p.remove(key) else p.setProperty(key, v.joinToString(","))
    }

    private fun persist() {
        runCatching { file.outputStream().use { p.store(it, "GCap desktop settings") } }
    }

    private fun saveRange() {
        putRange("dbh", dbhUser); putRange("hgt", hgtUser); putIntRange("age", ageUser)
        putRange("baf_range", bafUser)
        if (autosaveSeconds == null) p.remove("autosave_seconds")
        else p.setProperty("autosave_seconds", autosaveSeconds!!.toString())
        putSet("baf_extra", bafExtra); putSet("nv_accept", nvAccept); putSet("he_accept", heAccept)
        putSet("se_accept", seAccept); putSet("spm_accept", spmAccept); putSet("species_extra", speciesExtra)
        persist()
        applyToFieldChecks()
    }

    fun setAutosave(s: Int?) { autosaveSeconds = s?.takeIf { it in 20..120 }; saveRange() }
    fun setBafExtra(s: Set<String>) { bafExtra = s; saveRange() }
    fun setNvAccept(s: Set<String>) { nvAccept = s; saveRange() }
    fun setHeAccept(s: Set<String>) { heAccept = s; saveRange() }
    fun setSeAccept(s: Set<String>) { seAccept = s; saveRange() }
    fun setSpmAccept(s: Set<String>) { spmAccept = s; saveRange() }
    fun setSpeciesAccept(s: Set<String>) { speciesExtra = s; saveRange() }
    fun setDbhUser(r: ClosedRange<Double>?) { dbhUser = r; saveRange() }
    fun setHgtUser(r: ClosedRange<Double>?) { hgtUser = r; saveRange() }
    fun setAgeUser(r: IntRange?) { ageUser = r; saveRange() }
    fun setBafUser(r: ClosedRange<Double>?) { bafUser = r; saveRange() }

    /** Every saved setting, so the Settings page can put them back exactly. */
    fun snapshot(): Properties = Properties().apply { putAll(p) }

    fun restore(snap: Properties) {
        p.clear(); p.putAll(snap); persist(); readAll()
    }

    fun restoreDefaults() {
        p.clear(); persist(); readAll()
    }

    private fun applyToFieldChecks() {
        FieldChecks.extraSpecies = speciesExtra
        FieldChecks.nvAccept = nvAccept
        FieldChecks.heAccept = heAccept
        FieldChecks.seAccept = seAccept
        FieldChecks.spmAccept = spmAccept
        FieldChecks.dbhUserRange = dbhUser
        FieldChecks.hgtUserRange = hgtUser
        FieldChecks.ageUserRange = ageUser
        FieldChecks.bafUserRange = bafUser
    }
}

/** Plain-directory [GCapStorage] used by the desktop app. */
class FileStorage(private val dir: File) : GCapStorage {
    init {
        dir.mkdirs()
        File(dir, "GPS_MAP").mkdirs()
        File(dir, "XGV_ODS_XLSX").mkdirs()
    }

    val folder: File get() = dir

    override fun listDatNames(): List<String> =
        dir.listFiles { f -> f.isFile && f.name.endsWith(".dat") }
            .orEmpty().map { it.name.removeSuffix(".dat") }.sorted()

    override fun readBytes(name: String): ByteArray? =
        File(dir, name).takeIf { it.exists() }?.readBytes()

    override fun writeBytes(name: String, bytes: ByteArray): Boolean =
        try {
            FileOutputStreamCompat.write(File(dir, name), bytes); true
        } catch (e: Exception) { false }

    override fun writeSubFolderBytes(subFolder: String, name: String, bytes: ByteArray): Boolean =
        try {
            val sub = File(dir, subFolder).apply { mkdirs() }
            FileOutputStreamCompat.write(File(sub, name), bytes); true
        } catch (e: Exception) { false }

    override fun delete(name: String) {
        runCatching { File(dir, name).delete() }
    }

    override fun describe(): String = dir.absolutePath
}

private object FileOutputStreamCompat {
    fun write(f: File, bytes: ByteArray) {
        // make sure the parent exists for sidecars written before the folder is ready
        f.parentFile?.mkdirs()
        java.io.FileOutputStream(f).use { it.write(bytes) }
    }
}

/**
 * Sample repository: loads every `.dat` (+ sidecar) from the active folder and
 * saves through the byte-exact writer, mirroring GCapApp on Android. The sample
 * list is replaced whole on reload so a reader never sees a half-filled list.
 */
class SampleRepo(private val settings: DesktopSettings) {

    @Volatile
    var storage: FileStorage = FileStorage(Config.dataFolder()
        ?: File(System.getProperty("user.home"), "GroundCallsCapture511/Groundcalls"))
        private set

    @Volatile
    private var samples: List<GroundCall> = emptyList()

    fun setFolder(dir: File) {
        Config.setDataFolder(dir)
        storage = FileStorage(dir)
        loadSamples()
    }

    fun loadSamples() {
        val loaded = mutableListOf<GroundCall>()
        val st = storage
        for (id in st.listDatNames()) {
            try {
                val bytes = st.readBytes("$id.dat") ?: continue
                val gc = CaptureIo.merge(
                    DatParser.parseText(bytes.toString(Charsets.ISO_8859_1)),
                    st.readBytes("$id.gc.json"),
                )
                loaded.add(gc)
            } catch (e: Exception) {
                // skip unreadable file, like the Android app
            }
        }
        samples = loaded
    }

    fun samples(): List<GroundCall> = samples

    fun byId(id: String): GroundCall? = samples.firstOrNull { it.sampleId == id }

    fun save(gc: GroundCall): Boolean {
        val st = storage
        val datBytes = DatWriter.render(gc).toByteArray(Charsets.ISO_8859_1)
        val ok = st.writeBytes("${gc.sampleId}.dat", datBytes)
        CaptureIo.write(gc, st)
        val i = samples.indexOfFirst { it.sampleId == gc.sampleId }
        samples = if (i >= 0) samples.toMutableList().also { it[i] = gc } else samples + gc
        return ok
    }

    fun sampleExists(id: String): Boolean = storage.readBytes("$id.dat") != null

    fun nextSampleId(): String {
        val ids = storage.listDatNames().mapNotNull { it.toIntOrNull() }
        val next = (ids.maxOrNull() ?: 3000) + 1
        return next.toString().padStart(3, '0')
    }

    /** Autosave interval from settings (20..120); null = off. */
    val autosaveSeconds: Int? get() = settings.autosaveSeconds
}

/** Swing timer that saves every [autosaveSeconds] while a page is open. */
class Autosave(private val repo: SampleRepo, private val save: () -> Unit) {
    private var timer: javax.swing.Timer? = null

    fun start() {
        stop()
        val secs = repo.autosaveSeconds ?: return
        timer = javax.swing.Timer(secs * 1000) { save() }.apply { start() }
    }

    fun stop() {
        timer?.stop(); timer = null
    }
}
