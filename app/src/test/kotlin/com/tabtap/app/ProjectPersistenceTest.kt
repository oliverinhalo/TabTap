package com.tabtap.app

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tabtap.app.data.CompositionSerializer
import com.tabtap.app.data.ProjectEntity
import com.tabtap.app.data.ProjectRepository
import com.tabtap.app.data.TabTapDatabase
import com.tabtap.app.editing.CompositionEditor
import com.tabtap.music.Chord
import com.tabtap.music.ChordType
import com.tabtap.music.Composition
import com.tabtap.music.HarmonySpec
import com.tabtap.music.InstrumentFamily
import com.tabtap.music.Key
import com.tabtap.music.NoteValue
import com.tabtap.music.Pitch
import com.tabtap.music.ScaleType
import com.tabtap.music.TimeSignature
import com.tabtap.music.TrackRole
import com.tabtap.music.Tunings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Project persistence, against a real SQLite database.
 *
 * These are instrumentation tests because Room's generated code and SQLite itself are
 * part of what is being checked — an in-memory fake would not catch a schema or
 * transaction problem, which is exactly the kind of bug that loses someone's work.
 */
@RunWith(AndroidJUnit4::class)
class ProjectPersistenceTest {

    private lateinit var database: TabTapDatabase
    private lateinit var repository: ProjectRepository
    private var now = 1_700_000_000_000L

    @Before fun setUp() {
        database = TabTapDatabase.inMemory(ApplicationProvider.getApplicationContext())
        repository = ProjectRepository(database.projects(), clock = { now })
    }

    @After fun tearDown() {
        database.close()
    }

    private fun n(s: String) = Pitch.parse(s)!!

    /** A project with a solo, a generated harmony and a chord progression. */
    private fun fullComposition(): Composition {
        var c = Composition.newGuitarProject(
            Tunings.DROP_C, 144.0, Key.of("E", ScaleType.NATURAL_MINOR)!!, TimeSignature.SEVEN_EIGHT,
        )
        for (fret in listOf(0, 3, 5, 7, 10)) {
            c = CompositionEditor.appendNoteAtPosition(c, 1L, 0, fret, NoteValue.EIGHTH)
        }
        c = CompositionEditor.generateHarmonies(c, 1L, listOf(HarmonySpec.THIRD_ABOVE)).first
        c = CompositionEditor.appendChord(c, Chord.of("E", ChordType.MINOR)!!, 3360)
        c = CompositionEditor.setTrackVolume(c, 1L, 0.65f)
        c = CompositionEditor.setTrackPan(c, 1L, -0.4f)
        return c
    }

    @Test fun createAndLoadRoundTripsEverything() = runTest {
        val id = repository.create(
            name = "My Riff",
            family = InstrumentFamily.GUITAR,
            tuning = Tunings.DROP_C,
            bpm = 144.0,
            key = Key.of("E", ScaleType.NATURAL_MINOR)!!,
            timeSignature = TimeSignature.SEVEN_EIGHT,
        )
        assertTrue(id > 0)

        val loaded = repository.load(id)
        assertNotNull(loaded)
        assertEquals("My Riff", loaded!!.name)
        assertEquals(144.0, loaded.composition.bpm, 0.0)
        assertEquals(TimeSignature.SEVEN_EIGHT, loaded.composition.timeSignature)
        assertEquals(Tunings.DROP_C, loaded.composition.tracks.first().tuning)
        assertEquals(4, loaded.composition.key.tonicPitchClass)
        assertFalse(loaded.recoveredFromBackup)
    }

    @Test fun savingAndReloadingPreservesTheWholeComposition() = runTest {
        val id = repository.create("Project")
        val composition = fullComposition()
        assertTrue(repository.save(id, composition))

        val loaded = repository.load(id)!!.composition
        assertEquals(composition.bpm, loaded.bpm, 0.0)
        assertEquals(composition.timeSignature, loaded.timeSignature)
        assertEquals(composition.key, loaded.key)
        assertEquals(composition.tracks, loaded.tracks)
        assertEquals(composition.chordProgression, loaded.chordProgression)
        assertEquals(composition.nextId, loaded.nextId)
        assertEquals(emptyList<String>(), loaded.validate())
    }

    @Test fun theProjectListMirrorsTheMetadata() = runTest {
        val id = repository.create("Listed")
        repository.save(id, fullComposition())
        val summary = repository.observeProjects().first().first { it.id == id }
        assertEquals("Listed", summary.name)
        assertEquals(144.0, summary.bpm, 0.0)
        assertEquals("7/8", summary.timeSignature)
        assertEquals("E min", summary.keyName)
        assertEquals("Drop C", summary.tuningName)
        assertEquals(6, summary.stringCount)
        assertEquals(2, summary.trackCount)
        assertEquals(10, summary.noteCount)
        assertFalse(summary.isEmpty)
    }

