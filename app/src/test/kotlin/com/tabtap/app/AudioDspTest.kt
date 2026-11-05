package com.tabtap.app

import com.tabtap.app.audio.AllPass
import com.tabtap.app.audio.Biquad
import com.tabtap.app.audio.ClickVoice
import com.tabtap.app.audio.Envelope
import com.tabtap.app.audio.Limiter
import com.tabtap.app.audio.Noise
import com.tabtap.app.audio.OnePole
import com.tabtap.app.audio.PianoVoice
import com.tabtap.app.audio.Shapers
import com.tabtap.app.audio.StringVoice
import com.tabtap.app.audio.VoiceArticulation
import com.tabtap.app.audio.midiToFrequency
import com.tabtap.music.Pitch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Tests for the audio engine's signal processing.
 *
 * The voices are verified by rendering them and measuring the result: the pitch is
 * recovered by autocorrelation and checked against the note that was asked for, and the
 * decay, the release and the silence are all measured rather than assumed. A synthesis
 * engine that is merely plausible is not good enough — if a note is out of tune, nothing
 * built on top of it is usable.
 */
class AudioDspTest {

    private val sampleRate = 48_000

    // ---------------------------------------------------------------- filters

    @Test fun lowPassPassesLowAndStopsHigh() {
        val filter = Biquad().apply { lowPass(sampleRate, 1000f) }
        assertTrue(gainAt(filter, 100f) > 0.9f)
        val high = Biquad().apply { lowPass(sampleRate, 1000f) }
        assertTrue(gainAt(high, 8000f) < 0.1f)
    }

    @Test fun highPassStopsLowAndPassesHigh() {
        assertTrue(gainAt(Biquad().apply { highPass(sampleRate, 1000f) }, 100f) < 0.1f)
        assertTrue(gainAt(Biquad().apply { highPass(sampleRate, 1000f) }, 8000f) > 0.9f)
    }

    @Test fun lowPassIsMinusThreeDecibelsAtItsCutoff() {
        // A Butterworth-Q low-pass should be about 0.707 of unity at the cutoff.
        val gain = gainAt(Biquad().apply { lowPass(sampleRate, 1000f, 0.707f) }, 1000f)
        assertEquals(1f / sqrt(2f), gain, 0.05f)
    }

    @Test fun peakingFilterBoostsOnlyItsBand() {
        val atPeak = gainAt(Biquad().apply { peaking(sampleRate, 1000f, 1f, 12f) }, 1000f)
        val away = gainAt(Biquad().apply { peaking(sampleRate, 1000f, 1f, 12f) }, 80f)
        assertTrue("peak should boost: $atPeak", atPeak > 3.5f)
        assertEquals("away from the peak should be flat", 1f, away, 0.15f)
    }

    @Test fun shelvesActOnTheRightSide() {
        assertTrue(gainAt(Biquad().apply { lowShelf(sampleRate, 200f, 12f) }, 50f) > 3f)
        assertEquals(1f, gainAt(Biquad().apply { lowShelf(sampleRate, 200f, 12f) }, 8000f), 0.15f)
        assertTrue(gainAt(Biquad().apply { highShelf(sampleRate, 4000f, 12f) }, 12000f) > 3f)
    }

    @Test fun filtersAreStableAcrossTheWholeFrequencyRange() {
        // A filter that blows up produces a scream rather than a sound.
        for (frequency in listOf(20f, 100f, 1000f, 10_000f, 20_000f, 23_000f)) {
            for (filter in listOf(
                Biquad().apply { lowPass(sampleRate, frequency) },
                Biquad().apply { highPass(sampleRate, frequency) },
                Biquad().apply { peaking(sampleRate, frequency, 1f, 12f) },
                Biquad().apply { bandPass(sampleRate, frequency, 4f) },
            )) {
                var peak = 0f
                val noise = Noise(7)
                repeat(4000) {
                    val y = filter.process(noise.next())
                    assertTrue("$frequency produced a non-finite sample", y.isFinite())
                    peak = maxOf(peak, abs(y))
                }
                assertTrue("$frequency Hz filter is unstable (peak $peak)", peak < 40f)
            }
        }
    }

