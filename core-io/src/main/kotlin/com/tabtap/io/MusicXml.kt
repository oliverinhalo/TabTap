package com.tabtap.io

import com.tabtap.music.Articulation
import com.tabtap.music.ClefType
import com.tabtap.music.Composition
import com.tabtap.music.Fretboard
import com.tabtap.music.InstrumentFamily
import com.tabtap.music.InstrumentVoice
import com.tabtap.music.Key
import com.tabtap.music.MusicNote
import com.tabtap.music.NoteLetter
import com.tabtap.music.NoteValue
import com.tabtap.music.Pitch
import com.tabtap.music.ScaleType
import com.tabtap.music.SpelledPitch
import com.tabtap.music.TempoMap
import com.tabtap.music.Ticks
import com.tabtap.music.TimeSignature
import com.tabtap.music.Track
import com.tabtap.music.TrackRole
import com.tabtap.music.Tuning
import com.tabtap.music.Tunings
import com.tabtap.music.notation.AccidentalGlyph
import com.tabtap.music.notation.EngravedChord
import com.tabtap.music.notation.EngravedMeasure
import com.tabtap.music.notation.ScoreEngraver
import com.tabtap.music.notation.StemDirection
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import kotlin.math.roundToInt

/**
 * MusicXML 4.0 (partwise) export and import.
 *
 * MusicXML is written from the engraved layout rather than straight from the note list,
 * so the file carries the same beams, ties, accidentals, rests and stem directions the
 * app displays — which is what makes it open correctly in MuseScore, Sibelius, Finale
 * and Guitar Pro instead of needing their importers to guess.
 *
 * Guitar and bass parts are exported with their `staff-details` tuning, `fret`/`string`
 * technical notation and the octave-transposing clef conventions, so a reader shows both
 * notation and tablature for the part.
 */
object MusicXmlExporter {

    /** Serialise [composition] as a MusicXML document. */
    fun export(composition: Composition, title: String = "Untitled"): String {
        val sb = StringBuilder(8192)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n")
        sb.append(
            "<!DOCTYPE score-partwise PUBLIC \"-//Recordare//DTD MusicXML 4.0 Partwise//EN\" " +
                "\"http://www.musicxml.org/dtds/partwise.dtd\">\n",
        )
        sb.append("<score-partwise version=\"4.0\">\n")

        sb.append("  <work>\n    <work-title>").append(esc(title)).append("</work-title>\n  </work>\n")
        sb.append("  <identification>\n")
        sb.append("    <encoding>\n      <software>TabTap</software>\n")
        sb.append("      <supports element=\"accidental\" type=\"yes\"/>\n")
        sb.append("      <supports element=\"beam\" type=\"yes\"/>\n")
        sb.append("      <supports element=\"stem\" type=\"yes\"/>\n")
        sb.append("    </encoding>\n  </identification>\n")

        val tracks = composition.tracks.ifEmpty { Composition.newGuitarProject().tracks }

        // Part list.
        sb.append("  <part-list>\n")
        tracks.forEachIndexed { i, t ->
            sb.append("    <score-part id=\"P${i + 1}\">\n")
            sb.append("      <part-name>").append(esc(t.name)).append("</part-name>\n")
            sb.append("      <part-abbreviation>").append(esc(t.name.take(4))).append("</part-abbreviation>\n")
            sb.append("      <score-instrument id=\"P${i + 1}-I1\">\n")
            sb.append("        <instrument-name>").append(esc(t.voice.displayName))
                .append("</instrument-name>\n")
            sb.append("      </score-instrument>\n")
            sb.append("      <midi-instrument id=\"P${i + 1}-I1\">\n")
            sb.append("        <midi-channel>").append(i + 1).append("</midi-channel>\n")
            sb.append("        <midi-program>").append(t.voice.gmProgram + 1).append("</midi-program>\n")
            sb.append("        <volume>").append((t.volume * 100).roundToInt()).append("</volume>\n")
            sb.append("        <pan>").append((t.pan * 90).roundToInt()).append("</pan>\n")
            sb.append("      </midi-instrument>\n")
            sb.append("    </score-part>\n")
        }
        sb.append("  </part-list>\n")

        tracks.forEachIndexed { i, track ->
            exportPart(sb, "P${i + 1}", track, composition)
        }

        sb.append("</score-partwise>\n")
        return sb.toString()
    }

