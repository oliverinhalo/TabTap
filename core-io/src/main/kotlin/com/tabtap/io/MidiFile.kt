package com.tabtap.io

import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * A minimal, complete Standard MIDI File writer and reader.
 *
 * Written from the SMF 1.0 specification rather than wrapping a library, because the
 * JVM's `javax.sound.midi` is not available on Android and the format is small and
 * fully specified: a header chunk followed by track chunks of delta-time/event pairs,
 * with variable-length quantities for the delta times.
 */

/** SMF format: a single merged track, or one track per part. */
enum class MidiFormat(val value: Int) {
    /** Format 0: everything on one track. Maximum compatibility. */
    TYPE_0(0),

    /** Format 1: one track per part, sharing a timeline. What a DAW expects. */
    TYPE_1(1);
}

/** One MIDI event at an absolute tick. */
sealed interface MidiEvent {
    val tick: Long

    data class NoteOn(override val tick: Long, val channel: Int, val pitch: Int, val velocity: Int) : MidiEvent
    data class NoteOff(override val tick: Long, val channel: Int, val pitch: Int, val velocity: Int = 0) : MidiEvent
    data class ProgramChange(override val tick: Long, val channel: Int, val program: Int) : MidiEvent
    data class ControlChange(override val tick: Long, val channel: Int, val controller: Int, val value: Int) : MidiEvent
    data class PitchBend(override val tick: Long, val channel: Int, /** -8192..8191 */ val value: Int) : MidiEvent
    data class Tempo(override val tick: Long, val bpm: Double) : MidiEvent
    data class TimeSignatureEvent(
        override val tick: Long,
        val numerator: Int,
        val denominator: Int,
        val clocksPerClick: Int = 24,
        val thirtySecondsPerQuarter: Int = 8,
    ) : MidiEvent
    data class KeySignatureEvent(override val tick: Long, val fifths: Int, val minor: Boolean) : MidiEvent
    data class TrackName(override val tick: Long, val name: String) : MidiEvent
    data class InstrumentName(override val tick: Long, val name: String) : MidiEvent
    data class Text(override val tick: Long, val text: String) : MidiEvent
    data class Marker(override val tick: Long, val text: String) : MidiEvent
    data class Lyric(override val tick: Long, val text: String) : MidiEvent
    data object EndOfTrackMarker : MidiEvent { override val tick: Long get() = 0L }
}

/** One MIDI track: a name and a list of events at absolute ticks. */
data class MidiTrack(val name: String, val events: List<MidiEvent>)

/** A whole MIDI file ready to be written. */
data class MidiSequence(
    val format: MidiFormat,
    val ticksPerQuarter: Int,
    val tracks: List<MidiTrack>,
) {
    init {
        require(ticksPerQuarter in 1..0x7FFF) { "Invalid division $ticksPerQuarter" }
        require(tracks.isNotEmpty()) { "A MIDI file needs at least one track" }
    }
}

/** Writes [MidiSequence] objects as Standard MIDI Files. */
object MidiWriter {

    /** Serialise [sequence] to a byte array. */
    fun write(sequence: MidiSequence): ByteArray {
        val out = ByteArrayOutputStream(4096)
        write(sequence, out)
        return out.toByteArray()
    }

    /** Serialise [sequence] to [stream]. The stream is not closed. */
    fun write(sequence: MidiSequence, stream: OutputStream) {
        val tracks = when (sequence.format) {
            MidiFormat.TYPE_0 -> listOf(mergeToSingleTrack(sequence.tracks))
            MidiFormat.TYPE_1 -> sequence.tracks
        }
        // Header chunk.
        stream.write("MThd".toByteArray(Charsets.US_ASCII))
        writeInt32(stream, 6)
        writeInt16(stream, sequence.format.value)
        writeInt16(stream, tracks.size)
        writeInt16(stream, sequence.ticksPerQuarter)

        for (track in tracks) {
            val body = encodeTrack(track)
            stream.write("MTrk".toByteArray(Charsets.US_ASCII))
            writeInt32(stream, body.size)
            stream.write(body)
        }
    }

