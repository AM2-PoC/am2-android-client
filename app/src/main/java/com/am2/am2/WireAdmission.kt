package com.am2.am2

/**
 * What may be handed to the socket while something is already waiting on it.
 *
 * Audio and video share one WebSocket, and OkHttp drains it strictly in order
 * from a single writer thread. `send()` only enqueues — it returns immediately
 * and tells you nothing about the wire — so a ~20 KB video frame accepted ahead
 * of a ~45-byte Opus frame delays that audio by the time it takes to push the
 * video: about 160 ms on a 1 Mbps uplink, 625 ms at 256 kbps.
 *
 * It is not a single frame's worth of harm. Video is produced as fast as it
 * encodes, so on a constrained uplink the queue grows faster than it drains and
 * audio delay grows with it — roughly a second and a half of added delay for
 * every second of talking. The queue is bounded only by OkHttp's 16 MiB ceiling,
 * at which point it closes the connection outright and the audio goes with it.
 *
 * The decision is therefore made before enqueueing, and it is made here, as
 * arithmetic with no Android types in it, so it can be tested directly:
 *
 * - Audio is always admitted. It is the product; it is small; it is the thing
 *   the delay is measured against.
 * - Video is admitted only while the socket is close to drained. Above that it
 *   is dropped at source, where it can be counted, rather than queued where it
 *   would push audio behind it.
 *
 * Dropping is preferred to waiting because a late video frame has no value: by
 * the time a backlog cleared, the picture it carried would be history.
 */
internal object WireAdmission {

    /**
     * How many bytes may already be queued before video is refused.
     *
     * Roughly one frame at the sizes this app sends, so at most one video frame
     * is ever in flight ahead of an audio frame and head-of-line delay stays
     * bounded by a single transmission rather than by a growing queue.
     */
    const val VIDEO_QUEUE_BUDGET_BYTES = 24_000L

    /** Sustained pressure, at which video should also lower its own cost. */
    const val VIDEO_PRESSURE_BYTES = 12_000L

    fun shouldAdmitVideo(queuedBytes: Long, budgetBytes: Long = VIDEO_QUEUE_BUDGET_BYTES): Boolean =
        queuedBytes < budgetBytes

    /**
     * How hard video should try to be cheap, given what is already queued.
     *
     * Reported as a level rather than a quality number so the capture side owns
     * what to trade — resolution, quality, or skipping a frame — and this file
     * stays a statement about the socket.
     */
    fun videoPressure(queuedBytes: Long): Pressure = when {
        queuedBytes >= VIDEO_QUEUE_BUDGET_BYTES -> Pressure.BLOCKED
        queuedBytes >= VIDEO_PRESSURE_BYTES -> Pressure.HEAVY
        else -> Pressure.CLEAR
    }

    enum class Pressure { CLEAR, HEAVY, BLOCKED }
}
