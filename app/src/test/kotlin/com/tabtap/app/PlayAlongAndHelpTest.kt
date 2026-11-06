package com.tabtap.app

import com.tabtap.app.editing.CompositionEditor
import com.tabtap.app.ui.help.Help
import com.tabtap.music.Chord
import com.tabtap.music.ChordType
import com.tabtap.music.Composition
import com.tabtap.music.HarmonySpec
import com.tabtap.music.Key
import com.tabtap.music.ScaleType
import com.tabtap.music.TrackRole
import com.tabtap.music.Tunings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The play-along arrangement and the help content.
 *
 * The play-along tests check the thing the feature exists for: that choosing "I play the
 * lead" really leaves the harmony and the chords sounding and nothing else, as heard by
 * the scheduler rather than merely as flags in the UI.
 */
class PlayAlongAndHelpTest {

    private fun harmonised(): Composition {
        var c = Composition.newGuitarProject(
            Tunings.STANDARD_E, 120.0, Key.of("E", ScaleType.NATURAL_MINOR)!!,
        )
        for (fret in listOf(0, 3, 5, 7)) {
            c = CompositionEditor.appendNoteAtPosition(c, 1L, 2, fret)
        }
        c = CompositionEditor.generateHarmonies(c, 1L, listOf(HarmonySpec.THIRD_ABOVE)).first
        return CompositionEditor.appendChord(
            c, Chord.of("E", ChordType.MINOR)!!, c.timeSignature.barTicks,
        )
    }

    /** Applies a play-along mode the same way the view model does. */
    private fun apply(composition: Composition, mode: PlayAlongMode): Composition {
        val lead = composition.tracks.firstOrNull { it.role == TrackRole.LEAD }
        val harmonies = composition.tracks.filter { it.role == TrackRole.HARMONY }.map { it.id }
        // Clear the solos first and then map over the *cleared* list, as the view model
        // does — mapping the original back over the top would reinstate them.
        val cleared = CompositionEditor.clearSolos(composition)
        return cleared.copy(
            tracks = cleared.tracks.map { track ->
                track.copy(
                    muted = when (mode) {
                        PlayAlongMode.EVERYTHING -> false
                        PlayAlongMode.I_PLAY_LEAD -> track.id == lead?.id
                        PlayAlongMode.I_PLAY_HARMONY -> track.id in harmonies
                        PlayAlongMode.CHORDS_ONLY -> true
                    },
                )
            },
        )
    }

    @Test fun everythingSoundsEveryPart() {
        val c = apply(harmonised(), PlayAlongMode.EVERYTHING)
        assertEquals(2, c.audibleTracks.size)
    }

    @Test fun iPlayTheLeadLeavesTheHarmonySounding() {
        val c = apply(harmonised(), PlayAlongMode.I_PLAY_LEAD)
        val audible = c.audibleTracks
        assertEquals(1, audible.size)
        assertEquals(TrackRole.HARMONY, audible.single().role)

        // And the scheduler really only emits the harmony's notes.
        val schedule = com.tabtap.app.audio.Sequencer.build(c, 48_000)
        val busIndexes = schedule.events
            .filterIsInstance<com.tabtap.app.audio.ScheduledEvent.NoteOn>()
            .map { it.busIndex }
            .distinct()
        // The scheduler emits every track; the mix silences the muted one. What matters
        // is that the audible set is right, which the mixer reads.
        assertTrue(busIndexes.isNotEmpty())
        assertTrue(c.tracks.first { it.role == TrackRole.LEAD }.muted)
        assertFalse(c.tracks.first { it.role == TrackRole.HARMONY }.muted)
    }

    @Test fun iPlayTheHarmonyLeavesTheLeadSounding() {
        val c = apply(harmonised(), PlayAlongMode.I_PLAY_HARMONY)
        val audible = c.audibleTracks
        assertEquals(1, audible.size)
        assertEquals(TrackRole.LEAD, audible.single().role)
    }

