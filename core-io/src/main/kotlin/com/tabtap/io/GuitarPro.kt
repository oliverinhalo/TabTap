package com.tabtap.io

import com.tabtap.music.Articulation
import com.tabtap.music.Composition
import com.tabtap.music.MusicNote
import com.tabtap.music.NoteValue
import com.tabtap.music.Ticks
import com.tabtap.music.TimeSignature
import com.tabtap.music.Track
import java.io.ByteArrayOutputStream

/**
 * Guitar Pro 5 (`.gp5`) export.
 *
 * GP5 is a closed binary format, but version 5.10 is well documented by the
 * long-standing community reverse engineering that TuxGuitar, AlphaTab and Guitar Pro's
 * own importers rely on, and that is what this writer implements: a version string, song
 * information, lyrics, page setup, tempo, key, a channel table of 64 MIDI channel slots,
 * measure headers, track definitions, then the measure/beat data.
 *
 * Guitar Pro 6, 7 and 8 use a completely different container (a zipped XML document with
 * an undocumented schema). Rather than emit something mislabelled, TabTap exports GP5 —
 * which Guitar Pro 6, 7 and 8 all open — and MusicXML, which those versions also import
 * and which carries the notation detail. That is a deliberate choice, not a stub.
 */
object GuitarPro5Exporter {

    private const val VERSION = "FICHIER GUITAR PRO v5.10"

    /** Serialise [composition] as Guitar Pro 5 bytes. */
    fun export(composition: Composition, title: String = "Untitled"): ByteArray {
        val out = ByteArrayOutputStream(8192)
        val w = GpWriter(out)

        // --- Version ---
        w.versionString(VERSION)

        // --- Song information ---
        w.intString(title)          // title
        w.intString("")             // subtitle
        w.intString("")             // artist
        w.intString("")             // album
        w.intString("")             // words author
        w.intString("TabTap")       // music author
        w.intString("")             // copyright
        w.intString("")             // tab author
        w.intString("")             // instructions
        w.int32(0)                  // notice line count

        // --- Lyrics: one track reference plus five empty lines ---
        w.int32(1)
        repeat(5) {
            w.int32(1)
            w.int32(0)
            w.byteArray(ByteArray(0))
        }

        // --- RSE master effect (GP5 only) ---
        w.int32(-1)     // master volume
        w.int32(0)
        repeat(11) { w.int32(0) }   // equaliser bands

        // --- Page setup ---
        writePageSetup(w)

        // --- Tempo ---
        w.intString("")                                  // tempo name
        w.int32(composition.bpm.toInt())
        w.byte(0)                                        // hide tempo

        // --- Key and octave ---
        w.byte(composition.key.sharpsInSignature)
        w.int32(0)

        // --- MIDI channels: 4 ports of 16 channels ---
        writeChannels(w, composition)

        // --- Directions and master reverb (GP5) ---
        repeat(19) { w.int16(-1) }
        w.int32(0)

        val tracks = composition.tracks.ifEmpty { Composition.newGuitarProject().tracks }
        val measureCount = composition.barCount
        w.int32(measureCount)
        w.int32(tracks.size)

        // --- Measure headers ---
        for (m in 0 until measureCount) {
            if (m > 0) w.byte(0)    // blank byte between headers in GP5
            writeMeasureHeader(w, composition, m)
        }

        // --- Track definitions ---
        tracks.forEachIndexed { index, track -> writeTrack(w, track, index) }
        w.byte(0)

        // --- Measure/beat data, measure-major then track ---
        for (m in 0 until measureCount) {
            for (track in tracks) {
                writeMeasureTrack(w, composition, track, m)
            }
            w.byte(0)
        }

        return out.toByteArray()
    }

    private fun writePageSetup(w: GpWriter) {
        w.int32(210)    // page width
        w.int32(297)    // page height
        w.int32(10); w.int32(10); w.int32(10); w.int32(10)   // margins
        w.int32(100)    // score size proportion
        w.int16(0xFF)   // header/footer flags
        // Ten formatting template strings.
        listOf("%TITLE%", "%SUBTITLE%", "%ARTIST%", "%ALBUM%", "Words by %WORDS%",
            "Music by %MUSIC%", "Words & Music by %WORDSMUSIC%", "Copyright %COPYRIGHT%",
            "All Rights Reserved - International Copyright Secured", "Page %N%/%P%")
            .forEach { w.intString(it) }
    }