    private fun exportPart(sb: StringBuilder, id: String, track: Track, composition: Composition) {
        val score = ScoreEngraver.engrave(
            track = track,
            key = composition.key,
            timeSignature = composition.timeSignature,
            tempoMap = composition.tempoMap,
            systemWidth = Double.MAX_VALUE / 4,
            chordProgression = composition.chordProgression,
            minMeasures = composition.barCount,
        )
        sb.append("  <part id=\"").append(id).append("\">\n")

        val measures = score.systems.flatMap { it.measures }
        measures.forEachIndexed { index, measure ->
            sb.append("    <measure number=\"").append(index + 1).append("\">\n")
            if (index == 0) {
                writeAttributes(sb, track, composition)
                writeTempo(sb, composition.tempoMap)
            }
            writeMeasureContent(sb, measure, track)
            sb.append("    </measure>\n")
        }
        sb.append("  </part>\n")
    }

    private fun writeAttributes(sb: StringBuilder, track: Track, composition: Composition) {
        val key = composition.key
        sb.append("      <attributes>\n")
        // Divisions are per quarter note; using the project's own resolution keeps
        // every duration an exact integer in the file.
        sb.append("        <divisions>").append(Ticks.PPQ).append("</divisions>\n")
        sb.append("        <key>\n          <fifths>").append(key.sharpsInSignature)
            .append("</fifths>\n          <mode>").append(if (isMinorMode(key)) "minor" else "major")
            .append("</mode>\n        </key>\n")
        sb.append("        <time>\n          <beats>").append(composition.timeSignature.beats)
            .append("</beats>\n          <beat-type>").append(composition.timeSignature.beatValue)
            .append("</beat-type>\n        </time>\n")
        sb.append("        <clef>\n")
        when (track.clef) {
            ClefType.TREBLE, ClefType.TREBLE_8VB -> sb.append("          <sign>G</sign>\n          <line>2</line>\n")
            ClefType.BASS, ClefType.BASS_8VB -> sb.append("          <sign>F</sign>\n          <line>4</line>\n")
        }
        // An octave-transposing clef is a *display* convention: guitar and bass are not
        // transposing instruments in MusicXML, so <pitch> below still holds the sounding
        // pitch and only the printed clef carries the "8" below it. This is what
        // MuseScore, Guitar Pro and Sibelius all write and expect.
        if (track.clef.writtenOctaveShift != 0) {
            sb.append("          <clef-octave-change>-1</clef-octave-change>\n")
        }
        sb.append("        </clef>\n")
        // Tuning, so a reader can render the part as tablature too.
        sb.append("        <staff-details>\n")
        sb.append("          <staff-lines>").append(track.tuning.stringCount).append("</staff-lines>\n")
        track.tuning.openStrings.reversed().forEachIndexed { i, pitch ->
            val sp = SpelledPitch.of(pitch)
            sb.append("          <staff-tuning line=\"").append(track.tuning.stringCount - i).append("\">\n")
            sb.append("            <tuning-step>").append(sp.letter.name).append("</tuning-step>\n")
            if (sp.alter != 0) sb.append("            <tuning-alter>").append(sp.alter).append("</tuning-alter>\n")
            sb.append("            <tuning-octave>").append(sp.octave).append("</tuning-octave>\n")
            sb.append("          </staff-tuning>\n")
        }
        sb.append("        </staff-details>\n")
        sb.append("      </attributes>\n")
    }

    private fun writeTempo(sb: StringBuilder, tempoMap: TempoMap) {
        sb.append("      <direction placement=\"above\">\n        <direction-type>\n")
        sb.append("          <metronome>\n            <beat-unit>quarter</beat-unit>\n")
        sb.append("            <per-minute>").append(tempoMap.initialBpm.roundToInt())
            .append("</per-minute>\n          </metronome>\n")
        sb.append("        </direction-type>\n        <sound tempo=\"")
            .append(tempoMap.initialBpm.roundToInt()).append("\"/>\n      </direction>\n")
    }

