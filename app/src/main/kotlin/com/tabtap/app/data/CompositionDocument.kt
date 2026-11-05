package com.tabtap.app.data

import com.tabtap.music.Composition
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * The on-disk form of a composition.
 *
 * The document is versioned and wraps the composition rather than being it, so new
 * fields can be added without breaking files written by an older build. Unknown keys are
 * ignored on read for the same reason.
 */
@Serializable
data class CompositionDocument(
    val version: Int = ProjectEntity.CURRENT_DOCUMENT_VERSION,
    val composition: Composition,
)

/** Thrown when a stored document cannot be read at all. */
class DocumentCorruptException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Reads and writes composition documents.
 */
object CompositionSerializer {

    /**
     * `ignoreUnknownKeys` lets an older build open a newer file.
     * `encodeDefaults` keeps documents self-describing, which matters when a default
     * changes between versions.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = false
        allowStructuredMapKeys = true
    }

    fun encode(composition: Composition): String =
        json.encodeToString(CompositionDocument(composition = composition))

    /**
     * Decode a document, repairing anything structurally invalid.
     *
     * @throws DocumentCorruptException when the text is not a readable document at all.
     */
    fun decode(text: String): Composition {
        val document = try {
            json.decodeFromString<CompositionDocument>(text)
        } catch (e: Exception) {
            throw DocumentCorruptException("The project document could not be read", e)
        }
        val composition = document.composition
        // A document can be syntactically valid but musically inconsistent — written by
        // an older build, or truncated. Repair rather than refuse.
        return if (composition.validate().isEmpty()) composition else composition.repaired()
    }

    /** Decode without throwing; returns null when the text is unreadable. */
    fun decodeOrNull(text: String?): Composition? =
        if (text.isNullOrBlank()) null else runCatching { decode(text) }.getOrNull()
}
