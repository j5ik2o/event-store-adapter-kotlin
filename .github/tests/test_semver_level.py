import importlib.util
from pathlib import Path
import subprocess
import sys
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "semver-level.py"
SPEC = importlib.util.spec_from_file_location("semver_level", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)


def commits(*messages):
    return "\n".join(subject + "\x1f" + body + "\x1e" for subject, body in messages)


class SemverLevelTest(unittest.TestCase):
    def test_subject_breaking_marker(self):
        for subject in ("feat!: change API", "fix(scope)!: change API", "perf!: change API"):
            with self.subTest(subject=subject):
                self.assertEqual(MODULE.semver_level(commits((subject, ""))), "major")

    def test_body_breaking_marker(self):
        for marker in ("BREAKING CHANGE:", "BREAKING-CHANGE:"):
            with self.subTest(marker=marker):
                body = "Details\n\n" + marker + " change API\n\tMore details\n"
                self.assertEqual(MODULE.semver_level(commits(("fix: update API", body))), "major")

    def test_breaking_marker_without_conventional_subject(self):
        for marker in ("BREAKING CHANGE:", "BREAKING-CHANGE:"):
            with self.subTest(marker=marker):
                self.assertEqual(MODULE.semver_level(commits(("Update API", marker + " remove API\n"))), "major")
                self.assertEqual(MODULE.semver_level(commits((marker + " remove API", ""))), "major")

    def test_marker_must_start_a_line(self):
        for body in ("Mention BREAKING CHANGE: in prose", " BREAKING-CHANGE: indented"):
            with self.subTest(body=body):
                self.assertEqual(MODULE.semver_level(commits(("fix: update docs", body))), "patch")

    def test_minor_types(self):
        for subject in ("feat: add API", "feat(scope): add API", "revert: undo change"):
            with self.subTest(subject=subject):
                self.assertEqual(MODULE.semver_level(commits((subject, ""))), "minor")

    def test_other_types_are_patch(self):
        for kind in ("perf", "fix", "build", "ci", "docs", "style", "refactor", "test", "chore", "custom"):
            with self.subTest(kind=kind):
                self.assertEqual(MODULE.semver_level(commits((kind + ": update", ""))), "patch")

    def test_mixed_commits_choose_highest_level_in_any_order(self):
        patch = ("perf: improve throughput", "Details\n\tTabbed details\n")
        minor = ("feat: add API", "")
        major = ("fix: update API", "Details\nBREAKING-CHANGE: remove API\n")
        for messages, expected in (
            ((patch, minor), "minor"), ((minor, patch), "minor"),
            ((patch, minor, major), "major"), ((major, minor, patch), "major"),
        ):
            with self.subTest(messages=messages):
                self.assertEqual(MODULE.semver_level(commits(*messages)), expected)

    def test_body_newlines_and_tabs_preserve_records(self):
        data = commits(("fix: first\ttab", "feat!: body text\n\tperf: text\n"), ("feat: second", "\n"))
        self.assertEqual(MODULE.semver_level(data), "minor")

    def test_empty_or_ineligible_input_fails(self):
        for data in ("", "\n\t ", commits(("Merge branch main", ""))):
            with self.subTest(data=data):
                with self.assertRaises(ValueError):
                    MODULE.semver_level(data)
                result = subprocess.run([sys.executable, str(SCRIPT)], input=data, text=True, capture_output=True)
                self.assertNotEqual(result.returncode, 0)
                self.assertEqual(result.stdout, "")

    def test_cli_output(self):
        result = subprocess.run(
            [sys.executable, str(SCRIPT)],
            input=commits(("fix(scope)!: remove API", "Details\n\tTabbed text\n")),
            text=True, capture_output=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "major\n")


if __name__ == "__main__":
    unittest.main()
