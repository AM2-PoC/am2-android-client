#!/usr/bin/env python3
"""A frame that does not reach the wire says so.

Two ways audio was lost without leaving any record:

The recording loop guarded its own send on isConnectedOnSocket() and dropped the
frame when it was false, with no else branch. That flag stays false from the
moment a socket drops until login_success arrives on the new one — two to eight
hundred milliseconds on every reconnect — while the operator keeps talking. The
frame_dropped event added with the trace never fired for any of it, because
sendBinary was never reached. The largest source of lost audio was the one the
instrumentation could not see.

And enqueue was not synchronized against release, so the reader thread could
land a frame in a queue the mixer was clearing, on a handler it was destroying.

The rule both share: one place decides whether a frame goes, and that place
records what it decided.
"""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/am2/am2"
RECORDER = JAVA / "AudioRecorder.kt"
PLAYER = JAVA / "AudioPlayer.kt"
WS = JAVA / "WebSocketManager.kt"


def section(text: str, start: str, end: str) -> str:
    begin = text.index(start)
    return text[begin:text.index(end, begin)]


class FrameLossVisibilityContractTest(unittest.TestCase):
    def setUp(self):
        self.recorder = RECORDER.read_text()
        self.player = PLAYER.read_text()
        self.ws = WS.read_text()

    def test_the_recorder_does_not_silently_filter_its_own_frames(self):
        loop = section(self.recorder, "while (isRecording)", "\n            }")
        # Deciding here means the decision is invisible: sendBinary is where the
        # trace lives, and a frame filtered before it leaves no record at all.
        self.assertNotIn("isConnectedOnSocket()", loop,
                         "the recorder still drops frames before the place that records drops")

    def test_the_send_path_owns_the_decision_and_records_it(self):
        send = section(self.ws, "fun sendBinary(", "\n    fun isConnected()")
        self.assertIn("isConnectedOnSocket()", send,
                      "nothing checks the session state where it can be traced")
        self.assertIn('event = "frame_dropped"', send)

    def test_a_frame_lost_to_reauthentication_is_counted(self):
        send = section(self.ws, "fun sendBinary(", "\n    fun isConnected()")
        # The reconnect window is the biggest single source; it needs its own
        # reason, not to be folded into a generic failure.
        self.assertIn("reauth", send.lower())

    def test_enqueue_cannot_race_the_handler_being_released(self):
        enqueue = section(self.player, "fun enqueue(data", "@Synchronized")
        self.assertIn("@Synchronized", self.player[:self.player.index("fun enqueue(data")][-40:],
                      "enqueue is not mutually exclusive with release")


if __name__ == "__main__":
    unittest.main()
