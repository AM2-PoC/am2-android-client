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



class TransportBeforeLoginTest(unittest.TestCase):
    """A login screen needs the socket precisely when it is not authorised.

    Reconnecting was gated on isAuthorizedSession alone. On the login screen
    there is no session yet, so the first time the socket dropped -- the phone
    sleeping is enough -- nothing brought it back. The screen went on saying
    "Server Offline" against a relay that was up, and it only recovered
    because logging in calls connect() directly.

    Whether to resume a session and whether the transport should be up are two
    questions. The second one has an answer before anybody has logged in.
    """

    def setUp(self):
        self.ws = WS.read_text(encoding="utf-8")
        self.policy = (ROOT / "app/src/main/java/com/am2/am2/ReconnectPolicy.kt").read_text(
            encoding="utf-8")
        self.login = (ROOT / "app/src/main/java/com/am2/am2/LoginActivity.kt").read_text(
            encoding="utf-8")

    def test_the_policy_has_a_reason_to_reconnect_other_than_a_session(self):
        self.assertIn(
            "transportWanted", self.policy,
            "reconnecting is decided by authorisation alone, so a login screen "
            "whose socket drops never gets it back",
        )

    def test_every_reconnect_decision_goes_through_the_policy(self):
        # onFailure used to test isAuthorizedSession inline, so a rule added to
        # the policy would apply to half the ways a socket can end.
        body = re.sub(r"//[^\n]*", "", self.ws)
        for call in re.finditer(r"attemptReconnect\(\)", body):
            before = body[max(0, call.start() - 400):call.start()]
            self.assertNotIn(
                "if (isAuthorizedSession) {", before[-60:],
                "a reconnect is decided inline instead of by ReconnectPolicy",
            )

    def test_the_login_screen_asks_for_the_transport_and_gives_it_back(self):
        self.assertIn(
            "wantTransport(true)", self.login,
            "the login screen never asks for a socket it depends on",
        )
        self.assertIn(
            "wantTransport(false)", self.login,
            "the login screen never releases the socket, so it is held open behind "
            "every other screen",
        )

    def test_leaving_the_login_screen_does_not_end_an_authorised_session(self):
        # wantTransport(false) must not be disconnect(): by the time the screen
        # goes away the operator may have just signed in.
        stop = self.login[self.login.index("wantTransport(false)") - 400:]
        stop = stop[:stop.index("wantTransport(false)") + 200]
        self.assertNotIn(
            "WebSocketManager.disconnect(", stop,
            "leaving the login screen tears down the session it just established",
        )


if __name__ == "__main__":
    unittest.main()
