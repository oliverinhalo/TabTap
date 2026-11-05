package com.tabtap.app.audio

import com.tabtap.music.Pitch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * How a note should be excited and damped. Derived from the note's articulations, so the
 * audio engine never has to know about musical notation.
 */
data class VoiceArticulation(
    /** 0 = near the bridge (thin and bright), 1 = near the neck (round). */
    val pickPosition: Float = 0.22f,
    /** Extra damping, for palm mutes and dead notes. 0 = open, 1 = fully choked. */
    val damping: Float = 0f,
    /** Brightness of the attack, driven by velocity and pick type. */
    val brightness: Float = 0.5f,
    /** Vibrato depth in semitones. */
    val vibratoDepth: Float = 0f,
    /** Vibrato rate in Hz. */
    val vibratoRate: Float = 5.5f,
    /** Bend height in semitones, applied over [bendTimeSeconds]. */
    val bendSemitones: Float = 0f,
    val bendTimeSeconds: Float = 0.12f,
    /** Release the bend back to pitch after reaching the top. */
    val bendRelease: Boolean = false,
    /** Sound only the odd harmonics of a node, as a natural harmonic does. */
    val harmonic: Boolean = false,
    /** Strike with no pitch, as a dead note. */
    val dead: Boolean = false,
    /** Let the note ring past its written length. */
    val letRing: Boolean = false,
    /** Legato: continue the previous note on this string instead of re-exciting it. */
    val legato: Boolean = false,
) {
    companion object {
        val DEFAULT = VoiceArticulation()
    }
}

/** One voice in the polyphony pool. */
interface Voice {
    val isActive: Boolean

    /** True when the voice has been asked to stop but is still ringing out. */
    val isReleasing: Boolean

    /** Add [count] samples to [out] starting at [from]. The buffer is mono. */
    fun render(out: FloatArray, from: Int, count: Int)

    /** Stop the note, letting it decay naturally. */
    fun release()

    /** Stop immediately, for a panic or a transport stop. */
    fun kill()
}

/**
 * A plucked-string voice using an extended Karplus–Strong digital waveguide.
 *
 * The model is a delay line the length of one period of the note, fed back through a
 * loss filter. That is the physical picture of a wave travelling up and down a string
 * and losing a little energy at each reflection, and it is why the result sounds like a
 * plucked string rather than like a synthesiser: the harmonics decay at different rates,
 * the attack has the right transient, and the pluck position shapes the spectrum through
 * a comb filter exactly as it does on a real instrument.
 *
 * Three refinements make it usable musically rather than just demonstrably:
 *
 *  * A first-order all-pass gives a fractional-sample delay, so notes are tuned exactly
 *    rather than quantised to whatever period divides the sample rate. Without it the
 *    top of the fretboard is audibly out of tune.
 *  * The loop filter's cutoff scales with frequency, so high notes decay faster than low
 *    ones, as they do on a real string.
 *  * The delay length is modulated for vibrato and bends, which is physically what
 *    happens when a string is stretched.
 */
class StringVoice(private val sampleRate: Int) : Voice {

    private var buffer = FloatArray(1 shl 12)
    private var mask = buffer.size - 1
    private var writeIndex = 0

    private val loopFilter = OnePole()
    private val fractionalDelay = AllPass()
    private val dcBlocker = Biquad().apply { highPass(sampleRate, 25f, 0.707f) }
    private val noise = Noise()

    private var periodSamples = 100f
    private var basePeriodSamples = 100f
    private var integerDelay = 100
    private var loopGain = 0.999f
    private var amplitude = 0f

    private var active = false
    private var releasing = false
    private var releaseGain = 1f
    private var releaseCoefficient = 1f

    private var phase = 0f
    private var vibratoDepth = 0f
    private var vibratoRate = 5.5f
    private var bendSemitones = 0f
    private var bendTimeSamples = 1f
    private var bendRelease = false
    private var samplesSinceStart = 0L
    private var dead = false

    /** Set while the voice is sounding, so the engine can steal or re-trigger it. */
    var noteKey: Int = -1
        private set

    override val isActive: Boolean get() = active
    override val isReleasing: Boolean get() = releasing

    /** Peak absolute amplitude of the last rendered block, for voice stealing. */
    var lastLevel: Float = 0f
        private set

