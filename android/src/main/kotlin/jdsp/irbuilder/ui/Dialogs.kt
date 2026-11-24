package jdsp.irbuilder.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import jdsp.irbuilder.android.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The phone app's file handling behind the shared screens' dialog calls.
 *
 * - Opening: Android's file picker; each picked file is copied into the app's storage in a
 *   folder of its own, keeping its name (seat, side and band tags are read from it), so two
 *   files with the same name never overwrite each other.
 * - Saving and exporting: written in the app's cache first; once written ([written]) Android
 *   asks where to keep it and it's copied there.
 * - Revealing: the share sheet, to send a file straight to another app or device.
 *
 * Copies run on a background thread. What's pending (who asked to open files, which file is
 * waiting to be saved) lives here and in the activity's saved state, not in the activity, so a
 * result still lands if Android recreated the activity, or the whole app, while its picker was
 * open.
 */
object Dialogs {
    private var activity: MainActivity? = null

    /** What the open picker is for, and who gets the files. */
    private enum class OpenKind { MEASUREMENTS, PROJECT, CURVE }
    private var pendingOpen: OpenKind? = null
    private var onOpened: ((List<File>) -> Unit)? = null
    /** The written file waiting for the user to choose where to save it. */
    private var pendingSave: File? = null

    /** The label of the button that shows an exported file. */
    const val REVEAL_LABEL = "Share"

    private const val KEY_OPEN = "jdsp.pendingOpen"
    private const val KEY_SAVE = "jdsp.pendingSave"

    fun attach(a: MainActivity, saved: Bundle?) {
        activity = a
        // After the app was restarted with a picker open: what was pending comes back from the
        // saved state; files being opened go where that kind of file goes.
        if (saved != null) {
            if (pendingOpen == null) saved.getString(KEY_OPEN)?.let { k -> pendingOpen = OpenKind.valueOf(k) }
            if (pendingSave == null) saved.getString(KEY_SAVE)?.let { p -> pendingSave = File(p) }
        }
    }

    fun save(out: Bundle) {
        pendingOpen?.let { out.putString(KEY_OPEN, it.name) }
        pendingSave?.let { out.putString(KEY_SAVE, it.path) }
    }

    fun detach(a: MainActivity) { if (activity === a) activity = null }

    private fun requireActivity() = activity ?: error("The app isn't in the foreground")

    fun openFiles(title: String, extensions: List<String>, multiple: Boolean = true, onPicked: (List<File>) -> Unit) {
        pendingOpen = when {
            "jirb" in extensions -> OpenKind.PROJECT
            "cal" in extensions -> OpenKind.CURVE
            else -> OpenKind.MEASUREMENTS
        }
        onOpened = onPicked
        requireActivity().pick(multiple)
    }

    /** The picker's result, delivered to whichever activity is current. */
    fun opened(a: MainActivity, uris: List<Uri>) {
        val kind = pendingOpen ?: return
        val callback = onOpened
        pendingOpen = null
        onOpened = null
        if (uris.isEmpty()) return
        a.lifecycleScope.launch {
            val batch = File(a.filesDir, "imports/${System.currentTimeMillis()}")
            val files = withContext(Dispatchers.IO) {
                uris.mapIndexedNotNull { i, uri -> runCatching { copyIn(a, uri, File(batch, "$i").apply { mkdirs() }) }.getOrNull() }
            }
            if (files.size < uris.size) Toast.makeText(a, "${uris.size - files.size} file(s) couldn't be read", Toast.LENGTH_LONG).show()
            if (files.isEmpty()) return@launch
            // The screen that asked, or (after a restart) the place this kind of file goes.
            if (callback != null) callback(files) else when (kind) {
                OpenKind.MEASUREMENTS -> a.state.addFiles(files)
                OpenKind.PROJECT -> a.state.openProject(files.first())
                OpenKind.CURVE -> a.state.importCurve(files.first())
            }
        }
    }

    private fun copyIn(a: MainActivity, uri: Uri, dir: File): File {
        // The provider's display name; a plain file:// URI (some file managers) has none, so the last
        // path segment, which is the file's name; only failing both, a made-up one.
        val provided = runCatching {
            a.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        val name = (provided ?: uri.lastPathSegment)?.replace(Regex("""[\\/:*?"<>|]"""), "_") ?: "file-${System.nanoTime()}"
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

    /** [file] has been written in the cache: ask where to keep it. */
    fun written(file: File) {
        val a = activity ?: return
        pendingSave = file
        a.createDocument(file.name, mimeType(file))
    }

    /** The save location chosen (or null if cancelled): copy the waiting file there. */
    fun created(a: MainActivity, uri: Uri?) {
        val file = pendingSave ?: return
        pendingSave = null
        if (uri == null) return
        a.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { a.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } } }
            }
            result.onSuccess { Toast.makeText(a, "Saved ${file.name}", Toast.LENGTH_SHORT).show() }
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

    /** Tests only: forget anything pending. */
    internal fun reset() { pendingOpen = null; onOpened = null; pendingSave = null }
}
