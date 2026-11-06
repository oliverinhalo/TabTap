package com.tabtap.app.audio

import com.tabtap.music.Articulation
import com.tabtap.music.Composition
import com.tabtap.music.Fretboard
import com.tabtap.music.InstrumentVoice
import com.tabtap.music.MusicNote
import com.tabtap.music.TempoMap
import com.tabtap.music.Ticks
import com.tabtap.music.TimeSignature
import com.tabtap.music.Track
import com.tabtap.music.Tunings
import kotlin.math.roundToLong

/** One thing the audio engine has to do at a precise frame. */
sealed interface ScheduledEvent {
    /** Absolute frame position from the start of the timeline. */
    val frame: Long

    data class NoteOn(
        override val frame: Long,
        val busIndex: Int,
        val pitch: Int,
        val velocity: Float,
        val articulation: VoiceArticulation,
        val key: Int,
    ) : ScheduledEvent

    data class NoteOff(
        override val frame: Long,
        val busIndex: Int,
        val key: Int,
    ) : ScheduledEvent

    data class Click(
        override val frame: Long,
        val accented: Boolean,
    ) : ScheduledEvent
}

/** A track's mix settings, read live so mute, solo and volume respond immediately. */
class BusMix(
    @Volatile var gain: Float = 0.8f,
    @Volatile var pan: Float = 0f,
    @Volatile var audible: Boolean = true,
)

/**
 * A rendered timeline: the events to play, how long it is, and how to convert between
 * frames and musical ticks.
 */
class Schedule(
    val events: List<ScheduledEvent>,
    val lengthFrames: Long,
    val sampleRate: Int,
    private val tempoMap: TempoMap,
    /** Frames of count-in before tick 0. */
    val countInFrames: Long,
    /** Number of metronome pulses in the count-in. */
    val countInPulses: Int,
    /** One bus per track, in the same order as [Composition.tracks]. */
    val busVoices: List<InstrumentVoice>,
    val trackIds: List<Long>,
    /**
     * The musical tick this schedule begins at.
     *
     * Frame zero of a schedule is the start of *playback*, not the start of the piece, so
     * both conversions below have to offset by this. Without it, playing or looping from
     * the middle of a composition would report a playhead that is wrong by however far in
     * playback started — and the loop boundaries would be computed in the wrong place.
     */
    val fromTick: Long = 0L,
) {
    /** Musical tick at a frame position, accounting for the count-in and tempo changes. */
    fun tickAtFrame(frame: Long): Long {
        val musicalFrame = frame - countInFrames
        if (musicalFrame <= 0) return fromTick
        val secondsIntoPlayback = musicalFrame.toDouble() / sampleRate
        return tempoMap.tickAtSeconds(tempoMap.secondsAt(fromTick) + secondsIntoPlayback)
    }

    /** Frame position of a musical tick within this schedule. */
    fun frameAtTick(tick: Long): Long {
        val seconds = tempoMap.secondsAt(tick) - tempoMap.secondsAt(fromTick)
        return countInFrames + (seconds * sampleRate).roundToLong().coerceAtLeast(0L)
    }

    val isEmpty: Boolean get() = events.isEmpty()

    companion object {
        val EMPTY = Schedule(
            emptyList(), 0L, 48_000, TempoMap.of(120.0), 0L, 0, emptyList(), emptyList(), 0L,
        )
    }
}

/**
 * Turns a [Composition] into a [Schedule].
 *
 * All timing is resolved to integer frame positions here, once, against the tempo map.
 * The render thread then only has to compare a frame counter, which is what keeps
 * playback locked to the metronome: both come from the same clock and neither can drift.
 */
object Sequencer {

