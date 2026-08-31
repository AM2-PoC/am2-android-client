"""What a version string has to answer.

Semantic Versioning 2.0.0, rule 10: build metadata is "a series of dot
separated identifiers" of "[0-9A-Za-z-]", and it "MUST be ignored when
determining version precedence". Android agrees from the other side:
versionCode is what the system compares, versionName is "the only value
displayed to users" and has no required format.

So the build number belongs where it is, after the '+', and the release number
belongs to a person. What was missing is that neither of them says which source
the binary came from. Answering "which commit is build 210?" meant three
queries to CI, repeatedly, on a day when the answer decided whether a build was
guilty of anything.

The commit goes in the metadata beside the build number, which rule 10 permits
and rule 10 also guarantees changes nothing about ordering.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GRADLE = ROOT / "app/build.gradle.kts"
VERSION = ROOT / "app/version.properties"

SEMVER_METADATA = re.compile(r"^[0-9A-Za-z-]+(\.[0-9A-Za-z-]+)*$")


class AVersionNamesItsSourceTest(unittest.TestCase):
    def setUp(self):
        self.gradle = GRADLE.read_text(encoding="utf-8")
        self.version = VERSION.read_text(encoding="utf-8")

    def _declared(self):
        match = re.search(r"^versionName=(.+)$", self.version, re.M)
        self.assertIsNotNone(match, "version.properties declares no versionName")
        return match.group(1).strip()

    def test_the_release_number_is_a_plain_semver_core(self):
        self.assertRegex(
            self._declared(), r"^\d+\.\d+\.\d+$",
            "the declared release is not MAJOR.MINOR.PATCH; the build number "
            "belongs in metadata, not here",
        )

    def test_the_release_number_has_moved_since_the_token_work(self):
        # Rule 7: new backward compatible functionality is a MINOR. Device
        # tokens, token expiry, always-persisted sessions and the auth= field
        # are all that, and 1.1.0 predates every one of them.
        major, minor, patch = (int(p) for p in self._declared().split("."))
        self.assertGreater(
            (major, minor), (1, 1),
            "the declared release is still 1.1.0, which was decided before "
            "device tokens existed",
        )

    def test_the_build_metadata_names_the_source(self):
        self.assertRegex(
            self.gradle, r"AM2_SOURCE_SHA|sourceSha",
            "nothing puts the commit into the version, so which source a build "
            "came from can only be answered by querying CI",
        )

    def test_every_flavour_suffix_is_legal_metadata(self):
        # A suffix that breaks rule 10 is not a version any tool can parse.
        for suffix in re.findall(r'versionNameSuffix = "([^"]*)"', self.gradle):
            body = suffix.split("+", 1)[1] if "+" in suffix else ""
            # Gradle interpolation stands in for a value; check the shape around it.
            skeleton = re.sub(r"\$\{[^}]*\}", "X", body)
            if skeleton:
                self.assertRegex(
                    skeleton, SEMVER_METADATA,
                    "%r is not dot separated [0-9A-Za-z-] after the '+'" % suffix,
                )

    def test_the_source_identifier_cannot_contain_illegal_characters(self):
        # A short SHA is hex, but nothing stops a caller passing a branch name.
        self.assertRegex(
            self.gradle, r"AM2_SOURCE_SHA[\s\S]{0,400}?(require|matches|Regex|filter)",
            "the source identifier is used unchecked, so a value with a slash "
            "or a space would produce a version string no tool can parse",
        )


if __name__ == "__main__":
    unittest.main()
