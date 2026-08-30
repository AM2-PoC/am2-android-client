#!/usr/bin/env python3
"""The one control on the login screen, and what it actually promises.

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

    def _checkbox(self):
        start = self.layout.index("@+id/cbRememberMe")
        end = self.layout.index("/>", start)
        return self.layout[start:end]

    def test_the_control_says_what_it_does(self):
        box = self._checkbox()
        self.assertRegex(
            box, r"android:(text|contentDescription)=",
            "the control that decides whether the handset can sign itself back in "
            "is an unlabelled tick box",
        )

    def test_ticking_it_does_not_disable_the_fields_you_still_have_to_fill(self):
        listener = self.login[self.login.index("cbRememberMe.setOnCheckedChangeListener"):]
        listener = listener[:listener.index("\n        }")]
        self.assertNotIn(
            "updateInputStates(isChecked)", listener,
            "ticking the box locks the username and password fields, so on a fresh "
            "install an operator who ticks it first cannot type at all",
        )

    def test_the_lock_belongs_to_having_credentials_already(self):
        auto = self.login[self.login.index("fun checkAutoLogin()"):]
        self.assertIn(
            "updateInputStates(true)", auto,
            "nothing locks the fields even when they are filled in for you",
        )

    def test_no_stored_password_is_put_back_on_screen(self):
        self.assertNotIn(
            "etPassword.setText(", self.login,
            "the login screen still writes a stored password into a visible field",
        )


if __name__ == "__main__":
    unittest.main()
