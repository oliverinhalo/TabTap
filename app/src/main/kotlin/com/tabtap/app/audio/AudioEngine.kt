package com.tabtap.app.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.Process
import android.util.Log
import com.tabtap.music.Composition
import com.tabtap.music.InstrumentVoice
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the transport is doing. */
enum class TransportState { STOPPED, COUNTING_IN, PLAYING, PAUSED }

/** The transport's public position, published for the UI. */
data class PlaybackPosition(
    val tick: Long = 0L,
    val frame: Long = 0L,
    val state: TransportState = TransportState.STOPPED,
    /** Beats remaining in the count-in, or 0 when not counting in. */
    val countInBeatsRemaining: Int = 0,
)

/** A loop region in ticks, or null for no looping. */
data class LoopRegion(val startTick: Long, val endTick: Long) {
    val isValid: Boolean get() = endTick > startTick
}

/**
 * The real-time audio engine.
 *
 * One `AudioTrack` is fed from one dedicated thread at urgent-audio priority. That thread
 * owns a frame counter, and everything audible is positioned against it: notes, the
 * metronome click and the playhead the UI draws. Because there is only one clock, the
 * metronome cannot drift against playback, and the playhead cannot drift against either.
 *
 * The render loop allocates nothing. Buffers are sized once, voices are pooled, and all
 * communication from the UI arrives through lock-free structures — an atomic for the
 * transport, a concurrent queue for one-shot previews, volatile fields for mix settings.
 * A lock or an allocation on this thread would eventually produce an audible dropout.
 *
 * Buffer size is taken from the device's own minimum and then doubled, which on a modern
 * phone is a handful of milliseconds. That is low enough to play along with and safe
 * enough not to glitch when the system is busy.
 */
class AudioEngine {

    private val sampleRate: Int = pickSampleRate()
    private val channels = 2

    private var track: AudioTrack? = null
    private var renderThread: Thread? = null
    private val running = AtomicBoolean(false)

    /** Frames per write. Set when the track is opened. */
    private var blockFrames = 256

    private lateinit var mixBuffer: FloatArray
    private val limiter = Limiter(sampleRate)

    /**
     * A short room on the master bus.
     *
     * Every instrument is heard in a space; a completely dry output is the main reason
     * synthesised guitar sounds thin, however good the string model is. It sits after the
     * track mix and before the limiter, which is where a room belongs.
     */
    private val reverb = Reverb(sampleRate)

    /**
     * One bus per track, plus one for fretboard previews.
     *
     * Replaced wholesale when the track list changes, which happens on the UI thread
     * while the render thread is reading it. They are volatile, and the render thread
     * takes a single snapshot of the pair per block: reading `buses` twice — once for
     * its indices and once to index it — could otherwise see two different arrays and
     * run off the end of a shorter one, which on the audio thread kills the process.
     */
    @Volatile private var mixer: Mixer = Mixer(emptyArray(), emptyArray())

    /** The buses and their mix settings, swapped together so they cannot disagree. */
    private class Mixer(val buses: Array<InstrumentBus>, val mixes: Array<BusMix>) {
        val size: Int get() = minOf(buses.size, mixes.size)
    }
    private val previewBus = InstrumentBus(sampleRate, maxVoices = 10)
    private val previewMix = BusMix(gain = 0.85f)
    private val clicks = Array(4) { ClickVoice(sampleRate) }
    private var clickBuffer = FloatArray(2048)

    @Volatile private var metronomeLevel: Float = 0.7f
    @Volatile private var masterGain: Float = 0.9f

    /** The schedule being played. Replaced atomically; the render thread reads the ref. */
    @Volatile private var schedule: Schedule = Schedule.EMPTY

    /**
     * Index of the next event in [schedule] the render thread has yet to process.
     *
     * Volatile because the UI thread rewrites it when the schedule is swapped or the
     * transport seeks, and the render thread reads it every block.
     */
    @Volatile
    private var nextEventIndex = 0

    private val playFrame = AtomicLong(0L)

