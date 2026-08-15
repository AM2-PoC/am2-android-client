#!/usr/bin/env python3
"""Outgoing video must not be able to fall behind, and must encode once.

The capture callback submitted work on a wall-clock schedule to an executor
with an unbounded queue, so whenever encoding took longer than the interval the
backlog grew and the far end fell permanently behind live. Each frame was also
encoded twice, which is what made encoding slow enough for that to happen.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VIDEO = ROOT / "app/src/main/java/com/am2/am2/VideoActivity.kt"
LAYOUT = ROOT / "app/src/main/res/layout/activity_video.xml"


def section(text: str, start: str, end: str) -> str:
    begin = text.index(start)
    return text[begin:text.index(end, begin)]


class VideoPipelineContractTest(unittest.TestCase):
    def setUp(self):
        self.text = VIDEO.read_text()
        self.frame = section(self.text, "override fun onPreviewFrame(", "override fun onKeyDown(")

    def test_a_frame_is_captured_only_when_the_encoder_is_free(self):
        # Backpressure, not a schedule: queue depth can never exceed one.
        self.assertIn("compareAndSet(false, true)", self.frame)
        self.assertIn("AtomicBoolean", self.text)

    def test_the_wall_clock_throttle_is_gone(self):
        self.assertNotIn("FRAME_INTERVAL", self.text)
        self.assertNotIn("lastFrameTime", self.text)

    def test_the_gate_is_always_released(self):
        # A frame that throws must not wedge the pipeline shut forever.
        self.assertRegex(self.frame, r"finally\s*\{[^}]*encoding\.set\(false\)")

    def test_each_frame_is_encoded_exactly_once(self):
        self.assertIn("compressToJpeg", self.frame)
        # The decode-and-re-encode round trip is what made a frame cost more
        # than its own interval.
        self.assertNotIn("BitmapFactory.decodeByteArray", self.frame)
        self.assertNotIn("CompressFormat.WEBP", self.text)
        self.assertNotIn("Bitmap.createBitmap", self.frame)

    def test_rotation_happens_on_the_yuv_bytes(self):
        # Extracted so the index arithmetic can be tested directly; a wrong
        # index corrupts every frame and is invisible in review.
        self.assertIn("Nv21Transform.rotate(", self.frame)
        self.assertNotIn("Matrix()", self.frame)
        self.assertTrue((ROOT / "app/src/test/java/com/am2/am2/Nv21TransformTest.kt").is_file())

    def test_camera_geometry_is_not_read_per_frame(self):
        # camera.parameters is a native round trip and races releaseCamera().
        self.assertNotIn("camera?.parameters", self.frame)
        self.assertNotIn("camera.parameters", self.frame)
        self.assertIn("previewWidth", self.text)
        self.assertIn("previewHeight", self.text)

    def test_the_camera_is_asked_for_a_small_preview(self):
        open_camera = section(self.text, "private fun openCamera()", "private fun releaseCamera()")
        self.assertIn("supportedPreviewSizes", open_camera)
        self.assertIn("TARGET_FRAME_EDGE", open_camera)
        self.assertIn("ImageFormat.NV21", open_camera)


if __name__ == "__main__":
    unittest.main()
