#!/usr/bin/env python3
"""The release lane must gate itself, not lean on a GitHub environment.

`release-artifact` declared `environment: android-release`, and that
environment carried a deployment branch policy allowing only `main` and `v*`.
That policy was doing real work: the job's own `if:` accepted any ref for a
`workflow_dispatch` with `lane=release`, so the environment was the only thing
stopping a release build from an arbitrary branch -- one that could carry a
workflow step written to print the upload key.

The policy stops existing the moment the repository becomes private on GitHub
Free. The documentation is explicit that it is not disabled or warned about:

    "If you convert a repository from public to private, any configured
     protection rules or environment secrets will be ignored, and you will
     not be able to configure any environments."

Ignored. So the line would still read like a guard while guarding nothing.

The fix is not to delete the guard but to move it somewhere that survives:
the job's own condition, which is evaluated from the workflow file at the
commit being run. These assertions pin that the ref check exists in the
condition and that no environment declaration comes back to imply protection
the plan can no longer provide.

Assertions are booleans so a failure prints its reason, not the file.
"""
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/android-ci.yml"


class ReleaseGateContractTest(unittest.TestCase):
    def setUp(self):
        self.workflow = WORKFLOW.read_text()
        start = self.workflow.index("  release-artifact:")
        body = self.workflow[start:]
        end = body.index("\n    steps:")
        self.header = body[:end]

    def test_no_environment_declaration(self):
        self.assertNotIn(
            "environment:",
            self.header,
            "release-artifact must not declare a GitHub environment: on a "
            "private repository under GitHub Free the declaration is ignored "
            "rather than enforced, so it reads as a guard that is not one",
        )

    def test_dispatch_release_is_restricted_to_main(self):
        condition = self.header[self.header.index("if:"):]
        self.assertIn(
            "refs/heads/main",
            condition,
            "a workflow_dispatch with lane=release must be restricted to main; "
            "the deployment branch policy used to do this and disappears when "
            "the repository goes private",
        )

    def test_tag_releases_stay_restricted_to_version_tags(self):
        condition = self.header[self.header.index("if:"):]
        self.assertIn(
            "refs/tags/v",
            condition,
            "tag-triggered releases must still be restricted to v* tags",
        )


if __name__ == "__main__":
    unittest.main(verbosity=2)
