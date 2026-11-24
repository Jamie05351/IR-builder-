package jdsp.irbuilder.ui

import java.awt.Desktop
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser

/**
 * The Windows app's file dialogs (AWT for files, Swing for folders). The shared screens call these
 * with callbacks, so the Android app can answer the same calls asynchronously; here each dialog is
 * modal and the callback runs straight away with the choice (or not at all on cancel).
 */
object Dialogs {
    private var lastDir: String? = null

    /** The label of the button that shows an exported file. */
    const val REVEAL_LABEL = "Open folder"

    fun openFiles(title: String, extensions: List<String>, multiple: Boolean = true, onPicked: (List<File>) -> Unit) {
        val d = FileDialog(null as Frame?, title, FileDialog.LOAD)
        d.isMultipleMode = multiple
        lastDir?.let { d.directory = it }
        // Windows' native dialog ignores FilenameFilter; the pattern string is honoured instead.
        d.file = extensions.joinToString(";") { "*.$it" }
        d.setFilenameFilter { _, name -> extensions.any { name.lowercase().endsWith(".$it") } }
        d.isVisible = true
        val files = d.files?.toList().orEmpty()
        if (files.isEmpty()) return
        lastDir = files.first().parent
        onPicked(files)
    }

    fun saveFile(title: String, suggested: String, onFile: (File) -> Unit) {
        val d = FileDialog(null as Frame?, title, FileDialog.SAVE)
        lastDir?.let { d.directory = it }
        d.file = suggested
        d.isVisible = true
        val name = d.file ?: return
        lastDir = d.directory
        onFile(File(d.directory, name))
    }

    fun chooseFolder(title: String, start: File?, onFolder: (File) -> Unit) {
        val c = JFileChooser(start ?: lastDir?.let(::File))
        c.dialogTitle = title
        c.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        c.isAcceptAllFileFilterUsed = false
        if (c.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) onFolder(c.selectedFile.also { lastDir = it.path })
    }

    /** A file was written where the user chose: nothing more to do on Windows. */
    fun written(file: File) = Unit

    /** Shows [file] in Explorer. */
    fun reveal(file: File) {
        runCatching { Desktop.getDesktop().open(file.parentFile) }
    }
}