    /**
     * Start a note.
     *
     * @param frequency fundamental in Hz.
     * @param velocity 0..1.
     * @param articulation how the note is played.
     * @param key an identifier the engine uses to find this voice again.
     */
    fun noteOn(
        frequency: Float,
        velocity: Float,
        articulation: VoiceArticulation,
        key: Int,
    ) {
        noteKey = key
        val f = frequency.coerceIn(20f, sampleRate / 3f)
        basePeriodSamples = sampleRate / f
        periodSamples = basePeriodSamples
        dead = articulation.dead

        ensureCapacity(basePeriodSamples.toInt() + 4)

        // Decay: low strings ring far longer than high ones, and the loop filter's
        // cutoff tracks the fundamental so the harmonic decay is frequency dependent.
        // This is set *before* the delay line is sized, because the filter's own delay
        // is part of the loop and has to be subtracted from it.
        val brightness = articulation.brightness.coerceIn(0f, 1f)
        val cutoff = (f * (3f + 9f * brightness)).coerceIn(400f, sampleRate * 0.45f)
        loopFilter.setCutoff(sampleRate, cutoff)
        setPeriod(periodSamples)

        // Energy loss per round trip. A dead note loses almost everything immediately.
        val damping = articulation.damping.coerceIn(0f, 1f)
        loopGain = when {
            articulation.dead -> 0.80f
            else -> {
                // Target a decay time, converted to a per-period loss.
                val decaySeconds = (5.0f - 3.2f * damping) * (1f - 0.35f * (f / 400f).coerceAtMost(1f))
                val periods = (decaySeconds * f).coerceAtLeast(1f)
                exp(-6.9078f / periods)   // -60 dB over the decay time
            }
        }.coerceIn(0.5f, 0.99995f)

        amplitude = velocity.coerceIn(0.02f, 1f)
        vibratoDepth = articulation.vibratoDepth
        vibratoRate = articulation.vibratoRate
        bendSemitones = articulation.bendSemitones
        bendTimeSamples = max(1f, articulation.bendTimeSeconds * sampleRate)
        bendRelease = articulation.bendRelease
        samplesSinceStart = 0L
        phase = 0f
        releasing = false
        releaseGain = 1f
        active = true

        if (!articulation.legato) {
            excite(articulation, velocity)
        }
    }

    /**
     * Re-pitch a sounding voice without re-exciting it, which is what a hammer-on,
     * pull-off or slide does: the string keeps ringing and only its length changes.
     */
    fun retune(frequency: Float) {
        val f = frequency.coerceIn(20f, sampleRate / 3f)
        basePeriodSamples = sampleRate / f
        ensureCapacity(basePeriodSamples.toInt() + 4)
    }

    /**
     * Fill the delay line with a shaped noise burst.
     *
     * The burst is low-pass filtered by velocity — a soft pluck excites fewer high
     * harmonics — and then comb filtered at the pick position, which is the physical
     * reason a bridge pickup sounds thin and a neck pluck sounds round.
     */
    private fun excite(articulation: VoiceArticulation, velocity: Float) {
        val length = integerDelay + 1
        noise.reset(0x9E3779B97F4A7C15uL.toLong() * (noteKey + 1))
        loopFilter.reset()
        fractionalDelay.reset()
        dcBlocker.reset()

        val attackFilter = OnePole()
        attackFilter.setCutoff(sampleRate, 600f + 7000f * velocity.coerceIn(0f, 1f))

        // Build the raw burst.
        val scratch = FloatArray(length)
        if (articulation.dead) {
            // A dead note is a percussive click with no sustained pitch.
            for (i in 0 until length) {
                scratch[i] = attackFilter.process(noise.next()) * exp(-6f * i / length)
            }
        } else {
            for (i in 0 until length) scratch[i] = attackFilter.process(noise.next())
        }

        // Pluck position comb: subtract a delayed copy.
        val pickOffset = (articulation.pickPosition.coerceIn(0.04f, 0.5f) * length).toInt()
            .coerceIn(1, length - 1)
        if (!articulation.dead) {
            for (i in length - 1 downTo pickOffset) {
                scratch[i] -= scratch[i - pickOffset] * 0.86f
            }
        }

        // A natural harmonic is produced by damping the fundamental at a node, which
        // cancels the even partials; zeroing half the delay line does the same thing.
        if (articulation.harmonic) {
            val half = length / 2
            for (i in 0 until half) {
                scratch[i] = (scratch[i] - scratch[i + half]) * 0.5f
                scratch[i + half] = -scratch[i]
            }
        }

        // Normalise so velocity, not the random burst, decides the level.
        var peak = 1e-6f
        for (v in scratch) peak = max(peak, abs(v))
        val scale = amplitude / peak

        java.util.Arrays.fill(buffer, 0f)
        writeIndex = 0
        for (i in 0 until length) {
            buffer[i and mask] = scratch[i] * scale
        }
        writeIndex = length and mask
    }

