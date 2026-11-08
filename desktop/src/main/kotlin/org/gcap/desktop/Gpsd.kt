package org.gcap.desktop

import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket

/** One GPS fix from gpsd (mode >= 2 has a position). */
data class Fix(
    val mode: Int,
    val lat: Double?,
    val lon: Double?,
    val altM: Double?,
    val speedMps: Double?,
    val trackDeg: Double?,
    val timeIso: String?,
    /** Horizontal accuracy in metres (from gpsd epx/epy), or null when unknown. */
    val accuracyM: Double? = null,
    val atMs: Long = System.currentTimeMillis(),
)

/**
 * Small gpsd client: connects to a local gpsd (default localhost:2947), turns
 * on JSON streaming, and reports each TPV fix. Runs on a background thread and
 * reconnects on failure. This is the desktop replacement for Android's
 * LocationManager.
 */
class GpsdClient(
    private val host: String = System.getenv("GCAP_GPSD_HOST") ?: "localhost",
    private val port: Int = (System.getenv("GCAP_GPSD_PORT") ?: "2947").toIntOrNull() ?: 2947,
    private val onFix: (Fix) -> Unit,
    private val onStatus: (String) -> Unit,
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "gpsd-client").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun loop() {
        while (running) {
            try {
                Socket().use { sock ->
                    sock.connect(InetSocketAddress(host, port), 4000)
                    sock.tcpNoDelay = true
                    val out = OutputStreamWriter(sock.getOutputStream())
                    val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
                    out.write("?WATCH={\"enable\":true,\"json\":true}\n")
                    out.flush()
                    status("Connected to gpsd $host:$port")
                    while (running) {
                        val line = reader.readLine() ?: break
                        handleLine(line)
                    }
                    status("gpsd disconnected")
                }
            } catch (e: Exception) {
                if (running) {
                    status("gpsd not reachable ($host:$port): ${e.message}")
                    Thread.sleep(3000)
                }
            }
        }
    }

    private fun handleLine(line: String) {
        val o = try { JSONObject(line) } catch (e: Exception) { return }
        when (o.optString("class")) {
            "TPV" -> {
                val mode = o.optInt("mode", 0)
                val epx = o.optDouble("epx", Double.NaN)
                val epy = o.optDouble("epy", Double.NaN)
                val acc = if (epx.isNaN() && epy.isNaN()) null
                else Math.hypot(if (epx.isNaN()) 0.0 else epx, if (epy.isNaN()) 0.0 else epy)
                val fix = Fix(
                    mode = mode,
                    lat = o.optDouble("lat", Double.NaN).takeIf { !it.isNaN() },
                    lon = o.optDouble("lon", Double.NaN).takeIf { !it.isNaN() },
                    altM = o.optDouble("alt", Double.NaN).takeIf { !it.isNaN() },
                    speedMps = o.optDouble("speed", Double.NaN).takeIf { !it.isNaN() },
                    trackDeg = o.optDouble("track", Double.NaN).takeIf { !it.isNaN() },
                    timeIso = o.optString("time").takeIf { it.isNotBlank() && it != "null" },
                    accuracyM = acc,
                )
                onFix(fix)
            }
            "ERROR" -> status("gpsd error: ${o.optString("message")}")
        }
    }

    private fun status(msg: String) {
        onStatus(msg)
    }
}
