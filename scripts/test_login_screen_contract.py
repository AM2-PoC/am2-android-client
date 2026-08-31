#!/usr/bin/env python3
"""What the login screen keeps, and what it must never keep.

    The control this file was written for is gone: a radio assigned to a unit
    stays signed in, and being asked to choose was how one quietly stopped.
    What survives is the half that was always right -- the unit id is not a
    credential and is remembered, the password is neither.

The checkbox beside LOGIN carries no label at all -- no android:text, no
contentDescription, a grey tick box next to a button. Nothing on screen says
what it does, and what it does is decide whether this handset can bring itself
back after a restart. That is not a preference; on a field radio it is the
difference between a unit that returns by itself and one that needs a person
holding it.

Two behaviours behind it were wrong as well:

  * Ticking it disabled the username and password fields. The lock only makes
    sense once credentials are filled in for you; the listener applied it on
    any tick, so on a fresh install an operator who ticked the box first could
    no longer type anything.

  * It re-filled the password field from storage. Since the relay issues a
    device token there is no stored password to fill, and the line survives as
    the last place that would put one back on screen.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOGIN = ROOT / "app/src/main/java/com/am2/am2/LoginActivity.kt"
LAYOUT = ROOT / "app/src/main/res/layout/activity_login.xml"


def code(text: str) -> str:
    """Source without comments, so prose cannot satisfy an absence check."""
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


class LoginScreenContractTest(unittest.TestCase):
    def setUp(self):
        self.login = code(LOGIN.read_text(encoding="utf-8"))
        self.layout = LAYOUT.read_text(encoding="utf-8")

    def test_no_stored_password_is_put_back_on_screen(self):
        self.assertNotIn(
            "etPassword.setText(", self.login,
            "the login screen still writes a stored password into a visible field",
        )



class TheFileStillHasItsShapeTest(unittest.TestCase):
    """Every declaration sits where it was declared to sit.

    Removing a listener block took onCreate's closing brace with it. The file
    still had equal numbers of braces -- a whole-file count said zero and gave
    false confidence -- while every function after the deletion had become a
    local one inside onCreate. The compiler said so plainly:

        Modifier 'private' is not applicable to 'local function'

    Balance is not structure. This checks the structure.
    """

    def setUp(self):
        self.text = LOGIN.read_text(encoding="utf-8")

    def test_every_member_function_sits_at_class_level(self):
        body = self.text[self.text.index("class LoginActivity"):]
        depth = 0
        misplaced = []
        for line in body.split("\n"):
            if re.match(r"    (private |protected |override |)fun \w+", line):
                if depth != 1:
                    misplaced.append((line.strip()[:60], depth))
            depth += line.count("{") - line.count("}")
        self.assertEqual(
            [], misplaced,
            "these are nested inside another function, so the class lost a "
            "closing brace somewhere above them: %r" % (misplaced,),
        )

    def test_the_class_closes_exactly_once(self):
        body = self.text[self.text.index("class LoginActivity"):]
        depth = 0
        for ch in body:
            if ch == "{":
                depth += 1
            elif ch == "}":
                depth -= 1
                self.assertGreaterEqual(depth, 0, "a brace closes more than was opened")
        self.assertEqual(depth, 0, "the class body is left open")


if __name__ == "__main__":
    unittest.main()