    private fun writeMeasureContent(sb: StringBuilder, measure: EngravedMeasure, track: Track) {
        // Interleave stacks and rests in time order; MusicXML is a sequential format.
        data class Item(val tick: Long, val chord: EngravedChord?, val rest: com.tabtap.music.notation.EngravedRest?)
        val items = (measure.chords.map { Item(it.startTick, it, null) } +
            measure.rests.map { Item(it.startTick, null, it) }).sortedBy { it.tick }

        for (item in items) {
            val chord = item.chord
            if (chord != null) {
                chord.chordSymbol?.let { writeHarmony(sb, it) }
                chord.noteheads.forEachIndexed { i, head ->
                    writeNote(sb, chord, head, i > 0, measure, track)
                }
            } else {
                val rest = item.rest!!
                sb.append("      <note>\n        <rest/>\n")
                sb.append("        <duration>").append(rest.durationTicks).append("</duration>\n")
                sb.append("        <type>").append(typeName(rest.value)).append("</type>\n")
                repeat(rest.value.dots) { sb.append("        <dot/>\n") }
                sb.append("      </note>\n")
            }
        }
    }

    private fun writeNote(
        sb: StringBuilder,
        stack: EngravedChord,
        head: com.tabtap.music.notation.EngravedNotehead,
        isChordMember: Boolean,
        measure: EngravedMeasure,
        track: Track,
    ) {
        sb.append("      <note>\n")
        if (isChordMember) sb.append("        <chord/>\n")
        // <pitch> holds the sounding pitch. The engraver gave us the *written* spelling,
        // which for an octave-transposing clef sits an octave above, so the octave is
        // brought back down here; the letter and alteration are unchanged by an octave.
        val sounding = SpelledPitch(
            head.writtenPitch.letter,
            head.writtenPitch.alter,
            head.writtenPitch.octave - track.clef.writtenOctaveShift / 12,
        )
        sb.append("        <pitch>\n")
        sb.append("          <step>").append(sounding.letter.name).append("</step>\n")
        if (sounding.alter != 0) sb.append("          <alter>").append(sounding.alter).append("</alter>\n")
        sb.append("          <octave>").append(sounding.octave).append("</octave>\n")
        sb.append("        </pitch>\n")
        sb.append("        <duration>").append(stack.durationTicks).append("</duration>\n")

        if (head.tieFromPrevious) sb.append("        <tie type=\"stop\"/>\n")
        if (head.tieToNext) sb.append("        <tie type=\"start\"/>\n")

        sb.append("        <type>").append(typeName(stack.value)).append("</type>\n")
        repeat(stack.value.dots) { sb.append("        <dot/>\n") }

        if (stack.value.isTuplet) {
            sb.append("        <time-modification>\n")
            sb.append("          <actual-notes>").append(stack.value.tupletNum).append("</actual-notes>\n")
            sb.append("          <normal-notes>").append(stack.value.tupletDen).append("</normal-notes>\n")
            sb.append("        </time-modification>\n")
        }

        head.accidental?.let {
            sb.append("        <accidental>").append(accidentalName(it)).append("</accidental>\n")
        }

        when (stack.stem) {
            StemDirection.UP -> sb.append("        <stem>up</stem>\n")
            StemDirection.DOWN -> sb.append("        <stem>down</stem>\n")
            StemDirection.NONE -> {}
        }

        // Beams, derived from the engraved layout.
        if (!isChordMember) {
            val index = measure.chords.indexOf(stack)
            for (beam in measure.beams.filter { index in it.chordIndices }) {
                val pos = beam.chordIndices.indexOf(index)
                val value = when {
                    beam.partial -> if (beam.partialForward) "forward hook" else "backward hook"
                    pos == 0 -> "begin"
                    pos == beam.chordIndices.lastIndex -> "end"
                    else -> "continue"
                }
                sb.append("        <beam number=\"").append(beam.level).append("\">")
                    .append(value).append("</beam>\n")
            }
        }

        // Notations: ties, techniques, string and fret.
        val note = track.note(head.noteId)
        val hasNotations = head.tieToNext || head.tieFromPrevious ||
            note?.string != null || !(note?.articulations.isNullOrEmpty())
        if (hasNotations) {
            sb.append("        <notations>\n")
            if (head.tieFromPrevious) sb.append("          <tied type=\"stop\"/>\n")
            if (head.tieToNext) sb.append("          <tied type=\"start\"/>\n")
            if (note != null) writeTechnical(sb, note, track)
            sb.append("        </notations>\n")
        }
        sb.append("      </note>\n")
    }

