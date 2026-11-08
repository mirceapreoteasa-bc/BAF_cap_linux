package org.gcap.desktop

import org.gcap.core.ShpFormat
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JPanel

/** One drawable layer on the desktop map, styled like the Android MapCanvasView.MapLayer. */
class MapLayer(
    val name: String,
    var color: Color,
    var widthPx: Float,
    val kind: Kind,
    val editable: Boolean,
) {
    enum class Kind { LINE, POINT }

    var file: String = name
    var visible: Boolean = true
    var shapes: List<ShpFormat.Shape> = emptyList()
    var labelField: String? = null
    var labelColor: Color = Color(0x1B, 0x1B, 0x1B)
    /** Point: circle, square, triangle, diamond. Line: single, dash, dotted. */
    var symbol: String = "circle"
    /** Point diameter in px (points) or stroke width in px (lines). */
    var sizeDp: Double = 2.0
    /** Label text size in px. */
    var labelSizeSp: Double = 7.0
}

/**
 * Desktop port of the Android MapCanvasView: north-oriented, no-pan UTM canvas.
 * The viewport always shows a fixed ground rectangle whose extent doubles per
 * zoom step: scale 1:10000 -> 500m x 400m; 1:5000/2500 zoom in (halves),
 * 1:20000/40000 zoom out (doubles). Centered on the live GPS fix.
 */
class MapCanvas : JPanel() {

    var started: Boolean = false
        set(v) { field = v; repaint() }
    var editing: Boolean = false
        set(v) { field = v; repaint() }
    var editableLayerName: String? = null
        set(v) { field = v; repaint() }

    var centerE: Double = 0.0
    var centerN: Double = 0.0
    var scaleDenominator: Int = 10000
        set(v) { field = v; repaint() }

    var gpsE: Double = 0.0
    var gpsN: Double = 0.0
    var gpsAcc: Double = 0.0
    var haveGps: Boolean = false

    var showP1P2P3: Boolean = true

    var selected: Pair<Int, ShpFormat.Shape>? = null
        set(v) { field = v; repaint() }

    var onMapTap: ((e: Double, n: Double) -> Unit)? = null

    private val pMarkers = mutableListOf<Triple<String, Double, Double>>()
    val layers = mutableListOf<MapLayer>()