    /**
     * Build a schedule.
     *
     * @param fromTick where playback starts. Notes already sounding at that point are
     *   started at the beginning so playback from the middle of a held note still sounds.
     * @param countInBars bars of metronome before the music starts.
     * @param metronome include the click track.
     */
    fun build(
        composition: Composition,
        sampleRate: Int,
        fromTick: Long = 0L,
        toTick: Long? = null,
        countInBars: Int = 0,
        metronome: Boolean = false,
        metronomeLevel: Float = 0.7f,
        includeChords: Boolean = true,
        /**
         * Keep the schedule running to at least this tick, whatever the music is.
         *
         * Tapping a rhythm plays no tracks - the notes are sounded by the taps - so the
         * music's own end is tick zero and the click track was one bar long. Playback
         * then ended after that bar, the playhead stopped advancing, and every later tap
         * recorded the same tick, which piled the whole phrase onto beat one of bar one.
         */
        minimumEndTick: Long = 0L,
    ): Schedule {
        val tempoMap = composition.tempoMap
        val ts = composition.timeSignature
        val endTick = maxOf(
            toTick ?: maxOf(composition.endTick, ts.barTicks.toLong()),
            minimumEndTick,
        )

        val countInTicks = countInBars.toLong() * ts.barTicks
        val countInFrames = if (countInBars > 0) {
            (Ticks.toSeconds(countInTicks, tempoMap.bpmAt(fromTick)) * sampleRate).roundToLong()
        } else {
            0L
        }

        // Frame position of a tick, measured from the start of playback.
        fun frameOf(tick: Long): Long {
            val seconds = tempoMap.secondsAt(tick) - tempoMap.secondsAt(fromTick)
            return countInFrames + (seconds * sampleRate).roundToLong()
        }

        val events = ArrayList<ScheduledEvent>(256)
        val busVoices = ArrayList<InstrumentVoice>(composition.tracks.size + 1)
        val trackIds = ArrayList<Long>(composition.tracks.size + 1)

        composition.tracks.forEachIndexed { busIndex, track ->
            busVoices += track.voice
            trackIds += track.id

            // When each note actually stops sounding, which is not where it stops being
            // written. A plucked string rings on after the written length until that
            // same string is played again — that is the single biggest difference
            // between a guitar and a keyboard, and cutting every note off at its written
            // end is what makes tab playback sound clipped and mechanical.
            val ringUntil = ringOutMap(track, endTick, composition.bpm)

            for (note in track.notes) {
                if (note.endTick <= fromTick || note.startTick >= endTick) continue
                val startTick = maxOf(note.startTick, fromTick)
                val stopTick = ringUntil[note.id] ?: note.endTick
                addNote(events, note, track, busIndex, frameOf(startTick), frameOf(stopTick), sampleRate)
            }
        }

        // Chords are rendered through the same path as notes, so a progression plays
        // exactly as it exports.
        if (includeChords && composition.chordProgression.isNotEmpty()) {
            val busIndex = busVoices.size
            busVoices += InstrumentVoice.ACOUSTIC_STEEL
            trackIds += CHORD_BUS_TRACK_ID
            val board = composition.activeTrack?.fretboard ?: Fretboard(Tunings.STANDARD_E, 24)
            var key = CHORD_KEY_BASE
            for (event in composition.chordProgression) {
                if (event.endTick <= fromTick || event.startTick >= endTick) continue
                for (note in com.tabtap.io.ChordRenderer.render(event, board, key.toLong())) {
                    if (note.endTick <= fromTick) continue
                    val startTick = maxOf(note.startTick, fromTick)
                    val stopTick = minOf(note.endTick, endTick)
                    events += ScheduledEvent.NoteOn(
                        frame = frameOf(startTick),
                        busIndex = busIndex,
                        pitch = note.pitch,
                        velocity = note.velocity / 127f,
                        articulation = VoiceArticulation(pickPosition = 0.3f, brightness = 0.5f),
                        key = key,
                    )
                    events += ScheduledEvent.NoteOff(frameOf(stopTick), busIndex, key)
                    key++
                }
            }
        }

        // The click track shares the same frame clock as the notes, which is the whole
        // reason the metronome cannot drift against playback.
        if (metronome || countInBars > 0) {
            addClicks(events, ts, tempoMap, fromTick, endTick, countInBars, metronome, sampleRate, countInFrames)
        }

        val lastEventFrame = events.maxOfOrNull { it.frame } ?: 0L
        val musicEndFrame = frameOf(endTick)
        // Leave room for the longest plausible release so nothing is cut off.
        val tail = (sampleRate * 0.5).toLong()

        return Schedule(
            events = events.sortedWith(EVENT_ORDER),
            lengthFrames = maxOf(lastEventFrame, musicEndFrame) + tail,
            sampleRate = sampleRate,
            tempoMap = tempoMap,
            countInFrames = countInFrames,
            countInPulses = countInBars * ts.pulsesPerBar,
            busVoices = busVoices,
            trackIds = trackIds,
            fromTick = fromTick,
        )
    }

