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

    def test_every_helper_this_file_calls_is_declared_in_it(self):
        """A text edit can delete a declaration and leave its callers.

        That is exactly what happened: replacing reportVoxLevel wholesale meant
        slicing from its opening line to handleVoxLogic's, and noteVoxBlock sat
        between the two. The slice took the declaration; three calls stayed.

        No contract here could see it. They match text, and the text they were
        asserting on -- the calls -- was still present and still correct. Only a
        compiler resolves a reference, and there is no JDK on this host, so the
        first thing that noticed was CI six minutes later.

        This is the cheap half of a compiler: an unqualified call to a name
        this file never declares. Five such names are real -- two Kotlin
        builtins and three methods reached inside an `apply` receiver -- and
        they are named below, so anything new is a genuine unresolved
        reference.
        """
        text = re.sub(r"/\*.*?\*/", "", self.text, flags=re.S)
        text = re.sub(r"//[^\n]*", "", text)
        # Strings can hold parentheses and would otherwise read as call sites.
        text = re.sub(r'"(?:[^"\\]|\\.)*"', '""', text)

        declared = set(re.findall(r"\bfun\s+(\w+)\s*\(", text))
        called = set(re.findall(r"(?<![.\w])([a-z]\w*)\s*\(", text))

        control_flow = {"if", "while", "for", "when", "catch", "return", "try",
                        "synchronized", "run", "let", "apply", "also", "require", "check"}
        # Kotlin's own, and three reached through an `apply`/`?.apply` receiver.
        elsewhere = {"arrayOf", "thread", "putExtra", "release", "stop"}

        self.assertEqual(
            set(), called - declared - control_flow - elsewhere,
            "a name is called here that nothing in this file declares",
        )

    def test_no_new_unpublished_field_crosses_threads(self):
        # By absence: every mutable field in this file is either volatile,
        # atomic, or named here as deliberately confined to one thread.
        confined = {
            # Written and read only by the recording thread, inside handleVoxLogic.
            "voxTriggerCount", "voxSilenceTimer", "lastVoxTriggerAt",
            # Same thread, inside reportVoxLevel: diagnostic accumulators and
            # the stamp that rate-limits them. Nothing outside the loop reads
            # any of these -- they are summed per frame by the recording thread
            # and zeroed by the same thread when the window closes.
            "voxLevelPeak", "voxLevelReportedAt",
            "voxLevelSum", "voxLevelFloor", "voxLevelFrames",
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
