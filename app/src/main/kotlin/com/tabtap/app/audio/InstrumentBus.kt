package com.tabtap.app.audio

import com.tabtap.music.InstrumentVoice

/**
 * A per-track signal chain: polyphony plus the tone shaping that makes one instrument
 * voice sound different from another.
 *
 * Distortion is applied to the *sum* of the track's notes, not to each note separately,
 * because that is where its character comes from. A real amplifier distorts a chord as a
 * whole, and the intermodulation between the notes is most of what a distorted guitar
 * sounds like; shaping each note on its own produces a thin, synthetic result that gives
 * the game away immediately.
 */
class InstrumentBus(
    private val sampleRate: Int,
    maxVoices: Int = 14,
) {
    /** Plucked-string voices, used by every guitar and bass preset. */
    private val stringVoices = Array(maxVoices) { StringVoice(sampleRate) }

    /** Piano voices, used only by the reference piano preset. */
    private val pianoVoices = Array(6) { PianoVoice(sampleRate) }

    private var mono = FloatArray(2048)

    // Tone-shaping stages, reconfigured when the voice changes.
    private val highPass = Biquad()
    private val preEq = Biquad()
    private val toneMid = Biquad()
    private val tonePresence = Biquad()
    private val postLowPass = Biquad()
    private val bodyLow = Biquad()
    private val bodyMid = Biquad()
    private val bodyHigh = Biquad()

    private var voice: InstrumentVoice = InstrumentVoice.ELECTRIC_CLEAN
    private var driveAmount = 1f
    private var outputTrim = 1f
    private var shaper: Shaper = Shaper.NONE

    private enum class Shaper { NONE, SOFT, ASYMMETRIC, HARD }

    init {
        configure(InstrumentVoice.ELECTRIC_CLEAN)
    }

    /** True when any note on this track is still sounding. */
    val isSounding: Boolean
        get() = stringVoices.any { it.isActive } || pianoVoices.any { it.isActive }

    val activeVoiceCount: Int
        get() = stringVoices.count { it.isActive } + pianoVoices.count { it.isActive }

    /**
     * Set the instrument voice.
     *
     * Each preset is a different amplifier and speaker: the clean sound is barely shaped,
     * overdrive adds an asymmetric valve-like stage with a midrange push, distortion adds
     * much more gain with a tighter low end and a presence peak, and the acoustic presets
     * substitute three resonant peaks that stand in for an instrument body instead of an
     * amplifier.
     */
    fun configure(newVoice: InstrumentVoice) {
        voice = newVoice
        reset()
        when (newVoice) {
            InstrumentVoice.ELECTRIC_CLEAN -> {
                highPass.highPass(sampleRate, 70f)
                preEq.peaking(sampleRate, 2600f, 0.9f, 2.0f)
                toneMid.peaking(sampleRate, 700f, 0.8f, -1.0f)
                tonePresence.highShelf(sampleRate, 4200f, -2.0f)
                postLowPass.lowPass(sampleRate, 7500f, 0.8f)
                shaper = Shaper.SOFT
                driveAmount = 1.15f
                outputTrim = 0.95f
            }
            InstrumentVoice.ELECTRIC_OVERDRIVE -> {
                highPass.highPass(sampleRate, 95f)
                preEq.peaking(sampleRate, 900f, 0.7f, 5.0f)
                toneMid.peaking(sampleRate, 2200f, 1.1f, 2.5f)
                tonePresence.highShelf(sampleRate, 5200f, -4.0f)
                postLowPass.lowPass(sampleRate, 5600f, 0.9f)
                shaper = Shaper.ASYMMETRIC
                driveAmount = 5.5f
                outputTrim = 0.42f
            }
            InstrumentVoice.ELECTRIC_DISTORTION -> {
                // A tighter low cut keeps a high-gain sound from turning to mud.
                highPass.highPass(sampleRate, 135f, 0.8f)
                preEq.peaking(sampleRate, 750f, 0.6f, 7.0f)
                toneMid.peaking(sampleRate, 1800f, 1.3f, 4.0f)
                tonePresence.peaking(sampleRate, 3400f, 1.4f, 3.0f)
                // A speaker cabinet rolls off sharply above about 5 kHz; without this a
                // distorted sound is unbearably harsh.
                postLowPass.lowPass(sampleRate, 4600f, 1.1f)
                shaper = Shaper.HARD
                driveAmount = 14f
                outputTrim = 0.26f
            }
            InstrumentVoice.ACOUSTIC_STEEL -> {
                highPass.highPass(sampleRate, 80f)
                // Three body resonances: the air cavity, the top, and a bright peak.
                bodyLow.peaking(sampleRate, 105f, 2.2f, 6.0f)
                bodyMid.peaking(sampleRate, 220f, 1.8f, 3.5f)
                bodyHigh.peaking(sampleRate, 3000f, 0.9f, 4.0f)
                postLowPass.lowPass(sampleRate, 11000f, 0.7f)
                shaper = Shaper.NONE
                driveAmount = 1f
                outputTrim = 0.9f
            }
            InstrumentVoice.ACOUSTIC_NYLON -> {
                highPass.highPass(sampleRate, 70f)
                bodyLow.peaking(sampleRate, 95f, 2.0f, 5.0f)
                bodyMid.peaking(sampleRate, 400f, 1.4f, 2.0f)
                bodyHigh.peaking(sampleRate, 2200f, 0.8f, 1.5f)
                postLowPass.lowPass(sampleRate, 6500f, 0.7f)
                shaper = Shaper.NONE
                driveAmount = 1f
                outputTrim = 0.95f
            }
            InstrumentVoice.BASS_FINGER -> {
                highPass.highPass(sampleRate, 35f)
                preEq.lowShelf(sampleRate, 140f, 4.0f)
                toneMid.peaking(sampleRate, 700f, 0.9f, -2.5f)
                tonePresence.peaking(sampleRate, 1800f, 1.0f, 1.0f)
                postLowPass.lowPass(sampleRate, 3800f, 0.8f)
                shaper = Shaper.SOFT
                driveAmount = 1.4f
                outputTrim = 0.85f
            }
            InstrumentVoice.BASS_PICK -> {
                highPass.highPass(sampleRate, 40f)
                preEq.lowShelf(sampleRate, 120f, 3.0f)
                toneMid.peaking(sampleRate, 900f, 1.0f, 1.5f)
                tonePresence.peaking(sampleRate, 2600f, 1.2f, 3.0f)
                postLowPass.lowPass(sampleRate, 5200f, 0.8f)
                shaper = Shaper.SOFT
                driveAmount = 2.0f
                outputTrim = 0.78f
            }
            InstrumentVoice.PIANO -> {
                highPass.highPass(sampleRate, 30f)
                preEq.peaking(sampleRate, 1200f, 0.8f, 1.0f)
                toneMid.peaking(sampleRate, 300f, 0.9f, 1.0f)
                tonePresence.highShelf(sampleRate, 6000f, -2.0f)
                postLowPass.lowPass(sampleRate, 13000f, 0.7f)
                shaper = Shaper.NONE
                driveAmount = 1f
                outputTrim = 0.8f
            }
        }
    }

    private val usesPiano: Boolean get() = voice == InstrumentVoice.PIANO
    private val isAcoustic: Boolean
        get() = voice == InstrumentVoice.ACOUSTIC_STEEL || voice == InstrumentVoice.ACOUSTIC_NYLON

    /**
     * Start a note.
     *
     * @param key an identifier for the note, so [noteOff] can find it again. The engine
     *   uses the note's own id, which makes repeated pitches independent.
     */
    fun noteOn(pitch: Int, velocity: Float, articulation: VoiceArticulation, key: Int) {
        if (usesPiano) {
            val slot = pianoVoices.firstOrNull { !it.isActive }
                ?: pianoVoices.minByOrNull { it.lastLevel }!!
            slot.kill()
            slot.noteOn(midiToFrequency(pitch), velocity, key)
            return
        }
        val pickPosition = when (voice) {
            InstrumentVoice.ELECTRIC_DISTORTION, InstrumentVoice.ELECTRIC_OVERDRIVE -> 0.16f
            InstrumentVoice.BASS_PICK -> 0.12f
            InstrumentVoice.BASS_FINGER -> 0.30f
            InstrumentVoice.ACOUSTIC_NYLON -> 0.33f
            else -> articulation.pickPosition
        }
        val slot = stringVoices.firstOrNull { !it.isActive }
            // Prefer stealing a voice that is already releasing, then the quietest.
            ?: stringVoices.filter { it.isReleasing }.minByOrNull { it.lastLevel }
            ?: stringVoices.minByOrNull { it.lastLevel }!!
        slot.kill()
        slot.noteOn(
            frequency = midiToFrequency(pitch),
            velocity = velocity,
            articulation = articulation.copy(pickPosition = pickPosition),
            key = key,
        )
    }

    /** Release the note started with [key]. */
    fun noteOff(key: Int) {
        if (usesPiano) {
            pianoVoices.firstOrNull { it.isActive && it.noteKey == key }?.release()
            return
        }
        stringVoices.firstOrNull { it.isActive && it.noteKey == key }?.release()
    }

    /** Re-pitch a sounding note, for a slide or a hammer-on. */
    fun retune(key: Int, pitch: Int) {
        stringVoices.firstOrNull { it.isActive && it.noteKey == key }
            ?.retune(midiToFrequency(pitch))
    }

    /** Silence everything on this track immediately. */
    fun panic() {
        stringVoices.forEach { it.kill() }
        pianoVoices.forEach { it.kill() }
        reset()
    }

    private fun reset() {
        highPass.reset(); preEq.reset(); toneMid.reset(); tonePresence.reset()
        postLowPass.reset(); bodyLow.reset(); bodyMid.reset(); bodyHigh.reset()
    }

    /**
     * Render [frames] frames, mix into the interleaved stereo [out] at [outOffset] with
     * [gain] and [pan].
     */
    fun render(out: FloatArray, outOffset: Int, frames: Int, gain: Float, pan: Float) {
        if (mono.size < frames) mono = FloatArray(frames)
        java.util.Arrays.fill(mono, 0, frames, 0f)

        if (usesPiano) {
            for (v in pianoVoices) if (v.isActive) v.render(mono, 0, frames)
        } else {
            for (v in stringVoices) if (v.isActive) v.render(mono, 0, frames)
        }

        // Tone chain, applied to the summed track so distortion behaves as an amplifier.
        highPass.processInPlace(mono, 0, frames)
        if (isAcoustic) {
            bodyLow.processInPlace(mono, 0, frames)
            bodyMid.processInPlace(mono, 0, frames)
            bodyHigh.processInPlace(mono, 0, frames)
        } else {
            preEq.processInPlace(mono, 0, frames)
            if (shaper != Shaper.NONE) {
                for (i in 0 until frames) {
                    val x = mono[i]
                    mono[i] = when (shaper) {
                        Shaper.SOFT -> Shapers.soft(x, driveAmount)
                        Shaper.ASYMMETRIC -> Shapers.asymmetric(x, driveAmount)
                        Shaper.HARD -> Shapers.hard(x, driveAmount)
                        Shaper.NONE -> x
                    }
                }
            }
            toneMid.processInPlace(mono, 0, frames)
            tonePresence.processInPlace(mono, 0, frames)
        }
        postLowPass.processInPlace(mono, 0, frames)

        // Equal-power panning, so moving a track across the stereo field does not change
        // how loud it sounds.
        val angle = ((pan.coerceIn(-1f, 1f) + 1f) * 0.25f * Math.PI).toFloat()
        val leftGain = Math.cos(angle.toDouble()).toFloat() * gain * outputTrim
        val rightGain = Math.sin(angle.toDouble()).toFloat() * gain * outputTrim

        var o = outOffset
        for (i in 0 until frames) {
            val sample = mono[i]
            out[o] += sample * leftGain
            out[o + 1] += sample * rightGain
            o += 2
        }
    }
}