    @Test fun onePoleDarkensAsItsPoleRises() {
        val open = OnePole().apply { setPole(0f) }
        val dark = OnePole().apply { setPole(0.95f) }
        var openEnergy = 0f
        var darkEnergy = 0f
        val a = Noise(1)
        val b = Noise(1)
        repeat(4000) {
            openEnergy += abs(open.process(a.next()))
            darkEnergy += abs(dark.process(b.next()))
        }
        assertTrue(darkEnergy < openEnergy)
    }

    @Test fun allPassPreservesEnergyWhileDelaying() {
        val filter = AllPass().apply { setFraction(0.5f) }
        var input = 0f
        var output = 0f
        val noise = Noise(3)
        repeat(8000) {
            val x = noise.next()
            input += x * x
            val y = filter.process(x)
            output += y * y
            assertTrue(y.isFinite())
        }
        // An all-pass changes phase, not magnitude, so the energy should be close.
        assertEquals(1f, output / input, 0.05f)
    }

    // ---------------------------------------------------------------- shapers

    @Test fun shapersAreBoundedAndMonotonic() {
        for (drive in listOf(1f, 3f, 8f, 20f)) {
            var previousSoft = -2f
            var previousHard = -2f
            var x = -2f
            while (x <= 2f) {
                val soft = Shapers.soft(x, drive)
                val hard = Shapers.hard(x, drive)
                val asym = Shapers.asymmetric(x, drive)
                assertTrue("soft out of range at $x: $soft", abs(soft) <= 1.001f)
                assertTrue("hard out of range at $x: $hard", abs(hard) <= 1.01f)
                assertTrue("asym out of range at $x: $asym", abs(asym) <= 1.01f)
                assertTrue("soft not monotonic at $x", soft >= previousSoft - 1e-5f)
                assertTrue("hard not monotonic at $x", hard >= previousHard - 1e-5f)
                previousSoft = soft
                previousHard = hard
                x += 0.01f
            }
        }
    }

    @Test fun softShaperIsOddSymmetricButAsymmetricOneIsNot() {
        for (x in listOf(0.2f, 0.5f, 0.9f)) {
            assertEquals(-Shapers.soft(x, 4f), Shapers.soft(-x, 4f), 1e-5f)
            assertTrue(abs(Shapers.asymmetric(x, 4f)) != abs(Shapers.asymmetric(-x, 4f)))
        }
    }

    @Test fun shapersPassSilenceThrough() {
        assertEquals(0f, Shapers.soft(0f, 5f), 1e-6f)
        assertEquals(0f, Shapers.hard(0f, 5f), 1e-6f)
        assertEquals(0f, Shapers.asymmetric(0f, 5f), 1e-6f)
    }

    // ---------------------------------------------------------------- limiter

    @Test fun limiterKeepsTheOutputInRange() {
        val limiter = Limiter(sampleRate)
        val buffer = FloatArray(4096)
        // A signal four times over full scale.
        for (i in buffer.indices step 2) {
            val v = 4f * sin(2.0 * PI * 220.0 * (i / 2) / sampleRate).toFloat()
            buffer[i] = v
            buffer[i + 1] = v
        }
        limiter.process(buffer, 0, buffer.size / 2, 2)
        assertTrue(buffer.all { abs(it) <= 1f })
        // After the attack has settled it should be close to the threshold, not silent.
        val tail = buffer.drop(2048).maxOf { abs(it) }
        assertTrue("limiter over-suppressed: $tail", tail > 0.6f)
    }

    @Test fun limiterLeavesQuietSignalsAlone() {
        val limiter = Limiter(sampleRate)
        val buffer = FloatArray(2048) { 0.3f * sin(it * 0.05).toFloat() }
        val original = buffer.copyOf()
        limiter.process(buffer, 0, buffer.size / 2, 2)
        for (i in buffer.indices) {
            assertEquals("sample $i was altered", original[i], buffer[i], 1e-4f)
        }
    }

    // ---------------------------------------------------------------- envelope