    private fun writeTechnical(sb: StringBuilder, note: MusicNote, track: Track) {
        val articulations = note.articulations
        if (note.string != null && note.fret != null) {
            sb.append("          <technical>\n")
            // MusicXML numbers strings from the highest sounding downwards.
            sb.append("            <string>").append(track.tuning.stringCount - note.string!!)
                .append("</string>\n")
            sb.append("            <fret>").append(note.fret).append("</fret>\n")
            if (Articulation.HAMMER_ON in articulations) {
                sb.append("            <hammer-on type=\"start\">H</hammer-on>\n")
            }
            if (Articulation.PULL_OFF in articulations) {
                sb.append("            <pull-off type=\"start\">P</pull-off>\n")
            }
            if (Articulation.HARMONIC in articulations) {
                sb.append("            <harmonic><natural/></harmonic>\n")
            }
            if (Articulation.PINCH_HARMONIC in articulations) {
                sb.append("            <harmonic><artificial/></harmonic>\n")
            }
            if (Articulation.TAP in articulations) sb.append("            <tap>T</tap>\n")
            if (Articulation.BEND in articulations) {
                sb.append("            <bend>\n              <bend-alter>")
                    .append(note.bendSemitones).append("</bend-alter>\n")
                if (Articulation.BEND_RELEASE in articulations) sb.append("              <release/>\n")
                sb.append("            </bend>\n")
            }
            sb.append("          </technical>\n")
        }
        val marks = ArrayList<String>()
        if (Articulation.STACCATO in articulations) marks += "<staccato/>"
        if (Articulation.ACCENT in articulations) marks += "<accent/>"
        if (marks.isNotEmpty()) {
            sb.append("          <articulations>")
            marks.forEach { sb.append(it) }
            sb.append("</articulations>\n")
        }
        val ornaments = ArrayList<String>()
        if (Articulation.VIBRATO in articulations || Articulation.WIDE_VIBRATO in articulations) {
            ornaments += "<wavy-line type=\"start\"/>"
        }
        if (Articulation.TRILL in articulations) ornaments += "<trill-mark/>"
        if (ornaments.isNotEmpty()) {
            sb.append("          <ornaments>")
            ornaments.forEach { sb.append(it) }
            sb.append("</ornaments>\n")
        }
        if (Articulation.SLIDE_LEGATO in articulations || Articulation.SLIDE_UP in articulations ||
            Articulation.SLIDE_DOWN in articulations
        ) {
            sb.append("          <slide type=\"start\"/>\n")
        }
    }

    private fun writeHarmony(sb: StringBuilder, symbol: String) {
        // Split a symbol like "F#m7" or "C/G" into root, kind and bass.
        val slash = symbol.indexOf('/')
        val head = if (slash > 0) symbol.substring(0, slash) else symbol
        val bass = if (slash > 0) symbol.substring(slash + 1) else null
        val rootLetter = head.firstOrNull() ?: return
        var i = 1
        var alter = 0
        while (i < head.length && (head[i] == '#' || head[i] == 'b')) {
            alter += if (head[i] == '#') 1 else -1
            i++
        }
        val suffix = head.substring(i)
        sb.append("      <harmony>\n        <root>\n          <root-step>").append(rootLetter)
            .append("</root-step>\n")
        if (alter != 0) sb.append("          <root-alter>").append(alter).append("</root-alter>\n")
        sb.append("        </root>\n        <kind text=\"").append(esc(suffix)).append("\">")
            .append(kindFor(suffix)).append("</kind>\n")
        if (bass != null && bass.isNotEmpty()) {
            sb.append("        <bass>\n          <bass-step>").append(bass.first())
                .append("</bass-step>\n")
            val bAlter = bass.drop(1).count { it == '#' } - bass.drop(1).count { it == 'b' }
            if (bAlter != 0) sb.append("          <bass-alter>").append(bAlter).append("</bass-alter>\n")
            sb.append("        </bass>\n")
        }
        sb.append("      </harmony>\n")
    }

