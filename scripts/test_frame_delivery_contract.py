#!/usr/bin/env python3
"""A frame is delivered on the strength of who sent it, not what they are called.

Inbound binary frames were discarded when the sender's numeric id could not be
found in the locally held roster. That roster arrives in a separate message from
the stream status, so the two disagree whenever the roster is stale: the status
says someone is streaming, the screen switches to the incoming view, and every
one of that sender's frames is thrown away. The result is a black screen with
nothing logged, and silent audio from the same sender.

The window opens on every reconnect and for anyone who joined after the roster
snapshot, which is why it reads as a network problem when it is a race.

A name is a label. Delivery must not depend on having one.
"""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WS = ROOT / "app/src/main/java/com/am2/am2/WebSocketManager.kt"


def section(text: str, start: str, end: str) -> str:
    begin = text.index(start)
    return text[begin:text.index(end, begin)]


class FrameDeliveryContractTest(unittest.TestCase):
    def setUp(self):
        self.text = WS.read_text()
        self.handler = section(
            self.text,
            "private fun handleBinaryMessage(",
            "\n    private fun ",
        )

    def test_an_unnamed_sender_does_not_lose_its_frames(self):
        self.assertNotIn(
            "if (senderName == null) return",
            self.handler,
            "frames are still discarded when the roster has not caught up",
        )

    def test_every_sender_resolves_to_a_stable_identity(self):
        # Same numeric id must always produce the same key: the decoder map and
        # the speaker set are keyed by it, so an identity that varied per frame
        # would build a decoder per frame.
        self.assertIn("fun senderIdentity(", self.text)
        identity = section(self.text, "fun senderIdentity(", "\n    }")
        self.assertIn("findUserNameById", identity)
        self.assertNotIn("System.currentTimeMillis", identity)
        self.assertNotIn("Random", identity)

    def test_the_private_call_check_is_still_an_authorisation_decision(self):
        # Naming and authorisation were tangled together. Dropping a frame
        # because it is not from the private-call peer is correct and must stay.
        self.assertIn("targetIdInt", self.handler)
        self.assertIn("return", self.handler)

    def test_a_frame_from_an_unknown_sender_is_traceable(self):
        # It used to vanish with no record, which is why this took so long to
        # find. It has to be visible once, not per frame.
        self.assertIn("unknown sender", self.text.lower())


if __name__ == "__main__":
    unittest.main()
