# TabTap

A guitar and bass composition studio for Android: tap a riff or solo onto a virtual
fretboard, give it a rhythm, set a key, generate musically intelligent harmonies, then
edit the result as tablature or standard notation and export it.

## Modules

| Module | Platform | Contents |
| --- | --- | --- |
| `core-music` | Pure Kotlin/JVM | Pitch, tuning and fretboard maths, scales and keys, chord construction and voicing, rhythm and quantisation, the composition data model, the harmony engine, the notation layout engine. No Android dependencies, so all of it is unit-testable on the JVM. |
| `core-io` | Pure Kotlin/JVM | Standard MIDI File reading and writing, MusicXML, ASCII tab, Guitar Pro export. |
| `app` | Android | Jetpack Compose UI, Room persistence, the real-time audio engine, Storage Access Framework export, PDF output. |

## The single source of truth

Every view — fretboard, piano roll, tablature, score — reads and writes one
`Composition` object. A note always carries its sounding MIDI pitch; its string and fret
are a *view* of that pitch for a given tuning, recomputed when the tuning changes. That
is why editing a note in the tab moves it in the score, and why changing tuning keeps the
music and only changes the fingering.

Musical time is integer ticks at 960 ticks per quarter note, which divides exactly by 2,
3, 4, 5, 6, 8, 16 and 32 — so straight subdivisions, triplets and quintuplets are all
exact and MIDI export has no rounding error.

## Building

The build needs a JDK 17 or newer and an Android SDK with platform 35. Point `sdk.dir`
in `local.properties` at the SDK, then:

    ./gradlew :core-music:test :core-io:test   # pure JVM tests
    ./gradlew :app:assembleDebug               # build the app
