#!/usr/bin/env python3
import unittest
from pathlib import Path

from kotlin_source import executable_text

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/com/am2/am2/MapsActivity.kt"


def talking_assignments(source: str) -> str:
    start = source.index("private fun updateTalkingState")
    end = source.index("private fun centerOnMe", start)
    return source[start:end]


class MapTalkIndicatorContractTest(unittest.TestCase):
    def test_talking_state_keeps_the_speaking_indicator(self):
        source = executable_text(talking_assignments(SOURCE.read_text()))
        self.assertIn('binding.txtSpeakerName.text = "\\uD83D\\uDCE2 You are Speaking"', source)
        self.assertIn('binding.txtSpeakerName.text = "\\uD83D\\uDCE2 $username is Speaking"', source)

    def test_comment_only_indicator_does_not_satisfy_the_contract(self):
        source = executable_text('''
            private fun updateTalkingState() {
                /* binding.txtSpeakerName.text = "\\uD83D\\uDCE2 You are Speaking" */
                // binding.txtSpeakerName.text = "\\uD83D\\uDCE2 $username is Speaking"
            }
            private fun centerOnMe() {}
        ''')
        self.assertNotIn('binding.txtSpeakerName.text = "\\uD83D\\uDCE2 You are Speaking"', source)
        self.assertNotIn('binding.txtSpeakerName.text = "\\uD83D\\uDCE2 $username is Speaking"', source)


if __name__ == "__main__":
    unittest.main()
