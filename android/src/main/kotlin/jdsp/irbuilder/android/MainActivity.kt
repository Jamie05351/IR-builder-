package jdsp.irbuilder.android

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import jdsp.irbuilder.ui.App
import jdsp.irbuilder.ui.AppState
import jdsp.irbuilder.ui.Dialogs
import jdsp.irbuilder.ui.JdspTheme

/** Holds the app's state outside the activity, so it outlives the activity being recreated. */
class StateHolder : ViewModel() {
    val state = AppState(viewModelScope)
}

class MainActivity : ComponentActivity() {
    // Results go to Dialogs, which outlives this activity: a picker opened by an activity that
    // Android has since recreated still delivers to whoever asked.
    private val openOne = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> Dialogs.opened(this, listOfNotNull(uri)) }
    private val openMany = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> Dialogs.opened(this, uris) }
    private val create = registerForActivityResult(CreateTyped()) { uri -> Dialogs.created(this, uri) }

    lateinit var state: AppState
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        state = ViewModelProvider(this)[StateHolder::class.java].state
        Dialogs.attach(this, savedInstanceState)
        setContent {
            JdspTheme {
                Box(Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)) { App(state) }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        Dialogs.save(outState)
    }

    override fun onDestroy() {
        Dialogs.detach(this)
        super.onDestroy()
    }

    /** Asks for one or more documents of any type (the importer says what it can't read). */
    fun pick(multiple: Boolean) {
        if (multiple) openMany.launch(arrayOf("*/*")) else openOne.launch(arrayOf("*/*"))
    }

    /** Asks where to save a new document named [name] of [mimeType]. */
    fun createDocument(name: String, mimeType: String) = create.launch(name to mimeType)

    /** CreateDocument with the MIME type chosen per call (the stock one fixes it at registration). */
    private class CreateTyped : ActivityResultContract<Pair<String, String>, Uri?>() {
        override fun createIntent(context: Context, input: Pair<String, String>) =
            Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(input.second).putExtra(Intent.EXTRA_TITLE, input.first)

        override fun parseResult(resultCode: Int, intent: Intent?): Uri? = if (resultCode == RESULT_OK) intent?.data else null
    }
}