    @Test fun envelopeRunsThroughItsStagesAndStops() {
        val envelope = Envelope(sampleRate)
        envelope.configure(attackMs = 1f, decayMs = 10f, sustain = 0.5f, releaseMs = 10f)
        assertFalse(envelope.isActive)
        envelope.trigger()
        assertTrue(envelope.isActive)
        // Rises to full.
        var peak = 0f
        repeat(sampleRate / 500) { peak = maxOf(peak, envelope.next()) }
        assertEquals(1f, peak, 0.02f)
        // Settles to the sustain level.
        repeat(sampleRate / 50) { envelope.next() }
        assertEquals(0.5f, envelope.next(), 0.02f)
        // And releases to silence.
        envelope.release()
        repeat(sampleRate / 50) { envelope.next() }
        assertEquals(0f, envelope.next(), 1e-4f)
        assertFalse(envelope.isActive)
    }

    // ---------------------------------------------------------------- noise

    @Test fun noiseIsDeterministicAndCentred() {
        val a = Noise(42)
        val b = Noise(42)
        var sum = 0.0
        repeat(20_000) {
            val x = a.next()
            assertEquals(x, b.next(), 0f)
            assertTrue(abs(x) <= 1f)
            sum += x
        }
        assertEquals("noise should be centred on zero", 0.0, sum / 20_000, 0.03)
    }

    // ---------------------------------------------------------------- string voice

    @Test fun pluckedStringSoundsTheRequestedPitch() {
        // Every open string of a standard guitar, plus some fretted notes.
        for (name in listOf("E2", "A2", "D3", "G3", "B3", "E4", "A4", "E5", "A5")) {
            val midi = Pitch.parse(name)!!
            val measured = measurePitch(midi)
            val expected = Pitch.frequency(midi)
            val cents = 1200.0 * kotlin.math.ln(measured / expected) / kotlin.math.ln(2.0)
            // Ten cents is well inside what a guitarist would call in tune.
            assertTrue(
                "$name: expected ${"%.2f".format(expected)} Hz, measured " +
                    "${"%.2f".format(measured)} Hz (${"%.1f".format(cents)} cents)",
                abs(cents) < 10.0,
            )
        }
    }

    @Test fun pluckedStringProducesSoundThenDecaysToSilence() {
        val voice = StringVoice(sampleRate)
        voice.noteOn(midiToFrequency(Pitch.parse("A2")!!), 0.9f, VoiceArticulation.DEFAULT, 1)
        assertTrue(voice.isActive)

        val early = FloatArray(2048)
        voice.render(early, 0, early.size)
        val earlyPeak = early.maxOf { abs(it) }
        assertTrue("the pluck produced nothing", earlyPeak > 0.05f)
        assertTrue("the pluck clipped", earlyPeak <= 1.2f)

        // Well after the decay time the voice should have retired itself.
        val buffer = FloatArray(4096)
        var blocks = 0
        while (voice.isActive && blocks < 200) {
            java.util.Arrays.fill(buffer, 0f)
            voice.render(buffer, 0, buffer.size)
            blocks++
        }
        assertFalse("the voice never stopped: it would leak polyphony", voice.isActive)
    }

    @Test fun releaseShortensTheNoteWithoutClicking() {
        val held = renderVoice(Pitch.parse("A2")!!, blocks = 24, releaseAfterBlocks = null)
        val released = renderVoice(Pitch.parse("A2")!!, blocks = 24, releaseAfterBlocks = 4)
        val heldTail = held.takeLast(2048).maxOf { abs(it) }
        val releasedTail = released.takeLast(2048).maxOf { abs(it) }
        assertTrue("release did not shorten the note", releasedTail < heldTail * 0.5f)
        // A click shows up as a large jump between consecutive samples.
        val biggestJump = released.toList().zipWithNext { a, b -> abs(b - a) }.max()
        assertTrue("release clicked (jump of $biggestJump)", biggestJump < 0.4f)
    }

    @Test fun velocityControlsLoudness() {
        val quiet = renderVoice(Pitch.parse("A2")!!, velocity = 0.2f, blocks = 6).maxOf { abs(it) }
        val loud = renderVoice(Pitch.parse("A2")!!, velocity = 1.0f, blocks = 6).maxOf { abs(it) }
        assertTrue("velocity had no effect: $quiet vs $loud", loud > quiet * 2f)
    }

