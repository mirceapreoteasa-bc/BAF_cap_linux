package org.gcap.core

import org.json.JSONArray
import org.json.JSONObject

object CaptureIo {
    const val EXT = ".gc.json"

    private fun fileName(sampleId: String) = "$sampleId$EXT"

    fun write(gc: GroundCall, storage: GCapStorage) {
        storage.writeBytes(fileName(gc.sampleId), toJson(gc).toByteArray(Charsets.UTF_8))
    }

    fun merge(gc: GroundCall, json: ByteArray?): GroundCall {
        var out = gc
        var sidecarHasOcular = false
        if (json != null) {
            val o = try {
                JSONObject(String(json, Charsets.UTF_8))
            } catch (e: Exception) {
                return withOcularFallback(gc)
            }
            sidecarHasOcular = o.has("ocularComments")
            val trees = gc.trees.map { t ->
                val h = o.optJSONObject("heights")?.optJSONObject("${t.point}.${t.number}")
                if (h == null) t else t.copy(
                    hgtTopDeg = h.optDouble("top", Double.NaN).takeIf { !it.isNaN() },
                    hgtBotDeg = h.optDouble("bot", Double.NaN).takeIf { !it.isNaN() },
                    hgtSDistM = h.optDouble("sd", Double.NaN).takeIf { !it.isNaN() },
                    hgtCorM = h.optDouble("cor", Double.NaN).takeIf { !it.isNaN() },
                )
            }
            val base = gc.capture
            out = gc.copy(trees = trees, capture = CaptureMeta(
                baf = base.baf ?: o.optDouble("baf", Double.NaN).takeIf { !it.isNaN() },
                radiusM = o.optDouble("radiusM", Double.NaN).takeIf { !it.isNaN() } ?: base.radiusM,
                conflictChoice = o.optString("conflictChoice", base.conflictChoice),
                snags = intMap(o.optJSONObject("snags")).ifEmpty { base.snags },
                deadStems = intMap(o.optJSONObject("deadStems")).ifEmpty { base.deadStems },
                ocularComments = o.optString("ocularComments", ""),
                ocularSpeciesCodes = stringList(o.optJSONArray("ocularSpeciesCodes")),
                ocularSpeciesPcts = intList(o.optJSONArray("ocularSpeciesPcts")),
                callType = if (o.has("callType")) o.optString("callType", "") else base.callType,
                location = if (o.has("location")) o.optString("location", "") else base.location,
                utmZone = if (o.has("utmZone")) o.optString("utmZone", "") else base.utmZone,
                coordSource = if (o.has("coordSource")) o.optString("coordSource", "") else base.coordSource,
                poly = if (o.has("poly")) o.optString("poly", "") else base.poly,
                photo = if (o.has("photo")) o.optString("photo", "") else base.photo,
                utmPoints = utmPoints(o),
                utmChosenIndex = if (o.has("utmChosenIndex")) o.optInt("utmChosenIndex", -1).takeIf { it >= 0 } else null,
                utmTiePoint = o.optJSONObject("utmTiePoint")?.let { t -> UtmPoint(
                    id = t.optString("id", "Tie_Point"),
                    easting = t.optDouble("easting", Double.NaN).takeIf { !it.isNaN() },
                    northing = t.optDouble("northing", Double.NaN).takeIf { !it.isNaN() },
                    zone = t.optString("zone", ""),
                    elevM = t.optDouble("elevM", Double.NaN).takeIf { !it.isNaN() },
                    coordSource = t.optString("coordSource", ""),
                    latDeg = t.optDouble("latDeg", Double.NaN).takeIf { !it.isNaN() },
                    lonDeg = t.optDouble("lonDeg", Double.NaN).takeIf { !it.isNaN() },
                    capturedAtMs = t.optLong("capturedAtMs", 0L),
                ) },
                use3rdAsSecond = o.optBoolean("use3rdAsSecond", false),
            ))
        }
        // Only a sidecar without the key (older version) or no sidecar at all
        // borrows the .dat composition line. An explicit empty value is a
        // deliberate clear and must stay empty.
        return if (json != null && sidecarHasOcular) out else withOcularFallback(out)
    }

    private fun withOcularFallback(gc: GroundCall): GroundCall {
        val c = gc.capture
        return if (c.ocularComments.isBlank()) {
            gc.copy(capture = c.copy(ocularComments = gc.compositionRaw))
        } else gc
    }

