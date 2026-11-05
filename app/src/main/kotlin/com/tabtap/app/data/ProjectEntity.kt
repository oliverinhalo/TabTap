package com.tabtap.app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A stored project.
 *
 * The musical content is held as one JSON document in [compositionJson] rather than
 * spread across relational tables. That is a deliberate choice:
 *
 *  * A composition is only ever read and written as a whole — autosave, undo and
 *    playback all work on the complete object — so a single document write is both
 *    faster and atomic, with no chance of a half-saved edit.
 *  * It makes the format versionable and forward-compatible: an older build can still
 *    open a newer file, and [com.tabtap.music.Composition.repaired] fixes anything it
 *    cannot make sense of instead of refusing to load.
 *
 * Everything the project list needs to show — name, tempo, key, tuning, counts — is
 * mirrored into indexed columns, so the list is a cheap query that never has to parse
 * a document.
 *
 * [previousCompositionJson] keeps the last known-good document. If the app is killed
 * mid-save or a document turns out to be unreadable, the project opens from this instead
 * of being lost.
 */
@Entity(
    tableName = "projects",
    indices = [Index("lastOpenedAt"), Index("updatedAt"), Index("name")],
)
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,

    val name: String,

    /** Milliseconds since the epoch. */
    val createdAt: Long,
    val updatedAt: Long,
    val lastOpenedAt: Long,

    /** Incremented on every successful save, so a stale write can be detected. */
    val revision: Long = 1L,

    /** Version of the composition document format, for future migrations. */
    @ColumnInfo(defaultValue = "1")
    val documentVersion: Int = CURRENT_DOCUMENT_VERSION,

    /** The composition, serialised as JSON. */
    val compositionJson: String,

    /** The previous successfully saved document, kept for recovery. */
    val previousCompositionJson: String? = null,

    // --- Mirrored metadata, for the project list. ---
    val instrumentFamily: String,
    val tuningName: String,
    val stringCount: Int,
    val bpm: Double,
    val timeSignature: String,
    val keyName: String,
    val trackCount: Int,
    val noteCount: Int,
    val barCount: Int,

    /** Set when the document failed to parse and the project was opened from backup. */
    @ColumnInfo(defaultValue = "0")
    val recoveredFromBackup: Boolean = false,
) {
    companion object {
        const val CURRENT_DOCUMENT_VERSION = 1
    }
}

/** The project-list row: everything the list shows, without the document. */
data class ProjectSummary(
    val id: Long,
    val name: String,
    val updatedAt: Long,
    val lastOpenedAt: Long,
    val instrumentFamily: String,
    val tuningName: String,
    val stringCount: Int,
    val bpm: Double,
    val timeSignature: String,
    val keyName: String,
    val trackCount: Int,
    val noteCount: Int,
    val barCount: Int,
) {
    val isEmpty: Boolean get() = noteCount == 0
}