    /** Preview a single note or chord, for fretboard tapping. */
    fun previewEvents(
        pitches: List<Int>,
        voice: InstrumentVoice,
        sampleRate: Int,
        velocity: Float = 0.8f,
        durationSeconds: Float = 1.6f,
        keyBase: Int = PREVIEW_KEY_BASE,
    ): List<ScheduledEvent> {
        val out = ArrayList<ScheduledEvent>(pitches.size * 2)
        val frames = (durationSeconds * sampleRate).toLong()
        pitches.forEachIndexed { i, pitch ->
            val key = keyBase + i
            out += ScheduledEvent.NoteOn(
                frame = 0L, busIndex = PREVIEW_BUS, pitch = pitch, velocity = velocity,
                articulation = VoiceArticulation(brightness = 0.55f), key = key,
            )
            out += ScheduledEvent.NoteOff(frames, PREVIEW_BUS, key)
        }
        return out
    }

    /**
     * How long each note of [track] rings for.
     *
     * A note rings until the next note on the *same string* is played, or until a
     * generous ceiling, whichever comes first — because that is what physically happens:
     * lifting a finger does not silence a string, but fretting it again does. Notes that
     * are explicitly damped keep their written length, and notes marked to let ring are
     * given the full ceiling.
     *
     * The ceiling is in seconds rather than ticks so it does not change with the tempo,
     * which is right: a string's decay does not know what tempo you are playing at.
     */
    private fun ringOutMap(track: Track, endTick: Long, bpm: Double): Map<Long, Long> {
        val out = HashMap<Long, Long>(track.notes.size)
        val ceilingTicks = Ticks.fromSeconds(MAX_RING_SECONDS, bpm)

        // Group by string so each string's own sequence decides where its notes stop.
        val byString = track.notes.groupBy { it.string }
        for ((string, notes) in byString) {
            val ordered = notes.sortedBy { it.startTick }
            for ((index, note) in ordered.withIndex()) {
                // A damped note is as short as it is written; that is the point of damping.
                val damped = note.has(Articulation.STACCATO) ||
                    note.has(Articulation.DEAD_NOTE) ||
                    note.has(Articulation.PALM_MUTE)
                if (damped) {
                    out[note.id] = note.endTick
                    continue
                }
                // A note with no fretted position has no string to be stopped by.
                val nextOnThisString = if (string == null) {
                    null
                } else {
                    ordered.getOrNull(index + 1)?.startTick
                }
                // Deliberately not clamped to the end of the piece: that length depends
                // on the tempo, so clamping to it would make a string ring for less time
                // at a fast tempo than a slow one, which is not how a string behaves. The
                // schedule's length follows the last event, so a note ringing past the
                // final bar is covered.
                val ceiling = note.startTick + ceilingTicks
                val limit = listOfNotNull(nextOnThisString, ceiling).min()
                out[note.id] = maxOf(note.endTick, limit)
            }
        }
        return out
    }

    /** How long a plucked string is allowed to ring, in seconds. */
    private const val MAX_RING_SECONDS = 2.6

