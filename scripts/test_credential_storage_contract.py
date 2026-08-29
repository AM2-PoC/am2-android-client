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


if __name__ == "__main__":
    unittest.main(verbosity=2)
