"""What starts when the handset does.

BootReceiver launched LoginActivity:

    val launchIntent = Intent(context, LoginActivity::class.java)
        .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    context.startActivity(launchIntent)

Android 10 forbids starting an activity from the background. A broadcast
receiver is the background, so on API 29 and above the system drops the launch
without an error, a toast, or a log the operator could see. The setting was
therefore doing nothing at all on every handset in the fleet -- while being
labelled "Auto Login", which is how it came to be blamed for sessions not
resuming.

And an activity was the wrong thing to want. A radio coming out of a reboot
needs the service that holds the socket and the microphone, not a login screen
in the operator's face. PTTService initialises WebSocketManager, AudioRecorder
and SoundManager in its own onCreate, so it runs perfectly well with no
activity above it, and it stops itself when there is no session to resume.

Starting a foreground service is what BOOT_COMPLETED is allowed to do, which is
the other half of why this is the right call and the old one was not.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RECEIVER = ROOT / "app/src/main/java/com/am2/am2/BootReceiver.kt"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"
SERVICE = ROOT / "app/src/main/java/com/am2/am2/PTTService.kt"


def code(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


class TheRadioComesUpOnBootTest(unittest.TestCase):
    def setUp(self):
        self.receiver = code(RECEIVER.read_text(encoding="utf-8"))
        self.manifest = MANIFEST.read_text(encoding="utf-8")
        self.service = code(SERVICE.read_text(encoding="utf-8"))

    def test_boot_does_not_try_to_launch_a_screen(self):
        self.assertNotIn(
            "startActivity", self.receiver,
            "the boot receiver still launches an activity, which Android 10 and "
            "above drop silently -- the setting does nothing at all",
        )

    def test_boot_starts_the_service_that_holds_the_radio(self):
        # The call, with its bracket, and the service it is given. Matching the
        # name alone let `startForegroundServiceX(` satisfy this.
        self.assertRegex(
            self.receiver,
            r"ContextCompat\.startForegroundService\([\s\S]{0,200}?PTTService::class\.java",
            "nothing brings up the service that owns the socket and the "
            "microphone, so a rebooted handset is off the air until somebody "
            "opens the app",
        )
        self.assertNotIn(
            "if (false)", self.receiver,
            "the boot path is present but fenced off",
        )

    def test_it_still_honours_the_operator_choice(self):
        self.assertIn(
            '"start_on_boot"', self.receiver,
            "the boot path ignores the setting the operator can turn off",
        )

    def test_the_permission_and_the_receiver_are_declared(self):
        self.assertIn("android.permission.RECEIVE_BOOT_COMPLETED", self.manifest)
        self.assertIn("BootReceiver", self.manifest)
        self.assertIn("android.intent.action.BOOT_COMPLETED", self.manifest)

    def test_the_service_refuses_to_run_without_a_session(self):
        # Always-persisted sessions mean this is normally true; when it is not,
        # a service holding a notification and no session is worse than nothing.
        self.assertRegex(
            self.service,
            r"hasAuthorizedSession\(\)[\s\S]{0,400}?START_NOT_STICKY",
            "the service would stay up with no session to resume",
        )


if __name__ == "__main__":
    unittest.main()
