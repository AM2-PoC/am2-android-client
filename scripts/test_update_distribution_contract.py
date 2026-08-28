"""The update channel must be able to deliver a build.

Every part of this path existed and none of it worked together. The device
fetches a manifest and parses it strictly; the manifest that deployment
publishes uses different field names and omits two required ones, so the parse
throws before any version is compared. The approved download URL is a
compile-time constant pointing at production, so a staging build could never
accept a staging URL even if the manifest parsed. And the version code is a
literal that no build has ever changed, so the one comparison that decides
whether an update exists answers "no" for every build ever produced.

Each of those alone is enough to make the channel inert. Together they made it
impossible to establish which build a device was running, which is what let a
whole round of fixes be evaluated against an APK that did not contain them.

These are source contracts. They assert the shape that makes delivery possible
and fail by absence, so an edit that reintroduces a literal is caught rather
than silently restoring the old behaviour.
"""

import pathlib
import json
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
GRADLE = ROOT / "app" / "build.gradle.kts"
METADATA = ROOT / "app" / "src" / "main" / "java" / "com" / "am2" / "am2" / "update" / "UpdateMetadata.kt"
SOCKET = ROOT / "app" / "src" / "main" / "java" / "com" / "am2" / "am2" / "WebSocketManager.kt"
WORKFLOW = ROOT / ".github" / "workflows" / "android-ci.yml"


def read(path):
    return path.read_text(encoding="utf-8")


class VersionIdentity(unittest.TestCase):
    def test_version_code_is_not_a_literal(self):
        """A hardcoded version code makes every build indistinguishable.

        The device decides an update exists by `serverVersionCode >
        currentVersionCode`. With the same literal in every APK that comparison
        is false forever, so the channel can never offer anything -- and nothing
        on either side can name the build that is actually installed.
        """
        defaults = re.search(r"defaultConfig\s*\{(.*?)\n        \}", read(GRADLE), re.S)
        self.assertIsNotNone(defaults, "defaultConfig block not found")
        assignment = re.search(r"versionCode\s*=\s*(.+)", defaults.group(1))
        self.assertIsNotNone(assignment, "versionCode is not set")
        self.assertNotRegex(
            assignment.group(1).strip(),
            r"^\d+$",
            "versionCode is a literal; it must come from the build so each CI "
            "build is newer than the last",
        )

    def test_the_client_reports_its_version_to_the_relay(self):
        """Neither side could name the running build.

        The relay logs a username on login and nothing about the software.
        Sending the version code with the login is what makes it possible to
        answer "which build is that device running" from the server, with no
        cable, no device and nobody reading a screen -- the question that went
        unanswered through eight landings.
        """
        self.assertRegex(
            read(SOCKET),
            r'"client_version_code"',
            "the client never tells the relay which build it is",
        )


class ApprovedDownloadUrl(unittest.TestCase):
    def test_approved_download_url_is_per_flavor(self):
        """A single approved URL constant locks self-update to one environment.

        `UpdateMetadata` refuses any manifest whose `update_url` is not the
        approved one. While that constant is compiled in as production, a
        staging build rejects the staging manifest it was pointed at, so staging
        can never self-update and therefore can never be verified in the field.
        """
        source = read(METADATA)
        self.assertNotRegex(
            source,
            r'APPROVED_URL\s*=\s*"https://',
            "the approved update URL is a hardcoded constant; it must come from "
            "the flavor so each environment approves its own manifest",
        )
        self.assertIn(
            "BuildConfig",
            source,
            "the approved URL is not read from the build flavor",
        )

    def test_every_flavor_declares_its_own_download_url(self):
        self.assertGreaterEqual(
            read(GRADLE).count("UPDATE_APK_URL"),
            3,
            "each of dev, staging and production must declare the APK URL it approves",
        )


class PublishedManifest(unittest.TestCase):
    def test_ci_publishes_the_manifest_the_device_parses(self):
        """Nothing generated the manifest the device actually requires.

        What deployment published carried `download_url` and no digests. What
        the device parses requires `update_url`, `sha256` and `signer_sha256`,
        and throws on the first missing key -- so the check failed outright
        rather than reporting no update. The manifest has to be produced where
        the APK and its signature are, which is the build.
        """
        source = read(WORKFLOW)
        for field in ("version_code", "version_name", "update_url", "sha256", "signer_sha256"):
            self.assertIn(field, source, f"the build does not publish {field} in the manifest")

    def test_manifest_fields_match_what_the_parser_demands(self):
        """Guard the two schemas against drifting apart again."""
        required = set(re.findall(r'json\.get(?:String)?\("([a-z_]+)"\)', read(METADATA)))
        self.assertTrue(required, "no required manifest fields found in the parser")
        workflow = read(WORKFLOW)
        missing = sorted(field for field in required if field not in workflow)
        self.assertFalse(
            missing, f"the build never publishes these required fields: {missing}"
        )

    def test_release_notes_are_published_in_every_language_the_panel_renders(self):
        # The panel is bilingual and release notes are the one string on it that
        # cannot live in a catalogue -- they are written per release, not per
        # key. So the manifest carries an object keyed by locale, and both ends
        # already know how to read one: am2_release_notes() in the panel and
        # resolveReleaseNotes() in the relay, which fall back to a plain string
        # for every manifest published before this.
        #
        # It used to publish "staging build from <sha>", which is not a release
        # note in any language and which the version name now says better --
        # 1.1.0-staging+124 identifies the build on its own.
        notes = ROOT / "app/release-notes.json"
        self.assertTrue(notes.is_file(), "app/release-notes.json is missing")

        published = json.loads(notes.read_text())
        self.assertEqual(
            set(published), {"id", "en"},
            "the panel renders both languages, so both must be published",
        )
        for locale, text in published.items():
            self.assertTrue(
                isinstance(text, str) and text.strip(),
                f"the {locale} note is empty",
            )

        workflow = WORKFLOW.read_text()
        self.assertIn(
            "app/release-notes.json", workflow,
            "CI writes a changelog of its own instead of the published notes",
        )
        self.assertNotRegex(
            workflow, r'--arg changelog "staging build from',
            "CI still publishes a build stamp where a release note belongs",
        )

    def test_the_build_writes_a_manifest_at_all(self):
        self.assertIn(
            "version.json", read(WORKFLOW), "the build does not write a manifest"
        )


if __name__ == "__main__":
    unittest.main()
