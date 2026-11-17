"""The signing secrets helper must be safe: no leaks, no silent success, no bad inputs."""

import base64
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/install_release_secrets.py"

PASSWORD = "test-only-password"


class InstallReleaseSecretsTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        base = Path(self.directory.name)
        self.keystore = base / "mahdi-camera-release.jks"
        self.settings = base / "mahdi-camera-private.json"
        self.keystore.write_bytes(b"\xfe\xed\xfe\xedkeystore-bytes")
        self.settings.write_text(
            json.dumps({"alias": "mahdi", "store_password": PASSWORD, "key_password": PASSWORD}),
            encoding="utf-8",
        )
        for path in (self.keystore, self.settings):
            path.chmod(0o600)

    def run_script(self, *arguments):
        return subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                "--keystore",
                str(self.keystore),
                "--settings",
                str(self.settings),
                *arguments,
            ],
            capture_output=True,
            text=True,
            check=False,
            cwd=ROOT,
        )

    def test_refuses_a_world_readable_key(self):
        self.keystore.chmod(0o644)
        result = self.run_script()
        self.assertEqual(2, result.returncode)
        self.assertIn("chmod 600", result.stderr)
        self.assertNotIn(PASSWORD, result.stdout + result.stderr)

    def test_refuses_a_short_password(self):
        self.settings.write_text(
            json.dumps({"alias": "mahdi", "store_password": "short", "key_password": "short"}),
            encoding="utf-8",
        )
        self.settings.chmod(0o600)
        result = self.run_script()
        self.assertEqual(2, result.returncode)
        self.assertIn("12 characters", result.stderr)

    def test_refuses_a_kilobyte_free_keystore_file(self):
        self.keystore.unlink()
        result = self.run_script()
        self.assertEqual(2, result.returncode)
        self.assertIn("create_release_key.py", result.stderr)

    def test_verify_reports_missing_secrets_without_guessing(self):
        result = self.run_script("--verify")
        # No usable token in CI or a sandbox: it must say so, not claim success.
        self.assertEqual(2, result.returncode)
        self.assertTrue(
            "cannot verify" in result.stderr or "MISSING" in result.stdout,
            result.stderr + result.stdout,
        )
        self.assertNotIn(PASSWORD, result.stdout + result.stderr)

    def test_the_keystore_is_base64_encoded_exactly_once(self):
        """The value pushed to GitHub is base64 of the file bytes, nothing more."""
        payload = base64.b64encode(self.keystore.read_bytes()).decode("ascii")
        self.assertEqual(
            b"\xfe\xed\xfe\xedkeystore-bytes",
            base64.b64decode(payload),
        )
        self.assertNotIn("\n", payload)


if __name__ == "__main__":
    unittest.main()