    fun toJson(gc: GroundCall): String {
        val c = gc.capture
        val o = JSONObject().apply {
            put("sampleId", gc.sampleId)
            c.baf?.let { put("baf", it) }
            c.radiusM?.let { put("radiusM", it) }
            if (c.conflictChoice.isNotEmpty()) put("conflictChoice", c.conflictChoice)
            put("ocularComments", c.ocularComments)
            if (c.ocularSpeciesCodes.isNotEmpty()) put("ocularSpeciesCodes", JSONArray(c.ocularSpeciesCodes))
            if (c.ocularSpeciesPcts.isNotEmpty()) put("ocularSpeciesPcts", JSONArray(c.ocularSpeciesPcts))
            put("callType", c.callType)
            put("location", c.location)
            put("utmZone", c.utmZone)
            put("coordSource", c.coordSource)
            put("poly", c.poly)
            put("photo", c.photo)
            if (c.utmPoints.isNotEmpty()) {
                put("utmPoints", JSONArray().also { arr ->
                    for (pt in c.utmPoints) arr.put(JSONObject().apply {
                        put("id", pt.id)
                        pt.easting?.let { put("easting", it) }
                        pt.northing?.let { put("northing", it) }
                        if (pt.zone.isNotBlank()) put("zone", pt.zone)
                        pt.elevM?.let { put("elevM", it) }
                        if (pt.coordSource.isNotBlank()) put("coordSource", pt.coordSource)
                        pt.latDeg?.let { put("latDeg", it) }
                        pt.lonDeg?.let { put("lonDeg", it) }
                        if (pt.capturedAtMs > 0) put("capturedAtMs", pt.capturedAtMs)
                    })
                })
            }
            c.utmChosenIndex?.let { put("utmChosenIndex", it) }
            c.utmTiePoint?.let { tie ->
                put("utmTiePoint", JSONObject().apply {
                    put("id", tie.id)
                    tie.easting?.let { put("easting", it) }
                    tie.northing?.let { put("northing", it) }
                    if (tie.zone.isNotBlank()) put("zone", tie.zone)
                    tie.elevM?.let { put("elevM", it) }
                    if (tie.coordSource.isNotBlank()) put("coordSource", tie.coordSource)
                    tie.latDeg?.let { put("latDeg", it) }
                    tie.lonDeg?.let { put("lonDeg", it) }
                    if (tie.capturedAtMs > 0) put("capturedAtMs", tie.capturedAtMs)
                })
            }
            if (c.use3rdAsSecond) put("use3rdAsSecond", true)
            if (c.snags.isNotEmpty()) put("snags", JSONObject(c.snags.mapKeys { it.key.toString() }))
            if (c.deadStems.isNotEmpty()) put("deadStems", JSONObject(c.deadStems.mapKeys { it.key.toString() }))
            put("heights", JSONObject().apply {
                for (t in gc.trees) {
                    val has = t.hgtTopDeg != null || t.hgtBotDeg != null ||
                        t.hgtSDistM != null || t.hgtCorM != null
                    if (has) {
                        put("${t.point}.${t.number}", JSONObject().apply {
                            t.hgtTopDeg?.let { put("top", it) }
                            t.hgtBotDeg?.let { put("bot", it) }
                            t.hgtSDistM?.let { put("sd", it) }
                            t.hgtCorM?.let { put("cor", it) }
                        })
                    }
                }
            })
        }
        return o.toString()
    }

    private fun stringList(a: JSONArray?): List<String> {
        if (a == null) return emptyList()
        val out = ArrayList<String>(a.length())
        for (i in 0 until a.length()) out.add(a.optString(i, ""))
        return out
    }

    private fun intList(a: JSONArray?): List<Int> {
        if (a == null) return emptyList()
        val out = ArrayList<Int>(a.length())
        for (i in 0 until a.length()) out.add(a.optInt(i, 0))
        return out
    }

    private fun intMap(o: JSONObject?): Map<Int, Int> {
        if (o == null) return emptyMap()
        val m = mutableMapOf<Int, Int>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = o.optInt(k, -1)
            if (v >= 0) k.toIntOrNull()?.let { m[it] = v }
        }
        return m
    }

    private fun utmPoints(o: JSONObject): List<UtmPoint> {
        val arr = o.optJSONArray("utmPoints") ?: return emptyList()
        val list = mutableListOf<UtmPoint>()
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            list.add(UtmPoint(
                id = p.optString("id", ""),
                easting = p.optDouble("easting", Double.NaN).takeIf { !it.isNaN() },
                northing = p.optDouble("northing", Double.NaN).takeIf { !it.isNaN() },
                zone = p.optString("zone", ""),
                elevM = p.optDouble("elevM", Double.NaN).takeIf { !it.isNaN() },
                coordSource = p.optString("coordSource", ""),
                latDeg = p.optDouble("latDeg", Double.NaN).takeIf { !it.isNaN() },
                lonDeg = p.optDouble("lonDeg", Double.NaN).takeIf { !it.isNaN() },
                capturedAtMs = p.optLong("capturedAtMs", 0L),
            ))
        }
        return list
    }
}