    /**
     * Merge several tracks into one, as Format 0 requires.
     *
     * Events keep their channels, so a Format 0 file still plays the parts on separate
     * instruments; only the track structure is flattened.
     */
    fun mergeToSingleTrack(tracks: List<MidiTrack>, name: String = "TabTap"): MidiTrack {
        val all = tracks.flatMap { it.events }
            // Track names from the individual parts become markers so nothing is lost.
            .map { if (it is MidiEvent.TrackName) MidiEvent.Marker(it.tick, it.name) else it }
        return MidiTrack(name, listOf<MidiEvent>(MidiEvent.TrackName(0L, name)) + all.sortedWith(EVENT_ORDER))
    }

    private fun encodeTrack(track: MidiTrack): ByteArray {
        val out = ByteArrayOutputStream(1024)
        val events = track.events.sortedWith(EVENT_ORDER)
        var last = 0L
        var wroteName = events.any { it is MidiEvent.TrackName }
        if (!wroteName && track.name.isNotEmpty()) {
            writeVarLen(out, 0)
            writeMeta(out, 0x03, track.name.toByteArray(Charsets.UTF_8))
            wroteName = true
        }
        for (event in events) {
            if (event is MidiEvent.EndOfTrackMarker) continue
            val delta = (event.tick - last).coerceAtLeast(0L)
            writeVarLen(out, delta)
            writeEvent(out, event)
            last = maxOf(last, event.tick)
        }
        // End of track meta event is mandatory.
        writeVarLen(out, 0)
        writeMeta(out, 0x2F, ByteArray(0))
        return out.toByteArray()
    }

    private fun writeEvent(out: ByteArrayOutputStream, event: MidiEvent) {
        when (event) {
            is MidiEvent.NoteOn -> {
                out.write(0x90 or (event.channel and 0x0F))
                out.write(event.pitch and 0x7F)
                out.write(event.velocity.coerceIn(0, 127))
            }
            is MidiEvent.NoteOff -> {
                out.write(0x80 or (event.channel and 0x0F))
                out.write(event.pitch and 0x7F)
                out.write(event.velocity.coerceIn(0, 127))
            }
            is MidiEvent.ProgramChange -> {
                out.write(0xC0 or (event.channel and 0x0F))
                out.write(event.program and 0x7F)
            }
            is MidiEvent.ControlChange -> {
                out.write(0xB0 or (event.channel and 0x0F))
                out.write(event.controller and 0x7F)
                out.write(event.value.coerceIn(0, 127))
            }
            is MidiEvent.PitchBend -> {
                val v = (event.value.coerceIn(-8192, 8191) + 8192)
                out.write(0xE0 or (event.channel and 0x0F))
                out.write(v and 0x7F)
                out.write((v shr 7) and 0x7F)
            }
            is MidiEvent.Tempo -> {
                // Microseconds per quarter note, 24-bit big endian.
                val usPerQuarter = (60_000_000.0 / event.bpm).toInt()
                writeMeta(
                    out, 0x51,
                    byteArrayOf(
                        ((usPerQuarter shr 16) and 0xFF).toByte(),
                        ((usPerQuarter shr 8) and 0xFF).toByte(),
                        (usPerQuarter and 0xFF).toByte(),
                    ),
                )
            }
            is MidiEvent.TimeSignatureEvent -> {
                // The denominator is stored as a power of two.
                var power = 0
                var d = event.denominator
                while (d > 1) { d = d shr 1; power++ }
                writeMeta(
                    out, 0x58,
                    byteArrayOf(
                        event.numerator.toByte(),
                        power.toByte(),
                        event.clocksPerClick.toByte(),
                        event.thirtySecondsPerQuarter.toByte(),
                    ),
                )
            }
            is MidiEvent.KeySignatureEvent ->
                writeMeta(
                    out, 0x59,
                    byteArrayOf(event.fifths.toByte(), if (event.minor) 1 else 0),
                )
            is MidiEvent.TrackName -> writeMeta(out, 0x03, event.name.toByteArray(Charsets.UTF_8))
            is MidiEvent.InstrumentName -> writeMeta(out, 0x04, event.name.toByteArray(Charsets.UTF_8))
            is MidiEvent.Text -> writeMeta(out, 0x01, event.text.toByteArray(Charsets.UTF_8))
            is MidiEvent.Lyric -> writeMeta(out, 0x05, event.text.toByteArray(Charsets.UTF_8))
            is MidiEvent.Marker -> writeMeta(out, 0x06, event.text.toByteArray(Charsets.UTF_8))
            MidiEvent.EndOfTrackMarker -> writeMeta(out, 0x2F, ByteArray(0))
        }
    }

