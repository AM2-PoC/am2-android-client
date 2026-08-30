#!/usr/bin/env python3
"""The capture path installs no audio effect on the operator's microphone.

Before any of the VOX work this file attached nothing: no AutomaticGainControl,
no NoiseSuppressor, no AcousticEchoCanceler. `grep -c audiofx` on the commit
before 523db03 returns zero. The radio worked.

523db03 added all three, every one of them `enabled = true`, on the reasoning
that a handset whose VOX could not hear quiet speech needed help. c02697f and
f77fb34 then took only the suppressor's enable back out, which left a fourth
state that had never existed anywhere: gain control on, echo canceller on,
suppressor at whatever the platform chose.

What the field reported was audio arriving as fragments, on the button as well
as on VOX -- and it is the button that rules the VOX logic out, because this is
the capture path and every transmission goes through it. Automatic gain control
raises quiet passages, which on a loudspeaker handset means room noise and the
radio's own output are lifted with them; the measured sustained level in the
microphone reached 639 against a release threshold of 625, so VOX could not let
go either.

None of it was ever measured against the state it replaced. So the effects come
off, and the question that started it -- VOX deaf to quiet speech -- goes back
to the threshold and to hysteresis, where it belonged.

Reintroducing any of these means measuring it first, which is why this test
names all three rather than the one that was loudest.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RECORDER = ROOT / "app/src/main/java/com/am2/am2/AudioRecorder.kt"

EFFECTS = ("AutomaticGainControl", "NoiseSuppressor", "AcousticEchoCanceler")


def code(text: str) -> str:
    """Source without comments: this file explains the removal at length."""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


class CaptureEffectsContractTest(unittest.TestCase):
    def setUp(self):
        self.recorder = code(RECORDER.read_text(encoding="utf-8"))

    def test_no_audio_effect_is_attached_to_the_capture_session(self):
        for effect in EFFECTS:
            self.assertNotIn(
                effect, self.recorder,
                "%s is attached to the operator's microphone again. It was not "
                "there before the VOX work and the radio worked; putting one back "
                "needs a measurement against the state it replaces." % effect,
            )

    def test_nothing_imports_the_effects_api_at_all(self):
        self.assertNotIn(
            "android.media.audiofx", self.recorder,
            "the capture path still reaches for the audio effects API",
        )

    def test_the_recorder_still_reports_what_vox_measured(self):
        # The telemetry stays. It is what identified this, and it costs the
        # capture path nothing.
        self.assertRegex(
            self.recorder, r'WebSocketManager\.emit\(\s*"vox_level"',
            "removing the effects also removed the measurement that found them",
        )


if __name__ == "__main__":
    unittest.main()
