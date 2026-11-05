package com.tabtap.app.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Signal-processing primitives used by the instrument voices and track buses.
 *
 * Everything here is allocation-free once constructed and operates on `FloatArray`
 * buffers in place: the render thread must never allocate, because a garbage collection
 * pause in the middle of a buffer is an audible click.
 */

/**
 * A direct-form-I biquad filter.
 *
 * Coefficients follow the Audio EQ Cookbook, which is the standard derivation for
 * these filter types, so the tone controls behave the way they are labelled.
 */
class Biquad {
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    fun reset() {
        x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
    }

    /** Process one sample. */
    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }

    /** Process [count] samples of [buffer] in place, starting at [from]. */
    fun processInPlace(buffer: FloatArray, from: Int, count: Int) {
        for (i in from until from + count) buffer[i] = process(buffer[i])
    }

    private fun set(b0: Float, b1: Float, b2: Float, a0: Float, a1: Float, a2: Float) {
        val inv = 1f / a0
        this.b0 = b0 * inv; this.b1 = b1 * inv; this.b2 = b2 * inv
        this.a1 = a1 * inv; this.a2 = a2 * inv
    }

    fun lowPass(sampleRate: Int, frequency: Float, q: Float = 0.707f) {
        val w = 2.0 * PI * frequency.coerceIn(10f, sampleRate * 0.49f) / sampleRate
        val cosW = cos(w).toFloat()
        val alpha = (sin(w) / (2.0 * q)).toFloat()
        set((1 - cosW) / 2, 1 - cosW, (1 - cosW) / 2, 1 + alpha, -2 * cosW, 1 - alpha)
    }

    fun highPass(sampleRate: Int, frequency: Float, q: Float = 0.707f) {
        val w = 2.0 * PI * frequency.coerceIn(10f, sampleRate * 0.49f) / sampleRate
        val cosW = cos(w).toFloat()
        val alpha = (sin(w) / (2.0 * q)).toFloat()
        set((1 + cosW) / 2, -(1 + cosW), (1 + cosW) / 2, 1 + alpha, -2 * cosW, 1 - alpha)
    }

    fun peaking(sampleRate: Int, frequency: Float, q: Float, gainDb: Float) {
        val a = Math.pow(10.0, (gainDb / 40.0)).toFloat()
        val w = 2.0 * PI * frequency.coerceIn(10f, sampleRate * 0.49f) / sampleRate
        val cosW = cos(w).toFloat()
        val alpha = (sin(w) / (2.0 * q)).toFloat()
        set(1 + alpha * a, -2 * cosW, 1 - alpha * a, 1 + alpha / a, -2 * cosW, 1 - alpha / a)
    }

    fun lowShelf(sampleRate: Int, frequency: Float, gainDb: Float, slope: Float = 1f) {
        val a = Math.pow(10.0, (gainDb / 40.0)).toFloat()
        val w = 2.0 * PI * frequency.coerceIn(10f, sampleRate * 0.49f) / sampleRate
        val cosW = cos(w).toFloat()
        val alpha = (sin(w) / 2.0 * sqrt((a + 1 / a) * (1 / slope - 1) + 2.0)).toFloat()
        val twoSqrtAAlpha = 2 * sqrt(a) * alpha
        set(
            a * ((a + 1) - (a - 1) * cosW + twoSqrtAAlpha),
            2 * a * ((a - 1) - (a + 1) * cosW),
            a * ((a + 1) - (a - 1) * cosW - twoSqrtAAlpha),
            (a + 1) + (a - 1) * cosW + twoSqrtAAlpha,
            -2 * ((a - 1) + (a + 1) * cosW),
            (a + 1) + (a - 1) * cosW - twoSqrtAAlpha,
        )
    }

    fun highShelf(sampleRate: Int, frequency: Float, gainDb: Float, slope: Float = 1f) {
        val a = Math.pow(10.0, (gainDb / 40.0)).toFloat()
        val w = 2.0 * PI * frequency.coerceIn(10f, sampleRate * 0.49f) / sampleRate
        val cosW = cos(w).toFloat()
        val alpha = (sin(w) / 2.0 * sqrt((a + 1 / a) * (1 / slope - 1) + 2.0)).toFloat()
        val twoSqrtAAlpha = 2 * sqrt(a) * alpha
        set(
            a * ((a + 1) + (a - 1) * cosW + twoSqrtAAlpha),
            -2 * a * ((a - 1) + (a + 1) * cosW),
            a * ((a + 1) + (a - 1) * cosW - twoSqrtAAlpha),
            (a + 1) - (a - 1) * cosW + twoSqrtAAlpha,
            2 * ((a - 1) - (a + 1) * cosW),
            (a + 1) - (a - 1) * cosW - twoSqrtAAlpha,
        )
    }

    /** A resonant band-pass, used for instrument body resonances. */
    fun bandPass(sampleRate: Int, frequency: Float, q: Float) {
        val w = 2.0 * PI * frequency.coerceIn(10f, sampleRate * 0.49f) / sampleRate
        val cosW = cos(w).toFloat()
        val alpha = (sin(w) / (2.0 * q)).toFloat()
        set(alpha, 0f, -alpha, 1 + alpha, -2 * cosW, 1 - alpha)
    }
}

/** A first-order one-pole low-pass, cheap enough to run per sample inside a string loop. */
class OnePole(private var coefficient: Float = 0.5f) {
    private var state = 0f

    /** Set the pole from a cutoff frequency. */
    fun setCutoff(sampleRate: Int, frequency: Float) {
        val x = exp(-2.0 * PI * frequency.coerceIn(20f, sampleRate * 0.49f) / sampleRate)
        coefficient = x.toFloat()
    }

