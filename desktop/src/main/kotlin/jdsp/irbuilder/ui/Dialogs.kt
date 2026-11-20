package jdsp.irbuilder.ui

import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import javax.swing.JFileChooser

/** Native Windows file dialogs (AWT), and a Swing chooser for folders. */
object Dialogs {
    private var lastDir: String? = null

    fun openFiles(title: String, extensions: List<String>, multiple: Boolean = true): List<File>? {
        val d = FileDialog(null as Frame?, title, FileDialog.LOAD)
        d.isMultipleMode = multiple
        lastDir?.let { d.directory = it }
        // Windows' native dialog ignores FilenameFilter; the pattern string is honoured instead.
        d.file = extensions.joinToString(";") { "*.$it" }
        d.setFilenameFilter { _, name -> extensions.any { name.lowercase().endsWith(".$it") } }
        d.isVisible = true
        val files = d.files?.toList().orEmpty()
        if (files.isEmpty()) return null
        lastDir = files.first().parent
        return files
    }

    fun saveFile(title: String, suggested: String): File? {
        val d = FileDialog(null as Frame?, title, FileDialog.SAVE)
        lastDir?.let { d.directory = it }
        d.file = suggested
        d.isVisible = true
        val name = d.file ?: return null
        lastDir = d.directory
        return File(d.directory, name)
    }

    fun chooseFolder(title: String, start: File?): File? {
        val c = JFileChooser(start ?: lastDir?.let(::File))
        c.dialogTitle = title
        c.fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        c.isAcceptAllFileFilterUsed = false
        return if (c.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) c.selectedFile.also { lastDir = it.path } else null
    }
}
