#!/usr/bin/env python3
"""Receive-side latency must be bounded and must recover.

Frames are 20 ms of Opus at 16 kHz, so every frame of prefill or backlog is
20 ms the listener waits. The receive path had three ways to accumulate delay
and no way to give it back: a fixed prefill, a full re-prefill on any momentary
gap, and a queue that only shed frames after three seconds had piled up.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PLAYER = ROOT / "app/src/main/java/com/am2/am2/AudioPlayer.kt"

FRAME_MS = 20


def constant(text: str, name: str) -> int:
    match = re.search(rf"{name}\s*=\s*(\d+)", text)
    assert match, f"{name} is not defined"
    return int(match.group(1))


def section(text: str, start: str, end: str) -> str:
    begin = text.index(start)
    return text[begin:text.index(end, begin)]


class AudioJitterContractTest(unittest.TestCase):
    def setUp(self):
        self.text = PLAYER.read_text()

    def test_prefill_starts_low_and_is_bounded(self):
        low = constant(self.text, "MIN_PREFILL_FRAMES")
        high = constant(self.text, "MAX_PREFILL_FRAMES")
        # Never zero: a PTT listener tolerates a little delay far better than
        # choppiness. Never telephony-conservative either.
        self.assertGreaterEqual(low * FRAME_MS, 40)
        self.assertLessEqual(low * FRAME_MS, 80)
        self.assertLessEqual(high * FRAME_MS, 200)
        self.assertGreater(high, low)

    def test_a_momentary_gap_does_not_restart_buffering(self):
        decode = section(self.text, "fun decodeNext()", "@Synchronized\n        fun release()")
        self.assertIn("consecutiveUnderruns", decode)
        # Re-prefill is only legitimate once the talk spurt has actually ended.
        self.assertIn("END_OF_SPURT_FRAMES", decode)
        self.assertNotRegex(
            decode,
            r"if \(frame == null\) \{\s*isBuffering = true",
        )

    def test_the_end_of_spurt_bound_is_a_real_stall_not_a_hiccup(self):
        self.assertGreaterEqual(constant(self.text, "END_OF_SPURT_FRAMES") * FRAME_MS, 200)

    def test_prefill_adapts_to_a_network_that_keeps_underrunning(self):
        self.assertIn("targetPrefill", self.text)
        self.assertIn("MAX_PREFILL_FRAMES", self.text)
        # and gives the latency back once the network settles
        self.assertIn("PREFILL_DECAY_FRAMES", self.text)

    def test_backlog_is_shed_before_it_becomes_audible_delay(self):
        high_water = constant(self.text, "HIGH_WATER_FRAMES")
        self.assertLessEqual(high_water * FRAME_MS, 400)
        # Drop the oldest frames: the stale ones are the delay.
        self.assertIn("HIGH_WATER_FRAMES", section(self.text, "fun enqueue(", "fun decodeNext()"))
        self.assertNotIn("queue.size > 150", self.text)

    def test_the_track_buffer_is_not_a_second_hidden_jitter_buffer(self):
        setup = section(self.text, "private fun setupAudioTrack(", "private fun startMixerThread()")
        # 16384 bytes is 512 ms at 16 kHz mono, downstream of the queue and
        # invisible to it, so backlog parked there could never be recovered.
        self.assertNotIn("16384", setup)
        self.assertIn("MIN_BUFFER_SIZE", setup)

    def test_blocking_writes_do_not_hold_the_lock_the_network_thread_needs(self):
        mixer = section(self.text, "private fun startMixerThread()", "fun playAudio(")
        # A small buffer makes write() block to pace playback. playAudio() runs
        # on the network thread and takes the same lock, so the write must not
        # happen inside it.
        self.assertNotRegex(mixer, r"synchronized\(this\) \{[^}]*track\.write\(", )
        self.assertIn("track.write(", mixer)

    def test_silence_is_not_injected_faster_than_real_time(self):
        mixer = section(self.text, "private fun startMixerThread()", "fun playAudio(")
        self.assertNotIn("Thread.sleep(10)", mixer)


if __name__ == "__main__":
    unittest.main()