    private fun kindFor(suffix: String): String = when (suffix) {
        "" -> "major"
        "m" -> "minor"
        "5" -> "power"
        "dim" -> "diminished"
        "aug" -> "augmented"
        "sus2" -> "suspended-second"
        "sus4" -> "suspended-fourth"
        "maj7" -> "major-seventh"
        "m7" -> "minor-seventh"
        "7" -> "dominant"
        "mMaj7" -> "major-minor"
        "m7b5" -> "half-diminished"
        "dim7" -> "diminished-seventh"
        "6" -> "major-sixth"
        "m6" -> "minor-sixth"
        "maj9" -> "major-ninth"
        "m9" -> "minor-ninth"
        "9" -> "dominant-ninth"
        "add9" -> "major"
        "7sus4" -> "dominant"
        "13" -> "dominant-13th"
        "maj11" -> "major-11th"
        else -> "other"
    }

    internal fun typeName(value: NoteValue): String = when (value.divisionLevel) {
        0 -> "whole"; 1 -> "half"; 2 -> "quarter"; 3 -> "eighth"
        4 -> "16th"; 5 -> "32nd"; else -> "64th"
    }

    private fun accidentalName(a: AccidentalGlyph): String = when (a) {
        AccidentalGlyph.DOUBLE_FLAT -> "flat-flat"
        AccidentalGlyph.FLAT -> "flat"
        AccidentalGlyph.NATURAL -> "natural"
        AccidentalGlyph.SHARP -> "sharp"
        AccidentalGlyph.DOUBLE_SHARP -> "double-sharp"
    }

    private fun isMinorMode(key: Key): Boolean =
        key.scale.id.contains("minor") || key.scale.id == "blues" ||
            key.scale.id in setOf("dorian", "phrygian", "aeolian", "locrian")

    private fun esc(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}

/** The outcome of importing a MusicXML document. */
data class MusicXmlImportResult(
    val composition: Composition,
    val title: String?,
    val warnings: List<String>,
)

/**
 * MusicXML import.
 *
 * Reads score-partwise documents: parts become tracks, `divisions` is rescaled to the
 * project's tick resolution, ties are rejoined into single sounding notes, and
 * `string`/`fret` technical notation is used directly when present so an imported tab
 * keeps the fingering its author chose.
 */
object MusicXmlImporter {