    @Test fun savingRepeatedlyKeepsThePreviousDocumentAsABackup() = runTest {
        val id = repository.create("Backed up")
        val first = fullComposition()
        repository.save(id, first)
        val second = CompositionEditor.appendNoteAtPosition(first, 1L, 0, 12)
        repository.save(id, second)

        val entity = database.projects().find(id)!!
        assertEquals(3L, entity.revision)
        assertNotNull("the previous document should be kept", entity.previousCompositionJson)
        // The backup really is the version before the last save.
        val backup = CompositionSerializer.decode(entity.previousCompositionJson!!)
        assertEquals(first.tracks.first().notes.size, backup.tracks.first().notes.size)
        assertEquals(
            second.tracks.first().notes.size,
            CompositionSerializer.decode(entity.compositionJson).tracks.first().notes.size,
        )
    }

    @Test fun anUnreadableDocumentIsRecoveredFromTheBackup() = runTest {
        val id = repository.create("Damaged")
        val good = fullComposition()
        repository.save(id, good)
        repository.save(id, CompositionEditor.appendNoteAtPosition(good, 1L, 0, 12))

        // Corrupt the live document, as an interrupted write or a bad sector would.
        val entity = database.projects().find(id)!!
        database.projects().update(entity.copy(compositionJson = "{\"version\":1,\"compo"))

        val loaded = repository.load(id)!!
        assertTrue("the project should report that it was recovered", loaded.recoveredFromBackup)
        assertEquals(
            "the recovered project should be the previous good version",
            good.tracks.first().notes.size,
            loaded.composition.tracks.first().notes.size,
        )
        assertEquals(emptyList<String>(), loaded.composition.validate())

        // And the recovered document is promoted, so the next save is sound.
        val afterRecovery = repository.load(id)!!
        assertEquals(
            good.tracks.first().notes.size,
            afterRecovery.composition.tracks.first().notes.size,
        )
    }

    @Test fun aProjectWithBothDocumentsDamagedStillOpens() = runTest {
        val id = repository.create("Doubly damaged")
        repository.save(id, fullComposition())
        val entity = database.projects().find(id)!!
        database.projects().update(
            entity.copy(compositionJson = "rubbish", previousCompositionJson = "also rubbish"),
        )

        val loaded = repository.load(id)
        assertNotNull("a damaged project must still open", loaded)
        assertTrue(loaded!!.recoveredFromBackup)
        // The metadata survives even though the music is gone, so the user can see what
        // the project was and start again rather than facing an error screen.
        assertEquals("Doubly damaged", loaded.name)
        assertEquals(144.0, loaded.composition.bpm, 0.0)
        assertEquals(TimeSignature.SEVEN_EIGHT, loaded.composition.timeSignature)
        assertEquals("Drop C", loaded.composition.tracks.first().tuning.displayName)
        assertEquals(emptyList<String>(), loaded.composition.validate())
    }

    @Test fun savingToAMissingProjectFailsWithoutThrowing() = runTest {
        assertFalse(repository.save(9999L, fullComposition()))
        assertNull(repository.load(9999L))
    }

    @Test fun renameDuplicateAndDelete() = runTest {
        val id = repository.create("Original")
        repository.save(id, fullComposition())

        repository.rename(id, "Renamed")
        assertEquals("Renamed", repository.load(id)!!.name)

        val copyId = repository.duplicate(id)!!
        val copy = repository.load(copyId)!!
        assertEquals("Renamed copy", copy.name)
        assertEquals(
            repository.load(id)!!.composition.tracks.first().notes,
            copy.composition.tracks.first().notes,
        )
        // A copy starts its own history, with no inherited backup.
        assertEquals(1L, database.projects().find(copyId)!!.revision)
        assertNull(database.projects().find(copyId)!!.previousCompositionJson)

        // Duplicating the copy numbers it rather than piling up "copy copy".
        val secondCopyId = repository.duplicate(copyId)!!
        assertEquals("Renamed copy 2", repository.load(secondCopyId)!!.name)

        repository.delete(copyId)
        assertNull(repository.load(copyId))
        assertNotNull(repository.load(id))
    }

    @Test fun blankNamesGetASensibleDefault() = runTest {
        val id = repository.create("   ")
        assertEquals("Untitled", repository.load(id)!!.name)
        repository.rename(id, "")
        assertEquals("Untitled", repository.load(id)!!.name)
    }

