#!/usr/bin/env python3
"""A radio comes back the way it was left.

Credentials are on disk and survive an install-over: Android guarantees app
data across an update, and SharedPreferences is app data. What did not survive
was the app's willingness to use them.

    val startOnBoot = prefs?.getBoolean("start_on_boot", false) ?: false
    isAuthorizedSession = startOnBoot &&
            !savedUsername.isNullOrEmpty() &&
            !savedPassword.isNullOrEmpty()

isAuthorizedSession is the flag that means "there is a session worth
reconnecting". Gating it on start_on_boot -- a separate preference, off by
default, about whether the service should come up when the handset boots --
means a stored login is ignored unless the operator happened to enable an
unrelated setting.

It was reported as "every update deletes the session". Updates do end the
process, but so does swiping the app away and so does the system reclaiming
memory. The update is only where it is noticed most.

Two different questions, and they need two different answers: whether to start
without being asked, and whether there is a session to resume.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOCKET = ROOT / "app/src/main/java/com/am2/am2/WebSocketManager.kt"


def section(text: str, start: str, end: str) -> str:
    begin = text.index(start)
    return text[begin:text.index(end, begin)]


class SessionPersistenceContractTest(unittest.TestCase):
    def setUp(self):
        self.socket = SOCKET.read_text()

    def test_a_stored_login_is_a_session_whether_or_not_boot_start_is_on(self):
        init = section(self.socket, "fun init(context: Context)", "\n    fun ")
        assignment = re.search(r"isAuthorizedSession\s*=\s*([^\n]*(?:\n[^\n]*){0,3})", init)
        self.assertIsNotNone(assignment, "nothing decides whether a session resumes")
        self.assertNotIn(
            "startOnBoot", assignment.group(1),
            "resuming a stored session is gated on the boot preference, so a "
            "handset that has credentials still comes back signed out",
        )

    def test_the_credentials_themselves_still_decide(self):
        init = section(self.socket, "fun init(context: Context)", "\n    fun ")
        assignment = re.search(r"isAuthorizedSession\s*=\s*([^\n]*(?:\n[^\n]*){0,3})", init)
        self.assertIsNotNone(assignment, "nothing decides whether a session resumes")
        self.assertIn(
            "stored.canResume", assignment.group(1),
            "authorization does not come from the complete persisted credential state",
        )

    def test_boot_start_still_decides_starting_on_boot(self):
        # The preference keeps its own job, in the receiver that owns it.
        # Removing the gate must not remove the feature -- and naming the file
        # that actually reads it is how this assertion says which is which.
        boot = (ROOT / "app/src/main/java/com/am2/am2/BootReceiver.kt").read_text()
        self.assertIn(
            '"start_on_boot"', boot,
            "the boot preference no longer decides anything at all",
        )

    def test_the_boot_preference_no_longer_gates_the_session(self):
        # By absence, in the file that resumes: the two questions were joined
        # here and nowhere else.
        # The read, not the name. The comment above the assignment explains
        # why the gate was removed and says "start_on_boot" while doing it --
        # an absence check on the bare name fails against the very change it is
        # meant to protect.
        init = section(self.socket, "fun init(context: Context)", "\n    fun ")
        self.assertNotRegex(
            init, r'getBoolean\(\s*"start_on_boot"',
            "the session path reads the boot preference again",
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