    fun import(
        xml: String,
        tuning: Tuning = Tunings.STANDARD_E,
        fretCount: Int = 24,
    ): MusicXmlImportResult {
        val warnings = ArrayList<String>()
        val factory = DocumentBuilderFactory.newInstance().apply {
            // MusicXML references an external DTD; never fetch it, and refuse entities.
            isValidating = false
            isNamespaceAware = false
            setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", false) }
        }
        val doc = factory.newDocumentBuilder().parse(xml.byteInputStream())
        doc.documentElement.normalize()

        val root = doc.documentElement
        if (root.tagName != "score-partwise") {
            if (root.tagName == "score-timewise") {
                throw MusicXmlParseException(
                    "This is a timewise MusicXML file. TabTap reads the partwise form, " +
                        "which is what every editor exports by default.",
                )
            }
            throw MusicXmlParseException("Not a MusicXML document (root element is <${root.tagName}>)")
        }

        val title = root.firstChild("work")?.firstChild("work-title")?.textContent?.trim()
            ?: root.firstChild("movement-title")?.textContent?.trim()

        // Part names and programs from the part list.
        val partNames = HashMap<String, String>()
        val partPrograms = HashMap<String, Int>()
        root.firstChild("part-list")?.children("score-part")?.forEach { sp ->
            val id = sp.getAttribute("id")
            sp.firstChild("part-name")?.textContent?.trim()?.let { partNames[id] = it }
            sp.firstChild("midi-instrument")?.firstChild("midi-program")
                ?.textContent?.trim()?.toIntOrNull()?.let { partPrograms[id] = it - 1 }
        }

        var bpm = 120.0
        var timeSignature = TimeSignature.FOUR_FOUR
        var fifths = 0
        var minor = false
        var nextId = 1L
        var trackId = 1L
        val tracks = ArrayList<Track>()
        val board = Fretboard(tuning, fretCount)

        for (part in root.children("part")) {
            val partId = part.getAttribute("id")
            var divisions = 1
            val notes = ArrayList<MusicNote>()
            // Pending ties, keyed by sounding pitch.
            val openTies = HashMap<Int, MusicNote>()
            var tick = 0L
            // Semitones to add to a written pitch to get the sounding pitch, from
            // <transpose>. Zero for guitar and bass, which are not transposing in MusicXML.
            var soundingShift = 0
            var anchor = Fretboard.DEFAULT_ANCHOR_FRET
            var partStringCount: Int? = null

            for (measure in part.children("measure")) {
                for (child in measure.elementChildren()) {
                    when (child.tagName) {
                        "attributes" -> {
                            child.firstChild("divisions")?.textContent?.trim()?.toIntOrNull()
                                ?.let { if (it > 0) divisions = it }
                            child.firstChild("key")?.firstChild("fifths")?.textContent?.trim()
                                ?.toIntOrNull()?.let { fifths = it }
                            child.firstChild("key")?.firstChild("mode")?.textContent?.trim()
                                ?.let { minor = it.equals("minor", ignoreCase = true) }
                            child.firstChild("time")?.let { t ->
                                val beats = t.firstChild("beats")?.textContent?.trim()?.toIntOrNull()
                                val beatType = t.firstChild("beat-type")?.textContent?.trim()?.toIntOrNull()
                                if (beats != null && beatType != null) {
                                    timeSignature = runCatching { TimeSignature(beats, beatType) }
                                        .getOrElse {
                                            warnings += "Unsupported time signature $beats/$beatType; using 4/4"
                                            TimeSignature.FOUR_FOUR
                                        }
                                }
                            }
                            // clef-octave-change is a printing convention and does not
                            // change the sounding pitch, so it is deliberately ignored.
                            // <transpose> does, and is read instead.
                            child.firstChild("transpose")?.let { tr ->
                                val octaves = tr.firstChild("octave-change")
                                    ?.textContent?.trim()?.toIntOrNull() ?: 0
                                val chromatic = tr.firstChild("chromatic")
                                    ?.textContent?.trim()?.toIntOrNull() ?: 0
                                soundingShift = octaves * 12 + chromatic
                            }
                            child.firstChild("staff-details")?.firstChild("staff-lines")
                                ?.textContent?.trim()?.toIntOrNull()?.let { partStringCount = it }
                        }
                        "direction" -> {
                            child.firstChild("sound")?.getAttribute("tempo")?.toDoubleOrNull()
                                ?.let { if (it in 20.0..400.0) bpm = it }
                            child.firstChild("direction-type")?.firstChild("metronome")
                                ?.firstChild("per-minute")?.textContent?.trim()?.toDoubleOrNull()
                                ?.let { if (it in 20.0..400.0) bpm = it }
                        }
                        "backup" -> {
                            val d = child.firstChild("duration")?.textContent?.trim()?.toIntOrNull() ?: 0
                            tick = (tick - scale(d, divisions)).coerceAtLeast(0L)
                        }
                        "forward" -> {
                            val d = child.firstChild("duration")?.textContent?.trim()?.toIntOrNull() ?: 0
                            tick += scale(d, divisions)
                        }
                        "note" -> {
                            val duration = child.firstChild("duration")?.textContent?.trim()?.toIntOrNull() ?: 0
                            val ticks = scale(duration, divisions).toInt()
                            val isChordMember = child.firstChild("chord") != null
                            val isRest = child.firstChild("rest") != null
                            val isGrace = child.firstChild("grace") != null

                            if (isRest) {
                                tick += ticks
                                continue
                            }
                            val pitchElement = child.firstChild("pitch")
                            if (pitchElement == null) {
                                // An unpitched note (percussion) cannot be fretted.
                                if (!isChordMember) tick += ticks
                                continue
                            }
                            val step = pitchElement.firstChild("step")?.textContent?.trim() ?: "C"
                            val alter = pitchElement.firstChild("alter")?.textContent?.trim()?.toIntOrNull() ?: 0
                            val octave = pitchElement.firstChild("octave")?.textContent?.trim()?.toIntOrNull() ?: 4
                            val letter = runCatching { NoteLetter.valueOf(step.uppercase()) }.getOrNull()
                            if (letter == null) {
                                warnings += "Skipped a note with an unreadable step '$step'"
                                if (!isChordMember) tick += ticks
                                continue
                            }
                            val sounding = SpelledPitch(letter, alter, octave).midi + soundingShift
                            if (!Pitch.isValid(sounding)) {
                                warnings += "Skipped a note outside the MIDI range"
                                if (!isChordMember) tick += ticks
                                continue
                            }
                            if (isGrace) {
                                // Grace notes have no duration of their own; skip rather than mistime.
                                continue
                            }

                            val start = if (isChordMember) {
                                (notes.lastOrNull()?.startTick ?: tick)
                            } else {
                                tick
                            }

                            val tieStart = child.children("tie").any { it.getAttribute("type") == "start" }
                            val tieStop = child.children("tie").any { it.getAttribute("type") == "stop" }

                            if (tieStop) {
                                val open = openTies.remove(sounding)
                                if (open != null) {
                                    // Extend the held note rather than creating a new one.
                                    val index = notes.indexOfFirst { it.id == open.id }
                                    val extended = open.copy(
                                        durationTicks = ((start + ticks) - open.startTick).toInt()
                                            .coerceAtLeast(1),
                                    )
                                    if (index >= 0) notes[index] = extended
                                    if (tieStart) openTies[sounding] = extended
                                    if (!isChordMember) tick = start + ticks
                                    continue
                                }
                            }

                            val technical = child.firstChild("notations")?.firstChild("technical")
                            val xmlString = technical?.firstChild("string")?.textContent?.trim()?.toIntOrNull()
                            val xmlFret = technical?.firstChild("fret")?.textContent?.trim()?.toIntOrNull()
                            // MusicXML counts strings from the highest downwards.
                            var stringIndex = xmlString?.let { tuning.stringCount - it }
                            var fret = xmlFret
                            val consistent = stringIndex != null && fret != null &&
                                stringIndex in 0 until tuning.stringCount && fret in 0..fretCount &&
                                board.pitchAt(stringIndex, fret) == sounding
                            if (!consistent) {
                                val pos = board.bestPositionFor(sounding, anchor)
                                stringIndex = pos?.string
                                fret = pos?.fret
                            }
                            if (fret != null) anchor = fret

                            val note = MusicNote(
                                id = nextId++,
                                pitch = sounding,
                                startTick = start,
                                durationTicks = ticks.coerceAtLeast(1),
                                velocity = MusicNote.DEFAULT_VELOCITY,
                                string = stringIndex,
                                fret = fret,
                                articulations = articulationsOf(child),
                                tiedToNext = tieStart,
                            )
                            notes += note
                            if (tieStart) openTies[sounding] = note
                            if (!isChordMember) tick = start + ticks
                        }
                    }
                }
            }

            if (notes.isEmpty()) continue
            val unplayable = notes.count { it.fret == null }
            if (unplayable > 0) {
                warnings += "${partNames[partId] ?: partId}: $unplayable note(s) are outside " +
                    "the range of ${tuning.displayName}"
            }
            if (partStringCount != null && partStringCount != tuning.stringCount) {
                warnings += "${partNames[partId] ?: partId} was written for " +
                    "$partStringCount strings; re-fretted for ${tuning.displayName}"
            }
            tracks += Track(
                id = trackId++,
                name = partNames[partId] ?: "Part $partId",
                role = if (tracks.isEmpty()) TrackRole.LEAD else TrackRole.HARMONY,
                voice = voiceFor(partPrograms[partId], tuning.family),
                tuning = tuning,
                fretCount = fretCount,
                notes = notes,
                clef = ClefType.defaultFor(tuning.family),
                colorSlot = tracks.size % 6,
            )
        }

        if (tracks.isEmpty()) warnings += "The document contained no notes"

        val key = Key.of(
            keyName(fifths, minor),
            if (minor) ScaleType.NATURAL_MINOR else ScaleType.MAJOR,
        ) ?: Key.C_MAJOR

        val composition = Composition(
            tempoMap = TempoMap.of(bpm),
            timeSignature = timeSignature,
            key = key,
            tracks = tracks.ifEmpty { Composition.newGuitarProject(tuning).tracks },
            activeTrackId = 1L,
            nextId = nextId + 1,
        ).repaired()

        return MusicXmlImportResult(composition, title, warnings)
    }

