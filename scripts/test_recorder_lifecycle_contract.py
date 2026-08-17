#!/usr/bin/env python3
"""A restart must not run while the previous recording is still unwinding.

stopRecording only set a flag. The recording thread was still blocked inside
AudioRecord.read() and had not run its cleanup, so a new startRecording passed
the isRecording guard and:

  1. called createEncoder on the SHARED codec, destroying the native handle the
     old thread could still be inside nativeEncode with;
  2. built a new AudioRecord and published it;
  3. the old thread finally unwound, ran its cleanup, and released the NEW
     recorder, nulling the field;
  4. the new thread read `audioRecord ?: break` and stopped immediately.

The transmission then sent zero frames while the UI showed TX. It is a silent
failure: nothing throws, nothing logs, and the talker hears their own sidetone.

The guard is a join, not a flag. Publication of the shared fields is the rest.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "app/src/main/java/com/am2/am2"
RECORDER = JAVA / "AudioRecorder.kt"


def section(text: str, start: str, end: str) -> str:
    begin = text.index(start)
    return text[begin:text.index(end, begin)]


class RecorderLifecycleContractTest(unittest.TestCase):
    def setUp(self):
        self.text = RECORDER.read_text()

    def test_a_restart_waits_for_the_previous_recording_to_finish(self):
        start = section(self.text, "fun startRecording(", "private fun handleVoxLogic")
        self.assertIn("join(", start,
                      "startRecording does not wait for the previous thread to unwind")
        # The join has to come before the shared encoder is touched, or the
        # native handle can still be destroyed underneath the old thread.
        self.assertLess(start.index("join("), start.index("createEncoder"),
                        "the encoder is recreated before the previous thread has finished")

    def test_the_recording_thread_is_held_so_it_can_be_waited_on(self):
        # A bare `thread { }` returns a handle nobody kept, which is why the
        # original could only ever set a flag and hope.
        self.assertRegex(self.text, r"recordingThread\s*=",
                         "the recording thread handle is not retained")

    def test_the_recorder_handle_is_published_across_threads(self):
        # Written under a lock, read unlocked from the recording loop.
        self.assertRegex(self.text, r"@Volatile\s+private var audioRecord",
                         "audioRecord is still a plain field read across threads")

    def test_stopping_is_still_only_a_request_when_vox_or_gateway_owns_the_mic(self):
        stop = section(self.text, "fun stopRecording(", "\n    }")
        self.assertIn("voxEnabled", stop)
        self.assertIn("gatewayModeEnabled", stop)

    def test_no_new_unpublished_field_crosses_threads(self):
        # By absence: every mutable field in this file is either volatile,
        # atomic, or named here as deliberately confined to one thread.
        confined = {
            # Written and read only by the recording thread, inside handleVoxLogic.
            "voxTriggerCount", "voxSilenceTimer", "lastVoxTriggerAt",
            # Set once during init, before any thread reads it.
            "appContext",
        }
        # Public vars count too: settings write them, the recording thread reads
        # them. Restricting this to `private` is how two of them were missed.
        # Anchored to the object's own indent so locals inside functions and
        # fields of nested classes are not mistaken for shared state.
        declared = re.findall(r"^    (?:private )?var (\w+)", self.text, re.M)
        volatile = set(re.findall(r"@Volatile\n    (?:private )?var (\w+)", self.text))
        unpublished = {name for name in declared if name not in volatile} - confined
        self.assertEqual(set(), unpublished,
                         "a field crosses threads without being published")


if __name__ == "__main__":
    unittest.main()
