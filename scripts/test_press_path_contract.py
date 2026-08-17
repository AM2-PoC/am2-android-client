#!/usr/bin/env python3
"""Pressing the button must do press work, and nothing else, first.

Three separate things sat between the press and the microphone opening:

  - the confirmation tone, whose MediaPlayer.create() performs a synchronous
    prepare() costing 10-100 ms;
  - a location report, which makes Play Services binder calls;
  - Bluetooth SCO, only *requested* once the press had already begun.

None of them is what the operator pressed the button for, and each one delays
the thing they did press it for.

Separately, the debounce was built from two timers that compounded. stopTalking
enforced a minimum transmission by re-posting itself and restamping the end time
on every re-entry; startTalking then refused for a further period measured from
that restamped value. A 50 ms tap locked the button for 800 ms, and the refusal
was silent, so the button simply felt dead.

The minimum is legitimate radio behaviour and is kept. The stacking, the
restamping and the silence are not.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/am2/am2"
WS = JAVA / "WebSocketManager.kt"
DEVICE = JAVA / "AudioDeviceManager.kt"


def section(text: str, start: str, end: str) -> str:
    begin = text.index(start)
    return text[begin:text.index(end, begin)]


class PressPathContractTest(unittest.TestCase):
    def setUp(self):
        self.ws = WS.read_text()
        self.start = section(self.ws, "fun startTalking()", "private fun armAuthorizationFallback")

    def test_capture_is_armed_before_anything_that_is_not_capture(self):
        armed = self.start.index("armAuthorizationFallback()")
        for label, token in (("the confirmation tone", "SoundManager.playStartTx()"),
                             ("the location report", "reportLocation(")):
            self.assertIn(token, self.start, f"{label} left the press path entirely; update this test")
            self.assertLess(armed, self.start.index(token),
                            f"{label} still runs before the microphone is armed")

    def test_the_request_reaches_the_relay_before_anything_optional(self):
        signal = self.start.index("executePttStartSignal()")
        self.assertLess(signal, self.start.index("SoundManager.playStartTx()"),
                        "the tone is played before the relay is even asked")

    def test_no_spacing_rule_stands_between_two_presses(self):
        # Two timers used to compound here: the second measured from a value the
        # first kept moving, so a 50 ms tap locked the button for 800 ms. The
        # compounding went first and the remaining 500 ms floor went with it --
        # release is the end of a transmission, and the next press is immediate.
        self.assertNotIn("MIN_TRANSMISSION_MS", self.ws,
                         "a minimum transmission length still gates the release")
        self.assertNotRegex(self.ws, r"now - lastPttEndTime < \d+",
                            "a second, unnamed spacing rule is still in place")
        for literal in ("300", "500"):
            self.assertNotIn(f"< {literal})", self.start,
                             f"a bare {literal} is still deciding press behaviour")

    def test_the_release_is_not_deferred_at_all(self):
        # There is nothing left to cancel or to queue twice, because the release
        # no longer schedules any part of itself. What remains deferred is the
        # end *signal*, which only lets the last frames land; see
        # test_release_ends_transmission.py.
        stop = section(self.ws, "fun stopTalking()", "fun startVideoStreaming()")
        self.assertNotIn("pendingStop", self.ws,
                         "the deferred-stop machinery is still present")
        self.assertNotRegex(stop, r"postDelayed\(\{\s*stopTalking\(\)",
                            "stopTalking still re-posts itself")

    def test_a_refused_press_tells_the_operator(self):
        # Returning in silence is why the button felt broken rather than busy.
        self.assertIn("onPressRefused", self.ws)

    def test_a_bluetooth_route_with_no_microphone_is_ready_immediately(self):
        device = DEVICE.read_text()
        ready = section(device, "fun isCaptureRouteReady()", "\n    }")
        # A2DP is output only. Waiting for an SCO link it will never establish
        # made every press pay the full fallback.
        self.assertIn("ScoCapable", device)
        self.assertIn("ScoCapable", ready)


if __name__ == "__main__":
    unittest.main()