    init {
        background = BG
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (!started) return
                val w = screenToWorld(e.x.toDouble(), e.y.toDouble())
                onMapTap?.invoke(w[0], w[1])
            }
        })
    }

    fun addLayer(layer: MapLayer) { layers.add(layer); repaint() }
    fun removeLayer(name: String) { layers.removeAll { it.name == name }; repaint() }
    fun setP1P2P3(markers: List<Triple<String, Double, Double>>) {
        pMarkers.clear(); pMarkers.addAll(markers); repaint()
    }
    fun setGpsMarker(e: Double, n: Double, acc: Double, have: Boolean) {
        gpsE = e; gpsN = n; gpsAcc = acc; haveGps = have; repaint()
    }

    /** Ground width / height shown at the current zoom step. */
    fun windowMeters(): Pair<Double, Double> {
        val k = scaleDenominator / 10000.0
        return (500.0 * k) to (400.0 * k)
    }

    private fun worldToScreen(e: Double, n: Double): DoubleArray {
        val (w, h) = windowMeters()
        val cw = width.coerceAtLeast(1)
        val ch = height.coerceAtLeast(1)
        val mppxE = w / cw
        val mppxN = h / ch
        return doubleArrayOf(
            cw / 2.0 + (e - centerE) / mppxE,
            ch / 2.0 - (n - centerN) / mppxN,
        )
    }

    fun screenToWorld(x: Double, y: Double): DoubleArray {
        val (w, h) = windowMeters()
        val cw = width.coerceAtLeast(1)
        val ch = height.coerceAtLeast(1)
        val e = centerE + (x - cw / 2.0) * (w / cw)
        val n = centerN - (y - ch / 2.0) * (h / ch)
        return doubleArrayOf(e, n)
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        val w = width.toFloat(); val h = height.toFloat()
        if (!started) {
            g2.color = TEXT
            g2.font = g2.font.deriveFont(Font.BOLD, 16f)
            g2.drawString("  GPS_MAP  (Start_MAP)", 16f, h / 2 - 30f)
            g2.font = g2.font.deriveFont(Font.PLAIN, 12f)
            g2.color = SMALL
            g2.drawString("  15 buttons in the map-function bar", 16f, h / 2 + 20f)
            return
        }

        // north-oriented frame
        g2.color = FRAME
        g2.stroke = BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(10f, 8f), 0f)
        g2.drawRect(1, 1, (w - 1).toInt(), (h - 1).toInt())

        for (layer in layers) {
            if (!layer.visible) continue
            val radius = (layer.sizeDp * 0.6).coerceAtLeast(2.0)
            for (s in layer.shapes) {
                for (part in s.parts) {
                    if (layer.kind == MapLayer.Kind.LINE) {
                        if (part.size < 2) continue
                        g2.color = layer.color
                        g2.stroke = strokeFor(layer)
                        val xs = IntArray(part.size); val ys = IntArray(part.size)
                        for (i in part.indices) {
                            val p = worldToScreen(part[i][0], part[i][1])
                            xs[i] = p[0].toInt(); ys[i] = p[1].toInt()
                        }
                        g2.drawPolyline(xs, ys, part.size)
                    } else {
                        if (part.isEmpty()) continue
                        val p = worldToScreen(part[0][0], part[0][1])
                        g2.color = layer.color
                        drawSymbol(g2, layer.symbol, p[0], p[1], radius)
                        if (s.label.isNotEmpty()) {
                            g2.font = g2.font.deriveFont(layer.labelSizeSp.coerceAtLeast(9.0).toFloat())
                            val tx = (p[0] + 7).toInt(); val ty = (p[1] - 9).toInt()
                            g2.color = Color.WHITE
                            for (dx in -1..1) for (dy in -1..1) if (dx != 0 || dy != 0) g2.drawString(s.label, tx + dx, ty + dy)
                            g2.color = layer.labelColor
                            g2.drawString(s.label, tx, ty)
                        }
                    }
                }
            }
        }

        // selected feature highlight
        selected?.let { (li, s) ->
            if (li in layers.indices) {
                for (part in s.parts) for (pt in part) {
                    val p = worldToScreen(pt[0], pt[1])
                    g2.color = Color(0xFF, 0xD7, 0x00)
                    g2.fillOval((p[0] - 8).toInt(), (p[1] - 8).toInt(), 16, 16)
                    g2.color = Color.YELLOW
                    g2.stroke = BasicStroke(6f)
                    g2.drawOval((p[0] - 8).toInt(), (p[1] - 8).toInt(), 16, 16)
                }
            }
        }

        // P1/P2/P3 markers
        if (showP1P2P3) {
            for ((label, e, n) in pMarkers) {
                val p = worldToScreen(e, n)
                g2.color = Color.BLUE
                g2.fillOval((p[0] - 6).toInt(), (p[1] - 6).toInt(), 12, 12)
                g2.font = g2.font.deriveFont(Font.PLAIN, 12f)
                g2.drawString(label, (p[0] + 14).toInt(), (p[1] - 14).toInt())
            }
        }

        // GPS marker + accuracy
        if (haveGps) {
            val p = worldToScreen(gpsE, gpsN)
            if (gpsAcc > 0) {
                val (we, hn) = windowMeters()
                val rE = gpsAcc / (we / width.coerceAtLeast(1))
                val rN = gpsAcc / (hn / height.coerceAtLeast(1))
                g2.color = Color(0x00, 0xCC, 0x00, 34)
                g2.fillOval((p[0] - rE).toInt(), (p[1] - rN).toInt(), (2 * rE).toInt(), (2 * rN).toInt())
            }
            g2.color = Color.RED
            g2.stroke = BasicStroke(4f)
            g2.drawLine((p[0] - 16).toInt(), p[1].toInt(), (p[0] + 16).toInt(), p[1].toInt())
            g2.drawLine(p[0].toInt(), (p[1] - 16).toInt(), p[0].toInt(), (p[1] + 16).toInt())
        }

        // scale / centre / edit info
        g2.font = g2.font.deriveFont(Font.PLAIN, 12f)
        g2.color = SMALL
        g2.drawString("1:$scaleDenominator  N", 12f, 30f)
        val (we2, hn2) = windowMeters()
        g2.drawString("${we2.toInt()}m x ${hn2.toInt()}m  @ E ${centerE.toInt()} N ${centerN.toInt()}", 12f, 56f)
        g2.color = if (editing) Color.GREEN.darker() else SMALL
        g2.drawString("EDIT ${editableLayerName ?: "none"}${if (editing) "  (on)" else ""}", 12f, 82f)
    }

    private fun strokeFor(layer: MapLayer): BasicStroke {
        val w = layer.widthPx.coerceAtLeast(1f)
        return when (layer.symbol) {
            "dash" -> BasicStroke(w, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(18f, 10f), 0f)
            "dotted" -> BasicStroke(w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_MITER, 10f, floatArrayOf(3f, 8f), 0f)
            else -> BasicStroke(w)
        }
    }

    /** A point symbol centred on (x, y). Unknown names fall back to a circle. */
    private fun drawSymbol(g2: Graphics2D, symbol: String, x: Double, y: Double, r: Double) {
        when (symbol) {
            "square" -> g2.fillRect((x - r).toInt(), (y - r).toInt(), (2 * r).toInt(), (2 * r).toInt())
            "triangle" -> g2.fillPolygon(
                intArrayOf(x.toInt(), (x - r).toInt(), (x + r).toInt()),
                intArrayOf((y - r).toInt(), (y + r).toInt(), (y + r).toInt()), 3,
            )
            "diamond" -> g2.fillPolygon(
                intArrayOf(x.toInt(), (x + r).toInt(), x.toInt(), (x - r).toInt()),
                intArrayOf((y - r).toInt(), y.toInt(), (y + r).toInt(), y.toInt()), 4,
            )
            else -> g2.fillOval((x - r).toInt(), (y - r).toInt(), (2 * r).toInt(), (2 * r).toInt())
        }
    }

    companion object {
        private val BG = Color(0xF7, 0xF5, 0xF0)
        private val FRAME = Color(0x44, 0x44, 0x44)
        private val TEXT = Color(0x22, 0x22, 0x22)
        private val SMALL = Color(0x44, 0x44, 0x44)
    }
}
