#!/usr/bin/env python3
"""Login UI must never restore a stored password into a visible field."""
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
    """Every member declaration must remain at class scope."""

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