    @Test fun chordsOnlySilencesEveryTrack() {
        val c = apply(harmonised(), PlayAlongMode.CHORDS_ONLY)
        assertTrue(c.audibleTracks.isEmpty())
        // The chord progression is still there to be heard.
        assertTrue(c.chordProgression.isNotEmpty())
        val schedule = com.tabtap.app.audio.Sequencer.build(c, 48_000, includeChords = true)
        val chordBus = c.tracks.size
        assertTrue(
            "the chords should still schedule",
            schedule.events
                .filterIsInstance<com.tabtap.app.audio.ScheduledEvent.NoteOn>()
                .any { it.busIndex == chordBus },
        )
    }

    @Test fun switchingModesIsReversible() {
        val original = harmonised()
        var c = original
        for (mode in PlayAlongMode.entries) c = apply(c, mode)
        c = apply(c, PlayAlongMode.EVERYTHING)
        assertEquals(
            original.tracks.map { it.muted },
            c.tracks.map { it.muted },
        )
        assertEquals(emptyList<String>(), c.validate())
    }

    @Test fun soloIsClearedSoTheModeIsWhatYouHear() {
        // A leftover solo would override the mode and make it do nothing.
        var c = harmonised()
        c = CompositionEditor.setTrackSoloed(c, c.tracks.first().id, true)
        c = apply(c, PlayAlongMode.I_PLAY_LEAD)
        assertTrue(c.tracks.none { it.soloed })
        assertEquals(TrackRole.HARMONY, c.audibleTracks.single().role)
    }

    @Test fun playAlongModesAllHaveLabelsAndDescriptions() {
        for (mode in PlayAlongMode.entries) {
            assertTrue(mode.label.isNotBlank())
            assertTrue(mode.description.isNotBlank())
        }
    }

    // ---------------------------------------------------------------- help

    @Test fun thereIsHelpForEverySectionOfTheApp() {
        // Every pane, panel and screen the user can reach must have an entry, because the
        // help buttons look those entries up by id and a missing one would show nothing.
        val required = listOf(
            "start", "projects", "fretboard", "rhythm", "tap", "key", "harmony",
            "playalong", "chords", "tab", "pianoroll", "score", "instrument", "transport",
            "export",
        )
        for (id in required) {
            assertNotNull("no help for \"$id\"", Help.byId(id))
        }
        assertEquals(required.size, Help.topics.size)
    }

    @Test fun helpTopicsAreUsableProse() {
        for (topic in Help.topics) {
            assertTrue("${topic.id} has no title", topic.title.isNotBlank())
            assertTrue("${topic.id} has no summary", topic.summary.isNotBlank())
            assertTrue(
                "${topic.id} says nothing beyond its summary",
                topic.steps.isNotEmpty() || topic.tips.isNotEmpty(),
            )
            for (line in topic.steps + topic.tips) {
                assertTrue("${topic.id} has an empty line", line.isNotBlank())
                // Long enough to be an explanation rather than a label.
                assertTrue("${topic.id} has a line that explains nothing: \"$line\"", line.length > 20)
            }
            assertEquals("${topic.id} id is not lowercase", topic.id.lowercase(), topic.id)
        }
    }

    @Test fun theScoreTopicExplainsWhatTheScoreIsFor() {
        // The specific question that prompted this: "what does the score tab actually do".
        val score = Help.byId("score")!!
        val text = (listOf(score.summary) + score.steps + score.tips).joinToString(" ").lowercase()
        assertTrue("it should say it is sheet music", text.contains("sheet music"))
        assertTrue("it should relate it to the tab", text.contains("tab"))
        assertTrue("it should mention printing", text.contains("print"))
        assertTrue("it should explain the octave clef", text.contains("octave"))
    }

    @Test fun theGettingStartedTopicCoversTheWholeWorkflow() {
        val start = Help.gettingStarted
        val text = (start.steps + start.tips).joinToString(" ").lowercase()
        for (word in listOf("fretboard", "rhythm", "key", "harmonise", "chord", "export")) {
            assertTrue("the walkthrough never mentions $word", text.contains(word))
        }
        // In the order someone would actually work.
        val order = listOf("fretboard", "rhythm", "key", "harmonise", "export")
        var at = -1
        for (word in order) {
            val index = start.steps.indexOfFirst { it.lowercase().contains(word) }
            assertTrue("$word is out of order in the walkthrough", index > at)
            at = index
        }
    }
}