    /** Set the pole directly; 0 is wide open, values approaching 1 are very dark. */
    fun setPole(pole: Float) {
        coefficient = pole.coerceIn(0f, 0.999f)
    }

    fun reset() { state = 0f }

    fun process(x: Float): Float {
        state = (1f - coefficient) * x + coefficient * state
        return state
    }
}

/**
 * A first-order all-pass, used as a tunable fractional-sample delay.
 *
 * This is what lets a plucked string be tuned exactly: an integer delay line can only
 * produce frequencies that divide the sample rate, which would leave notes measurably
 * out of tune in the upper register.
 */
class AllPass {
    private var coefficient = 0f
    private var xPrev = 0f
    private var yPrev = 0f

    /** Set the delay to [fraction] of a sample, in 0..1. */
    fun setFraction(fraction: Float) {
        val f = fraction.coerceIn(0.001f, 0.999f)
        coefficient = (1f - f) / (1f + f)
    }

    fun reset() { xPrev = 0f; yPrev = 0f }

    fun process(x: Float): Float {
        val y = coefficient * (x - yPrev) + xPrev
        xPrev = x
        yPrev = y
        return y
    }
}

/** Waveshaping curves for the amplifier stages. */
object Shapers {

    /** A smooth, valve-like saturation. */
    fun soft(x: Float, drive: Float): Float = tanh(x * drive) / tanh(drive)

    /**
     * An asymmetric clip, which generates even harmonics as a real valve stage does and
     * is what makes overdrive sound warm rather than merely loud.
     */
    fun asymmetric(x: Float, drive: Float): Float {
        val d = x * drive
        return if (d >= 0f) {
            tanh(d)
        } else {
            // A gentler negative half produces the asymmetry.
            tanh(d * 0.7f) * 0.85f
        }
    }

    /**
     * A hard clip softened at the knee, the characteristic shape of a transistor
     * distortion pedal.
     */
    fun hard(x: Float, drive: Float): Float {
        val d = x * drive
        return when {
            d > 1.2f -> 1f
            d < -1.2f -> -1f
            else -> d - (d * d * d) / 4.32f
        }
    }
}

/**
 * A look-ahead-free soft limiter on the master bus.
 *
 * Several distorted guitar tracks playing together will exceed full scale, and hard
 * clipping at the output sounds like a fault rather than like loud guitars. This keeps
 * the output inside range with a gain reduction that recovers smoothly.
 */
class Limiter(sampleRate: Int) {
    private val attackCoefficient = exp(-1.0 / (0.002 * sampleRate)).toFloat()
    private val releaseCoefficient = exp(-1.0 / (0.120 * sampleRate)).toFloat()
    private var envelope = 0f

    var threshold = 0.92f

    fun reset() { envelope = 0f }

    /** Limit [count] interleaved stereo frames in place. */
    fun process(buffer: FloatArray, from: Int, count: Int, channels: Int) {
        var i = from
        val end = from + count * channels
        while (i < end) {
            var peak = 0f
            for (c in 0 until channels) {
                val v = abs(buffer[i + c])
                if (v > peak) peak = v
            }
            val coefficient = if (peak > envelope) attackCoefficient else releaseCoefficient
            envelope = peak + coefficient * (envelope - peak)
            val gain = if (envelope > threshold) threshold / envelope else 1f
            for (c in 0 until channels) {
                buffer[i + c] = (buffer[i + c] * gain).coerceIn(-1f, 1f)
            }
            i += channels
        }
    }
}

/** A deterministic noise source, so a rendered take sounds identical every time. */
class Noise(seed: Long = 0x5DEECE66DL) {
    private var state = seed or 1L

    fun next(): Float {
        // xorshift64*, fast and good enough for an excitation burst.
        state = state xor (state shl 13)
        state = state xor (state ushr 7)
        state = state xor (state shl 17)
        return ((state ushr 11).toFloat() / (1L shl 53).toFloat()) * 2f - 1f
    }

    fun reset(seed: Long) { state = seed or 1L }
}

/** A simple ADSR-style envelope, used by the piano voice and the metronome click. */
class Envelope(private val sampleRate: Int) {
    private var value = 0f
    private var stage = Stage.IDLE
    private var attackRate = 0f
    private var decayRate = 0f
    private var releaseRate = 0f
    private var sustainLevel = 0.7f

    private enum class Stage { IDLE, ATTACK, DECAY, SUSTAIN, RELEASE }

    val isActive: Boolean get() = stage != Stage.IDLE

    fun configure(attackMs: Float, decayMs: Float, sustain: Float, releaseMs: Float) {
        attackRate = 1f / (attackMs.coerceAtLeast(0.1f) * 0.001f * sampleRate)
        sustainLevel = sustain.coerceIn(0f, 1f)
        decayRate = (1f - sustainLevel) / (decayMs.coerceAtLeast(1f) * 0.001f * sampleRate)
        releaseRate = 1f / (releaseMs.coerceAtLeast(1f) * 0.001f * sampleRate)
    }

    fun trigger() { stage = Stage.ATTACK }

    fun release() { if (stage != Stage.IDLE) stage = Stage.RELEASE }

    fun kill() { stage = Stage.IDLE; value = 0f }

    fun next(): Float {
        when (stage) {
            Stage.IDLE -> return 0f
            Stage.ATTACK -> {
                value += attackRate
                if (value >= 1f) { value = 1f; stage = Stage.DECAY }
            }
            Stage.DECAY -> {
                value -= decayRate
                if (value <= sustainLevel) { value = sustainLevel; stage = Stage.SUSTAIN }
            }
            Stage.SUSTAIN -> {}
            Stage.RELEASE -> {
                value -= releaseRate
                if (value <= 0f) { value = 0f; stage = Stage.IDLE }
            }
        }
        return value
    }
}
