#!/usr/bin/env python3
"""An update that is refused says which check refused it.

UpdateVerifier.verify() answers eight different questions and returns the same
Boolean for all of them, and AboutActivity turns that Boolean into one
sentence: "Identitas atau signature APK tidak valid." A truncated download, a
version code that did not advance, a package name mismatch and an actual
signature failure are indistinguishable to the operator holding the radio and
to anyone reading a bug report from them.

That is not a cosmetic problem. A field handset refused an update whose signing
certificate was afterwards proven identical to the build already installed, and
nothing on the device or off it could name the reason.

There is also a real defect on the path that runs on modern Android:

    val flags = GET_SIGNATURES or (if SDK >= P) GET_SIGNING_CERTIFICATES
    ...
    val signingInfo = archive.signingInfo ?: return false

GET_SIGNATURES is requested and then never read on that branch. When
getPackageArchiveInfo returns a populated `signatures` array and a null
`signingInfo` -- which happens -- the update is refused although the
certificate it needed was right there.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
VERIFIER = ROOT / "app/src/main/java/com/am2/am2/update/UpdateVerifier.kt"
ABOUT = ROOT / "app/src/main/java/com/am2/am2/AboutActivity.kt"


def code(text: str) -> str:
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.S)
    return re.sub(r"//[^\n]*", "", text)


class UpdateVerifierContractTest(unittest.TestCase):
    def setUp(self):
        self.verifier = code(VERIFIER.read_text(encoding="utf-8"))
        self.about = code(ABOUT.read_text(encoding="utf-8"))

    def test_no_check_refuses_without_naming_itself(self):
        # A bare `return false` is a refusal nobody can act on.
        self.assertNotIn(
            "return false", self.verifier,
            "a check refuses without saying which one it was",
        )

    def test_the_reasons_are_distinct(self):
        # The stable prefix is the identifier; some reasons append the value
        # they saw, which is what makes a bug report actionable.
        reasons = re.findall(r'Refused\(\s*"([a-z0-9_]+)', self.verifier)
        self.assertGreaterEqual(
            len(reasons), 7,
            "fewer refusal reasons than there are checks: %r" % (reasons,),
        )
        self.assertEqual(
            len(reasons), len(set(reasons)),
            "two different checks refuse with the same reason: %r" % (reasons,),
        )

    def test_a_null_signing_info_falls_back_to_the_signatures_it_asked_for(self):
        modern = self.verifier[self.verifier.index("GET_SIGNING_CERTIFICATES"):]
        self.assertNotIn(
            "signingInfo ?: return", modern,
            "a null signingInfo refuses the update although GET_SIGNATURES was "
            "requested and archive.signatures may hold the certificate",
        )
        self.assertIn(
            "archive.signatures", modern,
            "the signatures the flag asked for are never read on the modern path",
        )

    def test_the_operator_is_told_which_check_refused(self):
        self.assertNotIn(
            'Exception("Identitas atau signature APK tidak valid.")', self.about,
            "eight different failures still reach the operator as one sentence",
        )
        self.assertRegex(
            self.about, r"(reason|refus)",
            "the update screen never surfaces the refusal reason",
        )


if __name__ == "__main__":
    unittest.main()
