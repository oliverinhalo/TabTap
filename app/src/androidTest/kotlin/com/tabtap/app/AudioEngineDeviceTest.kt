package com.tabtap.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tabtap.app.audio.AudioEngine
import com.tabtap.app.audio.LoopRegion
import com.tabtap.app.audio.Sequencer
import com.tabtap.app.audio.TransportState
import com.tabtap.app.editing.CompositionEditor
import com.tabtap.music.Composition
import com.tabtap.music.HarmonySpec
import com.tabtap.music.InstrumentVoice
import com.tabtap.music.Key
import com.tabtap.music.NoteValue
import com.tabtap.music.ScaleType
import com.tabtap.music.Ticks
import com.tabtap.music.Tunings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The audio engine, against the device's real audio output.
 *
 * This is the one part of the app that cannot be tested on the JVM: it needs a real
 * `AudioTrack`, a real render thread and a real clock. What is checked here is the
 * engine's *lifecycle* — that it opens, plays, loops, seeks, stops and releases without
 * leaving a thread running or a note sounding — because those are the failures that would
 * show up as a stuck note or a hung app rather than as a wrong pitch. The synthesis
 * itself is verified numerically in the JVM tests, where the output can be measured.
 *
 * Run with: gradlew :app:connectedAndroidTest
 */
@RunWith(AndroidJUnit4::class)
class AudioEngineDeviceTest {

    private val engine = AudioEngine()

    @After fun tearDown() {
        engine.shutdown()
    }

    private fun composition(): Composition {
        var c = Composition.newGuitarProject(
            Tunings.STANDARD_E, 140.0, Key.of("E", ScaleType.NATURAL_MINOR)!!,
        )
        for ((string, fret) in listOf(2 to 2, 2 to 4, 2 to 5, 3 to 2, 3 to 4, 3 to 2)) {
            c = CompositionEditor.appendNoteAtPosition(c, 1L, string, fret, NoteValue.EIGHTH)
        }
        return CompositionEditor.generateHarmonies(c, 1L, listOf(HarmonySpec.THIRD_ABOVE)).first
    }

