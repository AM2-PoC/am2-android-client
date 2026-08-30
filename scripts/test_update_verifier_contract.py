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



class TheChannelSurvivesAPathChangeTest(unittest.TestCase):
    """A handset already in the field must not be stranded by a URL edit.

    UpdateMetadata required the manifest's update_url to equal a string
    compiled into the APK, character for character. Every handset already
    installed therefore accepts exactly one path forever: change it, and those
    units can never be updated again by any means the operator has. They are
    not broken and not reachable -- the definition of stranded.

    What the check is for is making sure an APK cannot be fetched from
    somewhere else. That is a statement about the origin, not about the path,
    so the origin is what is compared and the path comes from the manifest.
    """

    def setUp(self):
        self.metadata = code(
            (ROOT / "app/src/main/java/com/am2/am2/update/UpdateMetadata.kt")
            .read_text(encoding="utf-8"))

    def test_the_path_is_not_frozen_into_every_installed_handset(self):
        self.assertNotIn(
            "require(url == approvedUrl)", self.metadata,
            "the manifest path must match a compiled literal exactly, so changing "
            "it strands every handset already in the field",
        )

    def test_the_origin_is_still_enforced(self):
        # Loosening the path must not loosen where an APK may come from.
        self.assertRegex(
            self.metadata, r"(origin|scheme|host)",
            "nothing constrains where the APK may be fetched from any more",
        )
        self.assertIn(
            "approvedUrl", self.metadata,
            "the build no longer states which channel it trusts",
        )


class ARefusalReachesTheRelayTest(unittest.TestCase):
    """A stranded handset says why, without anyone reading a toast to us.

    The reason a refusal is invisible is that it lives in a Toast on a radio in
    somebody's hand. vox_level is the precedent: three rounds of argument about
    VOX ended the moment the handset reported its own numbers.
    """

    def setUp(self):
        self.about = code(
            (ROOT / "app/src/main/java/com/am2/am2/AboutActivity.kt")
            .read_text(encoding="utf-8"))

    def test_the_refusal_is_reported_where_it_can_be_read(self):
        self.assertIn(
            '"update_refused"', self.about,
            "a refused update is only ever shown on the handset itself",
        )

    def test_the_report_carries_what_makes_it_actionable(self):
        report = self.about[self.about.index('"update_refused"'):]
        report = report[:report.index("\n        }") + 10] if "\n        }" in report else report
        for field in ("reason", "offered", "installed"):
            self.assertIn(
                field, report,
                "the report omits %s, so it names a failure nobody can place" % field,
            )



class ATruncatedDownloadIsNotASignatureFailureTest(unittest.TestCase):
    """A link that drops mid-download must say so, and be tried again.

    The handset reports `SocketException: Software caused connection abort`,
    and the relay independently measured its uplink stalling on 6 to 12 per
    cent of frames with gaps up to 3.4 seconds. A nine megabyte APK over that
    link does not always arrive whole.

    Nothing checked that it had. copyTo() wrote whatever arrived, the digest
    then disagreed, and the operator was told the identity or signature of the
    APK was invalid -- about a file that was simply incomplete. The same build
    had installed the day before, when the link was better, which is why this
    looked like a property of the build.
    """

    def setUp(self):
        self.about = code(
            (ROOT / "app/src/main/java/com/am2/am2/AboutActivity.kt")
            .read_text(encoding="utf-8"))
        # startManualDownload and the single attempt it repeats: the retry
        # lives in one and the completeness check in the other, and the
        # contract is about the pair.
        self.download = self.about[self.about.index("fun startManualDownload"):]
        self.download = self.download[:self.download.index("fun getInstalledVersionCode")]

    def test_a_short_download_raises_rather_than_being_written_and_forgotten(self):
        # Bound to the comparison and the throw it guards, not to the presence
        # of the words. An earlier pair of assertions here passed with the
        # condition replaced by `if (false)`.
        self.assertRegex(
            self.download,
            r"written\s*!=\s*promised[\s\S]{0,200}?throw",
            "the count that arrived is not compared with the length promised, "
            "or the mismatch does not stop the update: a truncated file reaches "
            "the signature check and is reported as an invalid APK",
        )
        self.assertRegex(
            self.download, r"(?i)throw[^\n]*(unduhan terputus|download incomplete)",
            "a short download is still reported as an identity or signature problem",
        )
        self.assertRegex(
            self.download, r"contentLength\(\)[\s\S]{0,400}?written\s*!=\s*promised",
            "the promised length is read but never the thing compared against",
        )

    def test_a_dropped_link_is_tried_again_before_giving_up(self):
        self.assertRegex(
            self.download,
            r"while\s*\(\s*attempt\s*<=\s*DOWNLOAD_ATTEMPTS\s*\)",
            "the download is not retried against a link known to drop",
        )
        self.assertRegex(
            self.about, r"DOWNLOAD_ATTEMPTS\s*=\s*([2-9]|[1-9][0-9])\b",
            "DOWNLOAD_ATTEMPTS is not a number greater than one, so nothing is "
            "actually retried",
        )

    def test_the_retry_is_bounded_and_waits_between_tries(self):
        self.assertRegex(
            self.about, r"DOWNLOAD_ATTEMPTS\s*=\s*[0-9]{1,2}\b",
            "the retry ceiling is not a small literal",
        )
        self.assertRegex(
            self.download, r"Thread\.sleep\(\s*DOWNLOAD_RETRY_DELAY_MS",
            "the retries are immediate, which on a congested link is the worst "
            "moment to try again",
        )


if __name__ == "__main__":
    unittest.main()
