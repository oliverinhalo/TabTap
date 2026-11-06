package com.tabtap.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.tabtap.app.design.TabTapTheme
import com.tabtap.app.ui.TabTapApp

/**
 * The only activity.
 *
 * It is configured to handle every configuration change itself — rotation, resizing,
 * multi-window, folding — so the audio engine and the editing state are never torn down
 * by the system. The engine lives in the application object and the editing state in a
 * view model, and the current document is flushed to disk in `onStop`, which together
 * mean there is no sequence of rotations, backgroundings or process deaths that loses
 * work.
 */
class MainActivity : ComponentActivity() {

    private var viewModel: StudioViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val app = application as TabTapApplication

        setContent {
            val model: StudioViewModel = viewModel(
                factory = object : ViewModelProvider.Factory {
                    @Suppress("UNCHECKED_CAST")
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        StudioViewModel(app, app.projects, app.audioEngine) as T
                },
            )
            viewModel = model
            val state by model.state.collectAsStateWithLifecycle()
            TabTapTheme(darkTheme = true, uiScale = state.uiScale) {
                TabTapApp(model, state)
            }
        }
    }

    /**
     * Save on the way out.
     *
     * `onStop` is the last callback guaranteed before the process can be killed, so this
     * is where the document is flushed. The autosave debounce means there is usually
     * nothing left to write, but a save here is what makes "closing the app" safe.
     */
    override fun onStop() {
        viewModel?.saveNow()
        super.onStop()
    }

    override fun onDestroy() {
        // The engine is owned by the application, but if the activity is going away for
        // good there is nothing left to hear, so stop the transport.
        if (isFinishing) {
            (application as TabTapApplication).audioEngine.panic()
        }
        super.onDestroy()
    }
}
