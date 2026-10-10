package com.hivecore.node.media

import com.hivecore.engine.text.Stitch
import com.hivecore.engine.text.SegmentText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VadStitchTrackerTest {
    /** Loud "speech" everywhere except 100 ms quiet gaps at the given seconds. */
    private fun audio(seconds: Int, gapsAt: List<Double>): ShortArray {
        val pcm = ShortArray(seconds * 16000) { if (it % 2 == 0) 3000 else -3000 }
        for (g in gapsAt) { val s = (g * 16000).toInt(); for (i in s until minOf(pcm.size, s + 1600)) pcm[i] = 0 }
        return pcm
    }

    @Test fun shortAudioIsOneSegment() {
        val s = Vad.split(audio(12, emptyList()))
        assertEquals(1, s.size); assertEquals(0, s[0].start); assertEquals(12 * 16000, s[0].end); assertTrue(s[0].speech)
    }

    @Test fun splitsAtQuietPointsWithOverlap() {
        val pcm = audio(70, listOf(27.0, 55.5))
        val s = Vad.split(pcm)
        assertEquals(3, s.size)
        assertEquals(1354 * 320, s[0].end)                       // cut at the last quiet frame of the gap (ties → latest)
        assertEquals(s[0].end - 16000, s[1].start)               // 1 s overlap
        assertEquals(2779 * 320, s[1].end)
        assertEquals(s[1].end - 16000, s[2].start)
        assertEquals(pcm.size, s[2].end)
        s.forEach { assertTrue(it.end - it.start <= 30 * 16000) }
        assertEquals(s, Vad.split(pcm))                          // deterministic
    }

    @Test fun noQuietPointStillCapsAt30s() {
        val s = Vad.split(audio(65, emptyList()))
        s.forEach { assertTrue(it.end - it.start <= 30 * 16000) }
        assertEquals(65 * 16000, s.last().end)
        for (i in 1 until s.size) assertEquals(s[i - 1].end - 16000, s[i].start)
    }

    @Test fun silentSegmentIsMarked() {
        val s = Vad.split(ShortArray(40 * 16000))
        assertTrue(s.none { it.speech })
    }

    @Test fun energyGateEndsAfterSilence() {
        val g = EnergyGate(silenceMs = 1200)
        val quiet = ShortArray(1600) { (it % 3 - 1).toShort() }
        val loud = ShortArray(1600) { if (it % 2 == 0) 4000 else -4000 }
        repeat(5) { assertFalse(g.feed(quiet)) }
        repeat(10) { assertFalse(g.feed(loud)) }
        assertTrue(g.heardSpeech)
        repeat(11) { assertFalse(g.feed(quiet)) }
        assertTrue(g.feed(quiet))                                // 12 × 100 ms of silence
    }

    @Test fun stitchRemovesDuplicatedOverlap() {
        val segs = listOf(
            SegmentText(29000, 58000, "and then we agreed to ship the new build on Fri"),
            SegmentText(0, 30000, "Good morning everyone. Let us start with the release plan and then"),
            SegmentText(57000, 70000, "ship the new build on Friday, said Ravi."),
        )
        assertEquals("Good morning everyone. Let us start with the release plan and then we agreed to ship the new build on Friday, said Ravi.",
            Stitch.merge(segs.subList(0, 2).let { listOf(it[1], it[0]) } + segs[2]))
    }

    @Test fun stitchWithoutOverlapJoins() {
        assertEquals("alpha beta gamma delta", Stitch.merge(listOf(SegmentText(0, 1, "alpha beta"), SegmentText(1, 2, "gamma delta"))))
        // single repeated boundary word is removed
        assertEquals("one two three four", Stitch.merge(listOf(SegmentText(0, 1, "one two three"), SegmentText(1, 2, "Three four"))))
        assertEquals("", Stitch.merge(emptyList()))
    }

    private fun person(x: Double, y: Double) = TBox("person", x, y, x + 40, y + 80)

    @Test fun trackerCountsLineCrossingsPerMinute() {
        // two people walk down through y = 0.5 of a 400×400 frame; one stays put; a car passes but is another label
        val frames = (0 until 10).map { k ->
            val t = 55_000L + k * 1000
            TFrame(t, listOf(person(50.0, 100.0 + k * 20), person(250.0, 120.0 + k * 20), person(150.0, 10.0),
                TBox("car", 300.0, 300.0 - k * 5, 380.0, 360.0 - k * 5)), 400, 400)
        }
        val r = Tracker(line = CountLine()).run(frames)
        assertEquals(4, r.tracks.size)
        assertEquals(listOf(10, 10, 10, 10), r.tracks.map { it.frames })
        // person 1 centre y: (140 + 20k)/400 ≥ 0.5 at k = 3 → 58 s → minute 0; person 2: (160 + 20k) at k = 2 → 57 s
        assertEquals(mapOf((0L to "person") to 2), r.counts)
        val r2 = Tracker(line = CountLine()).run(frames.map { it.copy(tMs = it.tMs + 2_500) })
        assertEquals(mapOf((0L to "person") to 1, (1L to "person") to 1), r2.counts)
    }

    @Test fun trackerWithoutLineCountsNewTracks() {
        val frames = listOf(
            TFrame(0, listOf(person(0.0, 0.0))),
            TFrame(1000, listOf(person(2.0, 2.0), person(300.0, 300.0))),
            TFrame(61_000, listOf(person(600.0, 600.0))),
        )
        val r = Tracker().run(frames)
        assertEquals(3, r.tracks.size)
        assertEquals(mapOf((0L to "person") to 2, (1L to "person") to 1), r.counts)
    }

    @Test fun trackerDropsTracksAfterMaxMissed() {
        val frames = listOf(TFrame(0, listOf(person(0.0, 0.0))), TFrame(1, emptyList()), TFrame(2, emptyList()),
            TFrame(3, emptyList()), TFrame(4, listOf(person(0.0, 0.0))))
        assertEquals(2, Tracker(maxMissed = 2).run(frames).tracks.size)
        assertEquals(1, Tracker(maxMissed = 3).run(frames).tracks.size)
    }

    @Test fun pcmMathResampleAndDownmix() {
        assertEquals(listOf<Short>(1, 3), PcmMath.downmix(shortArrayOf(0, 2, 2, 4), 2).toList())
        val up = PcmMath.resampleLinear(shortArrayOf(0, 100, 200, 300), 8000, 16000)
        assertEquals(listOf<Short>(0, 50, 100, 150, 200, 250, 300, 300), up.toList())
        val wav = Wav.encode(shortArrayOf(1, -2, 3))
        assertEquals(listOf<Short>(1, -2, 3), Wav.read16kMono(wav).toList())
    }

    @Test fun sceneMath() {
        assertEquals(listOf(0L, 5000L, 10000L), SceneMath.fixedTimes(12_000, 5000))
        val red = SceneMath.histogram(IntArray(16) { 0xffff0000.toInt() })
        val blue = SceneMath.histogram(IntArray(16) { 0xff0000ff.toInt() })
        assertEquals(2.0, SceneMath.distance(red, blue), 1e-12)
        val scenes = SceneMath.sceneTimes(listOf(0L to red, 1000L to red, 2000L to blue, 3000L to blue), 0.5)
        assertEquals(listOf(2000L), scenes)
        assertEquals(listOf(0L to "fixed", 2000L to "scene", 5000L to "fixed"), SceneMath.merge(listOf(0, 5000), listOf(2000, 5500), 1000))
    }
}