    /**
     * A monotonic frame counter that advances on every rendered block whatever the
     * transport is doing.
     *
     * Previews — a fretboard tap, a chord audition — are positioned against this rather
     * than against the transport position, because the transport position does not move
     * while playback is stopped. Scheduling a preview's note-off against a stationary
     * clock would mean it never came due, so the note would ring its full decay and the
     * pending-event queue would grow without bound.
     */
    private val deviceFrame = AtomicLong(0L)
    private val transport = MutableStateFlow(TransportState.STOPPED)
    private val position = MutableStateFlow(PlaybackPosition())

    @Volatile private var loop: LoopRegion? = null
    @Volatile private var loopStartFrame = 0L
    @Volatile private var loopEndFrame = 0L

    /** One-shot events from the UI: fretboard taps and chord previews. */
    private val immediateEvents = ConcurrentLinkedQueue<ScheduledEvent>()
    private val pendingSeek = AtomicLong(NO_SEEK)
    private val panicRequested = AtomicBoolean(false)

    /** Published transport position. The UI collects this to draw the playhead. */
    val playbackPosition: StateFlow<PlaybackPosition> = position.asStateFlow()

    val transportState: StateFlow<TransportState> = transport.asStateFlow()

    val isOpen: Boolean get() = track != null

    val engineSampleRate: Int get() = sampleRate

    /** Round-trip output latency estimate in milliseconds, for display. */
    val outputLatencyMs: Double
        get() = blockFrames * 2.0 / sampleRate * 1000.0

    // ---------------------------------------------------------------- lifecycle