    @Test fun deadNotesDecayFarFasterThanOpenOnes() {
        val open = renderVoice(Pitch.parse("A2")!!, blocks = 30)
        val dead = renderVoice(
            Pitch.parse("A2")!!, blocks = 30,
            articulation = VoiceArticulation(dead = true),
        )
        assertTrue(dead.takeLast(4096).maxOf { abs(it) } < open.takeLast(4096).maxOf { abs(it) })
    }

    @Test fun palmMutingDampensTheSustain() {
        val open = renderVoice(Pitch.parse("E2")!!, blocks = 24)
        val muted = renderVoice(
            Pitch.parse("E2")!!, blocks = 24,
            articulation = VoiceArticulation(damping = 0.75f),
        )
        assertTrue(muted.takeLast(4096).maxOf { abs(it) } < open.takeLast(4096).maxOf { abs(it) })
    }

    @Test fun highNotesDecayFasterThanLowOnes() {
        // A real string loses its high harmonics first and high notes die sooner.
        fun energyAfter(midi: Int): Float {
            val samples = renderVoice(midi, blocks = 40)
            return samples.takeLast(8192).sumOf { abs(it).toDouble() }.toFloat()
        }
        val low = energyAfter(Pitch.parse("E2")!!)
        val high = energyAfter(Pitch.parse("E5")!!)
        assertTrue("high notes should not outlast low ones ($high vs $low)", high < low)
    }

    @Test fun everyPlayableNoteProducesFiniteAudio() {
        // Nothing in the fretboard's range may produce NaN, infinity or a clipped roar.
        for (midi in 24..100) {
            val samples = renderVoice(midi, blocks = 4)
            assertTrue("MIDI $midi produced a non-finite sample", samples.all { it.isFinite() })
            assertTrue("MIDI $midi clipped", samples.all { abs(it) < 2f })
        }
    }

    @Test fun killSilencesAVoiceImmediately() {
        val voice = StringVoice(sampleRate)
        voice.noteOn(midiToFrequency(60), 1f, VoiceArticulation.DEFAULT, 1)
        val buffer = FloatArray(512)
        voice.render(buffer, 0, buffer.size)
        voice.kill()
        assertFalse(voice.isActive)
        java.util.Arrays.fill(buffer, 0f)
        voice.render(buffer, 0, buffer.size)
        assertTrue(buffer.all { it == 0f })
    }

    @Test fun bendRaisesThePitchOverTime() {
        val voice = StringVoice(sampleRate)
        voice.noteOn(
            midiToFrequency(Pitch.parse("A3")!!), 0.9f,
            VoiceArticulation(bendSemitones = 2f, bendTimeSeconds = 0.1f), 1,
        )
        // Skip the bend's rise, then measure where it ended up.
        val skip = FloatArray(8192)
        voice.render(skip, 0, skip.size)
        val after = FloatArray(16384)
        voice.render(after, 0, after.size)
        val measured = estimatePitch(after, sampleRate)
        val expected = Pitch.frequency(Pitch.parse("A3")!! + 2)
        assertEquals("a full-tone bend should land a tone higher", expected, measured, expected * 0.04)
    }

    @Test fun retuningKeepsTheVoiceSoundingForLegato() {
        val voice = StringVoice(sampleRate)
        voice.noteOn(midiToFrequency(Pitch.parse("A3")!!), 0.9f, VoiceArticulation.DEFAULT, 1)
        val first = FloatArray(2048)
        voice.render(first, 0, first.size)
        voice.retune(midiToFrequency(Pitch.parse("C4")!!))
        assertTrue("a hammer-on must not stop the string", voice.isActive)
        val after = FloatArray(2048)
        voice.render(after, 0, after.size)
        assertTrue("the string went silent on a hammer-on", after.maxOf { abs(it) } > 1e-4f)
    }

    // ---------------------------------------------------------------- other voices

    @Test fun pianoVoiceSoundsTheRequestedPitchAndStops() {
        val voice = PianoVoice(sampleRate)
        voice.noteOn(midiToFrequency(Pitch.parse("C4")!!), 0.9f, 1)
        val buffer = FloatArray(16384)
        voice.render(buffer, 0, buffer.size)
        assertTrue(buffer.maxOf { abs(it) } > 0.02f)
        assertTrue(buffer.all { it.isFinite() })
        val measured = estimatePitch(buffer, sampleRate)
        val expected = Pitch.frequency(Pitch.parse("C4")!!)
        assertEquals(expected, measured, expected * 0.03)

        voice.release()
        var blocks = 0
        val tail = FloatArray(4096)
        while (voice.isActive && blocks < 200) {
            java.util.Arrays.fill(tail, 0f)
            voice.render(tail, 0, tail.size)
            blocks++
        }
        assertFalse(voice.isActive)
    }

