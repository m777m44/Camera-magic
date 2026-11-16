import base64
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
import zipfile

import yaml

from camera_magic import app_release as app
from camera_magic.core import MagicError, read_json

ROOT = Path(__file__).resolve().parents[1]


def metadata():
    return {
        "application_id": app.APP_ID,
        "label": app.APP_LABEL,
        "min_sdk": "23",
        "target_sdk": "35",
        "permissions": list(app.PERMISSIONS),
        "permission_details": [
            {"name": name, "max_sdk": "28" if name.endswith("_EXTERNAL_STORAGE") else None}
            for name in app.PERMISSIONS
        ],
        "implied_permissions": [],
        "launchable_activity": app.APP_ID + ".MainActivity",
        "version_name": "1.0.1",
    }


def fixture(path, *, code=b"synthetic test dex", resource=b"original resource", signed=False):
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("AndroidManifest.xml", b"unit-test manifest, not installable")
        archive.writestr("classes.dex", code)
        archive.writestr("resources.arsc", resource)
        archive.writestr("META-INF/com/android/build/gradle/app-metadata.properties", b"toolchain")
        if signed:
            archive.writestr("META-INF/MANIFEST.MF", b"fixture JAR manifest")
            archive.writestr("META-INF/CERT.RSA", b"fixture, NOT a real signature")
    return path


class AppReleaseTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.apk = fixture(self.root / "source.apk")

    def transport(self):
        return {
            "kind": "independent_camera_unsigned",
            "source_commit": "a" * 40,
            "validation_run": "123",
            "sha256": hashlib.sha256(self.apk.read_bytes()).hexdigest(),
            "apk_base64": base64.b64encode(self.apk.read_bytes()).decode(),
            "metadata": metadata(),
        }

    def test_no_production_key_is_silently_generated(self):
        self.assertEqual("none", app.choose_mode({"action": "completed"}, False))
        self.assertEqual("sign", app.choose_mode({"action": "completed"}, True))

    def test_local_handoff_never_requires_a_private_key_in_the_event(self):
        self.assertEqual("unsigned", app.choose_mode({"action": "mahdi-build-unsigned"}, False))
        self.assertEqual("receive", app.choose_mode({"action": "mahdi-publish-signed"}, False))

    def test_independent_identity_and_offline_permissions(self):
        app.check_metadata(metadata())
        for field, value in (
            ("application_id", "com.blackmagicdesign.android.blackmagiccam"),
            ("label", "Blackmagic Camera"),
            ("min_sdk", "22"),
            ("target_sdk", "34"),
            ("permissions", list(app.PERMISSIONS) + ["android.permission.INTERNET"]),
        ):
            candidate = metadata()
            candidate[field] = value
            with self.subTest(field=field), self.assertRaises(MagicError):
                app.check_metadata(candidate)

    def test_aapt_implied_legacy_read_requires_proof_and_android_9_cap(self):
        candidate = metadata()
        read = "android.permission.READ_EXTERNAL_STORAGE"
        candidate["permissions"].append(read)
        detail = {"name": read, "max_sdk": "28"}
        candidate["permission_details"].append(detail)
        candidate["implied_permissions"] = [
            {**detail, "reason": "requested WRITE_EXTERNAL_STORAGE"}
        ]
        app.check_metadata(candidate)
        for field, value in (
            ("implied_permissions", []),
            ("implied_permissions", [{**detail, "reason": "unknown implication"}]),
            ("permission_details", []),
            (
                "permission_details",
                [
                    {**item, "max_sdk": None} if item["name"] == read else item
                    for item in candidate["permission_details"]
                ],
            ),
            ("permissions", candidate["permissions"] + [read]),
        ):
            with self.subTest(field=field), self.assertRaises(MagicError):
                app.check_metadata({**candidate, field: value})

    def test_legacy_write_cannot_request_uncapped_storage(self):
        candidate = metadata()
        for item in candidate["permission_details"]:
            if item["name"].endswith("WRITE_EXTERNAL_STORAGE"):
                item["max_sdk"] = None
        with self.assertRaises(MagicError):
            app.check_metadata(candidate)

    def test_signing_can_only_change_jar_signature_files_not_resources_or_dex(self):
        signed = fixture(self.root / "signed.apk", signed=True)
        self.assertEqual(app.content_digests(self.apk), app.content_digests(signed))
        modified = fixture(self.root / "changed.apk", resource=b"modified resource", signed=True)
        self.assertNotEqual(app.content_digests(self.apk), app.content_digests(modified))

    def test_apk_transport_is_hash_checked(self):
        output = self.root / "received.apk"
        app.decode_apk(self.transport(), output)
        self.assertEqual(self.apk.read_bytes(), output.read_bytes())
        invalid = self.transport()
        invalid["sha256"] = "b" * 64
        with self.assertRaisesRegex(MagicError, "checksum"):
            app.decode_apk(invalid, self.root / "invalid.apk")
        self.assertFalse((self.root / "invalid.apk").exists())

    def test_unsigned_transport_is_not_a_public_installable_release(self):
        release = {
            "draft": True,
            "tag_name": "mahdi-unsigned-123-1",
            "body": "```json\n" + json.dumps(self.transport()) + "\n```",
        }
        output = self.root / "extracted.apk"
        app.extract_transport(release, output)
        report = read_json(output.with_suffix(".provenance.json"))
        self.assertNotIn("apk_base64", report)
        self.assertEqual("a" * 40, report["source_commit"])
        release["draft"] = False
        with self.assertRaises(MagicError):
            app.extract_transport(release, self.root / "wrong.apk")

    def test_transport_cannot_publish_a_vendor_apk(self):
        data = self.transport()
        data["metadata"]["application_id"] = "com.blackmagicdesign.android.blackmagiccam"
        release = {
            "draft": True,
            "tag_name": "mahdi-unsigned-1-1",
            "body": "```json\n" + json.dumps(data) + "\n```",
        }
        with self.assertRaises(MagicError):
            app.extract_transport(release, self.root / "vendor.apk")

    def test_certificate_pin_rejects_wrong_or_multiple_signers(self):
        pin = read_json(ROOT / "config/mahdi-camera-signing.json")["certificate_sha256"]
        self.assertEqual(
            pin, app.certificate_digest("Signer #1 certificate SHA-256 digest: " + pin)
        )
        for text in (
            "Signer #1 certificate SHA-256 digest: " + "0" * 64,
            "Signer #1 certificate SHA-256 digest: "
            + pin
            + "\nSigner #2 certificate SHA-256 digest: "
            + pin,
        ):
            with self.assertRaises(MagicError):
                app.certificate_digest(text)

    @patch("camera_magic.app_release.gh_json")
    @patch("camera_magic.app_release.event")
    @patch("camera_magic.app_release.run")
    def test_preflight_requires_current_main_and_successful_real_app_validation(
        self, run, event, gh
    ):
        source = "a" * 40
        run.return_value = subprocess.CompletedProcess([], 0, source + "\n", "")
        event.return_value = {"client_payload": {"validation_run": "123"}}
        approved = {
            "name": app.VALIDATION,
            "conclusion": "success",
            "head_sha": source,
            "head_branch": "main",
            "head_repository": {"full_name": "owner/repo"},
        }
        with patch.dict(os.environ, {"GH_REPO": "owner/repo", "SOURCE_COMMIT": source}):
            gh.side_effect = [{"object": {"sha": source}}, approved]
            app.preflight()
            for key, value in (
                ("head_branch", "untrusted"),
                ("conclusion", "failure"),
                ("head_sha", "b" * 40),
                ("name", "Source and HDR kit validation"),
            ):
                invalid = {**approved, key: value}
                gh.side_effect = [{"object": {"sha": source}}, invalid]
                with self.subTest(key=key), self.assertRaises(MagicError):
                    app.preflight()
            gh.side_effect = [{"object": {"sha": "b" * 40}}]
            with self.assertRaisesRegex(MagicError, "stale"):
                app.preflight()

    def test_manifest_has_no_network_or_wide_storage_access(self):
        root = ET.parse(ROOT / "camera-app/src/main/AndroidManifest.xml").getroot()
        android = "{http://schemas.android.com/apk/res/android}"
        permissions = {node.get(android + "name") for node in root.findall("uses-permission")}
        self.assertEqual(app.PERMISSIONS, permissions)
        legacy = root.find(
            "uses-permission[@android:name='android.permission.WRITE_EXTERNAL_STORAGE']",
            {"android": android[1:-1]},
        )
        self.assertEqual("28", legacy.get(android + "maxSdkVersion"))
        self.assertIsNone(root.find("./application/service"))

    def test_look_assets_are_packaged_and_the_apk_stays_offline(self):
        assets = ROOT / "camera-app/src/main/assets/mahdi_lut"
        self.assertTrue((assets / "presets.json").is_file())
        self.assertTrue(list((assets / "luts").glob("*.png")))
        for shader in ("fullscreen.vert", "external.frag", "lut.frag", "copy.frag"):
            self.assertTrue((assets / "shaders" / shader).is_file(), shader)
        manifest = (ROOT / "camera-app/src/main/AndroidManifest.xml").read_text()
        # An in-app updater would need INTERNET; the camera APK must not gain it silently.
        self.assertNotIn("android.permission.INTERNET", manifest)
        gradle = (ROOT / "camera-app/build.gradle.kts").read_text()
        self.assertIn('assets.srcDir("src/main/assets")', gradle)

    def test_release_notes_describe_the_look_pipeline_and_no_updater(self):
        source = (ROOT / "camera_magic/app_release.py").read_text()
        self.assertIn("LOOK applies a 3D colour cube", source)
        self.assertIn("There is no in-app updater", source)
        # The old release copy must not keep claiming there is no LUT support at all.
        self.assertNotIn("No HDR/ProRes/LUT/Blackmagic Cloud equivalence", source)
        self.assertIn("SHA256SUMS.txt does not list", source)

    def test_workflow_does_not_publish_unvalidated_or_unsigned_artifacts(self):
        text = (ROOT / ".github/workflows/publish-mahdi.yml").read_text()
        data = yaml.load(text, Loader=yaml.BaseLoader)
        self.assertNotIn("pull_request", data["on"])
        self.assertNotIn("pull_request_target", data["on"])
        self.assertIn("refs/heads/main", data["jobs"]["release"]["if"])
        self.assertIn("head_repository.full_name == github.repository", text)
        self.assertLess(text.index("app_release preflight"), text.index("app_release unsigned"))
        self.assertIn("MAHDI_APP_KEYSTORE_B64", text)
        self.assertNotIn("APK_SOURCE_URL", text)
        self.assertNotIn("fetch-tool", text)
        self.assertIn("app_release receive", text)
        self.assertIn("app_release publish", text)

    def test_release_evidence_cannot_be_forged_by_setting_only_the_hash(self):
        target = self.root / app.ASSET
        target.write_bytes(self.apk.read_bytes())
        with self.assertRaises(MagicError):
            app.save_release_files(
                self.root,
                {
                    "kind": "independent_camera_signed",
                    "apk_sha256": hashlib.sha256(target.read_bytes()).hexdigest(),
                },
            )
        self.assertFalse((self.root / "SHA256SUMS.txt").exists())