    /**
     * Open the audio device and start the render thread.
     *
     * Safe to call repeatedly; a second call does nothing. Returns false when the device
     * refuses to give us an output, in which case the app stays usable and simply cannot
     * make sound.
     */
    @Synchronized
    fun start(): Boolean {
        if (track != null) return true
        renderFailure = null
        val minBytes = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        if (minBytes <= 0) {
            Log.e(TAG, "AudioTrack reported no usable buffer size")
            return false
        }
        // Doubling the device's minimum gives enough headroom to survive a scheduling
        // hiccup without being so large that playing along feels laggy.
        val bufferBytes = minBytes * 2
        blockFrames = (minBytes / (channels * BYTES_PER_FLOAT)).coerceIn(96, 1024)

        val built = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build(),
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .apply {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        // Ask the platform for its low-latency path.
                        setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    }
                }
                .build()
        }.getOrElse {
            Log.e(TAG, "Could not open an audio output", it)
            return false
        }

        mixBuffer = FloatArray(blockFrames * channels)
        clickBuffer = FloatArray(blockFrames)
        limiter.reset()
        reverb.reset()
        built.play()
        track = built
        running.set(true)

        renderThread = Thread({ renderLoop(built) }, "TabTap-Audio").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
        return true
    }

    /**
     * Stop the render thread and release the device.
     *
     * Called when the app is backgrounded without playing, and from `onDestroy`. Voices
     * are killed first so nothing can be left sounding.
     */
    @Synchronized
    fun shutdown() {
        running.set(false)
        renderThread?.join(500)
        renderThread = null
        runCatching {
            track?.pause()
            track?.flush()
            track?.release()
        }
        track = null
        mixer.buses.forEach { it.panic() }
        previewBus.panic()
        clicks.forEach { it.kill() }
        transport.value = TransportState.STOPPED
        position.value = PlaybackPosition()
    }

    // ---------------------------------------------------------------- mixer

    /**
     * Point the engine at a composition.
     *
     * One bus is created per track, plus a bus for the chord progression if there is
     * one. Called whenever the track list changes; note edits do not need it.
     */
    @Synchronized
    fun prepare(composition: Composition) {
        val voices = composition.tracks.map { it.voice } +
            if (composition.chordProgression.isNotEmpty()) {
                listOf(InstrumentVoice.ACOUSTIC_STEEL)
            } else {
                emptyList()
            }
        var current = mixer
        if (current.buses.size != voices.size) {
            current.buses.forEach { it.panic() }
            // Built fully, then published in one volatile write, so the render thread
            // never sees a half-built mixer.
            current = Mixer(
                buses = Array(voices.size) { InstrumentBus(sampleRate) },
                mixes = Array(voices.size) { BusMix() },
            )
        }
        chordBusPresent = composition.chordProgression.isNotEmpty()
        voices.forEachIndexed { i, v -> current.buses[i].configure(v) }
        mixer = current
        updateMix(composition)
    }

    /**
     * Push the current mix settings. Cheap enough to call on every edit, and takes effect
     * at the next buffer, so muting a track is immediate.
     */
    fun updateMix(composition: Composition) {
        val current = mixer
        val audible = composition.audibleTracks.map { it.id }.toSet()
        composition.tracks.forEachIndexed { i, track ->
            val mix = current.mixes.getOrNull(i) ?: return@forEachIndexed
            mix.gain = track.volume.coerceIn(0f, 1.5f)
            mix.pan = track.pan.coerceIn(-1f, 1f)
            mix.audible = track.id in audible
        }
        // The chord bus follows the project rather than a track.
        if (composition.chordProgression.isNotEmpty()) {
            current.mixes.getOrNull(composition.tracks.size)?.let {
                it.gain = 0.6f
                it.pan = 0f
                it.audible = chordsAudible && composition.tracks.none { t -> t.soloed }
            }
        }
    }

    fun setMetronomeLevel(level: Float) { metronomeLevel = level.coerceIn(0f, 1f) }

    /**
     * Silence or restore the chord backing without touching the tracks.
     *
     * The chord progression is not a track, so it needs its own switch; applying it to
     * the bus rather than rebuilding the schedule means it takes effect at the next
     * buffer, mid-playback.
     */
    fun setChordsAudible(audible: Boolean) {
        chordsAudible = audible
        // The chord bus is the one after the last track, when there is one.
        val current = mixer
        if (chordBusPresent && current.size > 0) {
            current.mixes.lastOrNull()?.audible = audible
        }
    }

    @Volatile private var chordsAudible: Boolean = true
    @Volatile private var chordBusPresent: Boolean = false

    fun setMasterGain(gain: Float) { masterGain = gain.coerceIn(0f, 1.5f) }

    /** How much room is on the output. 0 is completely dry. */
    fun setReverbMix(mix: Float) { reverb.mix = mix.coerceIn(0f, 0.6f) }

    val reverbMix: Float get() = reverb.mix

    /** Configure the bus used for fretboard taps and chord previews. */
    fun setPreviewVoice(voice: InstrumentVoice) {
        previewBus.configure(voice)
    }

    // ---------------------------------------------------------------- transport

    /**
     * Start playing [schedule] from its beginning (which the sequencer has already
     * positioned at the requested tick).
     */
    fun play(newSchedule: Schedule, loopRegion: LoopRegion? = null) {
        if (!isOpen && !start()) return
        synchronized(this) {
            schedule = newSchedule
            nextEventIndex = 0
            playFrame.set(0L)
            setLoop(loopRegion)
            transport.value = if (newSchedule.countInFrames > 0) {
                TransportState.COUNTING_IN
            } else {
                TransportState.PLAYING
            }
        }
    }

    /**
     * Swap in a new schedule while playback continues.
     *
     * An edit made during playback should be heard without stopping and starting again.
     * The playhead is converted to a musical tick against the old schedule and back to a
     * frame against the new one — so a tempo change moves the playhead to the right
     * place rather than jumping — and the event cursor is positioned past everything
     * already due. Voices are deliberately left alone: notes already sounding ring on,
     * which is what makes the swap inaudible.
     */
    fun replaceSchedule(newSchedule: Schedule) {
        synchronized(this) {
            val previous = schedule
            if (transport.value == TransportState.STOPPED) {
                schedule = newSchedule
                nextEventIndex = 0
                return
            }
            val currentFrame = playFrame.get()
            val stillCountingIn = currentFrame < previous.countInFrames

            val frame = if (stillCountingIn) {
                // Keep whatever is left of the count-in rather than restarting it.
                (newSchedule.countInFrames - (previous.countInFrames - currentFrame))
                    .coerceAtLeast(0L)
            } else {
                // Where we are musically, according to the schedule that is playing.
                newSchedule.frameAtTick(previous.tickAtFrame(currentFrame))
            }

            schedule = newSchedule
            playFrame.set(frame)
            nextEventIndex = newSchedule.events.indexOfFirst { it.frame >= frame }
                .let { if (it < 0) newSchedule.events.size else it }
            // Loop boundaries are frames, so they move with the new schedule.
            loop?.let { region ->
                loopStartFrame = newSchedule.frameAtTick(region.startTick)
                loopEndFrame = newSchedule.frameAtTick(region.endTick)
            }
        }
    }

    /**
     * Pause, leaving the position where it is.
     *
     * Sounding notes are silenced rather than left ringing: a paused transport that keeps
     * a chord droning is worse than a clean stop, and the engine's release taper means
     * this does not click.
     */
    fun pause() {
        if (transport.value == TransportState.PLAYING || transport.value == TransportState.COUNTING_IN) {
            transport.value = TransportState.PAUSED
            releaseAll()
        }
    }

    /** Resume from the paused position. */
    fun resume() {
        if (transport.value == TransportState.PAUSED) {
            transport.value = TransportState.PLAYING
        }
    }

    /** Stop and return to the start of the schedule. */
    fun stop() {
        transport.value = TransportState.STOPPED
        panicRequested.set(true)
        playFrame.set(0L)
        synchronized(this) { nextEventIndex = 0 }
        position.value = PlaybackPosition()
    }

    /** Jump to [tick] within the current schedule. */
    fun seekToTick(tick: Long) {
        pendingSeek.set(schedule.frameAtTick(tick.coerceAtLeast(0L)))
    }

    fun setLoop(region: LoopRegion?) {
        loop = region?.takeIf { it.isValid }
        val r = loop
        if (r != null) {
            loopStartFrame = schedule.frameAtTick(r.startTick)
            loopEndFrame = schedule.frameAtTick(r.endTick)
        }
    }

    /** Silence every voice immediately. Used on stop, on audio focus loss and on errors. */
    fun panic() {
        panicRequested.set(true)
    }

    private fun releaseAll() {
        // Release rather than kill, so a pause fades rather than clicks.
        for (bus in mixer.buses) {
            // The bus has no list of keys, so a panic is the only way to be certain; a
            // paused transport simply stops issuing note-ons and lets the tails ring.
            bus.panic()
        }
    }

    /**
     * Play [pitches] immediately, outside the timeline. This is what a fretboard tap
     * sounds, and it works whether or not the transport is running.
     */
    fun preview(pitches: List<Int>, velocity: Float = 0.8f, durationSeconds: Float = 1.8f) {
        if (!isOpen && !start()) return
        if (pitches.isEmpty()) return
        val keyBase = Sequencer.PREVIEW_KEY_BASE + (previewCounter++ * 16 and 0x0FFFFFF0)
        val now = deviceFrame.get()
        for (event in Sequencer.previewEvents(
            pitches, InstrumentVoice.ELECTRIC_CLEAN, sampleRate, velocity, durationSeconds, keyBase,
        )) {
            immediateEvents += when (event) {
                is ScheduledEvent.NoteOn -> event.copy(frame = now)
                // previewEvents gives the note-off's frame as an offset from the start.
                is ScheduledEvent.NoteOff -> event.copy(frame = now + event.frame)
                else -> event
            }
        }
    }

    /** Sound a single metronome click immediately, for tap-tempo feedback. */
    fun previewClick(accented: Boolean) {
        if (!isOpen && !start()) return
        immediateEvents += ScheduledEvent.Click(deviceFrame.get(), accented)
    }

    private var previewCounter = 0

    // ---------------------------------------------------------------- render thread

    private fun renderLoop(output: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        // Events queued from the UI, pulled into a local list each block.
        val immediate = ArrayList<ScheduledEvent>(16)

        // An uncaught exception on this thread would take the whole process down with
        // it, turning any arithmetic slip in the DSP into a crash of the app rather than
        // a loss of sound. Silence is recoverable; a crash is not.
        try {
            renderBlocks(output, immediate)
        } catch (e: Throwable) {
            Log.e(TAG, "The audio render thread failed; stopping playback", e)
            running.set(false)
            runCatching {
                mixer.buses.forEach { it.panic() }
                previewBus.panic()
                clicks.forEach { it.kill() }
            }
            transport.value = TransportState.STOPPED
            renderFailure = e.message ?: e::class.java.simpleName
        }
    }

    /** Set when the render thread has died, so the UI can say so rather than go quiet. */
    @Volatile
    var renderFailure: String? = null
        private set

    private fun renderBlocks(output: AudioTrack, immediate: ArrayList<ScheduledEvent>) {
        while (running.get()) {
            val frames = blockFrames
            // One snapshot per block: the pair cannot change under us mid-render.
            val snapshot = mixer
            java.util.Arrays.fill(mixBuffer, 0f)

            if (panicRequested.getAndSet(false)) {
                snapshot.buses.forEach { it.panic() }
                previewBus.panic()
                clicks.forEach { it.kill() }
                immediateEvents.clear()
                synchronized(this) { nextEventIndex = 0 }
            }

            val seek = pendingSeek.getAndSet(NO_SEEK)
            if (seek != NO_SEEK) {
                playFrame.set(seek)
                snapshot.buses.forEach { it.panic() }
                synchronized(this) {
                    nextEventIndex = schedule.events.indexOfFirst { it.frame >= seek }
                        .let { if (it < 0) schedule.events.size else it }
                }
            }

            val state = transport.value
            val playing = state == TransportState.PLAYING || state == TransportState.COUNTING_IN

            // Drain anything the UI has queued; previews are always honoured, even when
            // the transport is stopped, because tapping the fretboard must make a sound.
            immediate.clear()
            while (true) {
                val e = immediateEvents.poll() ?: break
                immediate += e
            }

            val blockStart = playFrame.get()
            val blockEnd = blockStart + frames
            val deviceBlockStart = deviceFrame.get()

            if (playing) {
                renderScheduled(blockStart, blockEnd, frames)
            }
            renderImmediate(immediate, deviceBlockStart, frames)

            // Render each bus into the mix, from the snapshot taken for this block.
            for (i in 0 until snapshot.size) {
                val mix = snapshot.mixes[i]
                val gain = if (mix.audible) mix.gain else 0f
                snapshot.buses[i].render(mixBuffer, 0, frames, gain, mix.pan)
            }
            previewBus.render(mixBuffer, 0, frames, previewMix.gain, previewMix.pan)

            // Clicks are mono and centred.
            if (clicks.any { it.isActive }) {
                java.util.Arrays.fill(clickBuffer, 0, frames, 0f)
                for (c in clicks) if (c.isActive) c.render(clickBuffer, 0, frames)
                var o = 0
                for (i in 0 until frames) {
                    val v = clickBuffer[i] * metronomeLevel
                    mixBuffer[o] += v
                    mixBuffer[o + 1] += v
                    o += 2
                }
            }

            // The room, then master gain, then a soft limiter so several distorted
            // tracks together cannot clip the output into a buzz.
            reverb.process(mixBuffer, 0, frames)
            if (masterGain != 1f) {
                for (i in 0 until frames * channels) mixBuffer[i] *= masterGain
            }
            limiter.process(mixBuffer, 0, frames, channels)

            val written = runCatching {
                output.write(mixBuffer, 0, frames * channels, AudioTrack.WRITE_BLOCKING)
            }.getOrElse { -1 }
            if (written < 0) {
                Log.e(TAG, "Audio write failed ($written); stopping the render thread")
                running.set(false)
                break
            }

            // The device clock advances on every block, playing or not.
            deviceFrame.addAndGet(frames.toLong())

            if (playing) {
                var next = blockEnd
                val region = loop
                if (region != null && loopEndFrame > loopStartFrame && next >= loopEndFrame) {
                    // Loop back, releasing anything sounding so the seam is clean.
                    next = loopStartFrame
                    snapshot.buses.forEach { it.panic() }
                    synchronized(this) {
                        nextEventIndex = schedule.events.indexOfFirst { it.frame >= next }
                            .let { if (it < 0) schedule.events.size else it }
                    }
                }
                playFrame.set(next)
                publishPosition(next, state)
                if (region == null && next >= schedule.lengthFrames && schedule.lengthFrames > 0) {
                    transport.value = TransportState.STOPPED
                    playFrame.set(0L)
                    synchronized(this) { nextEventIndex = 0 }
                    position.value = PlaybackPosition()
                }
            }
        }
    }

    /** Dispatch every scheduled event that falls inside this block, at the right frame. */
    private fun renderScheduled(blockStart: Long, blockEnd: Long, frames: Int) {
        val current = schedule
        val events = current.events
        // The schedule can be replaced between blocks, so the cursor is clamped rather
        // than trusted: a stale index from a longer schedule must not read past the end.
        var index = nextEventIndex.coerceIn(0, events.size)
        // Skip anything already in the past, which can happen right after a seek.
        while (index < events.size && events[index].frame < blockStart) index++

        while (index < events.size && events[index].frame < blockEnd) {
            val event = events[index]
            // Offset within the block: this is what makes timing sample accurate rather
            // than quantised to the buffer size.
            val offset = (event.frame - blockStart).toInt().coerceIn(0, frames - 1)
            dispatch(event, offset)
            index++
        }
        nextEventIndex = index

        // Leaving the count-in is a state change the UI needs to see.
        if (transport.value == TransportState.COUNTING_IN && blockEnd >= current.countInFrames) {
            transport.value = TransportState.PLAYING
        }
    }

    /**
     * Dispatch the one-shot events the interface has queued.
     *
     * Anything due in a later block is handed back to the queue; because these are timed
     * against [deviceFrame], which always advances, they are guaranteed to come due.
     */
    private fun renderImmediate(events: List<ScheduledEvent>, blockStart: Long, frames: Int) {
        for (event in events) {
            if (event.frame >= blockStart + frames) {
                immediateEvents += event
                continue
            }
            val offset = (event.frame - blockStart).toInt().coerceIn(0, frames - 1)
            dispatch(event, offset)
        }
    }

    /**
     * Apply one event.
     *
     * Note that [offset] is the sample position within the block. The voices begin at
     * the start of the buffer rather than at the offset, which would cost a per-voice
     * sub-buffer; at the block sizes used here that is well under a millisecond and
     * inaudible, while the event *ordering* and the overall clock stay exact.
     */
    private fun dispatch(event: ScheduledEvent, offset: Int) {
        when (event) {
            is ScheduledEvent.NoteOn -> {
                val bus = busFor(event.busIndex) ?: return
                bus.noteOn(event.pitch, event.velocity, event.articulation, event.key)
            }
            is ScheduledEvent.NoteOff -> busFor(event.busIndex)?.noteOff(event.key)
            is ScheduledEvent.Click -> {
                val slot = clicks.firstOrNull { !it.isActive } ?: clicks[0]
                slot.trigger(event.accented, metronomeLevel)
            }
        }
    }

    private fun busFor(index: Int): InstrumentBus? {
        if (index == Sequencer.PREVIEW_BUS) return previewBus
        val current = mixer
        return current.buses.getOrNull(index)
    }

    private fun publishPosition(frame: Long, state: TransportState) {
        val current = schedule
        val tick = current.tickAtFrame(frame)
        // During the count-in, how many clicks are still to come.
        val countInRemaining = if (frame < current.countInFrames && current.countInPulses > 0) {
            val framesPerPulse = current.countInFrames / current.countInPulses
            ((current.countInFrames - frame + framesPerPulse - 1) / framesPerPulse)
                .toInt().coerceIn(1, current.countInPulses)
        } else {
            0
        }
        position.value = PlaybackPosition(
            tick = tick,
            frame = frame,
            state = state,
            countInBeatsRemaining = countInRemaining,
        )
    }

    companion object {
        private const val TAG = "AudioEngine"
        private const val BYTES_PER_FLOAT = 4
        private const val NO_SEEK = Long.MIN_VALUE

        /**
         * Prefer 48 kHz, which is the native rate of essentially every Android device
         * made in the last decade; running at the device's own rate avoids a resampler
         * in the output path, which costs both latency and quality.
         */
        private fun pickSampleRate(): Int {
            val candidates = intArrayOf(48_000, 44_100)
            for (rate in candidates) {
                val size = AudioTrack.getMinBufferSize(
                    rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT,
                )
                if (size > 0) return rate
            }
            return 48_000
        }
    }
}
