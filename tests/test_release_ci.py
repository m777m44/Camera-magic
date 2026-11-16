import base64
import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import urllib.error

import yaml

from camera_magic.core import MagicError, write_json
from camera_magic.name_only import ASSET_NAME
from camera_magic.release_ci import (
    HttpsRedirectsOnly,
    download,
    main,
    prepare_key,
    prepare_notes,
    prepare_source,
    validate_url,
)
from tests.helpers import tiny_apk

ROOT = Path(__file__).resolve().parents[1]


class Response(io.BytesIO):
    headers = {}

    def geturl(self):
        return "https://example.com/input"


class ReleaseTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def opener(self, data):
        context = patch("camera_magic.release_ci.urllib.request.build_opener")
        mocked = context.start()
        self.addCleanup(context.stop)
        mocked.return_value.open.return_value = Response(data)
        return mocked

    def test_download_requires_https_and_no_login_credentials(self):
        for value in (
            "http://example.com/a",
            "file:///etc/passwd",
            "https://user:pass@example.com/a",
            "https://example.com/a#fragment",
        ):
            with self.subTest(value=value), self.assertRaises(MagicError):
                validate_url(value)

    def test_redirect_cannot_downgrade_https(self):
        with self.assertRaises(MagicError):
            HttpsRedirectsOnly().redirect_request(
                None, None, 302, "", {}, "http://example.com/insecure"
            )

    def test_checksum_verified_before_input_becomes_visible(self):
        payload = b"download fixture"
        self.opener(payload)
        output = self.root / "download.bin"
        download("https://example.com/input", hashlib.sha256(payload).hexdigest(), output)
        self.assertEqual(payload, output.read_bytes())

    def test_checksum_mismatch_removes_partial_file(self):
        self.opener(b"modified input")
        output = self.root / "download.bin"
        with self.assertRaisesRegex(MagicError, "SHA-256 mismatch"):
            download("https://example.com/input", "a" * 64, output)
        self.assertFalse(output.exists())
        self.assertEqual([], list(self.root.iterdir()))

    def test_unpinned_download_refused(self):
        with self.assertRaisesRegex(MagicError, "pinned"):
            download("https://example.com/input", "", self.root / "download")

    def test_download_size_is_bounded(self):
        self.opener(b"x" * 100)
        with (
            patch("camera_magic.release_ci.MAX_DOWNLOAD", 10),
            self.assertRaisesRegex(MagicError, "limit"),
        ):
            download("https://example.com/input", "a" * 64, self.root / "large")
        self.assertEqual([], list(self.root.iterdir()))

    @patch("camera_magic.release_ci.urllib.request.build_opener")
    def test_network_error_does_not_log_secret_source_url(self, mocked):
        private_url = "https://example.com/private?token=do-not-print-this-test-token"
        mocked.return_value.open.side_effect = urllib.error.URLError(private_url)
        with patch.dict(os.environ, {"APK_SOURCE_URL": private_url, "APK_SOURCE_SHA256": "a" * 64}):
            with contextlib.redirect_stderr(io.StringIO()) as errors:
                code = main(["prepare-source", "--output", str(self.root / "input.apk")])
        self.assertEqual(2, code)
        self.assertNotIn("do-not-print", errors.getvalue())
        self.assertNotIn(private_url, errors.getvalue())

    def test_valid_standalone_source_prepared(self):
        original = tiny_apk(self.root / "original.apk")
        data = original.read_bytes()
        self.opener(data)
        output = self.root / "source.apk"
        with patch.dict(
            os.environ,
            {
                "APK_SOURCE_URL": "https://example.com/input",
                "APK_SOURCE_SHA256": hashlib.sha256(data).hexdigest(),
            },
        ):
            prepare_source(output)
        self.assertEqual(data, output.read_bytes())

    def test_missing_settings_fail_without_publishing(self):
        with (
            patch.dict(os.environ, {}, clear=True),
            self.assertRaisesRegex(MagicError, "APK_SOURCE_URL"),
        ):
            prepare_source(self.root / "missing.apk")

    def test_key_restoration_is_private_and_not_overwritten(self):
        value = b"fake key fixture, not a signing key"
        env = {
            "APK_KEYSTORE_B64": base64.b64encode(value).decode(),
            "MAHDI_STORE_PASSWORD": "test-only-password-123",
            "MAHDI_KEY_PASSWORD": "test-only-password-456",
        }
        output = self.root / "release.jks"
        with patch.dict(os.environ, env):
            prepare_key(output)
            with self.assertRaises(MagicError):
                prepare_key(output)
        self.assertEqual(value, output.read_bytes())
        self.assertEqual(0o600, output.stat().st_mode & 0o777)

    def test_bad_base64_rejected_before_key_file_created(self):
        env = {
            "APK_KEYSTORE_B64": "not base64!",
            "MAHDI_STORE_PASSWORD": "test-password-123",
            "MAHDI_KEY_PASSWORD": "test-password-456",
        }
        with patch.dict(os.environ, env), self.assertRaisesRegex(MagicError, "base64"):
            prepare_key(self.root / "bad.jks")
        self.assertFalse((self.root / "bad.jks").exists())

    def release_fixture(self):
        apk = self.root / ASSET_NAME
        apk.write_bytes(b"unit-test fake package, never uploaded")
        report = {
            "kind": "name_only_signed",
            "source_sha256": "b" * 64,
            "apk_sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
            "signatures": {"v1": True, "v2": True, "v3": True},
            "alignment_verified": True,
            "code_libraries_assets_unchanged": True,
            "permissions_unchanged": True,
            "identity_sdk_version_unchanged": True,
            "package_changed": False,
            "icon_changed": False,
            "sdk_changed": False,
            "filters_added": False,
        }
        write_json(apk.with_suffix(".verification.json"), report)
        return apk, report

    def test_checksum_and_notes_only_for_verified_name_only_output(self):
        apk, report = self.release_fixture()
        prepare_notes(self.root)
        checksums = (self.root / "SHA256SUMS.txt").read_text()
        self.assertIn(report["apk_sha256"] + "  " + ASSET_NAME, checksums)
        self.assertIn("NOT an official", (self.root / "release-notes.md").read_text())
        self.assertIn("have not been tested", (self.root / "release-notes.md").read_text())
        self.assertNotIn(str(self.root), checksums)

    def test_forged_or_stale_report_blocks_release_files(self):
        apk, report = self.release_fixture()
        for field, value in (
            ("apk_sha256", "a" * 64),
            ("filters_added", True),
            ("kind", "rebrand_only_signed"),
            ("sdk_changed", True),
            ("code_libraries_assets_unchanged", False),
        ):
            invalid = dict(report)
            invalid[field] = value
            write_json(apk.with_suffix(".verification.json"), invalid)
            with self.subTest(field=field), self.assertRaises(MagicError):
                prepare_notes(self.root)
        self.assertFalse((self.root / "SHA256SUMS.txt").exists())

    def test_release_workflow_is_opt_in_trusted_main_and_draft_first(self):
        text = (ROOT / ".github/workflows/publish-apk.yml").read_text()
        workflow = yaml.load(text, Loader=yaml.BaseLoader)
        self.assertNotIn("pull_request", workflow["on"])
        self.assertNotIn("pull_request_target", workflow["on"])
        condition = workflow["jobs"]["publish"]["if"]
        self.assertIn("APK_RELEASE_ENABLED", condition)
        self.assertIn("refs/heads/main", condition)
        self.assertIn("head_repository.full_name == github.repository", condition)
        self.assertLess(text.index("gh release create"), text.index("gh release edit"))
        self.assertIn("--draft \\", text)
        self.assertIn("--draft=false --latest", text)
        self.assertIn('latest_main" != "$SOURCE_COMMIT', text)
        self.assertIn("releases/latest/download/" + ASSET_NAME, text)

    def test_pinned_official_tool_metadata_present(self):
        config = json.loads((ROOT / "config/release-tools.json").read_text())
        self.assertTrue(
            config["apktool_url"].startswith(
                "https://github.com/iBotPeaches/Apktool/releases/download/"
            )
        )
        self.assertEqual(64, len(config["apktool_sha256"]))