    private fun addNote(
        events: MutableList<ScheduledEvent>,
        note: MusicNote,
        track: Track,
        busIndex: Int,
        startFrame: Long,
        stopFrame: Long,
        sampleRate: Int,
    ) {
        val articulations = note.articulations
        val damping = when {
            Articulation.DEAD_NOTE in articulations -> 1f
            Articulation.PALM_MUTE in articulations -> 0.72f
            Articulation.STACCATO in articulations -> 0.25f
            else -> 0f
        }
        // Velocity drives both loudness and brightness, as picking harder does.
        val velocity = (note.velocity / 127f).let { v ->
            when {
                Articulation.ACCENT in articulations -> (v * 1.2f).coerceAtMost(1f)
                Articulation.GHOST_NOTE in articulations -> v * 0.5f
                else -> v
            }
        }
        val vibratoDepth = when {
            Articulation.WIDE_VIBRATO in articulations -> 0.55f
            Articulation.VIBRATO in articulations -> 0.22f
            else -> 0f
        }
        val articulation = VoiceArticulation(
            pickPosition = if (Articulation.TAP in articulations) 0.45f else 0.22f,
            damping = damping,
            brightness = (0.25f + 0.65f * velocity).coerceIn(0f, 1f),
            vibratoDepth = vibratoDepth,
            vibratoRate = if (Articulation.WIDE_VIBRATO in articulations) 4.5f else 5.8f,
            bendSemitones = if (Articulation.BEND in articulations ||
                Articulation.PRE_BEND in articulations
            ) note.bendSemitones else 0f,
            bendTimeSeconds = (note.durationTicks.toFloat() / Ticks.PPQ * 0.25f).coerceIn(0.04f, 0.4f),
            bendRelease = Articulation.BEND_RELEASE in articulations,
            harmonic = Articulation.HARMONIC in articulations ||
                Articulation.PINCH_HARMONIC in articulations,
            dead = Articulation.DEAD_NOTE in articulations,
            letRing = Articulation.LET_RING in articulations,
            // A hammer-on, pull-off or legato slide continues the ringing string rather
            // than re-plucking it, which is exactly what makes it sound legato.
            legato = Articulation.HAMMER_ON in articulations ||
                Articulation.PULL_OFF in articulations ||
                Articulation.SLIDE_LEGATO in articulations,
        )
        // The note's own id is the voice key, so two notes of the same pitch are
        // independent and releasing one never cuts the other short.
        val key = (note.id.toInt() and 0x00FFFFFF) or (busIndex shl 24)
        events += ScheduledEvent.NoteOn(startFrame, busIndex, note.pitch, velocity, articulation, key)
        // Let-ring notes are released well after their written end.
        val releaseFrame = if (articulation.letRing) {
            stopFrame + (sampleRate * 1.2).toLong()
        } else {
            stopFrame
        }
        events += ScheduledEvent.NoteOff(releaseFrame, busIndex, key)
    }

    private fun addClicks(
        events: MutableList<ScheduledEvent>,
        ts: TimeSignature,
        tempoMap: TempoMap,
        fromTick: Long,
        endTick: Long,
        countInBars: Int,
        metronomeDuringPlayback: Boolean,
        sampleRate: Int,
        countInFrames: Long,
    ) {
        // Count-in clicks, before the music, at the tempo in force where playback starts.
        if (countInBars > 0) {
            val bpm = tempoMap.bpmAt(fromTick)
            val pulses = countInBars * ts.pulsesPerBar
            for (p in 0 until pulses) {
                val seconds = Ticks.toSeconds(p.toLong() * ts.pulseTicks, bpm)
                events += ScheduledEvent.Click(
                    frame = (seconds * sampleRate).roundToLong(),
                    accented = p % ts.pulsesPerBar == 0,
                )
            }
        }
        if (!metronomeDuringPlayback) return

        // Clicks through the music, aligned to the bar grid so an accent always lands on
        // a downbeat even when playback starts mid-bar.
        val pulse = ts.pulseTicks.toLong()
        var tick = (fromTick / pulse) * pulse
        if (tick < fromTick) tick += pulse
        while (tick <= endTick) {
            val seconds = tempoMap.secondsAt(tick) - tempoMap.secondsAt(fromTick)
            events += ScheduledEvent.Click(
                frame = countInFrames + (seconds * sampleRate).roundToLong(),
                accented = tick % ts.barTicks.toLong() == 0L,
            )
            tick += pulse
        }
    }

    /** Note-offs are processed before note-ons at the same frame, as in MIDI export. */
    private val EVENT_ORDER: Comparator<ScheduledEvent> =
        compareBy<ScheduledEvent> { it.frame }.thenBy {
            when (it) {
                is ScheduledEvent.NoteOff -> 0
                is ScheduledEvent.Click -> 1
                is ScheduledEvent.NoteOn -> 2
            }
        }

    /** Bus index reserved for fretboard previews, which play outside the timeline. */
    const val PREVIEW_BUS = -1

    /** Voice keys for previews and chord rendering, kept clear of note ids. */
    const val PREVIEW_KEY_BASE = 0x40000000
    const val CHORD_KEY_BASE = 0x20000000

    /** Pseudo track id used for the chord progression's own bus. */
    const val CHORD_BUS_TRACK_ID = -2L
}