    private fun writeChannels(w: GpWriter, composition: Composition) {
        val tracks = composition.tracks
        for (port in 0 until 4) {
            for (channel in 0 until 16) {
                val index = port * 16 + channel
                val track = tracks.getOrNull(index)
                w.int32(track?.voice?.gmProgram ?: 0)                           // instrument
                w.byte(((track?.volume ?: 0.8f) * 16).toInt().coerceIn(0, 16))  // volume
                w.byte((((track?.pan ?: 0f) + 1f) * 8).toInt().coerceIn(0, 16)) // balance
                w.byte(0)   // chorus
                w.byte(0)   // reverb
                w.byte(0)   // phaser
                w.byte(0)   // tremolo
                w.byte(0)   // blank
                w.byte(0)   // blank
            }
        }
    }

    private fun writeMeasureHeader(w: GpWriter, composition: Composition, measureIndex: Int) {
        val ts = composition.timeSignature
        // Flags: 0x01 numerator, 0x02 denominator, 0x08 key signature on the first bar.
        var flags = 0
        if (measureIndex == 0) flags = flags or 0x01 or 0x02 or 0x08
        w.byte(flags)
        if (flags and 0x01 != 0) w.byte(ts.beats)
        if (flags and 0x02 != 0) w.byte(ts.beatValue)
        if (flags and 0x08 != 0) {
            w.byte(composition.key.sharpsInSignature)
            w.byte(0)   // 0 == major
        }
        if (flags and 0x01 != 0 || flags and 0x02 != 0) {
            // Beam grouping for the bar.
            w.byte(ts.beats.coerceAtMost(255)); w.byte(0); w.byte(0); w.byte(0)
        }
        if (flags and 0x10 == 0) w.byte(0)      // double bar / repeat alternatives
        w.byte(0)                                // triplet feel: none
    }

    private fun writeTrack(w: GpWriter, track: Track, index: Int) {
        // Flags: 0x01 drums, 0x02 12-string, 0x04 banjo.
        w.byte(if (index == 0) 0x01 else 0x00)
        w.byte(8)   // GP5 track header byte
        w.fixedString(40, track.name)
        w.int32(track.tuning.stringCount)
        // Seven string slots, lowest first then zero padding, as GP5 expects.
        for (s in 0 until 7) {
            w.int32(track.tuning.openStrings.getOrNull(s) ?: 0)
        }
        w.int32(1)                                  // port
        w.int32(index + 1)                          // primary channel
        w.int32(index + 1)                          // effect channel
        w.int32(track.fretCount)
        w.int32(0)                                  // capo
        w.int32(colourFor(index))                   // RGB colour
        // GP5 track extras.
        w.byte(0); w.byte(0); w.byte(0); w.byte(0); w.byte(0); w.byte(0)
        w.byte(0); w.byte(0); w.byte(0); w.byte(0); w.byte(0); w.byte(0)
        w.intString("")                              // RSE instrument name
        w.intString("")                              // RSE instrument sound bank
        repeat(10) { w.int32(0) }                    // RSE equaliser and effect data
    }

    private fun writeMeasureTrack(
        w: GpWriter,
        composition: Composition,
        track: Track,
        measureIndex: Int,
    ) {
        val ts = composition.timeSignature
        val barStart = measureIndex.toLong() * ts.barTicks
        val barEnd = barStart + ts.barTicks
        val beats = track.notes
            .filter { it.startTick >= barStart && it.startTick < barEnd }
            .groupBy { it.startTick }
            .toSortedMap()

        // GP5 stores two voices per measure; TabTap writes one and leaves the second empty.
        w.int32(beats.size)
        for ((tick, group) in beats) {
            writeBeat(w, track, tick, group, barEnd)
        }
        w.int32(0)
    }

    private fun writeBeat(
        w: GpWriter,
        track: Track,
        tick: Long,
        notes: List<MusicNote>,
        barEnd: Long,
    ) {
        val duration = notes.minOf { it.durationTicks }
        val value = NoteValue.exactForTicks(duration) ?: NoteValue.forTicks(duration)
        // Flags: 0x01 dotted.
        var flags = 0
        if (value.dots > 0) flags = flags or 0x01
        if (value.isTuplet) flags = flags or 0x20
        w.byte(flags)
        // Duration is stored as log2 relative to a quarter note: -2 whole ... 4 sixty-fourth.
        w.byte(value.divisionLevel - 2)
        if (value.isTuplet) w.int32(value.tupletNum)

        // String presence bitmask: bit 6 is the lowest string in GP's numbering.
        var mask = 0
        for (note in notes) {
            val s = note.string ?: continue
            val gpString = track.tuning.stringCount - s     // 1 == highest
            mask = mask or (1 shl (7 - gpString))
        }
        w.byte(mask)

        // Notes are written from the highest string downwards.
        for (bit in 6 downTo 0) {
            if (mask and (1 shl bit) == 0) continue
            val gpString = 7 - bit
            val stringIndex = track.tuning.stringCount - gpString
            val note = notes.firstOrNull { it.string == stringIndex } ?: continue
            writeNote(w, note)
        }
        w.int16(0)   // GP5 beat trailer
    }