    @Test fun clickVoiceIsShortAndDistinguishesTheAccent() {
        val accented = ClickVoice(sampleRate)
        accented.trigger(accented = true, level = 1f)
        val plain = ClickVoice(sampleRate)
        plain.trigger(accented = false, level = 1f)

        val a = FloatArray(4096)
        val b = FloatArray(4096)
        accented.render(a, 0, a.size)
        plain.render(b, 0, b.size)
        assertTrue("the accent should be louder", a.maxOf { abs(it) } > b.maxOf { abs(it) })
        // A click must be brief, or it masks the music.
        assertFalse(accented.isActive)
        assertTrue(a.drop(sampleRate / 10).all { abs(it) < 1e-4f })
    }

    // ---------------------------------------------------------------- helpers

    /** Magnitude response of [filter] at [frequency], measured by driving a sine through it. */
    private fun gainAt(filter: Biquad, frequency: Float): Float {
        // Let the filter settle, then measure the steady-state peak.
        val omega = 2.0 * PI * frequency / sampleRate
        repeat(2000) { filter.process(sin(omega * it).toFloat()) }
        var peak = 0f
        for (i in 2000 until 6000) {
            peak = maxOf(peak, abs(filter.process(sin(omega * i).toFloat())))
        }
        return peak
    }

    private fun renderVoice(
        midi: Int,
        velocity: Float = 0.9f,
        blocks: Int = 8,
        blockSize: Int = 2048,
        articulation: VoiceArticulation = VoiceArticulation.DEFAULT,
        releaseAfterBlocks: Int? = null,
    ): FloatArray {
        val voice = StringVoice(sampleRate)
        voice.noteOn(midiToFrequency(midi), velocity, articulation, midi)
        val out = FloatArray(blocks * blockSize)
        for (b in 0 until blocks) {
            if (releaseAfterBlocks != null && b == releaseAfterBlocks) voice.release()
            voice.render(out, b * blockSize, blockSize)
        }
        return out
    }

    /** Renders a note and recovers its fundamental. */
    private fun measurePitch(midi: Int): Double {
        val voice = StringVoice(sampleRate)
        voice.noteOn(midiToFrequency(midi), 0.9f, VoiceArticulation.DEFAULT, midi)
        // Skip the attack transient, which is noisy by design.
        val skip = FloatArray(4096)
        voice.render(skip, 0, skip.size)
        val samples = FloatArray(32768)
        voice.render(samples, 0, samples.size)
        return estimatePitch(samples, sampleRate)
    }

    /**
     * Estimates a fundamental by autocorrelation, refined by parabolic interpolation
     * around the peak so the estimate is not limited to whole-sample lags.
     */
    private fun estimatePitch(samples: FloatArray, sampleRate: Int): Double {
        val minLag = sampleRate / 1200
        val maxLag = (sampleRate / 25).coerceAtMost(samples.size / 2 - 2)
        var bestLag = minLag
        var bestScore = -Double.MAX_VALUE
        val correlations = DoubleArray(maxLag + 2)
        for (lag in minLag..maxLag) {
            var sum = 0.0
            var i = 0
            val n = samples.size - lag
            while (i < n) {
                sum += samples[i].toDouble() * samples[i + lag]
                i += 2    // Every other sample is plenty and halves the work.
            }
            correlations[lag] = sum
            if (sum > bestScore) { bestScore = sum; bestLag = lag }
        }
        // Parabolic interpolation through the three points around the peak.
        val y0 = correlations[(bestLag - 1).coerceAtLeast(minLag)]
        val y1 = correlations[bestLag]
        val y2 = correlations[(bestLag + 1).coerceAtMost(maxLag)]
        val denominator = 2 * (2 * y1 - y0 - y2)
        val offset = if (abs(denominator) < 1e-12) 0.0 else (y2 - y0) / denominator
        return sampleRate / (bestLag + offset)
    }
}
