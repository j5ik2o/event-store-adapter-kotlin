import importlib.util
from pathlib import Path
import subprocess
import sys
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "validate-version.py"
SPEC = importlib.util.spec_from_file_location("validate_version", SCRIPT)
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)

TAGS = ["v1.9.0", "v2.0.0", "v4.1.2"]


class ValidateVersionTest(unittest.TestCase):
    def test_greater_version_is_accepted(self):
        for version in ("4.1.3", "5.0.0", "10.0.0"):
            with self.subTest(version=version):
                self.assertEqual(MODULE.validate_version(version, TAGS), version)

    def test_no_tags_accepts_any_valid_version(self):
        self.assertEqual(MODULE.validate_version("5.0.0", []), "5.0.0")

    def test_invalid_format_is_rejected(self):
        for version in ("", "v5.0.0", "5.0", "5.0.0.0", "5.0.0-rc.1", "5.0.0+build", "5.0.x", " 5.0.0", "5.0.0\n", "05.0.0", "5.00.0", "5.0.01", "５.０.０"):
            with self.subTest(version=version):
                with self.assertRaises(ValueError):
                    MODULE.validate_version(version, TAGS)

    def test_not_greater_than_latest_is_rejected(self):
        for version in ("4.1.2", "4.1.1", "3.9.9", "1.0.0"):
            with self.subTest(version=version):
                with self.assertRaises(ValueError):
                    MODULE.validate_version(version, TAGS)

    def test_existing_tag_is_rejected(self):
        with self.assertRaises(ValueError):
            MODULE.validate_version("2.0.0", ["v1.0.0", "v2.0.0"])

    def test_cli(self):
        ok = subprocess.run([sys.executable, str(SCRIPT), "5.0.0"], input="\n".join(TAGS), text=True, capture_output=True)
        self.assertEqual((ok.returncode, ok.stdout), (0, "5.0.0\n"))
        ng = subprocess.run([sys.executable, str(SCRIPT), "4.1.2"], input="\n".join(TAGS), text=True, capture_output=True)
        self.assertNotEqual(ng.returncode, 0)
        self.assertEqual(ng.stdout, "")
        missing = subprocess.run([sys.executable, str(SCRIPT)], input="", text=True, capture_output=True)
        self.assertNotEqual(missing.returncode, 0)


if __name__ == "__main__":
    unittest.main()
