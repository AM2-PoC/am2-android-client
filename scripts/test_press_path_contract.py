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

    def test_the_minimum_transmission_is_named_and_is_the_only_spacing_rule(self):
        self.assertIn("MIN_TRANSMISSION_MS", self.ws)
        # The second timer measured from a value the first one kept moving, so
        # the two compounded into a lockout neither of them intended.
        self.assertNotRegex(self.ws, r"now - lastPttEndTime < \d+",
                            "a second, unnamed spacing rule is still in place")
        for literal in ("300", "500"):
            self.assertNotIn(f"< {literal})", self.start,
                             f"a bare {literal} is still deciding press behaviour")

    def test_a_deferred_stop_does_not_push_the_end_further_away(self):
        stop = section(self.ws, "fun stopTalking()", "fun startVideoStreaming()")
        # Restamping on re-entry is what turned a 50 ms tap into 800 ms.
        self.assertNotRegex(stop, r"lastPttEndTime = now[\s\S]{0,200}?postDelayed",
                            "the end time is still stamped before the deferral")

    def test_a_deferred_stop_is_cancellable_and_cannot_queue_twice(self):
        self.assertIn("pendingStop", self.ws)
        stop = section(self.ws, "fun stopTalking()", "fun startVideoStreaming()")
        self.assertNotRegex(stop, r"postDelayed\(\{\s*stopTalking\(\)",
                            "the deferral is still an anonymous lambda nobody can cancel")

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
