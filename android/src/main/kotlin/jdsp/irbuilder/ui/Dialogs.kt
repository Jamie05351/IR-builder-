package jdsp.irbuilder.ui

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.content.FileProvider
import jdsp.irbuilder.android.MainActivity
import java.io.File

/**
 * The phone app's file handling behind the shared screens' dialog calls.
 *
 * - Opening: Android's file picker; each picked file is copied into the app's storage, keeping
 *   its name (seat, side and band tags are read from it), in a folder per import so files with the
 *   same name from different sessions don't overwrite each other.
 * - Saving and exporting: written to the app's cache first; once written ([written]) Android asks
 *   where to keep it (Downloads, Drive, …) and it's copied there.
 * - Revealing: the share sheet, to send a file straight to another app or device.
 */
object Dialogs {
    private var activity: MainActivity? = null

    /** The label of the button that shows an exported file. */
    const val REVEAL_LABEL = "Share"

    fun attach(a: MainActivity) { activity = a }

    fun detach(a: MainActivity) { if (activity === a) activity = null }

    private fun requireActivity() = activity ?: error("The app isn't in the foreground")

    fun openFiles(title: String, extensions: List<String>, multiple: Boolean = true, onPicked: (List<File>) -> Unit) {
        val a = requireActivity()
        a.pick(multiple) { uris ->
            if (uris.isEmpty()) return@pick
            val dir = File(a.filesDir, "imports/${System.currentTimeMillis()}").apply { mkdirs() }
            val files = uris.mapNotNull { uri -> runCatching { copyIn(uri, dir) }.getOrNull() }
            if (files.size < uris.size) Toast.makeText(a, "${uris.size - files.size} file(s) couldn't be read", Toast.LENGTH_LONG).show()
            if (files.isNotEmpty()) onPicked(files)
        }
    }

    private fun copyIn(uri: Uri, dir: File): File {
        val a = requireActivity()
        val name = a.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }?.replace(Regex("""[\\/:*?"<>|]"""), "_") ?: "file-${System.nanoTime()}"
        val out = File(dir, name)
        a.contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    fun saveFile(title: String, suggested: String, onFile: (File) -> Unit) {
        val dir = File(requireActivity().cacheDir, "out/${System.currentTimeMillis()}").apply { mkdirs() }
        onFile(File(dir, suggested))
    }

    fun chooseFolder(title: String, start: File?, onFolder: (File) -> Unit) {
        onFolder(File(requireActivity().cacheDir, "export/${System.currentTimeMillis()}").apply { mkdirs() })
    }

    /** [file] has been written in the cache: ask where to keep it and copy it there. */
    fun written(file: File) {
        val a = activity ?: return
        a.createDocument(file.name, mimeType(file)) { uri ->
            if (uri == null) return@createDocument
            runCatching {
                a.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            }.onSuccess { Toast.makeText(a, "Saved ${file.name}", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(a, "Couldn't save ${file.name}: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    /** Sends [file] through the share sheet. */
    fun reveal(file: File) {
        val a = activity ?: return
        val uri = FileProvider.getUriForFile(a, "${a.packageName}.files", file)
        val send = Intent(Intent.ACTION_SEND).setType(mimeType(file)).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        a.startActivity(Intent.createChooser(send, file.name))
    }

    private fun mimeType(f: File) = when (f.extension.lowercase()) {
        "zip" -> "application/zip"
        "wav" -> "audio/wav"
        "json", "jirb" -> "application/json"
        else -> "text/plain"
    }
}
