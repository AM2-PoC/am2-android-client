"""What the button promises when it is released, and when the microphone refuses.

An earlier round removed the compounding debounce: the 300 ms lockout that was
restamped by every deferred re-entry is gone, and a refused press now makes a
sound instead of returning in silence. Three things it did not reach are here.

The microphone keeps recording after the operator lets go. `stopTalking`
defers its whole body while a transmission is younger than the minimum length,
and the recording loop runs until `isTalkingNow()` goes false -- which that
deferral is what postpones. So a short press holds the microphone open, and
whatever is said in the room after the operator believed they had stopped is
transmitted. On a radio that is a hot mic, not a debounce.

A capture that fails entirely says nothing. Three audio sources are tried in
turn; if all three refuse -- which is what a Bluetooth route in the wrong state
looks like -- the thread exits through `finally`, and the talking state it was
started for is never cleared. The UI holds TX, no frame is ever sent, and the
operator is told nothing at all. It is the failure that looks exactly like
working.

And the fallback bound still asks the wrong question about Bluetooth. Route
readiness learned to distinguish a headset from an A2DP speaker; the timeout
beside it did not, so a device with no microphone to wait for is still waited
for.
"""

import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
SOCKET = ROOT / "app" / "src" / "main" / "java" / "com" / "am2" / "am2" / "WebSocketManager.kt"
RECORDER = ROOT / "app" / "src" / "main" / "java" / "com" / "am2" / "am2" / "AudioRecorder.kt"


def read(path):
    return path.read_text(encoding="utf-8")


def section(text, start, end):
    begin = text.index(start)
    return text[begin:text.index(end, begin + len(start))]


class ReleaseStopsTheMicrophone(unittest.TestCase):
    def test_the_deferred_stop_releases_capture_immediately(self):
        """Letting go must close the microphone, whatever the floor policy is.

        Holding the transmission slot open is a defensible choice. Holding the
        *microphone* open is not: it broadcasts the room after the operator
        stopped speaking, and they have no way to know.
        """
        body = section(read(SOCKET), "fun stopTalking()", "\n    fun ")
        deferral = body[: body.index("pendingStop?.let")]
        self.assertRegex(
            deferral,
            r"AudioRecorder\.stopRecording\(",
            "the microphone stays open for the rest of the minimum transmission, "
            "so the room is transmitted after the operator let go",
        )


class CaptureFailureIsVisible(unittest.TestCase):
    def test_the_recorder_reports_a_capture_it_could_not_open(self):
        """All three sources refusing must not look like transmitting."""
        source = read(RECORDER)
        self.assertRegex(
            source,
            r"onCaptureFailed|captureFailed",
            "a capture that never opened is never reported, so the UI holds TX "
            "while nothing is sent",
        )

    def test_the_socket_ends_a_transmission_that_never_captured(self):
        source = read(SOCKET)
        self.assertRegex(
            source,
            r"fun onCaptureFailed",
            "nothing clears the talking state when the microphone never opened",
        )

    def test_the_operator_is_told(self):
        body = section(read(SOCKET), "fun onCaptureFailed", "\n    private fun ")
        self.assertRegex(
            body,
            r"SoundManager\.|Toast",
            "the failure is silent to the operator, which is how it goes unnoticed",
        )


class BluetoothBoundMatchesRouteReadiness(unittest.TestCase):
    def test_the_fallback_waits_only_for_a_route_that_can_carry_a_microphone(self):
        """The bound must ask the same question the wait does.

        `isCaptureRouteReady()` uses `isBluetoothScoCapable`, so an A2DP-only
        speaker is correctly treated as ready at once. The timeout beside it
        still keys off `isBluetoothConnected`, so that same speaker extends the
        bound as though a headset were still connecting.
        """
        body = section(read(SOCKET), "private fun armAuthorizationFallback()", "\n    private fun ")
        self.assertNotRegex(
            body,
            r"isBluetoothConnected",
            "the fallback bound keys off mere Bluetooth presence rather than "
            "whether anything connected can carry a microphone",
        )
        self.assertRegex(body, r"isBluetoothScoCapable")


class NoDeadDebounceState(unittest.TestCase):
    def test_the_removed_lockout_leaves_no_field_behind(self):
        """A written-but-never-read timestamp reads as a lockout that still exists."""
        source = read(SOCKET)
        writes = re.findall(r"lastPttEndTime", source)
        if not writes:
            return
        self.fail(
            "lastPttEndTime is assigned but never read: the 300 ms lockout it fed "
            "is gone, and leaving the field implies a rule that no longer exists"
        )


if __name__ == "__main__":
    unittest.main()
