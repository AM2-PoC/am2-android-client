#!/usr/bin/env python3
"""No placeholder label may be shown over the video.

The idle banner read "PREVIEW" from a string literal in code rather than from
resources, so it never passed resource review and reached production as a
green caption across the top of the camera.
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


class VideoOverlayContractTest(unittest.TestCase):
    def setUp(self):
        self.text = VIDEO.read_text()

    def test_no_placeholder_label_is_shown_over_the_camera(self):
        # A hardcoded string bypassed resource review and reached production as
        # a banner across the video.
        self.assertNotIn('"PREVIEW"', self.text)

    def test_the_overlay_is_hidden_when_nobody_is_streaming(self):
        observer = section(self.text, "WebSocketManager.activeVideoStreamers.observe", "WebSocketManager.isTalking.observe")
        self.assertIn("layoutVideoInfo", observer)
        self.assertRegex(observer, r"layoutVideoInfo\.visibility\s*=\s*View\.GONE")
        self.assertRegex(observer, r"layoutVideoInfo\.visibility\s*=\s*View\.VISIBLE")

    def test_the_overlay_has_an_id_so_it_can_be_hidden(self):
        self.assertIn('android:id="@+id/layoutVideoInfo"', LAYOUT.read_text())

    def test_remaining_overlay_text_comes_from_resources(self):
        observer = section(self.text, "WebSocketManager.activeVideoStreamers.observe", "WebSocketManager.isTalking.observe")
        for literal in re.findall(r'"([A-Za-z][^"]*)"', observer):
            self.assertNotRegex(literal, r"^[A-Z ]{4,}$", f"user-facing literal {literal!r} belongs in strings.xml")


if __name__ == "__main__":
    unittest.main()
