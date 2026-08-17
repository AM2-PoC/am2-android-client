"""The talking indicator follows the audio, and stops when it does.

The sender releases the key and their own side ends cleanly. On the receiving
handset the indicator stays lit for another second and a half, which reads as
the transmission still running long after it is over -- on a radio, that is the
one thing the indicator exists to say.

Nothing about it is a network delay. The relay broadcasts the empty speaker list
about a hundred milliseconds after release, and the client applies it
authoritatively. Two local holders keep the light on regardless.

`isActuallyPlaying()` returns true for a flat second after the last write:

    val isWithinGracePeriod = (System.currentTimeMillis() - lastDataWriteTime) < 1000

The comment says it exists so the indicator does not flicker to idle while the
tail is still faintly audible. But the line above it already answers that
precisely -- `totalFramesWritten > playbackHeadPosition` is the hardware's own
account of what it has left to render. The grace does not refine that signal, it
overrides it, and a second is an order of magnitude more than the hardware lag
it was meant to cover.

And the speaker itself is only dropped 2000 ms after its last frame, a bare
literal on a path where the authoritative message has already arrived.

These pin the intent: the indicator may outlast the audio by as long as the
hardware is genuinely still playing, and no longer.
"""

import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
PLAYER = ROOT / "app" / "src" / "main" / "java" / "com" / "am2" / "am2" / "AudioPlayer.kt"
SOCKET = ROOT / "app" / "src" / "main" / "java" / "com" / "am2" / "am2" / "WebSocketManager.kt"


def read(path):
    return path.read_text(encoding="utf-8")


def section(text, start, end):
    begin = text.index(start)
    return text[begin:text.index(end, begin + len(start))]


class PlaybackStateIsHonest(unittest.TestCase):
    def setUp(self):
        self.player = read(PLAYER)
        self.playing = section(self.player, "fun isActuallyPlaying()", "\n    }")

    def test_the_hardware_position_decides(self):
        """The precise signal must still be the one that answers."""
        self.assertIn(
            "playbackHeadPosition", self.playing,
            "playback state no longer consults what the hardware has rendered",
        )

    def test_the_grace_period_is_named_and_short(self):
        """A bare 1000 on this path is a second of lying about the air.

        It covers reporting lag between the write and the hardware position,
        which is tens of milliseconds. Anything near a second is not covering
        lag, it is replacing the measurement.
        """
        self.assertNotRegex(
            self.playing,
            r"<\s*1000\b",
            "the indicator is held for a flat second after the last audio write",
        )
        named = re.findall(r"([A-Z_]*GRACE[A-Z_]*)\s*=\s*(\d+)L", self.player)
        self.assertTrue(named, "the grace period is still an unnamed literal")
        for name, value in named:
            self.assertLessEqual(
                int(value), 250,
                f"{name} is {value} ms; that is long enough to outlast the "
                "transmission rather than its tail",
            )


class SpeakerExpiryIsNamed(unittest.TestCase):
    def setUp(self):
        self.ws = read(SOCKET)

    def test_the_frame_idle_timeout_is_named(self):
        """It is a fallback for a lost end message, not the primary rule.

        `ptt_active_status` already clears the speaker list authoritatively.
        This only has to cover the case where that message never arrives, so it
        should say so rather than sit in the code as a bare number.
        """
        update = section(self.ws, "private fun updateTalkingStatusUI()", "\n    private fun ")
        self.assertNotRegex(
            update,
            r"now - last > \d+",
            "the speaker expiry is a bare literal on the display path",
        )
        self.assertRegex(
            self.ws,
            r"[A-Z_]*IDLE[A-Z_]*\s*=\s*\d+L|SPEAKER_[A-Z_]*\s*=\s*\d+L",
            "the fallback timeout has no name",
        )


if __name__ == "__main__":
    unittest.main()
