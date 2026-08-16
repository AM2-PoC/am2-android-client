#!/usr/bin/env python3
"""Reconnecting starts by reconnecting, and a fleet does not retry in step.

Two defects in the same scheduler.

The backoff value was applied to the FIRST attempt, so a dropped socket meant
two seconds of doing nothing before the client even tried. Added to the login
round trip, that is well over two seconds of dead audio for a drop the network
never noticed — a relay restart, a server-side close, a failed TLS resume.
NetworkManager calls connect() directly when connectivity returns, so the fast
path exists for the one case that was already fast; every other cause paid the
full wait.

And the delay was exact. Every unit that dropped together retried together, hit
the relay together, failed together, and backed off together. For a fleet of
radios on one site — which is the whole use case — that is a self-inflicted
thundering herd.

Backoff protects a server from a client that cannot connect. It should not be
charged for the first attempt, and it should not be identical across devices.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WS = ROOT / "app/src/main/java/com/am2/am2/WebSocketManager.kt"


def section(text: str, start: str, end: str) -> str:
    begin = text.index(start)
    return text[begin:text.index(end, begin)]


class ReconnectContractTest(unittest.TestCase):
    def setUp(self):
        self.text = WS.read_text()
        self.attempt = section(self.text, "private fun attemptReconnect()", "\n    private fun ")

    def test_the_first_attempt_is_not_delayed(self):
        self.assertIn("RECONNECT_FIRST_ATTEMPT_MS", self.text)
        first = int(re.search(r"RECONNECT_FIRST_ATTEMPT_MS\s*=\s*(\d+)", self.text).group(1))
        self.assertLessEqual(first, 250,
                             "the first reconnect still waits long enough to be heard as a gap")

    def test_a_successful_session_resets_to_the_immediate_delay(self):
        # Resetting to the backoff base is what made the next drop pay it again.
        self.assertNotIn("reconnectDelay = 2000L", self.text)
        self.assertIn("reconnectDelay = RECONNECT_FIRST_ATTEMPT_MS", self.text)

    def test_backoff_still_grows_and_stays_bounded(self):
        self.assertIn("RECONNECT_BASE_DELAY_MS", self.text)
        self.assertIn("MAX_RECONNECT_DELAY", self.attempt)
        self.assertIn("coerceAtMost", self.attempt)

    def test_the_scheduled_delay_is_spread_so_a_fleet_does_not_retry_in_step(self):
        self.assertIn("RECONNECT_JITTER", self.text)
        self.assertIn("jitteredDelay", self.attempt)
        jitter = section(self.text, "private fun jitteredDelay(", "\n    }")
        self.assertRegex(jitter, r"Random|nextLong|nextInt",
                         "the spread is not actually random")

    def test_jitter_never_turns_into_a_longer_wait_than_the_cap(self):
        jitter = section(self.text, "private fun jitteredDelay(", "\n    }")
        # A spread that can exceed MAX_RECONNECT_DELAY would quietly extend the
        # worst case beyond the bound the backoff promises.
        self.assertIn("coerceIn", jitter)

    def test_an_immediate_attempt_stays_immediate(self):
        jitter = section(self.text, "private fun jitteredDelay(", "\n    }")
        # Spreading zero would reintroduce the very delay this removes.
        self.assertRegex(jitter, r"<=\s*0L|== 0L")


if __name__ == "__main__":
    unittest.main()