    private fun writeMeta(out: ByteArrayOutputStream, type: Int, data: ByteArray) {
        out.write(0xFF)
        out.write(type)
        writeVarLen(out, data.size.toLong())
        out.write(data)
    }

    /**
     * Event ordering at equal ticks. Meta events come first so tempo and names are in
     * place before any note sounds, note-offs precede note-ons so a repeated pitch
     * retriggers cleanly instead of being cut short by the previous note's release.
     */
    internal val EVENT_ORDER: Comparator<MidiEvent> = compareBy<MidiEvent> { it.tick }
        .thenBy { priority(it) }

    private fun priority(e: MidiEvent): Int = when (e) {
        is MidiEvent.TrackName -> 0
        is MidiEvent.InstrumentName -> 1
        is MidiEvent.Tempo -> 2
        is MidiEvent.TimeSignatureEvent -> 3
        is MidiEvent.KeySignatureEvent -> 4
        is MidiEvent.Text, is MidiEvent.Marker, is MidiEvent.Lyric -> 5
        is MidiEvent.ProgramChange -> 6
        is MidiEvent.ControlChange -> 7
        is MidiEvent.NoteOff -> 8
        is MidiEvent.PitchBend -> 9
        is MidiEvent.NoteOn -> 10
        MidiEvent.EndOfTrackMarker -> 11
    }

