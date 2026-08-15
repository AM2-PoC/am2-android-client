package com.am2.am2

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PttTraceFormatTest {
    @Test
    fun formatsOnlyTraceMetricsWithStableFieldOrder() {
        assertEquals(
            "event=frame_sent trace_id=7 mono_ns=123 frame_seq=4 frame_bytes=72 queue_frames=3",
            PttTraceFormat.format(
                event = "frame_sent",
                traceId = 7,
                monotonicNanos = 123,
                frameSequence = 4,
                frameBytes = 72,
                queueFrames = 3,
            ),
        )
    }

    @Test
    fun omitsUnsetOptionalMetrics() {
        val line = PttTraceFormat.format("button_down", 1, 2)
        assertEquals("event=button_down trace_id=1 mono_ns=2", line)
        assertFalse(line.contains("username"))
        assertFalse(line.contains("audio="))
    }

    @Test
    fun samplesTheFirstFramesAndThenHalfSecondIntervals() {
        assertTrue(PttTrace.shouldSampleFrame(1))
        assertTrue(PttTrace.shouldSampleFrame(3))
        assertFalse(PttTrace.shouldSampleFrame(4))
        assertTrue(PttTrace.shouldSampleFrame(25))
    }

    @Test
    fun authorizationTraceEventUsesTheExistingPrivacySafeFormat() {
        assertEquals(
            "event=start_authorized trace_id=7 mono_ns=123",
            PttTraceFormat.format("start_authorized", 7, 123),
        )
    }

    @Test
    fun receiveRegistryKeepsTheRelayedTraceAcrossStatusAndFrames() {
        var nextId = 40L
        val registry = PttReceiveTraceRegistry { ++nextId }
        val transitions = registry.syncActive(setOf("sender-a"), "sender-a", 41L)
        assertEquals(listOf(PttReceiveTraceTransition.Started(41L)), transitions)
        assertEquals(41L, registry.traceIdForFrame("sender-a"))
        assertEquals(emptyList<PttReceiveTraceTransition>(), registry.syncActive(setOf("sender-a")))
    }

    @Test
    fun receiveRegistryEndsRemovedSenderAndAllocatesNewTraceOnNextFrame() {
        var nextId = 70L
        val registry = PttReceiveTraceRegistry { ++nextId }
        assertEquals(71L, registry.traceIdForFrame("sender-a"))
        assertEquals(
            listOf(PttReceiveTraceTransition.Ended(71L)),
            registry.syncActive(emptySet()),
        )
        assertEquals(72L, registry.traceIdForFrame("sender-a"))
    }

    @Test
    fun receiveRegistryTracksConcurrentSendersWithoutExposingTheirNames() {
        var nextId = 90L
        val registry = PttReceiveTraceRegistry { ++nextId }
        val transitions = registry.syncActive(linkedSetOf("sender-a", "sender-b"))
        assertEquals(
            listOf(PttReceiveTraceTransition.Started(91L), PttReceiveTraceTransition.Started(92L)),
            transitions,
        )
        assertEquals(91L, registry.traceIdForFrame("sender-a"))
        assertEquals(92L, registry.traceIdForFrame("sender-b"))
    }

    @Test
    fun relayedTraceOnlyAppliesToTheMatchingSender() {
        var nextId = 100L
        val registry = PttReceiveTraceRegistry { ++nextId }
        val transitions = registry.syncActive(
            linkedSetOf("existing-speaker", "new-speaker"),
            relayedSenderKey = "new-speaker",
            relayedTraceId = 501L,
        )
        assertEquals(
            listOf(PttReceiveTraceTransition.Started(101L), PttReceiveTraceTransition.Started(501L)),
            transitions,
        )
    }
}