    private fun ensureCapacity(samples: Int) {
        var size = buffer.size
        while (size < samples * 2) size = size shl 1
        if (size != buffer.size) {
            // Growing happens only when a voice is first given a very low note; it never
            // happens in steady state, so it cannot cause a dropout mid-phrase.
            buffer = FloatArray(size)
            mask = size - 1
            writeIndex = 0
        }
    }

    /**
     * Size the delay line for a period of [period] samples.
     *
     * The sounding period is the delay line *plus* everything else in the loop, and the
     * loop filter's own phase delay at the fundamental is several samples at the cutoffs
     * a plucked string uses — around twelve for a low E. Subtracting it is what makes the
     * note land on the right pitch instead of tens of cents flat.
     */
    private fun setPeriod(period: Float) {
        val omega = 2.0 * PI / period
        val filterDelay = loopFilter.phaseDelaySamples(omega)
        val total = (period - filterDelay).toFloat().coerceAtLeast(2f)
        integerDelay = total.toInt()
        fractionalDelay.setFraction(total - integerDelay)
    }

    override fun render(out: FloatArray, from: Int, count: Int) {
        if (!active) { lastLevel = 0f; return }
        var peak = 0f
        val vibratoIncrement = (2.0 * PI * vibratoRate / sampleRate).toFloat()

        for (i in 0 until count) {
            // Pitch modulation: bend first, then vibrato on top of it.
            var semitones = 0f
            if (bendSemitones != 0f) {
                val t = samplesSinceStart / bendTimeSamples
                semitones += if (!bendRelease) {
                    bendSemitones * min(1f, t)
                } else {
                    // Rise to the bend then come back down over the same time again.
                    if (t <= 1f) bendSemitones * t else bendSemitones * max(0f, 2f - t)
                }
            }
            if (vibratoDepth != 0f) {
                semitones += vibratoDepth * sin(phase)
                phase += vibratoIncrement
                if (phase > 2f * PI.toFloat()) phase -= 2f * PI.toFloat()
            }
            if (semitones != 0f) {
                setPeriod(basePeriodSamples * 2f.pow(-semitones / 12f))
            } else if (periodSamples != basePeriodSamples) {
                periodSamples = basePeriodSamples
                setPeriod(periodSamples)
            }

            // Read the delayed sample, filter it, and write it back: one round trip.
            val readIndex = (writeIndex - integerDelay) and mask
            val delayed = buffer[readIndex]
            val tuned = fractionalDelay.process(delayed)
            val fed = loopFilter.process(tuned) * loopGain
            buffer[writeIndex] = fed
            writeIndex = (writeIndex + 1) and mask

            // The release is an output envelope, not a loop loss. A factor applied
            // inside the loop only compounds once per round trip — hundreds of samples
            // for a low string — which would make the release almost inaudible.
            if (releasing) releaseGain *= releaseCoefficient
            val sample = dcBlocker.process(fed) * releaseGain
            out[from + i] += sample
            val magnitude = abs(sample)
            if (magnitude > peak) peak = magnitude
            samplesSinceStart++
        }

        lastLevel = peak
        // Retire the voice once it is inaudible, which frees it for the next note.
        if (peak < 1e-5f && samplesSinceStart > sampleRate / 50) {
            active = false
            releasing = false
            noteKey = -1
        }
    }

    /**
     * Release the note.
     *
     * A fretted note that is lifted does not stop dead, it damps over a few tens of
     * milliseconds, so the release applies an exponential taper to the output rather
     * than a hard gate. That is also what prevents the click a hard cut would produce.
     */
    override fun release() {
        if (!active || releasing) return
        releasing = true
        val tailSeconds = if (dead) 0.03f else 0.09f
        releaseCoefficient = exp(-6.9078f / (tailSeconds * sampleRate))
    }

    override fun kill() {
        active = false
        releasing = false
        noteKey = -1
        java.util.Arrays.fill(buffer, 0f)
        loopFilter.reset()
        fractionalDelay.reset()
        dcBlocker.reset()
        lastLevel = 0f
    }
}

/**
 * A piano voice, offered as a pitch reference alongside the guitars.
 *
 * Piano is not a plucked string and the waveguide model does not suit it, so this is
 * additive: a few detuned partials with individual decay rates, which captures the
 * inharmonicity and the way the upper partials die away first. It is deliberately a
 * reference tone rather than a concert instrument.
 */
class PianoVoice(private val sampleRate: Int) : Voice {

    private val partialCount = 7
    private val phases = FloatArray(partialCount)
    private val increments = FloatArray(partialCount)
    private val amplitudes = FloatArray(partialCount)
    private val decays = FloatArray(partialCount)
    private val levels = FloatArray(partialCount)
    private val envelope = Envelope(sampleRate)
    private var active = false
    private var releasing = false
    private var amplitude = 0f