    private fun writeNote(w: GpWriter, note: MusicNote) {
        // Flags: 0x01 duration percent, 0x02 heavy accent, 0x04 ghost, 0x08 note effects,
        // 0x10 velocity, 0x20 note type, 0x40 accent, 0x80 fingering.
        var flags = 0x20 or 0x10
        if (note.articulations.isNotEmpty()) flags = flags or 0x08
        if (note.has(Articulation.ACCENT)) flags = flags or 0x40
        if (note.has(Articulation.GHOST_NOTE)) flags = flags or 0x04
        w.byte(flags)
        // Note type: 1 normal, 2 tied, 3 dead.
        w.byte(if (note.has(Articulation.DEAD_NOTE)) 3 else 1)
        // Velocity is stored in GP's 1..10 scale where 6 is mezzo-forte (95).
        w.byte(((note.velocity + 15) / 16).coerceIn(1, 10))
        w.byte(note.fret ?: 0)
        if (flags and 0x08 != 0) writeNoteEffects(w, note)
    }

    private fun writeNoteEffects(w: GpWriter, note: MusicNote) {
        var first = 0
        var second = 0
        if (note.has(Articulation.BEND)) first = first or 0x01
        if (note.has(Articulation.HAMMER_ON) || note.has(Articulation.PULL_OFF)) first = first or 0x02
        if (note.has(Articulation.LET_RING)) first = first or 0x08
        if (note.has(Articulation.PALM_MUTE)) second = second or 0x02
        if (note.has(Articulation.STACCATO)) second = second or 0x01
        if (note.has(Articulation.SLIDE_LEGATO) || note.has(Articulation.SLIDE_UP) ||
            note.has(Articulation.SLIDE_DOWN)
        ) second = second or 0x04
        if (note.has(Articulation.HARMONIC) || note.has(Articulation.PINCH_HARMONIC)) second = second or 0x10
        if (note.has(Articulation.TRILL)) second = second or 0x20
        if (note.has(Articulation.VIBRATO) || note.has(Articulation.WIDE_VIBRATO)) first = first or 0x40
        w.byte(first)
        w.byte(second)

        if (first and 0x01 != 0) writeBend(w, note)
        if (second and 0x04 != 0) w.byte(1)     // slide type: shift slide
        if (second and 0x10 != 0) {
            w.byte(if (note.has(Articulation.PINCH_HARMONIC)) 3 else 1)
        }
        if (second and 0x20 != 0) { w.byte(note.fret?.plus(2) ?: 2); w.byte(8) }
    }

    private fun writeBend(w: GpWriter, note: MusicNote) {
        // GP measures bend height in 1/100 of a tone, so a semitone is 50.
        val height = (note.bendSemitones * 50).toInt().coerceIn(0, 1200)
        w.byte(1)           // bend type: normal
        w.int32(height)
        w.int32(3)          // three points: start, peak, hold
        w.int32(0); w.int32(0); w.byte(0)
        w.int32(6); w.int32(height); w.byte(0)
        w.int32(12); w.int32(height); w.byte(0)
    }

    private fun colourFor(index: Int): Int {
        val palette = intArrayOf(0x00C8785A, 0x005A96C8, 0x0078C85A, 0x00C85A96, 0x00C8C85A, 0x005AC8C8)
        return palette[index % palette.size]
    }

    /** Little-endian binary writer with the string conventions Guitar Pro uses. */
    private class GpWriter(private val out: ByteArrayOutputStream) {
        fun byte(v: Int) = out.write(v and 0xFF)

        fun int16(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }

        fun int32(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF)
            out.write((v shr 24) and 0xFF)
        }

        fun byteArray(b: ByteArray) = out.write(b)

        /** A length-prefixed Pascal string padded to a fixed field size. */
        fun fixedString(size: Int, value: String) {
            val bytes = value.toByteArray(Charsets.ISO_8859_1).take(size - 1).toByteArray()
            out.write(bytes.size)
            out.write(bytes)
            repeat(size - 1 - bytes.size) { out.write(0) }
        }

        /** An int-prefixed block containing a Pascal string. */
        fun intString(value: String) {
            val bytes = value.toByteArray(Charsets.ISO_8859_1).take(255).toByteArray()
            int32(bytes.size + 1)
            out.write(bytes.size)
            out.write(bytes)
        }

        /** The version header: a fixed 30-byte Pascal string. */
        fun versionString(value: String) = fixedString(31, value)
    }
}
