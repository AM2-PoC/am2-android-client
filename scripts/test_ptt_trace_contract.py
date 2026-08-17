#!/usr/bin/env python3
"""The trace has to be able to answer "how long did it take, and where".

The app already emits a timed event at every stage of a transmission, but two
things stopped it being usable as evidence: the transmit trace id was captured
once per recording thread, so in VOX and gateway mode every later transmission
logged a stale id; and nothing recorded how much was waiting on the socket, so a
frame delayed by an uplink backlog looked identical to one delayed by encoding.

Latency claims have been opinions up to now. This contract is what turns them
into numbers.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/am2/am2"
RECORDER = JAVA / "AudioRecorder.kt"
TRACE = JAVA / "PttTrace.kt"
WS = JAVA / "WebSocketManager.kt"
REPORT = ROOT / "scripts/ptt_latency_report.py"


def section(text: str, start: str, end: str) -> str:
    begin = text.index(start)
    return text[begin:text.index(end, begin)]


class PttTraceContractTest(unittest.TestCase):
    def setUp(self):
        self.recorder = RECORDER.read_text()
        self.trace = TRACE.read_text()
        self.ws = WS.read_text()

    def test_the_transmit_trace_id_is_read_per_frame_not_per_thread(self):
        start = section(self.recorder, "fun startRecording(", "fun stopRecording(")
        loop = section(start, "while (isRecording)", "\n            }")
        self.assertIn("currentTransmitTraceId()", loop,
                      "the trace id is still captured once, so later transmissions log a stale one")
        # Reading it once before the thread starts is what made VOX and gateway
        # transmissions untraceable, which is where latency is worst.
        before_thread = start[:start.index("thread(")]
        self.assertNotIn("currentTransmitTraceId()", before_thread)

    def test_the_trace_can_carry_how_much_is_waiting_on_the_socket(self):
        self.assertIn("queueBytes", self.trace)
        self.assertIn("queue_bytes=", self.trace)

    def test_a_sent_frame_records_the_socket_backlog(self):
        self.assertRegex(
            self.ws,
            r'event = "frame_sent"[\s\S]{0,400}?queueBytes',
            "frame_sent does not record the backlog it was queued behind",
        )

    def test_a_dropped_frame_is_recorded_rather_than_lost_silently(self):
        # A frame discarded without a trace is indistinguishable from one that
        # was never captured, which is the state that made this hard to measure.
        self.assertIn('event = "frame_dropped"', self.ws)

    def test_the_report_tool_exists_and_is_runnable(self):
        self.assertTrue(REPORT.is_file(), "scripts/ptt_latency_report.py is missing")
        text = REPORT.read_text()
        self.assertTrue(text.startswith("#!/usr/bin/env python3"))
        for segment in ("button_down", "start_sent", "capture_started", "playback_written"):
            self.assertIn(segment, text)

    def test_the_report_refuses_to_subtract_clocks_from_different_devices(self):
        # Two devices' System.nanoTime origins are unrelated. A tool that
        # subtracts across them produces confident nonsense.
        text = REPORT.read_text()
        self.assertRegex(text, r"per-device|same device|one device",
                         "the report does not state that segments are per-device")

    def test_no_trace_event_name_is_written_twice_with_different_spellings(self):
        emitted = set(re.findall(r'event = "([a-z_]+)"', "\n".join(
            p.read_text() for p in JAVA.rglob("*.kt")
        )))
        known = {
            "button_down", "button_up", "capture_failed", "capture_started", "end_sent",
            "frame_decoded", "frame_dropped", "frame_encoded", "frame_received",
            "frame_sent", "playback_written", "recorder_start_failed",
            "start_authorization_timeout", "start_authorized", "start_sent",
        }
        self.assertEqual(set(), emitted - known,
                         "an event the report tool does not know about was added")


if __name__ == "__main__":
    unittest.main()
