# TabTap

A guitar and bass composition studio for Android. Tap a riff or a solo onto a virtual
fretboard, give it a rhythm, set a key, generate musically intelligent harmonies, then
edit the result as tablature or standard notation and export it.

Everything works offline. There is no account, no subscription, and the app declares no
internet permission at all.

## What it does

**Fretboard.** 4- to 8-string instruments and arbitrary custom tunings, 12 to 30 frets,
with physically accurate fret spacing. Tap to record notes in the order tapped; two
fingers at once makes a chord. Overlays for note names, intervals, scale degrees, octaves,
the selected scale and its root. Pinch to change how much of the neck is visible.

**Composition.** Four views of one piece of music — fretboard, tablature, piano roll and
standard notation. An edit in any one of them appears in the others, because they are
views of a single document rather than four files.

**Rhythm.** A metronome with adjustable tempo, tap tempo, a visual beat indicator,
count-in, and time signatures from 2/4 to 12/8 and beyond, including compound and
irregular metres. Work either way round: enter the notes and then assign a rhythm, or tap
the rhythm first and fill in the pitches afterwards. Quantise to 1/4 down to 1/32,
straight, triplet, swing or shuffle, at any strength, with deterministic humanising.

**Harmony.** Diatonic harmonisation that works in scale-degree space, so harmonising in
thirds produces a mixture of major and minor thirds exactly where the key requires —
not a constant transposition. Several voices at once, with an explicit choice of how
out-of-key notes are treated and whether the harmony follows the chord progression.
Generated parts are fretted on the target instrument, moved by octaves when they will
not fit, and reported rather than silently dropped when they cannot be played.

**Chords.** 25 chord types plus custom ones, with voicings searched on the project's
actual tuning rather than looked up in a table of six-string shapes — so it works on a
7-string, a bass or anything you have invented. Diatonic suggestions with Roman numerals,
common progressions, inversions, strum and arpeggio patterns.

**Sound.** A synthesis engine, not a sample library: an extended Karplus–Strong digital
waveguide for the plucked strings, with per-track amplifier modelling for clean,
overdriven and distorted electric tones, body resonances for the acoustics, and a
reference piano. Bends, vibrato, palm muting, harmonics, dead notes and legato all affect
the sound, not just the page.

**Notation and tab.** A real engraver: key signatures, correct accidental handling,
ties across bar lines, conventional rests, beaming that follows the metre, stems, dots and
tuplets. Tab with techniques, bends, a rhythm ruler and automatic bar division.

**Export.** Standard MIDI files (format 0 and 1), MusicXML, Guitar Pro 5, ASCII tab and
printable PDF, written wherever you choose through the Storage Access Framework. MIDI and
MusicXML can be imported.

## Building

Requires a JDK 17 or newer and an Android SDK with platform 35. Point `sdk.dir` in
`local.properties` at your SDK, then:

```
./gradlew :core-music:test :core-io:test :app:testDebugUnitTest   # 404 tests, on the JVM
./gradlew :app:assembleDebug                                      # build the app
./gradlew :app:connectedAndroidTest                               # audio tests, needs a device
```

`./gradlew check` runs everything that does not need a device.

## Architecture

| Module | Platform | Contents |
| --- | --- | --- |
| `core-music` | Pure Kotlin/JVM | Pitch, tuning and fretboard maths; scales and keys; chord construction and voicing search; rhythm and quantisation; the composition data model; the harmony engine; the notation and tab layout engines. No Android dependencies, so all of it is unit-testable on the JVM. |
| `core-io` | Pure Kotlin/JVM | Standard MIDI File reading and writing, MusicXML, Guitar Pro 5, ASCII tab, and the export/import registry. |
| `app` | Android | Jetpack Compose UI, Room persistence, the real-time audio engine, SAF export, PDF output. |

### One document, four views

Every view reads and writes one `Composition`. A note always carries its sounding MIDI
pitch; its string and fret are a *view* of that pitch for a given tuning, recomputed when
the tuning changes. That is why editing a note in the tab moves it in the score, and why
retuning the instrument keeps the music and changes only the fingering.

Musical time is integer ticks at 960 per quarter note, which divides exactly by 2, 3, 4,
5, 6, 8, 16 and 32 — so straight subdivisions, triplets and quintuplets are all exact and
MIDI export has no rounding error anywhere between the fretboard and the file.

### Audio

One `AudioTrack` is fed from one dedicated thread at urgent-audio priority. That thread
owns a frame counter, and everything audible is positioned against it: the notes, the
metronome click, and the playhead the interface draws. Because there is only one clock,
the metronome cannot drift against playback.

The render loop allocates nothing — buffers are sized once, voices are pooled, and the
interface communicates with it only through atomics and a lock-free queue. A lock or an
allocation on that thread would eventually produce an audible dropout.

Distortion is applied to the sum of a track's notes rather than to each note separately,
because the intermodulation between them is most of what a distorted guitar sounds like.

### Persistence

Projects are stored in Room as one versioned JSON document plus indexed metadata for the
project list. A save rotates the previous document into a backup column inside a single
transaction, so an interrupted save cannot destroy the last good version. Loading tries
the main document, then the backup, then a repaired empty composition built from the
stored metadata — a damaged project opens with something to work from rather than an
error. Edits are autosaved on a short debounce and flushed again in `onStop`.

### Engraving

The notation layout is computed as pure data — positions in abstract staff spaces, with
no reference to Android — and turned into platform-neutral draw commands. A Compose
canvas executes them on screen and an Android canvas executes the identical list for PDF,
which is why the printed page matches what is on the screen instead of being a second
implementation that drifts from the first.

## Known limitations

These are deliberate decisions, documented rather than hidden:

- **Guitar Pro 6, 7 and 8 files are not written.** Those versions use an undocumented
  zipped-XML container. TabTap writes Guitar Pro 5.10, which all of them open, and
  MusicXML, which all of them import with full notation. Producing a file with a `.gp`
  extension and guessed contents would be worse than offering the formats that work.
- **No soundfont is shipped.** The instruments are synthesised. A plucked-string
  waveguide gets genuinely close to an electric guitar and responds to technique in a way
  samples do not, but it is not a multi-sampled library, and the piano in particular is a
  reference tone rather than a concert instrument.
- **Clefs, rests and flags are drawn as outlines.** Android's fonts do not contain the
  Unicode musical symbols for them and no SMuFL music font can be assumed present, so
  they are built from curves. The accidentals do use the system font, which has them.
- **Dynamic markings are not engraved.** Velocity is stored, exported to MIDI and shown
  in the piano roll, but `mf`/`cresc.` and the like are not printed on the score.
- **Notation is engraved by TabTap's own engine.** There is no mature, maintained
  engraving library for Android — the established ones are JavaScript and would mean
  shipping a WebView. The engine here implements the conventions that matter for guitar
  and bass music and is covered by tests; it is not a full engraving system.

## Tests

404 tests run on the JVM, covering note and frequency calculations, string/fret
conversion, tuning configuration, scale and interval calculations, harmony generation,
chord construction, rhythm calculations, quantisation, MIDI file generation, project
persistence against a real SQLite database, the audio DSP, the editing operations, the
interface at every window width, and the complete composition workflow end to end.

The audio tests measure rather than assume: a rendered note's pitch is recovered by
autocorrelation and compared against the note that was asked for, filter responses are
measured by driving sine waves through them, and decay, release and silence are checked
numerically. A further suite runs on a device to exercise the real audio output's
lifecycle, which cannot be done on the JVM.
