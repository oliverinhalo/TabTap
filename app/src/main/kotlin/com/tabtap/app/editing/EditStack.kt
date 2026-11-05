package com.tabtap.app.editing

import com.tabtap.music.Composition

/** One undoable step: a composition snapshot and the name of the action that produced it. */
data class EditSnapshot(
    val composition: Composition,
    val label: String,
    /** Used to coalesce a run of similar changes, such as dragging a volume fader. */
    val coalesceKey: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

/**
 * Undo and redo.
 *
 * Steps are whole-composition snapshots rather than inverse operations. A composition is
 * a few tens of kilobytes at most and every operation already produces a new immutable
 * one, so snapshots are both cheap and impossible to get wrong — there is no way for an
 * inverse operation to be subtly asymmetric and corrupt the document, which is the usual
 * failure mode of command-based undo.
 *
 * Successive changes that share a [EditSnapshot.coalesceKey] and land within
 * [coalesceWindowMs] replace one another, so dragging a fader or typing a name is one
 * undo step rather than fifty.
 */
class EditStack(
    initial: Composition,
    private val capacity: Int = 150,
    private val coalesceWindowMs: Long = 600L,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val undoStack = ArrayDeque<EditSnapshot>()
    private val redoStack = ArrayDeque<EditSnapshot>()

    /** The current state. Always valid. */
    var current: EditSnapshot = EditSnapshot(initial, "Open")
        private set

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Label of the action that undo would reverse, for the button's tooltip. */
    val undoLabel: String? get() = if (canUndo) current.label else null

    /** Label of the action that redo would reapply. */
    val redoLabel: String? get() = redoStack.lastOrNull()?.label

    val undoDepth: Int get() = undoStack.size

    /**
     * Record a new state.
     *
     * Does nothing when the composition is unchanged, so a no-op edit never consumes an
     * undo step.
     */
    fun push(composition: Composition, label: String, coalesceKey: String? = null) {
        if (composition == current.composition) return

        val now = clock()
        val canCoalesce = coalesceKey != null &&
            current.coalesceKey == coalesceKey &&
            now - current.timestamp <= coalesceWindowMs

        if (!canCoalesce) {
            undoStack.addLast(current)
            if (undoStack.size > capacity) undoStack.removeFirst()
        }
        current = EditSnapshot(composition, label, coalesceKey, now)
        redoStack.clear()
    }

    /**
     * Replace the current state without creating an undo step.
     *
     * Used for things that are not edits — switching the active track, moving the
     * playhead — which should not be undoable but do change the document.
     */
    fun replaceCurrent(composition: Composition) {
        current = current.copy(composition = composition, timestamp = clock())
    }

    /** Undo one step, returning the composition to show, or null when there is nothing to undo. */
    fun undo(): Composition? {
        val previous = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        current = previous
        return previous.composition
    }

    /** Redo one step. */
    fun redo(): Composition? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        current = next
        return next.composition
    }

    /** Discard the history, keeping the current state. Used when a project is opened. */
    fun reset(composition: Composition, label: String = "Open") {
        undoStack.clear()
        redoStack.clear()
        current = EditSnapshot(composition, label, null, clock())
    }
}
