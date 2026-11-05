package com.tabtap.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Project storage.
 *
 * Saves go through [saveDocument], which is a single transaction that rotates the
 * previous document into the backup column and bumps the revision, so a save is either
 * fully applied or not applied at all.
 */
@Dao
interface ProjectDao {

    @Query(
        """
        SELECT id, name, updatedAt, lastOpenedAt, instrumentFamily, tuningName,
               stringCount, bpm, timeSignature, keyName, trackCount, noteCount, barCount
        FROM projects
        ORDER BY lastOpenedAt DESC
        """,
    )
    fun observeSummaries(): Flow<List<ProjectSummary>>

    @Query(
        """
        SELECT id, name, updatedAt, lastOpenedAt, instrumentFamily, tuningName,
               stringCount, bpm, timeSignature, keyName, trackCount, noteCount, barCount
        FROM projects
        ORDER BY lastOpenedAt DESC
        LIMIT :limit
        """,
    )
    fun observeRecent(limit: Int): Flow<List<ProjectSummary>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun find(id: Long): ProjectEntity?

    @Query("SELECT * FROM projects WHERE id = :id")
    fun observe(id: Long): Flow<ProjectEntity?>

    @Query("SELECT id FROM projects ORDER BY lastOpenedAt DESC LIMIT 1")
    suspend fun mostRecentId(): Long?

    @Query("SELECT COUNT(*) FROM projects")
    suspend fun count(): Int

    @Insert
    suspend fun insert(project: ProjectEntity): Long

    @Update
    suspend fun update(project: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE projects SET name = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, name: String, now: Long)

    @Query("UPDATE projects SET lastOpenedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)

    @Query("UPDATE projects SET recoveredFromBackup = 0 WHERE id = :id")
    suspend fun clearRecoveryFlag(id: Long)

    /**
     * Save a new document for a project, atomically.
     *
     * The current document is rotated into the backup column first, so an interrupted
     * save can never leave the project without a readable document. The revision is only
     * bumped on success, which is how a stale in-flight save is detected.
     */
    @Query(
        """
        UPDATE projects SET
            previousCompositionJson = compositionJson,
            compositionJson = :json,
            revision = revision + 1,
            documentVersion = :documentVersion,
            updatedAt = :now,
            bpm = :bpm,
            timeSignature = :timeSignature,
            keyName = :keyName,
            tuningName = :tuningName,
            stringCount = :stringCount,
            instrumentFamily = :instrumentFamily,
            trackCount = :trackCount,
            noteCount = :noteCount,
            barCount = :barCount,
            recoveredFromBackup = 0
        WHERE id = :id
        """,
    )
    suspend fun saveDocument(
        id: Long,
        json: String,
        documentVersion: Int,
        now: Long,
        bpm: Double,
        timeSignature: String,
        keyName: String,
        tuningName: String,
        stringCount: Int,
        instrumentFamily: String,
        trackCount: Int,
        noteCount: Int,
        barCount: Int,
    )

    /** Mark a project as having been opened from its backup document. */
    @Query("UPDATE projects SET recoveredFromBackup = 1, compositionJson = :json WHERE id = :id")
    suspend fun markRecovered(id: Long, json: String)

    @Transaction
    suspend fun duplicate(id: Long, newName: String, now: Long): Long? {
        val source = find(id) ?: return null
        return insert(
            source.copy(
                id = 0L,
                name = newName,
                createdAt = now,
                updatedAt = now,
                lastOpenedAt = now,
                revision = 1L,
                previousCompositionJson = null,
                recoveredFromBackup = false,
            ),
        )
    }
}
