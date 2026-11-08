package org.gcap.desktop

import java.io.File
import javax.swing.JFileChooser
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import javax.swing.UIManager

/**
 * GCap Linux desktop entry point. On first run the user picks the ground-call
 * data folder; it is remembered in ~/.config/gcap and reused afterwards.
 */
fun main() {
    try {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName())
    } catch (e: Exception) {
        // keep the cross-platform look and feel
    }

    val settings = DesktopSettings()
    val repo = SampleRepo(settings)

    if (Config.dataFolder() == null) {
        val chooser = JFileChooser(File(System.getProperty("user.home"), "GroundCallsCapture511")).apply {
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            dialogTitle = "Choose your ground-call data folder"
        }
        if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) {
            JOptionPane.showMessageDialog(null, "No data folder chosen - exiting.")
            return
        }
        repo.setFolder(chooser.selectedFile)
    }
    repo.loadSamples()

    SwingUtilities.invokeLater {
        MainFrame(repo, settings).isVisible = true
    }
}
