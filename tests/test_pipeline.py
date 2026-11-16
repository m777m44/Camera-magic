from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from camera_magic.core import MagicError
from camera_magic.manifest import DEFAULT_ID, DEFAULT_LABEL
from camera_magic.pipeline import (
    badging,
    build,
    parse_signatures,
    password_environment,
    sign,
    verify,
)
from tests.helpers import tiny_apk

SIGS = """Verifies
Verified using v1 scheme (JAR signing): true
Verified using v2 scheme (APK Signature Scheme v2): true
Verified using v3 scheme (APK Signature Scheme v3): true
Signer #1 certificate SHA-256 digest: fixture-only
"""
BADGING = f"""package: name='{DEFAULT_ID}' versionCode='1'
sdkVersion:'23'
targetSdkVersion:'35'
application-label:'{DEFAULT_LABEL}'
launchable-activity: name='com.vendor.camera.MainActivity' label='{DEFAULT_LABEL}'
uses-permission: name='android.permission.CAMERA'
"""


class DummyTools:
    def sdk_tool(self, name):
        return name


class PipelineTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.apk = tiny_apk(self.root / "unsigned.apk")
        self.output = self.root / "Camera_REBRAND_ONLY.apk"
        self.key = self.root / "test.jks"
        self.key.write_bytes(b"unit-test fixture, not a key")
        self.key.chmod(0o600)
        self.tools = DummyTools()
        self.environment = {
            "TEST_STORE_PASS": "not-a-real-secret-123",
            "TEST_KEY_PASS": "not-a-real-secret-456",
        }

    def fake_verify_run(self, args, **kwargs):
        text = SIGS if args[0] == "apksigner" else BADGING if args[0] == "aapt2" else "aligned"
        return subprocess.CompletedProcess(args, 0, text, "")

    def test_signature_parser_requires_all_three(self):
        self.assertEqual({"v1": True, "v2": True, "v3": True}, parse_signatures(SIGS))
        self.assertFalse(parse_signatures(SIGS.replace("v1 scheme", "v9 scheme"))["v1"])
        self.assertFalse(
            parse_signatures(
                SIGS.replace("v3 scheme (APK Signature Scheme v3): true", "v3 scheme: false")
            )["v3"]
        )

    @patch("camera_magic.pipeline.run")
    def test_verify_checks_identity_signatures_alignment(self, mocked):
        mocked.side_effect = self.fake_verify_run
        result = verify(self.apk, self.tools)
        self.assertEqual(DEFAULT_ID, result["metadata"]["application_id"])
        self.assertTrue(all(result["signatures"].values()))
        self.assertEqual("not_tested", result["runtime_compatibility"])
        self.assertEqual("not_verified", result["hdr_pipeline_integrated"])
        self.assertIn("-c", mocked.call_args_list[0].args[0])
        self.assertIn("16", mocked.call_args_list[0].args[0])

    @patch("camera_magic.pipeline.run")
    def test_verify_rejects_missing_v1(self, mocked):
        def respond(args, **kwargs):
            result = self.fake_verify_run(args)
            result.stdout = result.stdout.replace(
                "v1 scheme (JAR signing): true", "v1 scheme (JAR signing): false"
            )
            return result

        mocked.side_effect = respond
        with self.assertRaisesRegex(MagicError, "Android 6"):
            verify(self.apk, self.tools)

    @patch("camera_magic.pipeline.run")
    def test_verify_rejects_wrong_identity(self, mocked):
        def respond(args, **kwargs):
            result = self.fake_verify_run(args)
            if args[0] == "aapt2":
                result.stdout = result.stdout.replace(DEFAULT_ID, "com.wrong.app")
            return result

        mocked.side_effect = respond
        with self.assertRaisesRegex(MagicError, "application_id"):
            verify(self.apk, self.tools)

    @patch("camera_magic.pipeline.run")
    def test_sign_order_and_secret_references_only(self, mocked):
        commands = []

        def respond(args, **kwargs):
            commands.append(args)
            if args[0] == "zipalign" and "-c" not in args:
                shutil.copyfile(args[-2], args[-1])
            elif args[0] == "apksigner" and args[1] == "sign":
                shutil.copyfile(args[-1], args[args.index("--out") + 1])
            return self.fake_verify_run(args)

        mocked.side_effect = respond
        with patch.dict(os.environ, self.environment):
            result = sign(
                self.apk,
                self.output,
                self.key,
                "mahdi",
                self.tools,
                store_env="TEST_STORE_PASS",
                key_env="TEST_KEY_PASS",
            )
        self.assertTrue(self.output.exists())
        self.assertEqual("zipalign", commands[0][0])
        self.assertEqual(["apksigner", "sign"], commands[1][:2])
        self.assertEqual("env:TEST_STORE_PASS", commands[1][commands[1].index("--ks-pass") + 1])
        self.assertNotIn(self.environment["TEST_STORE_PASS"], str(commands))
        for version in ("v1", "v2", "v3"):
            position = commands[1].index(f"--{version}-signing-enabled")
            self.assertEqual("true", commands[1][position + 1])
        self.assertFalse(result["hdr_pipeline_integrated"])
        self.assertTrue(self.output.with_suffix(".verification.json").exists())

    @patch("camera_magic.pipeline.run")
    def test_verification_failure_does_not_publish_apk(self, mocked):
        def respond(args, **kwargs):
            if args[0] == "zipalign":
                shutil.copyfile(args[-2], args[-1])
            else:
                shutil.copyfile(args[-1], args[args.index("--out") + 1])
            return subprocess.CompletedProcess(args, 0, "", "")

        mocked.side_effect = respond
        with (
            patch.dict(os.environ, self.environment),
            patch("camera_magic.pipeline.verify", side_effect=MagicError("bad signature")),
        ):
            with self.assertRaisesRegex(MagicError, "bad signature"):
                sign(
                    self.apk,
                    self.output,
                    self.key,
                    "mahdi",
                    self.tools,
                    store_env="TEST_STORE_PASS",
                    key_env="TEST_KEY_PASS",
                )
        self.assertFalse(self.output.exists())
        self.assertEqual([], list(self.root.glob(".mahdi-sign-*")))

    def test_keystore_permissions(self):
        self.key.chmod(0o644)
        with self.assertRaisesRegex(MagicError, "chmod 600"):
            sign(self.apk, self.output, self.key, "mahdi", self.tools, store_env="A", key_env="B")

    def test_output_label_prevents_fake_hdr_deliverable(self):
        with self.assertRaisesRegex(MagicError, "REBRAND_ONLY"):
            build(self.root, self.root / "Blackmagic_Camera_Mahdi.apk", self.tools)

    def test_passwords_are_variable_names_and_required(self):
        with self.assertRaises(MagicError):
            password_environment("this is a password", "OTHER")
        with patch.dict(os.environ, {"TOO_SHORT": "abc"}):
            with self.assertRaises(MagicError):
                password_environment("TOO_SHORT", "TOO_SHORT")

    @patch("camera_magic.pipeline.run")
    def test_badging_preserves_original_launch_class(self, mocked):
        mocked.side_effect = self.fake_verify_run
        info = badging(self.apk, self.tools)
        self.assertEqual("1", info["version_code"])
        self.assertEqual("com.vendor.camera.MainActivity", info["launchable_activity"])
        self.assertIn("android.permission.CAMERA", info["permissions"])

    @patch("camera_magic.pipeline.run")
    def test_badging_accepts_modern_and_legacy_min_sdk_spelling(self, mocked):
        for field in ("sdkVersion", "minSdkVersion"):
            text = BADGING.replace("sdkVersion:", field + ":")
            mocked.return_value = subprocess.CompletedProcess([], 0, text, "")
            with self.subTest(field=field):
                self.assertEqual("23", badging(self.apk, self.tools)["min_sdk"])

    def test_changed_yaml_after_patch_is_rejected(self):
        import yaml
        from tests.helpers import decoded_fixture
        from camera_magic.manifest import patch_tree

        source = decoded_fixture(self.root / "decoded")
        patched = self.root / "patched"
        assets = Path(__file__).resolve().parents[1] / "assets/mahdi_hdr"
        patch_tree(
            source,
            patched,
            hdr_assets=assets,
            new_id=DEFAULT_ID,
            label=DEFAULT_LABEL,
            min_sdk=23,
            target_sdk=35,
            acknowledge_downgrade=True,
            optional_features=[],
            remove_max_sdk=False,
        )
        path = patched / "apktool.yml"
        metadata = yaml.safe_load(path.read_text())
        metadata["sdkInfo"]["minSdkVersion"] = "29"
        path.write_text(yaml.safe_dump(metadata))
        with self.assertRaisesRegex(MagicError, "apktool.yml disagree"):
            build(patched, self.output, self.tools)
        self.assertFalse(self.output.exists())