    private fun scale(duration: Int, divisions: Int): Long =
        (duration.toLong() * Ticks.PPQ) / divisions.coerceAtLeast(1)

    private fun articulationsOf(note: Element): Set<Articulation> {
        val out = HashSet<Articulation>()
        val notations = note.firstChild("notations") ?: return out
        notations.firstChild("technical")?.let { tech ->
            if (tech.firstChild("hammer-on") != null) out += Articulation.HAMMER_ON
            if (tech.firstChild("pull-off") != null) out += Articulation.PULL_OFF
            if (tech.firstChild("tap") != null) out += Articulation.TAP
            if (tech.firstChild("bend") != null) out += Articulation.BEND
            tech.firstChild("harmonic")?.let { h ->
                out += if (h.firstChild("artificial") != null) {
                    Articulation.PINCH_HARMONIC
                } else {
                    Articulation.HARMONIC
                }
            }
        }
        notations.firstChild("articulations")?.let { a ->
            if (a.firstChild("staccato") != null) out += Articulation.STACCATO
            if (a.firstChild("accent") != null) out += Articulation.ACCENT
        }
        notations.firstChild("ornaments")?.let { o ->
            if (o.firstChild("wavy-line") != null) out += Articulation.VIBRATO
            if (o.firstChild("trill-mark") != null) out += Articulation.TRILL
        }
        if (notations.firstChild("slide") != null) out += Articulation.SLIDE_LEGATO
        if (notations.firstChild("glissando") != null) out += Articulation.SLIDE_LEGATO
        return out
    }