    /** Waits for [condition], up to [timeoutMs]. */
    private fun waitFor(timeoutMs: Long = 4_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    @Test fun theEngineOpensAndCloses() {
        assertTrue("the device refused to give us an audio output", engine.start())
        assertTrue(engine.isOpen)
        // Starting twice is harmless.
        assertTrue(engine.start())
        assertTrue(engine.engineSampleRate == 48_000 || engine.engineSampleRate == 44_100)
        assertTrue("latency estimate looks wrong", engine.outputLatencyMs in 1.0..200.0)
        engine.shutdown()
        assertFalse(engine.isOpen)
        // And it can be reopened.
        assertTrue(engine.start())
    }

    @Test fun playbackRunsAndFinishesOnItsOwn() {
        assertTrue(engine.start())
        val composition = composition()
        engine.prepare(composition)
        val schedule = Sequencer.build(composition, engine.engineSampleRate)
        engine.play(schedule)

        assertTrue(
            "playback never started",
            waitFor { engine.transportState.value == TransportState.PLAYING },
        )
        // The position advances.
        assertTrue(
            "the playhead never moved",
            waitFor { engine.playbackPosition.value.tick > 0 },
        )
        // And it stops by itself at the end rather than running for ever.
        val lengthSeconds = schedule.lengthFrames.toDouble() / engine.engineSampleRate
        assertTrue(
            "playback did not stop at the end",
            waitFor((lengthSeconds * 1000 + 3_000).toLong()) {
                engine.transportState.value == TransportState.STOPPED
            },
        )
        assertEquals(0L, engine.playbackPosition.value.tick)
    }

    @Test fun pauseAndResumeAndStop() {
        assertTrue(engine.start())
        val composition = composition()
        engine.prepare(composition)
        engine.play(Sequencer.build(composition, engine.engineSampleRate))
        waitFor { engine.playbackPosition.value.tick > 0 }

        engine.pause()
        assertEquals(TransportState.PAUSED, engine.transportState.value)
        val paused = engine.playbackPosition.value.tick
        Thread.sleep(250)
        assertEquals("the playhead moved while paused", paused, engine.playbackPosition.value.tick)

        engine.resume()
        assertTrue(waitFor { engine.playbackPosition.value.tick > paused })

        engine.stop()
        assertTrue(waitFor { engine.transportState.value == TransportState.STOPPED })
        assertEquals(0L, engine.playbackPosition.value.tick)
    }

    @Test fun loopingKeepsPlayingAndStaysInsideTheRegion() {
        assertTrue(engine.start())
        val composition = composition()
        engine.prepare(composition)
        val region = LoopRegion(0L, composition.timeSignature.barTicks.toLong())
        engine.play(Sequencer.build(composition, engine.engineSampleRate), region)

        // Over several bars' worth of time the playhead must never leave the region.
        val barSeconds = Ticks.toSeconds(region.endTick, composition.bpm)
        val deadline = System.currentTimeMillis() + (barSeconds * 2500).toLong() + 1500
        var sawProgress = false
        while (System.currentTimeMillis() < deadline) {
            val tick = engine.playbackPosition.value.tick
            if (tick > 0) sawProgress = true
            assertTrue("the playhead left the loop: $tick", tick <= region.endTick + 240)
            Thread.sleep(20)
        }
        assertTrue(sawProgress)
        // And it is still playing: a loop does not end.
        assertEquals(TransportState.PLAYING, engine.transportState.value)
    }

    @Test fun seekingMovesThePlayhead() {
        assertTrue(engine.start())
        val composition = composition()
        engine.prepare(composition)
        engine.play(Sequencer.build(composition, engine.engineSampleRate))
        waitFor { engine.transportState.value == TransportState.PLAYING }

        val target = composition.timeSignature.barTicks.toLong()
        engine.seekToTick(target)
        assertTrue(
            "the seek did not take effect",
            waitFor { engine.playbackPosition.value.tick >= target },
        )
    }

    @Test fun theCountInDelaysTheMusic() {
        assertTrue(engine.start())
        val composition = composition()
        engine.prepare(composition)
        engine.play(
            Sequencer.build(
                composition, engine.engineSampleRate, countInBars = 1, metronome = true,
            ),
        )
        assertTrue(
            "the count-in state was never reported",
            waitFor(2_000) {
                engine.transportState.value == TransportState.COUNTING_IN ||
                    engine.playbackPosition.value.countInBeatsRemaining > 0
            },
        )
        assertTrue(
            "playback never began after the count-in",
            waitFor(6_000) { engine.transportState.value == TransportState.PLAYING },
        )
    }

    @Test fun previewSoundsWithoutTheTransportRunning() {
        assertTrue(engine.start())
        engine.setPreviewVoice(InstrumentVoice.ELECTRIC_CLEAN)
        // A fretboard tap must make a sound even when nothing is playing.
        engine.preview(listOf(40, 45, 50, 55, 59, 64))
        Thread.sleep(300)
        assertEquals(TransportState.STOPPED, engine.transportState.value)
        // Previewing repeatedly must not accumulate state or throw.
        repeat(40) {
            engine.preview(listOf(40 + it % 24))
            Thread.sleep(10)
        }
        engine.panic()
        Thread.sleep(100)
    }

    @Test fun panicSilencesEverythingAndTheEngineStaysUsable() {
        assertTrue(engine.start())
        val composition = composition()
        engine.prepare(composition)
        engine.play(Sequencer.build(composition, engine.engineSampleRate))
        waitFor { engine.playbackPosition.value.tick > 0 }
        engine.panic()
        Thread.sleep(150)
        // Still able to play afterwards: a panic is not a shutdown.
        engine.play(Sequencer.build(composition, engine.engineSampleRate))
        assertTrue(waitFor { engine.playbackPosition.value.tick > 0 })
    }

    @Test fun changingTheMixWhilePlayingTakesEffectWithoutInterruption() {
        assertTrue(engine.start())
        var composition = composition()
        engine.prepare(composition)
        engine.play(Sequencer.build(composition, engine.engineSampleRate))
        waitFor { engine.playbackPosition.value.tick > 0 }

        // Mute, solo and change volumes mid-playback — the common gesture when checking
        // a harmony against its source. None of it may stop the transport.
        for (round in 0 until 6) {
            composition = CompositionEditor.setTrackMuted(composition, 1L, round % 2 == 0)
            composition = CompositionEditor.setTrackVolume(composition, 1L, 0.2f + 0.1f * round)
            engine.updateMix(composition)
            Thread.sleep(60)
            assertEquals(TransportState.PLAYING, engine.transportState.value)
        }
    }

    @Test fun preparingADifferentProjectWhilePlayingIsSafe() {
        assertTrue(engine.start())
        val first = composition()
        engine.prepare(first)
        engine.play(Sequencer.build(first, engine.engineSampleRate))
        waitFor { engine.playbackPosition.value.tick > 0 }

        // Switching projects is a track-list change: the buses are rebuilt underneath a
        // running render thread, which must not crash it.
        val second = Composition.newBassProject(Tunings.BASS_5_STANDARD).let {
            CompositionEditor.appendNoteAtPosition(it, 1L, 0, 5)
        }
        engine.prepare(second)
        engine.play(Sequencer.build(second, engine.engineSampleRate))
        assertTrue(waitFor { engine.playbackPosition.value.tick >= 0 })
        assertTrue(engine.isOpen)
    }

    @Test fun shutdownLeavesNoRenderThreadBehind() {
        assertTrue(engine.start())
        engine.prepare(composition())
        engine.play(Sequencer.build(composition(), engine.engineSampleRate))
        waitFor { engine.playbackPosition.value.tick > 0 }
        engine.shutdown()
        Thread.sleep(300)
        val alive = Thread.getAllStackTraces().keys.filter {
            it.name == "TabTap-Audio" && it.isAlive
        }
        assertTrue("the render thread outlived shutdown: $alive", alive.isEmpty())
        assertEquals(TransportState.STOPPED, engine.transportState.value)
    }

    @Test fun repeatedStartAndShutdownCyclesAreStable() {
        // Backgrounding and foregrounding the app repeatedly must not leak threads or
        // audio devices.
        repeat(5) {
            assertTrue(engine.start())
            engine.prepare(composition())
            engine.play(Sequencer.build(composition(), engine.engineSampleRate))
            Thread.sleep(120)
            engine.shutdown()
        }
        val alive = Thread.getAllStackTraces().keys.count { it.name == "TabTap-Audio" && it.isAlive }
        assertEquals(0, alive)
    }
}
