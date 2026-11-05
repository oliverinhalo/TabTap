package com.tabtap.io

import com.tabtap.music.Composition
import com.tabtap.music.TimeSignature
import com.tabtap.music.notation.TabEngraver

/**
 * The export formats TabTap can produce, and what each one carries.
 *
 * The descriptions are shown on the export screen so the choice is informed rather than
 * a list of extensions.
 */
enum class ExportFormat(
    val id: String,
    val displayName: String,
    val extension: String,
    val mimeType: String,
    val description: String,
    /** True when the format keeps each part on its own track. */
    val multiTrack: Boolean,
) {
    MIDI_TYPE_1(
        "midi_1", "MIDI (multi-track)", "mid", "audio/midi",
        "Standard MIDI File, format 1. One track per part with its own instrument, " +
            "plus a conductor track carrying tempo, metre and key. What a DAW expects.",
        multiTrack = true,
    ),
    MIDI_TYPE_0(
        "midi_0", "MIDI (single track)", "mid", "audio/midi",
        "Standard MIDI File, format 0. Everything merged onto one track, with the parts " +
            "kept apart by MIDI channel. Maximum compatibility with older hardware.",
        multiTrack = false,
    ),
    MUSICXML(
        "musicxml", "MusicXML", "musicxml", "application/vnd.recordare.musicxml+xml",
        "Full notation: beams, ties, accidentals, stems, techniques, string and fret " +
            "numbers and the tuning. Opens in MuseScore, Sibelius, Finale and Guitar Pro.",
        multiTrack = true,
    ),
    GUITAR_PRO_5(
        "gp5", "Guitar Pro 5", "gp5", "application/x-guitar-pro",
        "Guitar Pro 5.10 tablature, which Guitar Pro 6, 7 and 8 also open. Carries " +
            "tuning, frets, techniques and bends.",
        multiTrack = true,
    ),
    ASCII_TAB(
        "ascii", "ASCII tab", "txt", "text/plain",
        "Plain-text tablature, the format used in forum posts and text files.",
        multiTrack = false,
    ),
    PDF(
        "pdf", "PDF sheet music", "pdf", "application/pdf",
        "Printable notation and tablature, engraved to the same layout shown on screen.",
        multiTrack = true,
    );

    companion object {
        /** Formats produced entirely by `core-io`; PDF needs the Android renderer. */
        val PURE_KOTLIN: List<ExportFormat> =
            listOf(MIDI_TYPE_1, MIDI_TYPE_0, MUSICXML, GUITAR_PRO_5, ASCII_TAB)

        fun byId(id: String): ExportFormat? = entries.firstOrNull { it.id == id }
    }
}

/** The import formats TabTap can read. */
enum class ImportFormat(
    val id: String,
    val displayName: String,
    val extensions: List<String>,
    val mimeTypes: List<String>,
) {
    MIDI("midi", "MIDI", listOf("mid", "midi"), listOf("audio/midi", "audio/x-midi")),
    MUSICXML(
        "musicxml", "MusicXML",
        listOf("musicxml", "xml"),
        listOf("application/vnd.recordare.musicxml+xml", "application/xml", "text/xml"),
    );

    companion object {
        fun forFileName(name: String): ImportFormat? {
            val ext = name.substringAfterLast('.', "").lowercase()
            return entries.firstOrNull { ext in it.extensions }
        }
    }
}

/**
 * Produces export bytes for every format that needs no Android APIs.
 *
 * Keeping this in the pure-Kotlin layer means exports are unit tested by reading the
 * bytes back, rather than only being checked by eye.
 */
object CompositionExporter {

    /**
     * Export [composition] in [format].
     *
     * @throws IllegalArgumentException for [ExportFormat.PDF], which the Android layer
     *   renders because it needs a platform canvas.
     */
    fun export(
        composition: Composition,
        format: ExportFormat,
        title: String = "Untitled",
        options: MidiExportOptions = MidiExportOptions(),
    ): ByteArray = when (format) {
        ExportFormat.MIDI_TYPE_1 ->
            MidiExporter.export(composition, options.copy(format = MidiFormat.TYPE_1))
        ExportFormat.MIDI_TYPE_0 ->
            MidiExporter.export(composition, options.copy(format = MidiFormat.TYPE_0))
        ExportFormat.MUSICXML ->
            MusicXmlExporter.export(composition, title).toByteArray(Charsets.UTF_8)
        ExportFormat.GUITAR_PRO_5 ->
            GuitarPro5Exporter.export(composition, title)
        ExportFormat.ASCII_TAB ->
            asciiTab(composition, title).toByteArray(Charsets.UTF_8)
        ExportFormat.PDF ->
            throw IllegalArgumentException("PDF export is rendered by the Android layer")
    }

    /** A complete ASCII tab document: a header, then every track's tab. */
    fun asciiTab(composition: Composition, title: String): String {
        val sb = StringBuilder()
        sb.append(title).append('\n')
        sb.append("=".repeat(title.length.coerceAtLeast(3))).append('\n')
        sb.append("Key: ").append(composition.key.displayName).append('\n')
        sb.append("Tempo: ").append(composition.bpm.toInt()).append(" BPM")
        sb.append("    Time: ").append(composition.timeSignature).append('\n')
        if (composition.chordProgression.isNotEmpty()) {
            sb.append("Chords: ")
                .append(composition.chordProgression.joinToString(" | ") { it.chord.symbol })
                .append('\n')
        }
        sb.append('\n')
        for (track in composition.tracks) {
            sb.append(track.name).append("  (").append(track.voice.displayName).append(", ")
                .append(track.tuning.displayName).append(": ")
                .append(track.tuning.stringNames.joinToString(" ")).append(")\n\n")
            sb.append(TabEngraver.toAsciiTab(track, composition.timeSignature))
            sb.append('\n')
        }
        sb.append("Generated by TabTap\n")
        return sb.toString()
    }

    /** Suggested file name for an export, with unsafe characters removed. */
    fun fileName(title: String, format: ExportFormat): String {
        val safe = title.trim()
            // Unsafe characters become a space rather than vanishing, so "Riff/1: draft"
            // stays readable as "Riff 1 draft" instead of running together.
            .replace(Regex("[^A-Za-z0-9 _.-]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifEmpty { "TabTap project" }
        val suffix = when (format) {
            ExportFormat.MIDI_TYPE_0 -> " (type 0)"
            else -> ""
        }
        return "$safe$suffix.${format.extension}"
    }
}
