package com.tabtap.app.data

import android.content.Context
import android.util.Log
import com.tabtap.music.Composition
import com.tabtap.music.InstrumentFamily
import com.tabtap.music.Key
import com.tabtap.music.ScaleType
import com.tabtap.music.TimeSignature
import com.tabtap.music.Tuning
import com.tabtap.music.Tunings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/** A project loaded and ready to edit. */
data class LoadedProject(
    val id: Long,
    val name: String,
    val composition: Composition,
    val revision: Long,
    /** True when the main document was unreadable and the backup was used instead. */
    val recoveredFromBackup: Boolean = false,
)

/**
 * The single way the app reads and writes projects.
 *
 * Loading never fails outright: if the stored document is unreadable the backup is tried,
 * and if that fails too the project opens as an empty composition with its metadata
 * intact, so a damaged file still gives the user something to work from rather than an
 * error screen.
 */
class ProjectRepository(
    private val dao: ProjectDao,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    fun observeProjects(): Flow<List<ProjectSummary>> = dao.observeSummaries()

    fun observeRecent(limit: Int = 5): Flow<List<ProjectSummary>> = dao.observeRecent(limit)

    suspend fun projectCount(): Int = withContext(io) { dao.count() }

    suspend fun mostRecentProjectId(): Long? = withContext(io) { dao.mostRecentId() }

    /** Create a project and return its id. */
    suspend fun create(
        name: String,
        family: InstrumentFamily = InstrumentFamily.GUITAR,
        tuning: Tuning = Tunings.defaultFor(family, if (family == InstrumentFamily.GUITAR) 6 else 4),
        bpm: Double = 120.0,
        key: Key = Key.of("C", ScaleType.MAJOR) ?: Key.C_MAJOR,
        timeSignature: TimeSignature = TimeSignature.FOUR_FOUR,
    ): Long = withContext(io) {
        val composition = Composition.newGuitarProject(tuning, bpm, key, timeSignature).let { c ->
            if (family == InstrumentFamily.BASS) {
                c.updateTrack(1L) { it.copy(name = "Bass", role = com.tabtap.music.TrackRole.BASS) }
            } else {
                c
            }
        }
        val now = clock()
        dao.insert(
            ProjectEntity(
                name = name.trim().ifEmpty { "Untitled" },
                createdAt = now,
                updatedAt = now,
                lastOpenedAt = now,
                compositionJson = CompositionSerializer.encode(composition),
                instrumentFamily = family.name,
                tuningName = tuning.displayName,
                stringCount = tuning.stringCount,
                bpm = bpm,
                timeSignature = timeSignature.toString(),
                keyName = key.shortName,
                trackCount = composition.tracks.size,
                noteCount = 0,
                barCount = composition.barCount,
            ),
        )
    }

    /** Create a project directly from an imported composition. */
    suspend fun createFromImport(name: String, composition: Composition): Long = withContext(io) {
        val now = clock()
        val track = composition.tracks.firstOrNull()
        dao.insert(
            ProjectEntity(
                name = name.trim().ifEmpty { "Imported" },
                createdAt = now,
                updatedAt = now,
                lastOpenedAt = now,
                compositionJson = CompositionSerializer.encode(composition),
                instrumentFamily = (track?.tuning?.family ?: InstrumentFamily.GUITAR).name,
                tuningName = track?.tuning?.displayName ?: Tunings.STANDARD_E.displayName,
                stringCount = track?.tuning?.stringCount ?: 6,
                bpm = composition.bpm,
                timeSignature = composition.timeSignature.toString(),
                keyName = composition.key.shortName,
                trackCount = composition.tracks.size,
                noteCount = composition.tracks.sumOf { it.notes.size },
                barCount = composition.barCount,
            ),
        )
    }

    /**
     * Load a project.
     *
     * Returns null only when no project with that id exists. A project whose document is
     * damaged still loads: the backup document is tried first, then an empty composition
     * built from the stored metadata, and [LoadedProject.recoveredFromBackup] says so.
     */
    suspend fun load(id: Long): LoadedProject? = withContext(io) {
        val entity = dao.find(id) ?: return@withContext null
        dao.touch(id, clock())

        val primary = runCatching { CompositionSerializer.decode(entity.compositionJson) }
        if (primary.isSuccess) {
            return@withContext LoadedProject(
                id = entity.id,
                name = entity.name,
                composition = primary.getOrThrow(),
                revision = entity.revision,
                recoveredFromBackup = entity.recoveredFromBackup,
            )
        }
        Log.w(TAG, "Project $id has an unreadable document; trying the backup", primary.exceptionOrNull())

        val backup = CompositionSerializer.decodeOrNull(entity.previousCompositionJson)
        if (backup != null) {
            // Promote the backup so the next save has a sound starting point.
            dao.markRecovered(id, CompositionSerializer.encode(backup))
            return@withContext LoadedProject(entity.id, entity.name, backup, entity.revision, true)
        }

        Log.e(TAG, "Project $id could not be recovered; opening an empty composition")
        val fallback = Composition.newGuitarProject(
            tuning = Tunings.presets.firstOrNull { it.displayName == entity.tuningName }
                ?: Tunings.defaultFor(
                    runCatching { InstrumentFamily.valueOf(entity.instrumentFamily) }
                        .getOrDefault(InstrumentFamily.GUITAR),
                    entity.stringCount,
                ),
            bpm = entity.bpm,
            timeSignature = TimeSignature.parse(entity.timeSignature) ?: TimeSignature.FOUR_FOUR,
        )
        LoadedProject(entity.id, entity.name, fallback, entity.revision, true)
    }

    /**
     * Save [composition] to project [id].
     *
     * The write is a single transaction that rotates the current document into the backup
     * column, so an interrupted save can never destroy the last good version. Returns
     * false when the project no longer exists.
     */
    suspend fun save(id: Long, composition: Composition): Boolean = withContext(io) {
        val track = composition.activeTrack ?: composition.tracks.firstOrNull()
        val json = CompositionSerializer.encode(composition)
        val exists = dao.find(id) != null
        if (!exists) return@withContext false
        dao.saveDocument(
            id = id,
            json = json,
            documentVersion = ProjectEntity.CURRENT_DOCUMENT_VERSION,
            now = clock(),
            bpm = composition.bpm,
            timeSignature = composition.timeSignature.toString(),
            keyName = composition.key.shortName,
            tuningName = track?.tuning?.displayName ?: Tunings.STANDARD_E.displayName,
            stringCount = track?.tuning?.stringCount ?: 6,
            instrumentFamily = (track?.tuning?.family ?: InstrumentFamily.GUITAR).name,
            trackCount = composition.tracks.size,
            noteCount = composition.tracks.sumOf { it.notes.size },
            barCount = composition.barCount,
        )
        true
    }

    suspend fun rename(id: Long, name: String) = withContext(io) {
        dao.rename(id, name.trim().ifEmpty { "Untitled" }, clock())
    }

    suspend fun duplicate(id: Long): Long? = withContext(io) {
        val source = dao.find(id) ?: return@withContext null
        dao.duplicate(id, nextCopyName(source.name), clock())
    }

    suspend fun delete(id: Long) = withContext(io) { dao.delete(id) }

    suspend fun acknowledgeRecovery(id: Long) = withContext(io) { dao.clearRecoveryFlag(id) }

    /** "Riff" becomes "Riff copy"; "Riff copy" becomes "Riff copy 2". */
    private fun nextCopyName(name: String): String {
        val match = Regex("^(.*) copy( (\\d+))?$").find(name)
        return if (match == null) {
            "$name copy"
        } else {
            val base = match.groupValues[1]
            val n = match.groupValues[3].toIntOrNull() ?: 1
            "$base copy ${n + 1}"
        }
    }

    companion object {
        private const val TAG = "ProjectRepository"

        fun create(context: Context): ProjectRepository =
            ProjectRepository(TabTapDatabase.get(context).projects())
    }
}
