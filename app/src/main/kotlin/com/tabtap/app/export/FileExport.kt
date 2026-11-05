package com.tabtap.app.export

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.tabtap.io.CompositionExporter
import com.tabtap.io.ExportFormat
import com.tabtap.io.ImportFormat
import com.tabtap.io.MidiExportOptions
import com.tabtap.io.MidiImporter
import com.tabtap.io.MusicXmlImporter
import com.tabtap.music.Composition
import com.tabtap.music.Tuning
import com.tabtap.music.Tunings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream

/** The outcome of writing a file. */
sealed interface ExportResult {
    data class Success(val format: ExportFormat, val bytes: Int, val uri: Uri) : ExportResult
    data class Failure(val message: String) : ExportResult
}

/** The outcome of reading a file. */
sealed interface ImportResult {
    data class Success(
        val composition: Composition,
        val suggestedName: String,
        val warnings: List<String>,
    ) : ImportResult

    data class Failure(val message: String) : ImportResult
}

/**
 * Writes exports to, and reads imports from, locations the user chooses.
 *
 * Everything goes through the Storage Access Framework rather than a private directory,
 * so a file lands wherever the user asked for it — their Downloads folder, a cloud drive,
 * anywhere a document provider offers — and TabTap needs no storage permission at all.
 */
object FileExport {

    /** Write [composition] in [format] to [uri]. */
    suspend fun export(
        context: Context,
        uri: Uri,
        composition: Composition,
        format: ExportFormat,
        title: String,
        midiOptions: MidiExportOptions = MidiExportOptions(),
        pdfOptions: PdfOptions = PdfOptions(),
    ): ExportResult = withContext(Dispatchers.IO) {
        try {
            var written = 0
            context.contentResolver.openOutputStream(uri, "wt")?.use { raw ->
                BufferedOutputStream(raw).use { out ->
                    if (format == ExportFormat.PDF) {
                        PdfExporter.export(composition, pdfOptions.copy(title = title), out)
                        // PdfDocument writes directly; the size is not known up front.
                        written = -1
                    } else {
                        val bytes = CompositionExporter.export(
                            composition, format, title, midiOptions,
                        )
                        out.write(bytes)
                        written = bytes.size
                    }
                    out.flush()
                }
            } ?: return@withContext ExportResult.Failure(
                "That location could not be opened for writing",
            )
            ExportResult.Success(format, written, uri)
        } catch (e: SecurityException) {
            ExportResult.Failure("Permission to write there was refused")
        } catch (e: java.io.IOException) {
            ExportResult.Failure("The file could not be written: ${e.message ?: "I/O error"}")
        } catch (e: Exception) {
            ExportResult.Failure("Export failed: ${e.message ?: e::class.simpleName ?: "unknown error"}")
        }
    }

    /** Read a MIDI or MusicXML file from [uri]. */
    suspend fun import(
        context: Context,
        uri: Uri,
        tuning: Tuning = Tunings.STANDARD_E,
        fretCount: Int = 24,
    ): ImportResult = withContext(Dispatchers.IO) {
        val name = displayName(context.contentResolver, uri) ?: "Imported"
        val format = ImportFormat.forFileName(name)
            ?: sniffFormat(context, uri)
            ?: return@withContext ImportResult.Failure(
                "TabTap can read MIDI (.mid) and MusicXML (.musicxml, .xml) files. " +
                    "\"$name\" does not look like either.",
            )
        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: return@withContext ImportResult.Failure("That file could not be opened")
            if (bytes.isEmpty()) return@withContext ImportResult.Failure("That file is empty")

            val baseName = name.substringBeforeLast('.').ifBlank { "Imported" }
            when (format) {
                ImportFormat.MIDI -> {
                    val result = MidiImporter.import(bytes, tuning, fretCount)
                    ImportResult.Success(result.composition, baseName, result.warnings)
                }
                ImportFormat.MUSICXML -> {
                    val result = MusicXmlImporter.import(
                        bytes.toString(Charsets.UTF_8), tuning, fretCount,
                    )
                    ImportResult.Success(
                        result.composition,
                        result.title?.takeIf { it.isNotBlank() } ?: baseName,
                        result.warnings,
                    )
                }
            }
        } catch (e: com.tabtap.io.MidiParseException) {
            ImportResult.Failure(e.message ?: "That MIDI file could not be read")
        } catch (e: com.tabtap.io.MusicXmlParseException) {
            ImportResult.Failure(e.message ?: "That MusicXML file could not be read")
        } catch (e: SecurityException) {
            ImportResult.Failure("Permission to read that file was refused")
        } catch (e: Exception) {
            ImportResult.Failure("Import failed: ${e.message ?: e::class.simpleName ?: "unknown error"}")
        }
    }

    /** Suggested file name for a format, from the project title. */
    fun fileName(title: String, format: ExportFormat): String =
        CompositionExporter.fileName(title, format)

    private fun displayName(resolver: ContentResolver, uri: Uri): String? =
        runCatching {
            resolver.query(
                uri,
                arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
                null, null, null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment

    /**
     * Identify a file by its first bytes, for providers that give no useful name.
     * MIDI files begin with "MThd"; MusicXML is XML.
     */
    private fun sniffFormat(context: Context, uri: Uri): ImportFormat? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            val head = ByteArray(512)
            val read = stream.read(head)
            if (read < 4) return@use null
            val text = String(head, 0, read.coerceAtMost(read), Charsets.ISO_8859_1)
            when {
                text.startsWith("MThd") -> ImportFormat.MIDI
                text.contains("score-partwise") || text.contains("score-timewise") ->
                    ImportFormat.MUSICXML
                text.trimStart().startsWith("<?xml") -> ImportFormat.MUSICXML
                else -> null
            }
        }
    }.getOrNull()
}
