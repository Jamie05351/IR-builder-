package jdsp.irbuilder.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import jdsp.irbuilder.ui.App
import jdsp.irbuilder.ui.AppState
import jdsp.irbuilder.ui.Dialogs
import jdsp.irbuilder.ui.JdspTheme

class MainActivity : ComponentActivity() {
    private var onOpened: ((List<Uri>) -> Unit)? = null
    private var onCreated: ((Uri?) -> Unit)? = null

    private val openOne = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> onOpened?.invoke(listOfNotNull(uri)); onOpened = null }
    private val openMany = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> onOpened?.invoke(uris); onOpened = null }
    private val create = registerForActivityResult(CreateTyped()) { uri -> onCreated?.invoke(uri); onCreated = null }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Dialogs.attach(this)
        setContent {
            JdspTheme {
                val scope = rememberCoroutineScope()
                val state = remember { AppState(scope) }
                Box(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) { App(state) }
            }
        }
    }

    override fun onDestroy() {
        Dialogs.detach(this)
        super.onDestroy()
    }

    /** Asks for one or more documents of any type (the importer says what it can't read). */
    fun pick(multiple: Boolean, onPicked: (List<Uri>) -> Unit) {
        onOpened = onPicked
        if (multiple) openMany.launch(arrayOf("*/*")) else openOne.launch(arrayOf("*/*"))
    }

    /** Asks where to save a new document named [name] of [mimeType]. */
    fun createDocument(name: String, mimeType: String, onUri: (Uri?) -> Unit) {
        onCreated = onUri
        create.launch(name to mimeType)
    }

    /** CreateDocument with the MIME type chosen per call (the stock one fixes it at registration). */
    private class CreateTyped : ActivityResultContract<Pair<String, String>, Uri?>() {
        override fun createIntent(context: Context, input: Pair<String, String>) =
            Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(input.second).putExtra(Intent.EXTRA_TITLE, input.first)

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == RESULT_OK) intent?.data else null
    }
}
