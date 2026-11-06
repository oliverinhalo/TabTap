package com.tabtap.app

import android.app.Application
import com.tabtap.app.audio.AudioEngine
import com.tabtap.app.data.ProjectRepository
import com.tabtap.app.data.TabTapDatabase

/**
 * Holds the two things that must outlive any screen: the audio engine and the project
 * database.
 *
 * The audio engine lives here rather than in a view model because playback must survive
 * a rotation, a navigation, or the activity being recreated. Stopping and restarting the
 * render thread on a configuration change would produce an audible gap in the middle of
 * a take, which is exactly the kind of thing that makes an app feel unreliable.
 */
class TabTapApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        // Installed first, so a fault anywhere after this point is recorded.
        CrashReporter.install(this)
    }

    /** The one audio engine for the process. Opened lazily on first playback. */
    val audioEngine: AudioEngine by lazy { AudioEngine() }

    val database: TabTapDatabase by lazy { TabTapDatabase.get(this) }

    val projects: ProjectRepository by lazy { ProjectRepository(database.projects()) }

    override fun onTerminate() {
        // Only called on emulators, but releasing here is still correct.
        audioEngine.shutdown()
        super.onTerminate()
    }
}
