"""What the About screen shows after the socket comes back.

The screen read one of the two identity fields once and observed the other:

    binding.tvAboutUserId.text = "Username: ${WebSocketManager.myUserId ?: "-"}"
    WebSocketManager.myUserNameLiveData.observe(this) { name -> ... }

WebSocketManager.clearSession() sets myUserId to null on every disconnect, and
production shows this handset re-authenticating roughly ninety-five times a day
-- through the night, so it is the socket, not an operator. Opening the screen
inside one of those gaps therefore renders "Username: -", and because the read
was a snapshot rather than a subscription, it stays that way for the life of the
activity even after the session is fully restored.

That is the reported fault: the operator sees the update screen fail to know who
they are, some time after a login that worked.

The fix is the shape already used for the name: a LiveData mirror, so the field
cannot be rendered from a stale snapshot. This contract locks the invariant
rather than the spelling of one line -- identity on screen is subscribed to, not
sampled.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANAGER = ROOT / "app/src/main/java/com/am2/am2/WebSocketManager.kt"
ABOUT = ROOT / "app/src/main/java/com/am2/am2/AboutActivity.kt"


def code(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


class TheScreenFollowsTheSessionTest(unittest.TestCase):
    def setUp(self):
        self.manager = code(MANAGER.read_text(encoding="utf-8"))
        self.about = code(ABOUT.read_text(encoding="utf-8"))

    def test_both_identity_fields_are_observable(self):
        # Not one of them. An asymmetry here is exactly how this fault arrived:
        # the name was made observable and the id was left a plain read.
        for field in ("myUserNameLiveData", "myUserIdLiveData"):
            self.assertTrue(
                re.search(r"val %s\s*:\s*LiveData" % field, self.manager),
                "%s is not exposed as LiveData, so a screen showing it cannot "
                "learn that the session came back" % field,
            )

    def test_the_identity_livedata_is_actually_fed(self):
        # A LiveData nothing ever posts to is a field that is always null, which
        # would pass the declaration check above and still show a dash forever.
        self.assertTrue(
            re.search(r"_myUserIdLiveData\.postValue", self.manager),
            "nothing ever publishes the user id, so the mirror stays empty",
        )

    def test_the_about_screen_subscribes_to_the_identity(self):
        for field in ("myUserNameLiveData", "myUserIdLiveData"):
            self.assertTrue(
                re.search(r"%s\s*\.observe\(" % field, self.about),
                "the About screen does not observe %s, so whatever it renders "
                "is whatever happened to be true when the screen opened" % field,
            )

    def test_the_about_screen_does_not_sample_the_identity(self):
        # The specific regression: a direct read of the backing property into a
        # view. Observing and then also sampling would reintroduce the stale
        # first paint this contract exists to prevent.
        offenders = re.findall(
            r"^.*\.text\s*=.*WebSocketManager\.(myUserId|myUserName)\b.*$",
            self.about,
            flags=re.M,
        )
        self.assertEqual(
            offenders, [],
            "the About screen still assigns a view from a one-shot read of "
            "WebSocketManager identity; use the LiveData so the screen "
            "corrects itself when the socket returns",
        )


if __name__ == "__main__":
    unittest.main()
