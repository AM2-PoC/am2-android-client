#!/usr/bin/env python3
"""What a version name has to say about the build it came from.

versionName was "1.1.${versionCode}", so build 124 called itself 1.1.124. That
identifies the build, which was the point, and it does it by claiming a hundred
and twenty-four backward compatible bug fixes -- because that is what Semantic
Versioning says the PATCH component means:

    "Patch version Z (x.y.Z | x > 0) MUST be incremented if only backward
     compatible bug fixes are introduced."

Semver has a slot for build identity and it is not that one. Everything after a
'+' is build metadata: it names the artifact and "MUST be ignored when
determining version precedence".

Google keeps the two roles apart for the same reason. versionCode is internal,
monotonic and "should not be displayed"; versionName is "the only value
displayed to users" and is given no mandated format at all.

So: a release a human declares, in one file, plus the build that produced the
artifact -- except on the one lane that has a store listing to keep tidy.
"""
import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GRADLE = (ROOT / "app/build.gradle.kts").read_text()
VERSION_FILE = ROOT / "app/version.properties"

# The Play listing is the only place a version string is read by strangers.
STORE_LANE = "play"


def suffix_of(flavour: str) -> str:
    block = GRADLE[GRADLE.index(f'create("{flavour}")'):]
    block = block[:block.index("\n        }")]
    found = re.search(r'versionNameSuffix\s*=\s*"([^"]*)"', block)
    return found.group(1) if found else ""


class VersionNameContractTest(unittest.TestCase):
    def test_the_release_is_declared_once_in_a_file_ci_can_read(self):
        self.assertTrue(VERSION_FILE.is_file(), "app/version.properties is missing")
        self.assertRegex(
            VERSION_FILE.read_text(),
            re.compile(r"^versionName=\d+\.\d+\.\d+$", re.MULTILINE),
            "the declared release must be a bare MAJOR.MINOR.PATCH",
        )
        self.assertNotRegex(
            GRADLE, r'versionName\s*=\s*"',
            "versionName is a literal; CI cannot read it and it cannot be reviewed",
        )

    def test_the_build_is_never_folded_into_the_patch_component(self):
        # 1.1.124 says a hundred and twenty-four bug fixes shipped. They did not.
        self.assertNotRegex(
            GRADLE, r'versionName\s*=\s*"[^"]*\$\{buildVersionCode',
            "the build is in the PATCH component, which means something else",
        )

    def test_every_lane_but_the_store_names_its_build(self):
        for flavour in ("dev", "staging", "production"):
            self.assertIn(
                "+${buildVersionCode", suffix_of(flavour),
                f"the {flavour} lane produces a version name that names no build",
            )

    def test_the_store_lane_stays_a_plain_release(self):
        # A store listing is read by people who are not looking for a build
        # number, and Play shows versionName verbatim.
        self.assertNotIn(
            "+", suffix_of(STORE_LANE),
            "the Play lane publishes build metadata into its store listing",
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
