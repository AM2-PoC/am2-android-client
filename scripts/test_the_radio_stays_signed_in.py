"""A field radio stays signed in; it is not asked to choose.

"Remember me" is a browser idea. It exists because a browser may be running on
a shared computer, so the person is offered a choice. A handset assigned to a
unit is not a shared computer, and a radio that signs itself out is a radio off
the air -- which is a safety property, not a convenience one.

The control also did more than not-save. An interactive login without it ran
CredentialStore.clear(), so one unticked sign-in threw away a token that was
already working. Until #62 it carried no label at all: a grey tick box beside
LOGIN whose effect was destructive and whose meaning was nowhere on screen.
That is how a fleet radio quietly stopped resuming.

So the choice is gone and the session is always kept. Two things replace it:

  the username is remembered, because it is not a credential and retyping a
  unit id is the part that was actually tedious;

  the password is not, and is never written to a field or to disk. A lost
  handset must cost a revocation, not a password change for the person.

Logout stays. With the session permanent it stops meaning "step away" and comes
to mean "this radio is going to a different operator" -- which is exactly when
a password should be typed.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LOGIN = ROOT / "app/src/main/java/com/am2/am2/LoginActivity.kt"
LAYOUT = ROOT / "app/src/main/res/layout/activity_login.xml"
SOCKET = ROOT / "app/src/main/java/com/am2/am2/WebSocketManager.kt"


def code(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


class TheRadioStaysSignedInTest(unittest.TestCase):
    def setUp(self):
        self.login = code(LOGIN.read_text(encoding="utf-8"))
        self.layout = LAYOUT.read_text(encoding="utf-8")
        self.socket = code(SOCKET.read_text(encoding="utf-8"))

    def test_the_operator_is_not_asked_whether_to_stay_signed_in(self):
        self.assertNotIn("cbRememberMe", self.layout,
                         "the choice is still on the login screen")
        self.assertNotIn("cbRememberMe", self.login,
                         "the login screen still reads the choice")

    def test_an_interactive_login_is_always_kept(self):
        # login() used to take the answer and pass it straight through.
        self.assertNotRegex(
            self.socket, r"fun login\([^)]*remember\s*:",
            "signing in still takes a remember flag, so a caller can still "
            "ask for a session that evaporates",
        )

    def test_nothing_clears_the_store_merely_because_a_choice_was_absent(self):
        success = self.socket[self.socket.index('"login_success" ->'):]
        success = success[:success.index('"login_error" ->')]
        self.assertNotIn(
            "shouldRemember", success,
            "the stored session still depends on a choice that no longer exists",
        )

    def test_the_username_survives_signing_out(self):
        # Both halves. An earlier version of this matched the write alone and
        # survived deleting the read, which would have shipped a unit id that
        # is stored and never used.
        self.assertRegex(
            self.login, r"edit\(\)[\s\S]{0,80}?putString\(LAST_USERNAME",
            "the unit id is never recorded when it is typed",
        )
        self.assertRegex(
            self.login,
            r"getString\(LAST_USERNAME[\s\S]{0,200}?etUsername\.setText",
            "the recorded unit id is never put back on the screen, so it still "
            "has to be retyped after signing out",
        )

    def test_the_password_is_never_put_back_on_screen(self):
        self.assertNotIn("etPassword.setText(", self.login,
                         "a stored password is written into a visible field")

    def test_logout_still_exists(self):
        self.assertIn("fun logout()", self.socket,
                      "the operator can no longer hand the radio to someone else")


if __name__ == "__main__":
    unittest.main()
