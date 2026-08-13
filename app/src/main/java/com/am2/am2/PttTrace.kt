package com.am2.am2

import com.am2.am2.logging.SafeLog
import java.util.concurrent.atomic.AtomicLong

internal object PttTraceFormat {
    fun format(
        event: String,
        traceId: Long,
        monotonicNanos: Long,
        frameSequence: Long? = null,
        frameBytes: Int? = null,
        queueFrames: Int? = null,
    ): String = buildString {
        append("event=").append(event)
        append(" trace_id=").append(traceId)
        append(" mono_ns=").append(monotonicNanos)
        frameSequence?.let { append(" frame_seq=").append(it) }
        frameBytes?.let { append(" frame_bytes=").append(it) }
        queueFrames?.let { append(" queue_frames=").append(it) }
    }
}

internal sealed interface PttReceiveTraceTransition {
    val traceId: Long

    data class Started(override val traceId: Long) : PttReceiveTraceTransition
    data class Ended(override val traceId: Long) : PttReceiveTraceTransition
}

internal class PttReceiveTraceRegistry(
    private val nextTraceId: () -> Long = { PttTrace.newTraceId() },
) {
    private val traceIdsBySender = linkedMapOf<String, Long>()

    @Synchronized
    fun traceIdForFrame(senderKey: String): Long =
        traceIdsBySender.getOrPut(senderKey, nextTraceId)

    @Synchronized
    fun syncActive(
        senderKeys: Set<String>,
        relayedSenderKey: String? = null,
        relayedTraceId: Long? = null,
    ): List<PttReceiveTraceTransition> {
        val transitions = mutableListOf<PttReceiveTraceTransition>()
        traceIdsBySender.keys.filterNot(senderKeys::contains).forEach { senderKey ->
            traceIdsBySender.remove(senderKey)?.let { transitions += PttReceiveTraceTransition.Ended(it) }
        }
        senderKeys.forEach { senderKey ->
            if (senderKey !in traceIdsBySender) {
                val traceId = if (senderKey == relayedSenderKey) relayedTraceId ?: nextTraceId()
                    else nextTraceId()
                traceIdsBySender[senderKey] = traceId
                transitions += PttReceiveTraceTransition.Started(traceId)
            }
        }
        return transitions
    }

    @Synchronized
    fun clear(): List<PttReceiveTraceTransition> {
        val transitions = traceIdsBySender.values.map(PttReceiveTraceTransition::Ended)
        traceIdsBySender.clear()
        return transitions
    }
}

internal object PttTrace {
    private const val TAG = "PttTrace"
    private const val SAMPLE_EVERY_FRAMES = 25L
    private val nextTraceId = AtomicLong(0)

    fun newTraceId(): Long = nextTraceId.incrementAndGet()

    fun shouldSampleFrame(frameSequence: Long): Boolean =
        frameSequence <= 3L || frameSequence % SAMPLE_EVERY_FRAMES == 0L

    fun emit(
        event: String,
        traceId: Long,
        frameSequence: Long? = null,
        frameBytes: Int? = null,
        queueFrames: Int? = null,
    ) {
        SafeLog.d(
            TAG,
            PttTraceFormat.format(
                event = event,
                traceId = traceId,
                monotonicNanos = System.nanoTime(),
                frameSequence = frameSequence,
                frameBytes = frameBytes,
                queueFrames = queueFrames,
            ),
        )
    }
}