    @Test fun theListIsOrderedByWhenEachProjectWasLastOpened() = runTest {
        val first = repository.create("First")
        now += 1000
        val second = repository.create("Second")
        now += 1000
        val third = repository.create("Third")

        assertEquals(listOf(third, second, first), repository.observeProjects().first().map { it.id })

        // Opening the oldest moves it to the top.
        now += 1000
        repository.load(first)
        assertEquals(listOf(first, third, second), repository.observeProjects().first().map { it.id })
        assertEquals(first, repository.mostRecentProjectId())
    }

    @Test fun recentProjectsAreLimited() = runTest {
        repeat(8) {
            now += 1000
            repository.create("Project $it")
        }
        assertEquals(3, repository.observeRecent(3).first().size)
        assertEquals(8, repository.projectCount())
    }

    @Test fun importedCompositionsBecomeProjects() = runTest {
        val composition = fullComposition()
        val id = repository.createFromImport("Imported song", composition)
        val loaded = repository.load(id)!!
        assertEquals("Imported song", loaded.name)
        assertEquals(composition.tracks.size, loaded.composition.tracks.size)
        val summary = repository.observeProjects().first().first { it.id == id }
        assertEquals(composition.tracks.sumOf { it.notes.size }, summary.noteCount)
    }

    @Test fun aLargeProjectSavesAndLoadsIntact() = runTest {
        // Something the size of a full song: four tracks of a few hundred notes each.
        var composition = Composition.newGuitarProject(Tunings.SEVEN_STANDARD_B)
        for (bar in 0 until 32) {
            for (step in 0 until 8) {
                composition = CompositionEditor.addNoteAt(
                    composition, 1L,
                    string = step % 7,
                    fret = (bar + step) % 20,
                    startTick = bar * 3840L + step * 480L,
                    durationTicks = 480,
                )
            }
        }
        composition = CompositionEditor.generateHarmonies(
            composition, 1L,
            listOf(HarmonySpec.THIRD_ABOVE, HarmonySpec.SIXTH_ABOVE, HarmonySpec.OCTAVE_BELOW),
        ).first
        assertEquals(1024, composition.tracks.sumOf { it.notes.size })

        val id = repository.create("Big")
        assertTrue(repository.save(id, composition))
        val loaded = repository.load(id)!!.composition
        assertEquals(composition.tracks.map { it.notes }, loaded.tracks.map { it.notes })
        assertEquals(emptyList<String>(), loaded.validate())
    }

    @Test fun manySavesInSuccessionAllLandCorrectly() = runTest {
        // Autosave fires often; each write must be complete and the last must win.
        val id = repository.create("Autosaved")
        var composition = Composition.newGuitarProject()
        repeat(40) { i ->
            composition = CompositionEditor.appendNoteAtPosition(composition, 1L, i % 6, i % 12)
            assertTrue(repository.save(id, composition))
        }
        val loaded = repository.load(id)!!
        assertEquals(40, loaded.composition.tracks.first().notes.size)
        assertEquals(41L, database.projects().find(id)!!.revision)
        assertEquals(emptyList<String>(), loaded.composition.validate())
    }

    @Test fun aNewerDocumentVersionStillOpens() = runTest {
        // Forward compatibility: a project written by a later build, with fields this
        // one does not know about, must still open.
        val id = repository.create("From the future")
        val document = CompositionSerializer.encode(fullComposition())
            .replaceFirst("\"version\":1", "\"version\":99")
            .replaceFirst("{", "{\"unknownField\":\"ignored\",")
        val entity = database.projects().find(id)!!
        database.projects().update(entity.copy(compositionJson = document))

        val loaded = repository.load(id)!!
        assertFalse("it should load, not fall back to the backup", loaded.recoveredFromBackup)
        assertEquals(2, loaded.composition.tracks.size)
    }

    @Test fun theEntityDefaultsAreCoherent() {
        val entity = ProjectEntity(
            name = "x", createdAt = 0, updatedAt = 0, lastOpenedAt = 0,
            compositionJson = "{}", instrumentFamily = "GUITAR", tuningName = "Standard E",
            stringCount = 6, bpm = 120.0, timeSignature = "4/4", keyName = "C maj",
            trackCount = 1, noteCount = 0, barCount = 1,
        )
        assertEquals(ProjectEntity.CURRENT_DOCUMENT_VERSION, entity.documentVersion)
        assertEquals(1L, entity.revision)
        assertFalse(entity.recoveredFromBackup)
        assertNull(entity.previousCompositionJson)
    }
}
