package org.gcap.desktop

import java.awt.Color
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTextField

/**
 * Shared UI colours and small widget factories, matching the yellow / green /
 * blue / red scheme the Android pages use so the desktop reads the same.
 */
object Gc {
    val RED_LBL = Color(0xFF, 0xDC, 0xDC)
    val GRAY_LBL = Color(0xED, 0xED, 0xED)
    val GREEN_ED = Color(0xA9, 0xE8, 0x9C)
    val BLUE_DER = Color(0x9C, 0xCB, 0xFF)
    val YELLOW_BT = Color(0xFF, 0xE1, 0x4D)
    val ORANGE_BT = Color(0xFF, 0xA5, 0x00)
    val NOTICE = Color(0xFF, 0xC8, 0xC8)
    val MENU_YELLOW = Color(0xFF, 0xD4, 0x00)
    val MENU_BORDER = Color(0xD3, 0x2F, 0x2F)
    val RED_BORDER = MENU_BORDER
    val BLACK_BORDER = Color.BLACK

    val baseFont = Font("SansSerif", Font.PLAIN, 12)
    val boldFont = Font("SansSerif", Font.BOLD, 12)
    val titleFont = Font("SansSerif", Font.BOLD, 18)

    /** Black outline + inner padding, used for every label cell and value display. */
    fun cellBorder(pad: Int = 4): javax.swing.border.Border =
        BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(BLACK_BORDER, 1),
            BorderFactory.createEmptyBorder(pad, pad, pad, pad),
        )

    fun grayLabel(text: String): JLabel = JLabel(text, JLabel.CENTER).apply {
        background = GRAY_LBL
        isOpaque = true
        font = boldFont
        border = cellBorder()
    }

    fun redLabel(text: String): JLabel = JLabel(text, JLabel.CENTER).apply {
        background = RED_LBL
        isOpaque = true
        font = boldFont
        border = cellBorder()
    }

    fun blueValue(text: String): JLabel = JLabel(text, JLabel.CENTER).apply {
        background = BLUE_DER
        isOpaque = true
        font = baseFont
        border = cellBorder()
    }

    fun spacer(): JPanel = JPanel().apply { isOpaque = false }

    fun field(text: String): JTextField = JTextField(text).apply {
        background = GREEN_ED
        font = baseFont
        horizontalAlignment = JTextField.CENTER
        border = cellBorder(3)
    }

    fun combo(values: List<String>, current: String): JComboBox<String> {
        val options = (listOf("") + values.filter { it.isNotBlank() }).toTypedArray()
        val cb = JComboBox(options)
        cb.background = GREEN_ED
        cb.font = baseFont
        cb.border = cellBorder(2)
        val idx = options.indexOfFirst { it.equals(current.trim(), true) }
        cb.selectedIndex = if (idx >= 0) idx else 0
        return cb
    }

    fun button(text: String, bg: Color = YELLOW_BT, onClick: () -> Unit): JButton =
        JButton(text).apply {
            // GTK (the system LAF) ignores JButton backgrounds, which painted the
            // selected point tab's white text onto a theme-grey button (invisible).
            // BasicButtonUI honours getBackground(), so the yellow/orange scheme paints.
            ui = javax.swing.plaf.basic.BasicButtonUI()
            background = bg
            foreground = Color.BLACK
            font = baseFont
            isOpaque = true
            isContentAreaFilled = true
            isFocusPainted = false
            border = BorderFactory.createLineBorder(RED_BORDER, 2)
            addActionListener { onClick() }
        }

    fun menuButton(text: String, bg: Color = MENU_YELLOW, onClick: () -> Unit): JButton = JButton(text).apply {
        ui = javax.swing.plaf.basic.BasicButtonUI()
        background = bg
        foreground = Color.BLACK
        font = Font("SansSerif", Font.PLAIN, 16)
        isOpaque = true
        isContentAreaFilled = true
        border = BorderFactory.createLineBorder(RED_BORDER, 2)
        isFocusPainted = false
        alignmentX = java.awt.Component.LEFT_ALIGNMENT
        maximumSize = java.awt.Dimension(Int.MAX_VALUE, 44)
        addActionListener { onClick() }
    }

    /** Format like the Android summary: integer when whole, else one decimal. */
    fun fmt(d: Double?): String {
        if (d == null || !d.isFinite()) return ""
        val l = d.toLong()
        return if (d == l.toDouble()) l.toString() else "%.1f".format(d)
    }
}
