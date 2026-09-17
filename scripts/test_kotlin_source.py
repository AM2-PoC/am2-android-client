#!/usr/bin/env python3
import unittest

from kotlin_source import executable_text


class KotlinSourceTest(unittest.TestCase):
    def test_nested_comments_are_removed(self):
        source = "val before = 1\n/* outer /* nested */ forbidden() */\nval after = 2"
        cleaned = executable_text(source)
        self.assertNotIn("forbidden", cleaned)
        self.assertIn("val before = 1", cleaned)
        self.assertIn("val after = 2", cleaned)

    def test_comment_markers_inside_strings_remain(self):
        source = 'val mime = "*/*"\nval url = "https://example.invalid"\nallowed()'
        cleaned = executable_text(source)
        self.assertIn('"*/*"', cleaned)
        self.assertIn('"https://example.invalid"', cleaned)
        self.assertIn("allowed()", cleaned)


if __name__ == "__main__":
    unittest.main()