    private fun voiceFor(program: Int?, family: InstrumentFamily): InstrumentVoice {
        if (program == null) return InstrumentVoice.defaultFor(family)
        return InstrumentVoice.entries.firstOrNull { it.gmProgram == program }
            ?: InstrumentVoice.defaultFor(family)
    }

    private fun keyName(fifths: Int, minor: Boolean): String {
        val majors = listOf("Cb", "Gb", "Db", "Ab", "Eb", "Bb", "F", "C", "G", "D", "A", "E", "B", "F#", "C#")
        val minors = listOf("Ab", "Eb", "Bb", "F", "C", "G", "D", "A", "E", "B", "F#", "C#", "G#", "D#", "A#")
        val i = (fifths + 7).coerceIn(0, 14)
        return if (minor) minors[i] else majors[i]
    }

    // --- Small DOM helpers, so the parsing code above stays readable. ---

    private fun Element.elementChildren(): List<Element> {
        val out = ArrayList<Element>()
        val nodes = childNodes
        for (i in 0 until nodes.length) {
            val n = nodes.item(i)
            if (n.nodeType == Node.ELEMENT_NODE) out += n as Element
        }
        return out
    }

    private fun Element.children(tag: String): List<Element> =
        elementChildren().filter { it.tagName == tag }

    private fun Element.firstChild(tag: String): Element? =
        elementChildren().firstOrNull { it.tagName == tag }
}

/** Thrown when a document is not readable MusicXML. */
class MusicXmlParseException(message: String) : Exception(message)
