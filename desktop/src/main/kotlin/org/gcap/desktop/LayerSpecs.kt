package org.gcap.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.awt.Color

/**
 * Desktop port of the Android LayerSpec: how one GPS_MAP layer is drawn. The
 * same `layer_settings.json` file is read and written, so layers come up the
 * same way on the phone and on the desktop.
 */
data class LayerSpec(
    val name: String,
    val visible: Boolean = false,
    /** point: circle, square, triangle, diamond. line: single, dash, dotted. */
    val symbol: String = "circle",
    val color: String = "orange",
    /** Point diameter in px, or line width in px. */
    val size: Double = 2.0,
    /** DBF field used as the on-map label, or "" for none. */
    val label: String = "",
    val labelSize: Double = 7.0,
)

object LayerSpecs {
    const val FILE = "layer_settings.json"

    val POINT_SYMBOLS = listOf("circle", "square", "triangle", "diamond")
    val LINE_SYMBOLS = listOf("single", "dash", "dotted")

    val COLOURS: Map<String, Int> = linkedMapOf(
        "orange" to 0xFFCC6600.toInt(),
        "blue" to 0xFF3366CC.toInt(),
        "green" to 0xFF2E7D32.toInt(),
        "violet" to 0xFF8A2BE2.toInt(),
        "red" to 0xFFCC0000.toInt(),
        "brown" to 0xFF795548.toInt(),
        "yellow" to 0xFFD6A800.toInt(),
        "black" to 0xFF000000.toInt(),
        "pink" to 0xFFE91E63.toInt(),
        "white" to 0xFFFFFFFF.toInt(),
        "gray" to 0xFF888888.toInt(),
    )

    fun colorOf(name: String): Int = COLOURS[name.trim().lowercase()] ?: 0xFF888888.toInt()

    fun awtColorOf(name: String): Color {
        val argb = colorOf(name)
        return Color(argb shr 16 and 0xFF, argb shr 8 and 0xFF, argb and 0xFF)
    }

    fun defaults(): List<LayerSpec> = listOf(
        LayerSpec("Access", visible = true, symbol = "single", color = "gray", size = 2.0),
        LayerSpec("Transect", visible = true, symbol = "single", color = "blue", size = 2.0),
        LayerSpec("Ground_Call", visible = true, symbol = "circle", color = "orange", size = 10.0,
            label = "Call_Num", labelSize = 7.0),
        LayerSpec("XGV_MP", visible = true, symbol = "circle", color = "green", size = 10.0),
    )

    fun isPointSymbol(s: String) = s in POINT_SYMBOLS

    fun parse(text: String?): List<LayerSpec> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            val arr: JSONArray = JSONObject(text).optJSONArray("layers") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val name = o.optString("name").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                LayerSpec(
                    name = name,
                    visible = o.optBoolean("visible", false),
                    symbol = o.optString("symbol", "circle").ifBlank { "circle" },
                    color = o.optString("color", "orange").ifBlank { "orange" },
                    size = o.optDouble("size", 2.0).takeIf { !it.isNaN() && it > 0 } ?: 2.0,
                    label = o.optString("label", ""),
                    labelSize = o.optDouble("labelSize", 7.0).takeIf { !it.isNaN() && it > 0 } ?: 7.0,
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun write(specs: List<LayerSpec>): String {
        val arr = JSONArray()
        for (s in specs) {
            arr.put(JSONObject().apply {
                put("name", s.name)
                put("visible", s.visible)
                put("symbol", s.symbol)
                put("color", s.color)
                put("size", s.size)
                put("label", s.label)
                put("labelSize", s.labelSize)
            })
        }
        return JSONObject().apply { put("layers", arr) }.toString(1)
    }

    fun styleFor(name: String, saved: List<LayerSpec>): LayerSpec =
        saved.firstOrNull { it.name.equals(name, true) }
            ?: defaults().firstOrNull { it.name.equals(name, true) }
            ?: LayerSpec(name)

    fun labelColor(label: String): Color =
        if (label.isNotBlank()) Color(0x8A, 0x2B, 0xE2) else Color(27, 27, 27)
}
