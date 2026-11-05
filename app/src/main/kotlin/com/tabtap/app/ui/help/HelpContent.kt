package com.tabtap.app.ui.help

/**
 * The in-app help.
 *
 * Every section of the app has an entry here explaining what it is *for*, not just what
 * the controls are called — a fretboard app is full of things that are obvious once you
 * know and opaque until you do. The text lives in one place so it can be shown from a
 * question mark on each screen and browsed as a whole.
 */
data class HelpTopic(
    val id: String,
    val title: String,
    /** One line: what this part of the app is for. */
    val summary: String,
    /** How to use it, in order. */
    val steps: List<String> = emptyList(),
    /** Things that are easy to miss. */
    val tips: List<String> = emptyList(),
)

object Help {

    /** The walkthrough shown on an empty project and from the help index. */
    val gettingStarted = HelpTopic(
        id = "start",
        title = "Making a harmonised song",
        summary = "The whole workflow, in the order you would actually do it.",
        steps = listOf(
            "**Play** — tap notes on the fretboard. They record in the order you tap " +
                "them, so tap the line as you would play it. Two fingers at once makes a " +
                "chord.",
            "**Notes** — give the line a rhythm. Every note has a length you can change " +
                "with its − and +, and the strip at the top sets them all at once: tap " +
                "1/8 and the whole line becomes eighth notes.",
            "**Setup** — set the key. This never moves your notes; it tells the harmony " +
                "which scale to follow.",
            "**Harmony** — tap Harmonise. Pick an interval, a 3rd above being the classic " +
                "twin-guitar sound, and it generates a second part that follows your line.",
            "**Harmony** — choose what you hear: everything, or just the harmony so you " +
                "can play the original yourself.",
            "**Chords** — add a backing underneath, if you want one.",
            "**Tab** or **Score** — read the result. Both show every part.",
            "**Export** — save it. Everything, or just one part.",
        ),
        tips = listOf(
            "Each page does one job, because there is not much height on a phone. The " +
                "strip down the side (or along the bottom in portrait) moves between them.",
            "Nothing is ever lost when you move between pages — Play, Notes, Tab and " +
                "Score are all views of the same music.",
            "Undo is always on the top bar, and the project saves itself as you work.",
        ),
    )

