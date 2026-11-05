# Working on TabTap

## Always finish by building and uploading

Every change ends with a built, installable APK in the user's hands. Do not stop at
"the code compiles" or "the tests pass".

1. Run the tests: `./gradlew :core-music:test :core-io:test :app:testDebugUnitTest`
2. Bump `versionCode` and `versionName` in `app/build.gradle.kts`. Android will not
   install a build over one with the same `versionCode`, and the user needs to be able
   to tell builds apart.
3. Build both APKs: `./gradlew :app:assembleDebug :app:assembleRelease`
4. Sign the release APK with the **same key every time** — Android refuses an update
   signed with a different key, and the user would have to uninstall and lose their
   projects. If the keystore has been lost, say so plainly rather than silently
   generating a new one.
5. Send both APKs to the user, release first.
6. Publish a GitHub Release: trigger the `release.yml` workflow with the new tag.
   This session type cannot create releases directly (HTTP 403) or push tags, so the
   workflow is the route.

## This session cannot run the app

There is no emulator. The interface is verified by Robolectric tests that compose and
lay out every view, which catches crashes and layout overflow but not whether something
*looks* right or is findable. Treat "the user cannot see the change" as a real and
likely report, not a misunderstanding — it has happened, and the cause was controls
hidden behind unlabelled chips and a transport bar that dropped buttons below 420 dp.

When adding a feature, ask where a player would look for it, and put it there.

## Architecture rules that must not be broken

- **One document, four views.** The fretboard, tab, piano roll and score all read and
  write one `Composition`. Never add a second source of truth for the music.
- **Pitch is the truth; string and fret are a view of it.** A note always carries its
  sounding MIDI pitch. Its position is advisory and is recomputed when the tuning
  changes. Loading or importing must only fill in *missing* positions
  (`Track.withPositionsFilled`), never re-fret everything — that silently destroys
  fingerings the player chose.
- **Integer ticks at 960 per quarter.** No floating-point musical time anywhere.
- **The audio render loop allocates nothing** and takes no locks. Buffers are sized
  once, voices are pooled, and the UI talks to it through atomics and a lock-free queue.
- **One clock.** Notes, the metronome and the playhead are all positioned against the
  audio thread's frame counter. Anything timed against the transport position must cope
  with it not advancing while stopped — use the monotonic device clock instead.
- **Chord and note lengths are musical, not arbitrary.** Chord lengths come from
  `CompositionEditor.chordLengthOptions`, which is expressed in bars and beats of the
  project's metre. A whole note is a bar only in 4/4.
- **Every edit goes through `CompositionEditor`** and is pushed onto the `EditStack`, so
  undo, autosave and the audio mix cannot drift from the document.

## Testing expectations

Tests measure rather than assume. The audio tests recover a rendered note's pitch by
autocorrelation and compare it against the note that was asked for; filter responses are
measured by driving sines through them. Keep that standard: a test that only checks a
flag was set would not have caught the string model being 32 cents flat.

Every section reachable in the app must have an entry in `ui/help/HelpContent.kt`; there
is a test asserting this, because the help buttons look topics up by id.

## Known limitations to state honestly, not hide

No Guitar Pro 6/7/8 writer (undocumented container). No shipped soundfont — the
instruments are synthesised. Clefs and rests are drawn as outlines because Android has no
guaranteed music font. No engraved dynamics. The engraver is TabTap's own.