    var noteKey: Int = -1
        private set

    override val isActive: Boolean get() = active
    override val isReleasing: Boolean get() = releasing

    var lastLevel: Float = 0f
        private set

    fun noteOn(frequency: Float, velocity: Float, key: Int) {
        noteKey = key
        val f = frequency.coerceIn(20f, sampleRate / 2.2f)
        amplitude = velocity.coerceIn(0.02f, 1f)
        for (i in 0 until partialCount) {
            val harmonic = i + 1
            // Real strings are slightly inharmonic: partials sit a little above exact
            // multiples, which is a large part of what makes a piano sound like one.
            val stretch = 1f + 0.00018f * harmonic * harmonic
            val partialHz = f * harmonic * stretch
            increments[i] = (2.0 * PI * partialHz / sampleRate).toFloat()
            phases[i] = 0f
            amplitudes[i] = 1f / (harmonic * harmonic).toFloat() *
                (if (harmonic % 2 == 0) 0.6f else 1f)
            // Upper partials decay faster.
            val decaySeconds = (6.0f / harmonic) * (1f - 0.5f * (f / 800f).coerceAtMost(1f))
            decays[i] = exp(-6.9078f / (decaySeconds.coerceAtLeast(0.15f) * sampleRate))
            levels[i] = 1f
            if (partialHz > sampleRate * 0.45f) amplitudes[i] = 0f
        }
        envelope.configure(attackMs = 3f, decayMs = 400f, sustain = 0.65f, releaseMs = 320f)
        envelope.trigger()
        active = true
        releasing = false
    }

    override fun render(out: FloatArray, from: Int, count: Int) {
        if (!active) { lastLevel = 0f; return }
        var peak = 0f
        for (i in 0 until count) {
            var sample = 0f
            for (p in 0 until partialCount) {
                if (amplitudes[p] == 0f) continue
                sample += sin(phases[p]) * amplitudes[p] * levels[p]
                phases[p] += increments[p]
                if (phases[p] > 2f * PI.toFloat()) phases[p] -= 2f * PI.toFloat()
                levels[p] *= decays[p]
            }
            val value = sample * amplitude * 0.5f * envelope.next()
            out[from + i] += value
            val magnitude = abs(value)
            if (magnitude > peak) peak = magnitude
        }
        lastLevel = peak
        if (!envelope.isActive || peak < 1e-5f) {
            active = false
            releasing = false
            noteKey = -1
        }
    }

    override fun release() {
        if (!active) return
        releasing = true
        envelope.release()
    }

    override fun kill() {
        active = false
        releasing = false
        noteKey = -1
        envelope.kill()
        lastLevel = 0f
    }
}

/**
 * The metronome click.
 *
 * A short filtered noise transient plus a pitched body: audible against a distorted
 * guitar without being unpleasant, and distinct between the accented downbeat and the
 * other beats.
 */
class ClickVoice(private val sampleRate: Int) : Voice {
    private val noise = Noise()
    private val tone = Biquad()
    private val body = Biquad()
    private var remaining = 0
    private var total = 1
    private var amplitude = 0f
    private var phase = 0f
    private var increment = 0f

    override val isActive: Boolean get() = remaining > 0
    override val isReleasing: Boolean get() = false

    fun trigger(accented: Boolean, level: Float) {
        total = (sampleRate * if (accented) 0.045 else 0.030).toInt().coerceAtLeast(16)
        remaining = total
        amplitude = level.coerceIn(0f, 1f) * if (accented) 1f else 0.62f
        val frequency = if (accented) 1600f else 1050f
        increment = (2.0 * PI * frequency / sampleRate).toFloat()
        phase = 0f
        tone.bandPass(sampleRate, frequency * 2.2f, 1.1f)
        body.bandPass(sampleRate, frequency, 6f)
        noise.reset(if (accented) 11L else 23L)
    }

    override fun render(out: FloatArray, from: Int, count: Int) {
        if (remaining <= 0) return
        val n = min(count, remaining)
        for (i in 0 until n) {
            val progress = 1f - remaining.toFloat() / total
            val decay = exp(-9f * progress)
            val transient = tone.process(noise.next()) * decay
            val pitched = body.process(sin(phase)) * decay
            phase += increment
            out[from + i] += (transient * 0.55f + pitched * 0.9f) * amplitude
            remaining--
        }
    }

    override fun release() = Unit

    override fun kill() { remaining = 0 }
}

/** Converts a MIDI pitch to a frequency the voices can use. */
fun midiToFrequency(pitch: Int, detuneCents: Float = 0f): Float =
    (Pitch.frequency(pitch) * 2.0.pow(detuneCents / 1200.0)).toFloat()
