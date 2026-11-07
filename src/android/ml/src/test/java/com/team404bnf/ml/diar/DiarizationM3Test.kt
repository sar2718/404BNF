package com.team404bnf.ml.diar

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.abs

class DiarizationM3Test {
    @Test fun vadFindsLongSpeechAndMergesPadding() {
        val probs = List(5) { 0.1f } + List(20) { 0.95f } + List(20) { 0.1f }
        val spans = DiarizationTimeline.speechIntervals(probs, 45 * .032)
        assertEquals(1, spans.size)
        assertTrue(spans[0].start > 0.0)
        assertTrue(spans[0].end < 1.44)
    }
    @Test fun shortSpeechDoesNotMakeFakeSpeaker() {
        val spans = listOf(SpeechInterval(0.2, 0.65))
        val windows = DiarizationTimeline.windows(spans)
        assertTrue(windows.isEmpty())
        val turns = DiarizationTimeline.assignTimeline(spans, windows, emptyList())
        assertEquals("UNKNOWN", turns.single().speakerId)
    }
    @Test fun windowsUseReferenceTwoSecondsAndHop() {
        val windows = DiarizationTimeline.windows(listOf(SpeechInterval(0.0, 4.5)))
        assertEquals(4, windows.size)
        assertEquals(0.0, windows[0].start, 1e-8)
        assertEquals(2.4, windows.last().start, 1e-8)
    }
    @Test fun cosineAverageLinkageClustersIdenticalSpeakers() {
        val a = floatArrayOf(1f, 0f, 0f)
        val a2 = floatArrayOf(.99f, .1f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        val b2 = floatArrayOf(.1f, .99f, 0f)
        val groups = DiarizationTimeline.cluster(listOf(a, a2, b, b2), 0.55)
        assertEquals(listOf(0, 0, 1, 1), groups)
    }
    @Test fun voiceTimelineTracksWindowsWithinSameVadTurn() {
        val spans = listOf(SpeechInterval(0.0, 2.0))
        val windows = listOf(EmbedWindow(0.0, 0.5, 0), EmbedWindow(1.5, 2.0, 0))
        val turns = DiarizationTimeline.assignTimeline(spans, windows, listOf(0, 1))
        assertEquals(2, turns.size)
        assertEquals("SPEAKER_00", turns[0].speakerId)
        assertEquals("SPEAKER_01", turns[1].speakerId)
        assertEquals(2.0, turns.last().end, 1e-8)
    }
    @Test fun fbankHammingCmnFramesFiniteAndCentered() {
        val wave = FloatArray(16000) { (0.3 * sin(2 * PI * 440.0 * it / 16000)).toFloat() }
        val f = KaldiFbank80().extract(wave)
        assertEquals(98, f.frames)
        assertEquals(f.frames * 80, f.values.size)
        assertTrue(f.values.all(Float::isFinite))
        for (band in 0 until 80) {
            val mean = (0 until f.frames).sumOf { f.values[it * 80 + band].toDouble() } / f.frames
            assertTrue("band=$band mean=$mean", abs(mean) < 0.005)
        }
    }
    @Test fun rejectEmptyInvalidEmbeddings() {
        assertTrue(DiarizationTimeline.cluster(emptyList()).isEmpty())
        assertThrows(IllegalArgumentException::class.java) {
            DiarizationTimeline.cluster(listOf(floatArrayOf(0f, 0f), floatArrayOf(1f, 0f)))
        }
    }
    @Test fun largeNumberOfEmbeddingsClustersWithoutException() {
        // A. 600 embeddings, two alternating speakers -> should return 2 distinct speaker IDs
        val speakerA = FloatArray(192) { if (it % 2 == 0) 1.0f / 9.797959f else 0.0f }
        val speakerB = FloatArray(192) { if (it % 2 != 0) 1.0f / 9.797959f else 0.0f }
        val embeddings = List(600) { index -> if (index % 2 == 0) speakerA else speakerB }

        val labels = DiarizationTimeline.cluster(embeddings, 0.55)
        assertEquals(600, labels.size)
        assertEquals(2, labels.distinct().size)
    }

    @Test fun largeNumberOfDistinctEmbeddingsNotForcedToMerge() {
        // B. 600 distinct Gaussian random unit vectors in 192D -> max similarity ~0.25 (distance > 0.75 > 0.55)
        var seed = 123456789L
        fun nextGaussian(): Double {
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            val u1 = ((seed ushr 12) and 0x1FFFFFFFFFFFFFL).toDouble() / (1L shl 53).toDouble()
            seed = (seed * 6364136223846793005L + 1442695040888963407L)
            val u2 = ((seed ushr 12) and 0x1FFFFFFFFFFFFFL).toDouble() / (1L shl 53).toDouble()
            return kotlin.math.sqrt(-2.0 * kotlin.math.ln(maxOf(1e-10, u1))) * kotlin.math.cos(2.0 * kotlin.math.PI * u2)
        }

        val embeddings = List(600) {
            val vec = FloatArray(192) { nextGaussian().toFloat() }
            val norm = kotlin.math.sqrt(vec.sumOf { (it * it).toDouble() }).toFloat()
            FloatArray(192) { dim -> vec[dim] / norm }
        }

        val labels = DiarizationTimeline.cluster(embeddings, 0.55)
        assertEquals(600, labels.size)
        assertEquals(600, labels.distinct().size) // All 600 distinct vectors remain unmerged!
    }

    @Test fun speakerReappearancePreservesSpeakerId() {
        // C. Reappearance of Speaker A after 500 other embeddings
        val speakerA = FloatArray(192) { if (it % 2 == 0) 1.0f / 9.797959f else 0.0f }
        val speakerB = FloatArray(192) { if (it % 2 != 0) 1.0f / 9.797959f else 0.0f }
        val list = ArrayList<FloatArray>()
        list.add(speakerA) // Index 0: Speaker A
        repeat(500) { list.add(speakerB) } // Index 1..500: Speaker B
        list.add(speakerA) // Index 501: Speaker A reappears!

        val labels = DiarizationTimeline.cluster(list, 0.55)
        assertEquals(502, labels.size)
        assertEquals(labels[0], labels[501]) // Same speaker ID preserved!
    }
}
