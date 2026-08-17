"""Letting go ends the transmission. Nothing is held after that.

Closing the microphone on release removed the hot mic, but the slot was still
held to the floor for the rest of a 500 ms minimum: the roster kept showing the
operator as talking, and their own next press was refused until the timer ran
out. On a handheld radio that is not how the key behaves -- release is the end
of the transmission, and the next press is immediate.

The minimum existed as anti-chatter, and it was never a decision recorded in
this repository: both constants arrived with the initial code import. What it
actually bought was churn protection on the relay roster, which is the relay's
problem to solve if it turns out to have one; what it cost was an operator whose
radio ignored them for half a second after every short press.

Fifty milliseconds of speech is three Opus frames. There is no codec reason to
pad it, and a receiver decodes it exactly as it decodes any other burst.

These assert by absence: a deferral reintroduced under any name fails here.
"""

import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOCKET = ROOT / "app" / "src" / "main" / "java" / "com" / "am2" / "am2" / "WebSocketManager.kt"


def read(path):
    return path.read_text(encoding="utf-8")


def section(text, start, end):
    begin = text.index(start)
    return text[begin:text.index(end, begin + len(start))]


class ReleaseIsTheEnd(unittest.TestCase):
    def setUp(self):
        self.ws = read(SOCKET)
        body = section(self.ws, "fun stopTalking()", "\n    fun ")
        # Past the declaration, so a self-call is distinguishable from the name.
        self.stop = body[body.index("{") + 1:]

    def test_the_teardown_the_operator_feels_is_synchronous(self):
        """Everything the operator can perceive happens before stopTalking returns.

        The talking flag, the UI, the mute state and the microphone are all
        settled in one pass. Only the end *signal* to the relay may wait, and
        only to let the last frames land.
        """
        head = self.stop[: self.stop.index("pttHandler.postDelayed")] if "pttHandler.postDelayed" in self.stop else self.stop
        for settled in ("internalIsTalking = false", "captureStarted = false",
                        "AudioRecorder.stopRecording(", "_isTalking.postValue(false)"):
            self.assertIn(settled, head,
                          f"{settled} happens after a deferral, so release is not immediate")

    def test_the_only_deferral_is_the_tail_drain_and_it_is_explained(self):
        """One deferral, bounded and documented.

        Ending the transmission the instant the key is released would truncate
        it: the relay discards audio from a speaker it has already removed, so
        frames still in flight are lost. That short wait is legitimate, and it
        is invisible to the operator because everything they perceive has
        already happened. A bare number is not -- it reads as another debounce.
        """
        deferrals = re.findall(r"postDelayed\(", self.stop)
        self.assertLessEqual(len(deferrals), 1,
                             "release schedules more than one thing for later")
        if not deferrals:
            return
        tail = self.stop[self.stop.index("pttHandler.postDelayed"):]
        self.assertRegex(tail, r"ptt_audio_end",
                         "the deferral is not the end signal")
        self.assertNotRegex(
            tail, r"\}, \d+\)",
            "the drain is a bare literal; name it so it cannot be read as a debounce",
        )

    def test_stopping_does_not_re_enter(self):
        self.assertNotIn(
            "stopTalking()", self.stop,
            "stopTalking calls itself again; a release should complete in one pass",
        )

    def test_a_press_during_the_drain_still_ends_the_previous_transmission(self):
        """The drain must not be skippable by pressing again.

        The deferred block used to run only `if (!internalIsTalking)`, so a
        press inside the window meant the relay was never told the previous
        transmission ended. It recovered only because the next start re-added
        the speaker, which is luck rather than design.
        """
        tail = self.stop[self.stop.index("pttHandler.postDelayed"):]
        self.assertNotRegex(
            tail, r"if \(!internalIsTalking\)",
            "a new press swallows the previous transmission's end signal",
        )

    def test_no_minimum_transmission_remains(self):
        self.assertNotIn(
            "MIN_TRANSMISSION_MS", self.ws,
            "a minimum transmission length still gates the release",
        )

    def test_no_bare_debounce_literal_returns(self):
        """Named constants, so a reintroduced pause cannot hide as a number."""
        press = section(self.ws, "fun startTalking()", "private fun startCaptureWhenReady()")
        for literal in re.findall(r"\b(\d{3,4})L\b", press + self.stop):
            self.assertNotIn(
                literal, {"300", "500", "800"},
                f"a bare {literal} ms pause is back on the press or release path",
            )

    def test_the_next_press_is_not_refused_by_a_pending_stop(self):
        """With nothing deferred there is no pending stop to refuse against."""
        start = section(self.ws, "fun startTalking()", "private fun startCaptureWhenReady()")
        self.assertNotIn(
            "pendingStop", start,
            "a press is still refused because a previous release has not finished",
        )
        self.assertNotIn(
            "pendingStop", self.ws,
            "the deferred-stop machinery is still present",
        )

    def test_release_still_closes_capture(self):
        """The hot-mic fix must survive removing the hold that motivated it."""
        self.assertRegex(
            self.stop,
            r"captureStarted = false",
            "release no longer marks capture closed",
        )

    def test_a_refused_press_still_tells_the_operator(self):
        """Refusals remain -- half duplex and RX still refuse -- and must be audible."""
        self.assertIn("onPressRefused", self.ws)
        refused = section(self.ws, "private fun onPressRefused()", "\n    private fun ")
        self.assertRegex(refused, r"SoundManager\.")


if __name__ == "__main__":
    unittest.main()