    val topics: List<HelpTopic> = listOf(
        gettingStarted,

        HelpTopic(
            id = "projects",
            title = "Projects",
            summary = "Your songs. Each one remembers its own instrument, tuning, key and tempo.",
            steps = listOf(
                "New project: pick guitar or bass, how many strings, a tuning, a key and a tempo. " +
                    "Every choice has a sensible default, so you can just tap Create.",
                "Tap a project to open it. The most recent one opens automatically next time.",
            ),
            tips = listOf(
                "Rename, duplicate and delete are the icons on each row.",
                "Import brings in a MIDI or MusicXML file as a new project.",
            ),
        ),

        HelpTopic(
            id = "fretboard",
            title = "Fretboard",
            summary = "Where you play the music in. Tapping a fret records that note.",
            steps = listOf(
                "Tap a fret to add that note to the end of the line, and hear it.",
                "Two fingers at once stacks the notes into a chord.",
                "Press and hold a fret to hear it without recording it.",
                "Pinch to show more or less of the neck; drag sideways to move along it.",
            ),
            tips = listOf(
                "The board fills the whole page, with one strip underneath for the note " +
                    "length, the part you are writing into, and undo.",
                "The numbers in the dots are the order the notes will play in.",
                "The faint dots are the notes of the key you have chosen — handy for " +
                    "staying in key while you work out a line.",
                "Setup turns note names, intervals, scale degrees and left-handed layout " +
                    "on and off.",
            ),
        ),

        HelpTopic(
            id = "rhythm",
            title = "Rhythm and the metronome",
            summary = "How long each note lasts, and the click to play against.",
            steps = listOf(
                "The quickest way: on the **Notes** page, tap 1/4, 1/8 or 1/16 at the top " +
                    "to put the whole line on that rhythm.",
                "For one note only, use the − and + on its row.",
                "For a rhythm you cannot tap out as a grid, go to **Setup** and record it: " +
                    "the click runs, you tap the rhythm, then apply it to your notes.",
                "You can also work the other way round — record a rhythm first, keep it, " +
                    "then tap notes and each one takes the next slot.",
            ),
            tips = listOf(
                "Quantise tidies up timing you played by hand. Strength below 100% tightens " +
                    "it without making it robotic.",
                "Swing and triplet grids are under Rhythm, along with the count-in.",
            ),
        ),

        HelpTopic(
            id = "key",
            title = "Key and scale",
            summary = "What scale the song is in, on the Setup page. This is what makes " +
                "the harmony musical.",
            steps = listOf(
                "Pick a root note and a scale.",
                "Detect key guesses it from the notes you have played.",
            ),
            tips = listOf(
                "Changing the key never moves your notes. It changes what gets highlighted " +
                    "and how the harmony is worked out. Transpose here is the separate " +
                    "button that does move them.",
                "The readout tells you how many of your notes fall outside the key.",
            ),
        ),

        HelpTopic(
            id = "harmony",
            title = "Harmony",
            summary = "Generates a second part that follows your line, in the key you chose.",
            steps = listOf(
                "Play a line in on the Play page first, and set the key in Setup.",
                "Tap Harmonise and choose an interval. A 3rd above is the usual twin-guitar " +
                    "harmony; a 6th below is the other classic.",
                "You can pick several at once to get several harmony parts.",
                "Generate. The new part appears as its own track, in its own colour.",
            ),
            tips = listOf(
                "Diatonic means the harmony bends to the key — in E minor, thirds come out " +
                    "major or minor exactly where the key needs them, which is what makes it " +
                    "sound right rather than parallel. Parallel moves everything by the same " +
                    "interval, which is sometimes what you want.",
                "Octave moves the whole harmony part up or down, to suit the instrument.",
                "If you edit your original line afterwards, Update regenerates the harmony " +
                    "to match.",
                "Generated notes are fretted for you, and anything that will not fit on the " +
                    "neck is moved by an octave rather than dropped.",
            ),
        ),

        HelpTopic(
            id = "playalong",
            title = "Play along",
            summary = "Choose which parts you hear, so you can play the other one yourself.",
            steps = listOf(
                "Everything: the whole arrangement.",
                "I play the lead: you hear the harmony and the chords, and play the original " +
                    "yourself on your own guitar.",
                "I play the harmony: the other way round.",
                "Just the chords: a backing to improvise over.",
            ),
            tips = listOf(
                "Loop the whole section and it will go round and round while you practise.",
                "The mixer at the bottom of the Harmony page has per-part volume, pan, " +
                    "mute and solo, and a Room slider for how much space is around the sound.",
            ),
        ),

        HelpTopic(
            id = "chords",
            title = "Chords",
            summary = "A chord backing that plays under your parts and exports with them.",
            steps = listOf(
                "Pick a root and a chord type, or tap one of the chords listed for your key.",
                "Choose a length in bars or beats, and a strum or arpeggio.",
                "Add it to the progression. Chords play one after another with no gaps.",
            ),
            tips = listOf(
                "The diagrams are worked out for your actual tuning, not copied from a " +
                    "six-string chord book — so they are right on a 7-string or a bass.",
                "In the progression list you can move chords up and down, repeat them, " +
                    "halve or double their length, change the strum, or tap Edit to change " +
                    "the chord itself.",
                "Chords in <your key> are the ones that will sound right together. The " +
                    "progression buttons add a whole familiar sequence in one tap.",
                "To notes writes the progression into a track so you can edit it note by note.",
            ),
        ),

        HelpTopic(
            id = "tab",
            title = "Tab",
            summary = "Your music written as guitar tablature: which fret on which string.",
            steps = listOf(
                "All parts shows every track on its own tab staff — your line and each " +
                    "harmony — which is how you would read a harmonised arrangement.",
                "Tap a staff to make that part the one you are editing.",
                "Tap a fret number to select it; double-tap to raise it by a fret.",
                "Tap an empty spot on a line to add a note there.",
            ),
            tips = listOf(
                "The − and + at the top of the page make the tab bigger or smaller.",
                "The marks under the staff are the rhythm: a stem per note, beams for " +
                    "eighths and shorter.",
                "Editing here changes the actual music, so the fretboard and the score " +
                    "change with it.",
                "Techniques — bends, slides, hammer-ons, palm muting — are written the way " +
                    "tab normally writes them. Select a note and use Technique to add them.",
            ),
        ),

        HelpTopic(
            id = "pianoroll",
            title = "Piano roll",
            summary = "Your music as bars on a grid: pitch up the side, time across.",
            steps = listOf(
                "Tap an empty spot to add a note there.",
                "Press and hold a bar, then drag it to move it in pitch and time.",
                "Press and hold near a bar's right-hand edge and drag to change its length.",
                "Double-tap anywhere to move the playhead there.",
            ),
            tips = listOf(
                "This is the easiest view for fixing timing, because you can see the beat " +
                    "grid behind the notes.",
                "The other parts are shown faded behind the one you are editing.",
                "A note's brightness is how hard it is played.",
            ),
        ),

        HelpTopic(
            id = "score",
            title = "Score",
            summary = "Your music as ordinary sheet music — the same notes as the tab, written " +
                "on a stave instead of on strings.",
            steps = listOf(
                "Every part gets its own stave, in its own colour, so you can read your line " +
                    "and its harmony together.",
                "Tap a notehead to select it. The Note panel then changes its length, " +
                    "technique or velocity.",
                "Press and hold a notehead and drag it up or down to move it to the next " +
                    "line or space, staying in the key.",
            ),
            tips = listOf(
                "This is for reading and for printing — it is the view to use if you want " +
                    "sheet music rather than tab, or if someone else needs to read your part.",
                "The − and + at the top of the page make the score bigger or smaller.",
                "The key signature, the accidentals, the beams, the rests and the ties are " +
                    "all worked out from your notes; you do not place them.",
                "Guitar and bass music is written an octave above where it sounds, which is " +
                    "why the clef has a little 8 under it. That is correct, not a mistake.",
                "Export as PDF to print exactly what you see here.",
            ),
        ),

        HelpTopic(
            id = "instrument",
            title = "Instrument and tuning",
            summary = "On the Setup page: guitar or bass, how many strings, tuned how, and " +
                "what it sounds like.",
            steps = listOf(
                "Switch between guitar and bass, or change the string count, at any time.",
                "Pick a tuning preset, or tune each string by itself.",
                "Choose the sound: clean, overdriven, distorted, acoustic, bass or piano.",
            ),
            tips = listOf(
                "Retuning never changes your music — it keeps every note and works out new " +
                    "fingerings, exactly like retuning a real guitar.",
                "If a note will not fit the new tuning it keeps its pitch and still plays; " +
                    "the panel tells you how many.",
            ),
        ),

        HelpTopic(
            id = "transport",
            title = "Playing and looping",
            summary = "The bar at the top: play, stop, loop, tempo, position and undo.",
            steps = listOf(
                "The bar across the top of every page: it stays put as you move between " +
                    "them, so playback is always one tap away.",
                "Play starts from the beginning, or from the loop if one is set.",
                "The loop button sets a loop; its menu chooses what the loop covers — the " +
                    "whole section, this bar, two or four bars, or your selection.",
                "The position reads as bar . beat, counting from one.",
            ),
            tips = listOf(
                "The row of lamps is the beat indicator: one per beat of the bar, the wider " +
                    "one being the downbeat.",
                "Tap the tempo number to tap a tempo in.",
                "The metronome button turns the click on and off during playback.",
            ),
        ),

        HelpTopic(
            id = "export",
            title = "Export",
            summary = "Saving your music as a file you can use somewhere else.",
            steps = listOf(
                "Choose which parts to export. Harmony only saves just the generated part.",
                "Choose a format — each one says what it carries.",
                "Pick where to save it. It goes wherever you choose on your phone.",
            ),
            tips = listOf(
                "MIDI (multi-track) is the one for a recording app: each part arrives as its " +
                    "own track with its own instrument.",
                "Guitar Pro 5 and MusicXML both open in Guitar Pro, MuseScore and the like, " +
                    "with the tab and the notation intact.",
                "PDF prints the notation and the tab exactly as you see them.",
                "ASCII tab is the plain-text kind you paste into a forum post.",
            ),
        ),
    )

    fun byId(id: String): HelpTopic? = topics.firstOrNull { it.id == id }
}
