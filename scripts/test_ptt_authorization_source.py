#!/usr/bin/env python3
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WS = ROOT / "app" / "src" / "main" / "java" / "com" / "am2" / "am2" / "WebSocketManager.kt"


def section(text: str, start: str, end: str) -> str:
    begin = text.index(start)
    return text[begin:text.index(end, begin)]


class PttAuthorizationSourceTest(unittest.TestCase):
    def setUp(self):
        self.text = WS.read_text()

    def test_start_no_longer_pays_a_fixed_capture_delay(self):
        start = section(self.text, "fun startTalking()", "private fun executePttStartSignal()")
        self.assertNotIn("700L", start)
        self.assertNotIn("400L", start)
        self.assertRegex(start, r"if \(isGateway\) \{\s*executeStartRecording\(\)")

    def test_non_gateway_capture_starts_on_the_matching_authorization(self):
        handler = section(self.text, '"ptt_audio_start_authorized" ->', '"ptt_active_status" ->')
        self.assertIn("traceId == activeTransmitTraceId", handler)
        self.assertIn("internalIsTalking", handler)
        # Authorization is one of two conditions; the microphone route is the
        # other. See test_audio_route_readiness.py for the combined gate.
        self.assertIn("startCaptureWhenReady()", handler)

    def test_capture_still_opens_when_the_relay_never_acknowledges(self):
        self.assertIn("private const val AUTHORIZATION_FALLBACK_MS = 500L", self.text)
        fallback = section(self.text, "private fun armAuthorizationFallback()", "private fun cancelAuthorizationFallback()")
        self.assertIn("pttHandler.postDelayed(fallback, bound)", fallback)
        self.assertIn("start_authorization_timeout", fallback)
        self.assertIn("traceId == activeTransmitTraceId", fallback)
        self.assertIn("!captureStarted", fallback)

    def test_a_transmission_captures_once_and_releases_its_fallback(self):
        record = section(self.text, "private fun executeStartRecording()", "fun stopTalking()")
        self.assertIn("if (!internalIsTalking || captureStarted) return", record)
        self.assertIn("cancelAuthorizationFallback()", record)
        self.assertIn("captureStarted = true", record)
        self.assertEqual(1, record.count("AudioRecorder.startRecording("))

        stop = section(self.text, "fun stopTalking()", "fun startVideoStreaming()")
        self.assertIn("captureStarted = false", stop)
        self.assertIn("cancelAuthorizationFallback()", stop)

    def test_reconnect_rerequests_authorization_before_capturing(self):
        reconnect = section(self.text, '"login_success" ->', '"login_error" ->')
        self.assertIn("executePttStartSignal()", reconnect)
        self.assertIn("armAuthorizationFallback()", reconnect)
        self.assertNotRegex(reconnect, r"executePttStartSignal\(\)\s*executeStartRecording\(\)")

    def test_the_fallback_is_bounded_below_the_delay_it_replaced(self):
        bound = int(re.search(r"AUTHORIZATION_FALLBACK_MS = (\d+)L", self.text).group(1))
        self.assertLess(bound, 700)


if __name__ == "__main__":
    unittest.main()
