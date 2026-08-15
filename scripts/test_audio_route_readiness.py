#!/usr/bin/env python3
"""Capture must not open before the microphone route it will be bound to is ready.

AudioRecord binds its input route when it is constructed, and it does not follow
a Bluetooth SCO link that connects afterwards. Starting capture early therefore
does not merely clip the first moments — it pins the whole transmission to the
built-in microphone. The former fixed 400/700 ms delay was sized to outlast that
connect; this contract requires the readiness signal Android already broadcasts
to be used instead.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/am2/am2"
DEVICE = JAVA / "AudioDeviceManager.kt"
WS = JAVA / "WebSocketManager.kt"


def section(text: str, start: str, end: str) -> str:
    begin = text.index(start)
    return text[begin:text.index(end, begin)]


class AudioRouteReadinessTest(unittest.TestCase):
    def setUp(self):
        self.device = DEVICE.read_text()
        self.ws = WS.read_text()

    def test_sco_state_is_read_from_the_broadcast_extra(self):
        # The receiver already subscribes to the action; the defect was that it
        # discarded the state extra and so could not tell CONNECTING from CONNECTED.
        self.assertIn("ACTION_SCO_AUDIO_STATE_UPDATED", self.device)
        self.assertIn("EXTRA_SCO_AUDIO_STATE", self.device)
        self.assertIn("SCO_AUDIO_STATE_CONNECTED", self.device)

    def test_route_readiness_is_exposed_and_is_true_without_bluetooth(self):
        self.assertIn("fun isCaptureRouteReady()", self.device)
        ready = section(self.device, "fun isCaptureRouteReady()", "\n    }")
        # Only Bluetooth has an asynchronous route handshake. Wired, USB and the
        # built-in microphone are ready as soon as they are selected.
        self.assertIn("isBluetoothConnected", ready)

    def test_capture_requires_authorization_and_a_ready_route(self):
        self.assertIn("fun startCaptureWhenReady()", self.ws)
        gate = section(self.ws, "private fun startCaptureWhenReady()", "\n    }")
        self.assertIn("transmitAuthorized", gate)
        self.assertIn("isCaptureRouteReady()", gate)

    def test_becoming_ready_later_starts_a_waiting_capture(self):
        # Readiness usually arrives after the press, so the route change has to
        # drive capture rather than only being polled once at press time.
        self.assertIn("onCaptureRouteReady()", self.device)
        self.assertIn("fun onCaptureRouteReady()", self.ws)

    def test_authorization_alone_no_longer_opens_capture(self):
        handler = section(self.ws, '"ptt_audio_start_authorized" ->', '"ptt_active_status" ->')
        self.assertIn("transmitAuthorized = true", handler)
        self.assertIn("startCaptureWhenReady()", handler)
        self.assertNotIn("executeStartRecording()", handler)

    def test_bluetooth_keeps_the_original_worst_case_bound(self):
        # The fallback still guarantees audio, but the longer bound is only paid
        # when a Bluetooth route genuinely has not reported ready.
        self.assertIn("private const val AUTHORIZATION_FALLBACK_MS = 500L", self.ws)
        self.assertIn("private const val BLUETOOTH_ROUTE_FALLBACK_MS = 700L", self.ws)
        arm = section(self.ws, "private fun armAuthorizationFallback()", "\n    }")
        self.assertIn("BLUETOOTH_ROUTE_FALLBACK_MS", arm)
        self.assertIn("isBluetoothConnected", arm)

    def test_no_fixed_delay_returns_to_the_press_path(self):
        start = section(self.ws, "fun startTalking()", "private fun executePttStartSignal()")
        self.assertNotRegex(start, r"postDelayed\(\s*\{")
        for bound in re.findall(r"\b(\d+)L\b", start):
            self.assertNotIn(bound, {"400", "700"})


if __name__ == "__main__":
    unittest.main()
