#!/usr/bin/env python3
"""Where an operator's password lives on the handset.

It lived in two places, both readable by anything with the file:

    putString("password", pass)              // SharedPreferences, cleartext
    File(filesDir, "cred.txt").writeText("$user|$pass")

and the manifest let both leave the device:

    android:allowBackup="true"

with backup_rules.xml and data_extraction_rules.xml still carrying the empty
Android Studio template, every rule commented out. Nothing was excluded from
cloud backup or device transfer.

minSdk is 16 and EncryptedSharedPreferences needs 23, so this splits the way
TLS already does here: the modern path gets the platform's keystore, the legacy
path keeps what it had, and the boundary is stated rather than hidden. What
does not split is cred.txt, which is redundant with the preferences on every
API level and strictly worse, and the backup rules, which apply to all of them.

The real answer is a revocable token rather than a stored password. That
changes the relay too and is not this.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def code(text: str) -> str:
    """Source with its comments removed.

    Six assertions in one session matched prose rather than code -- a comment
    explaining why something was removed says the same words the check was
    looking for. An absence check that reads comments cannot distinguish a
    change from an explanation of it.
    """
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)

JAVA = ROOT / "app/src/main/java/com/am2/am2"
MANIFEST = ROOT / "app/src/main/AndroidManifest.xml"


class CredentialStorageContractTest(unittest.TestCase):
    def test_no_source_writes_a_password_in_cleartext(self):
        offenders = []
        for path in JAVA.rglob("*.kt"):
            text = code(path.read_text())
            if re.search(r'putString\(\s*"password"', text):
                offenders.append(path.name)
            if re.search(r'"cred\.txt"[\s\S]{0,200}?writeText', text):
                offenders.append(path.name + " (writes cred.txt)")
        self.assertEqual(
            [], sorted(set(offenders)),
            "a password is written where anything with the file can read it",
        )

    def test_the_plaintext_file_is_removed_rather_than_merely_stopped(self):
        # Handsets already carry one. Not writing it again leaves every
        # existing device exactly as exposed as before.
        store = (JAVA / "CredentialStore.kt")
        self.assertTrue(store.is_file(), "nothing owns credential storage")
        self.assertIn(
            "cred.txt", code(store.read_text()),
            "the plaintext file that handsets already carry is never deleted",
        )

    def test_the_modern_path_uses_the_platform_keystore(self):
        secure = JAVA / "SecureCredentialStore.kt"
        self.assertTrue(secure.is_file(), "there is no encrypted path at all")
        self.assertIn("EncryptedSharedPreferences", code(secure.read_text()))

    def test_the_keystore_class_is_never_reached_below_its_own_minimum(self):
        # Referencing a class the platform cannot load is a verify-time crash on
        # old Android, so the import lives in a file the legacy path never
        # touches, and the branch is on the version.
        store = code((JAVA / "CredentialStore.kt").read_text())
        self.assertNotIn(
            "EncryptedSharedPreferences", store,
            "the keystore type is named in the file that runs on API 16",
        )
        self.assertRegex(
            store, r"SDK_INT\s*>=\s*(23|Build\.VERSION_CODES\.M)",
            "nothing checks the platform version before using a 23+ API",
        )

    def test_every_resource_xml_is_well_formed(self):
        """A resource the compiler rejects takes the whole build with it.

        The first version of the backup rules carried a prose double hyphen
        inside an XML comment, which the specification forbids, and aapt
        stopped the build on it. Nothing here had parsed the file that was
        written; CI did it first, several minutes later.
        """
        import xml.dom.minidom
        for path in sorted((ROOT / "app/src/main/res/xml").glob("*.xml")):
            with self.subTest(resource=path.name):
                try:
                    xml.dom.minidom.parse(str(path))
                except Exception as err:
                    self.fail(f"{path.name} is not well-formed XML: {err}")

    def test_backup_excludes_what_it_must_not_carry(self):
        manifest = MANIFEST.read_text()
        if 'android:allowBackup="true"' not in manifest:
            return

        # Each file on its own. Checking them together let an empty one pass on
        # the strength of the other, which is exactly what happened once here:
        # data_extraction_rules.xml was reverted to the template and the
        # assertion stayed green because backup_rules.xml was still correct.
        for name in ("backup_rules.xml", "data_extraction_rules.xml"):
            text = (ROOT / "app/src/main/res/xml" / name).read_text()
            uncommented = re.sub(r"<!--.*?-->", "", text, flags=re.S)
            self.assertIn(
                "exclude", uncommented,
                f"{name} excludes nothing, so stored credentials leave the "
                "device with the backup that is switched on",
            )
            for domain in ("sharedpref", "device_sharedpref", "file", "device_file"):
                self.assertIn(
                    f'domain="{domain}"', uncommented,
                    f"{name} leaves credential material in {domain} eligible for transfer",
                )



class DeviceTokenContractTest(unittest.TestCase):
    """The handset keeps a credential the operator can take back.

    Encrypting a stored password helps on the handsets that have a keystore and
    does nothing on the ones that do not, and in neither case does it make the
    credential revocable. It is the operator's own password, it works from any
    device, and a lost handset means changing it for the person.

    A device token is issued to one handset and deleted by an admin. That is
    the property worth having, and it is the reason the password may go.
    """

    def setUp(self):
        self.socket = (JAVA / "WebSocketManager.kt").read_text()
        self.store = (JAVA / "CredentialStore.kt").read_text()

    def test_a_token_is_sent_when_the_handset_has_one(self):
        self.assertRegex(
            code(self.socket), r'put\("token"',
            "the handset only ever offers a password, so the token it was "
            "issued does nothing",
        )

    def test_the_password_is_dropped_once_a_token_arrives(self):
        # Otherwise the token is an addition rather than a replacement, and
        # every handset still carries the thing that cannot be revoked.
        # The body of saveToken, not the file. A bare name match found the
        # definition of forgetPassword further down and passed against a build
        # where the call had been deleted -- the seventh assertion in this
        # session to match a declaration rather than a use.
        body = code(self.store)
        body = body[body.index("fun saveToken"):]
        body = body[:body.index("\n    fun ")]
        self.assertIn(
            "StoredCredentialState(username, null, token)", body,
            "the token is persisted without the username needed to present it after restart",
        )
        self.assertNotIn(
            "StoredCredentialState(username, password", body,
            "the password survives the token that replaces it",
        )

    def test_a_stored_token_counts_as_a_session(self):
        # The password is gone by then. Prove that init restores the complete
        # token-only state rather than merely naming the token flag somewhere.
        init = self.socket[self.socket.index("fun init(context: Context)"):]
        init = code(init[:init.index("\n    fun ")])
        self.assertIn(
            "CredentialStore.state(context)", init,
            "process recreation still loads only username+password, so a "
            "token-only session loses its username and cannot resume",
        )
        self.assertIn(
            "stored.canResume", init,
            "the restored username+token state never decides authorization",
        )

    def test_automatic_login_success_does_not_delete_the_token(self):
        login = (JAVA / "LoginActivity.kt").read_text()
        observer = login[login.index("WebSocketManager.loginEvent.observe"):]
        observer = code(observer[:observer.index("\n    }")])
        success = observer[observer.index("LoginEvent.Success"):observer.index("LoginEvent.Error")]
        self.assertNotIn(
            "CredentialStore", success,
            "the login screen mutates an automatic token session after success",
        )
        manager_success = code(self.socket)
        manager_success = manager_success[
            manager_success.index('"login_success" ->'):manager_success.index('"login_error" ->')
        ]
        self.assertIn(
            "wasInteractive", manager_success,
            "login success cannot distinguish explicit login from automatic reconnect",
        )
        self.assertIn(
            "shouldRemember", manager_success,
            "an explicit successful login ignores the Remember Me choice",
        )

    def test_explicit_logout_clears_the_persisted_session(self):
        socket = code(self.socket)
        logout = socket[socket.index("fun logout()"):]
        logout = logout[:logout.index("\n    fun ")]
        self.assertIn(
            "CredentialStore.clear(", logout,
            "logout leaves the token on disk, so the next launch signs back in",
        )
        self.assertRegex(
            logout,
            r"socketGeneration\s*\+=\s*1[\s\S]{0,500}?CredentialStore\.clear\(",
            "logout clears disk before invalidating an in-flight login callback",
        )
        store = code(self.store)
        clear = store[store.index("fun clear(context: Context)"):]
        clear = clear[:clear.index("\n    private fun ")]
        self.assertIn(
            ".commit()", clear,
            "logout uses asynchronous preference removal before process termination",
        )
        self.assertIn(
            "return cleared", clear,
            "logout ignores a failed durable credential deletion",
        )
        menu = code((JAVA / "MenuActivity.kt").read_text())
        self.assertIn(
            "if (!WebSocketManager.logout())", menu,
            "the Logout button exits even when session deletion failed",
        )
        self.assertNotIn(
            "System.exit", menu,
            "logout force-kills the process around credential deletion",
        )

    def test_a_revoked_token_is_discarded_rather_than_retried(self):
        self.assertRegex(
            code(self.socket), r'token_revoked[\s\S]{0,300}?clearToken\(',
            "a revoked token is kept and presented again, which is a loop",
        )

if __name__ == "__main__":
    unittest.main(verbosity=2)