    private fun writeInt16(out: OutputStream, v: Int) {
        out.write((v shr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    private fun writeInt32(out: OutputStream, v: Int) {
        out.write((v shr 24) and 0xFF)
        out.write((v shr 16) and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write(v and 0xFF)
    }

    /** Write a variable-length quantity, the SMF delta-time encoding. */
    internal fun writeVarLen(out: ByteArrayOutputStream, value: Long) {
        require(value >= 0) { "Variable-length quantity cannot be negative" }
        var buffer = value and 0x7F
        var v = value shr 7
        while (v > 0) {
            buffer = (buffer shl 8) or 0x80 or (v and 0x7F)
            v = v shr 7
        }
        while (true) {
            out.write((buffer and 0xFF).toInt())
            if (buffer and 0x80L != 0L) buffer = buffer shr 8 else break
        }
    }
}

/** Thrown when a file is not a readable Standard MIDI File. */
class MidiParseException(message: String) : Exception(message)

/** Reads Standard MIDI Files of format 0, 1 and 2. */
object MidiReader {

    /**
     * Parse [bytes] as a Standard MIDI File.
     * @throws MidiParseException when the data is not a valid SMF.
     */
    fun read(bytes: ByteArray): MidiSequence {
        val r = Cursor(bytes)
        if (r.ascii(4) != "MThd") throw MidiParseException("Not a MIDI file: missing MThd header")
        val headerLength = r.int32()
        if (headerLength < 6) throw MidiParseException("Truncated MIDI header")
        val formatValue = r.int16()
        val trackCount = r.int16()
        val division = r.int16()
        // Skip any extra header bytes a future revision might add.
        r.skip(headerLength - 6)

        if (division and 0x8000 != 0) {
            throw MidiParseException("SMPTE time division is not supported; use ticks per quarter note")
        }
        val format = MidiFormat.entries.firstOrNull { it.value == formatValue }
            ?: MidiFormat.TYPE_1

        val tracks = ArrayList<MidiTrack>(trackCount)
        var index = 0
        while (!r.atEnd && tracks.size < trackCount) {
            val id = r.ascii(4)
            val length = r.int32()
            if (id != "MTrk") { r.skip(length); continue }
            tracks += readTrack(Cursor(r.bytes(length)), index++)
        }
        if (tracks.isEmpty()) throw MidiParseException("MIDI file contains no tracks")
        return MidiSequence(format, division, tracks)
    }

    private fun readTrack(r: Cursor, index: Int): MidiTrack {
        val events = ArrayList<MidiEvent>()
        var tick = 0L
        var runningStatus = 0
        var name = "Track ${index + 1}"

        while (!r.atEnd) {
            tick += r.varLen()
            var status = r.u8()
            if (status < 0x80) {
                // Running status: reuse the previous status byte.
                if (runningStatus == 0) throw MidiParseException("Running status with no prior status byte")
                r.back()
                status = runningStatus
            } else if (status < 0xF0) {
                runningStatus = status
            }

            when {
                status == 0xFF -> {
                    val type = r.u8()
                    val length = r.varLen().toInt()
                    val data = r.bytes(length)
                    when (type) {
                        0x2F -> return MidiTrack(name, events)
                        0x51 -> if (length >= 3) {
                            val us = ((data[0].toInt() and 0xFF) shl 16) or
                                ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
                            if (us > 0) events += MidiEvent.Tempo(tick, 60_000_000.0 / us)
                        }
                        0x58 -> if (length >= 2) {
                            val num = data[0].toInt() and 0xFF
                            val den = 1 shl (data[1].toInt() and 0xFF)
                            events += MidiEvent.TimeSignatureEvent(tick, num, den)
                        }
                        0x59 -> if (length >= 2) {
                            events += MidiEvent.KeySignatureEvent(
                                tick, data[0].toInt(), (data[1].toInt() and 0xFF) == 1,
                            )
                        }
                        0x03 -> {
                            name = String(data, Charsets.UTF_8)
                            events += MidiEvent.TrackName(tick, name)
                        }
                        0x04 -> events += MidiEvent.InstrumentName(tick, String(data, Charsets.UTF_8))
                        0x01 -> events += MidiEvent.Text(tick, String(data, Charsets.UTF_8))
                        0x05 -> events += MidiEvent.Lyric(tick, String(data, Charsets.UTF_8))
                        0x06 -> events += MidiEvent.Marker(tick, String(data, Charsets.UTF_8))
                    }
                }
                status == 0xF0 || status == 0xF7 -> {
                    // System-exclusive data is skipped rather than rejected.
                    val length = r.varLen().toInt()
                    r.skip(length)
                }
                else -> {
                    val channel = status and 0x0F
                    when (status and 0xF0) {
                        0x80 -> events += MidiEvent.NoteOff(tick, channel, r.u8(), r.u8())
                        0x90 -> {
                            val pitch = r.u8()
                            val velocity = r.u8()
                            // A note-on with zero velocity is a note-off.
                            events += if (velocity == 0) {
                                MidiEvent.NoteOff(tick, channel, pitch, 0)
                            } else {
                                MidiEvent.NoteOn(tick, channel, pitch, velocity)
                            }
                        }
                        0xA0 -> { r.u8(); r.u8() }        // Polyphonic aftertouch: ignored.
                        0xB0 -> events += MidiEvent.ControlChange(tick, channel, r.u8(), r.u8())
                        0xC0 -> events += MidiEvent.ProgramChange(tick, channel, r.u8())
                        0xD0 -> r.u8()                     // Channel aftertouch: ignored.
                        0xE0 -> {
                            val lsb = r.u8()
                            val msb = r.u8()
                            events += MidiEvent.PitchBend(tick, channel, ((msb shl 7) or lsb) - 8192)
                        }
                        else -> throw MidiParseException("Unknown MIDI status byte 0x%02X".format(status))
                    }
                }
            }
        }
        return MidiTrack(name, events)
    }

    /** A bounds-checked byte cursor. */
    private class Cursor(private val data: ByteArray) {
        private var pos = 0
        val atEnd: Boolean get() = pos >= data.size

        fun u8(): Int {
            if (pos >= data.size) throw MidiParseException("Unexpected end of MIDI data")
            return data[pos++].toInt() and 0xFF
        }

        fun back() { if (pos > 0) pos-- }

        fun int16(): Int = (u8() shl 8) or u8()

        fun int32(): Int = (u8() shl 24) or (u8() shl 16) or (u8() shl 8) or u8()

        fun ascii(n: Int): String = String(bytes(n), Charsets.US_ASCII)

        fun bytes(n: Int): ByteArray {
            if (n < 0 || pos + n > data.size) throw MidiParseException("Unexpected end of MIDI data")
            val out = data.copyOfRange(pos, pos + n)
            pos += n
            return out
        }

        fun skip(n: Int) { pos = (pos + n).coerceAtMost(data.size) }

        fun varLen(): Long {
            var value = 0L
            var count = 0
            while (true) {
                val b = u8()
                value = (value shl 7) or (b and 0x7F).toLong()
                if (b and 0x80 == 0) break
                if (++count > 4) throw MidiParseException("Malformed variable-length quantity")
            }
            return value
        }
    }
}
