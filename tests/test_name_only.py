from pathlib import Path
import os
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET
import zipfile

from camera_magic.core import MagicError, sha256
from camera_magic.manifest import A, DEFAULT_LABEL, parse_xml
from camera_magic.name_only import (
    android_literal,
    payload_digests,
    rename_apk,
    rename_labels,
    verify_preserved,
)
from tests.helpers import decoded_fixture
from tests.test_pipeline import SIGS


def payload_apk(path, *, asset=b"original asset", dex=b"dex\n039\x00fixture"):
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("AndroidManifest.xml", b"unit-test fixture, not installable")
        archive.writestr("classes.dex", dex)
        archive.writestr("classes2.dex", b"secondary fixture")
        archive.writestr("assets/vendor.bin", asset)
        archive.writestr("lib/arm64-v8a/libcamera.so", b"unchanged native fixture")
        archive.writestr("res/raw/original.txt", b"original resource")
    return path


def metadata(label="Original"):
    return {
        "application_id": "com.vendor.camera",
        "min_sdk": "29",
        "target_sdk": "34",
        "version_code": "35",
        "version_name": "3.5.0",
        "label": label,
        "launchable_activity": "com.vendor.camera.MainActivity",
        "permissions": ["android.permission.CAMERA"],
    }


class NameOnlyTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.source = decoded_fixture(self.root / "decoded")

    def test_only_display_label_attributes_change(self):
        before = parse_xml(self.source / "AndroidManifest.xml").getroot()
        report = rename_labels(self.source)
        after = parse_xml(self.source / "AndroidManifest.xml").getroot()
        changed = []
        for first, second in zip(before.iter(), after.iter()):
            self.assertEqual(first.tag, second.tag)
            for key in first.attrib.keys() | second.attrib.keys():
                if first.get(key) != second.get(key):
                    changed.append((first.tag, key))
        self.assertEqual([("application", A + "label"), ("activity", A + "label")], changed)
        self.assertEqual("com.vendor.camera", after.get("package"))
        self.assertEqual("29", after.find("uses-sdk").get(A + "minSdkVersion"))
        self.assertEqual("34", after.find("uses-sdk").get(A + "targetSdkVersion"))
        self.assertEqual("@mipmap/old", after.find("application").get(A + "icon"))
        self.assertFalse(report["filters_added"])

    def test_existing_strings_sdk_smali_icons_and_assets_not_touched(self):
        files = {
            p.relative_to(self.source): sha256(p) for p in self.source.rglob("*") if p.is_file()
        }
        report = rename_labels(self.source)
        for path, digest in files.items():
            if str(path) != "AndroidManifest.xml":
                self.assertEqual(digest, sha256(self.source / path), str(path))
        self.assertEqual(
            '"' + DEFAULT_LABEL + '"', parse_xml(self.source / report["resource"]).getroot()[0].text
        )
        self.assertFalse((self.source / "assets/mahdi_hdr").exists())

    def test_launcher_alias_label_changes_but_nonlauncher_labels_stay(self):
        path = self.source / "AndroidManifest.xml"
        tree = parse_xml(path)
        alias = tree.getroot().find("./application/activity-alias")
        intent = ET.SubElement(alias, "intent-filter")
        ET.SubElement(intent, "category", {A + "name": "android.intent.category.LAUNCHER"})
        tree.write(path)
        report = rename_labels(self.source)
        self.assertEqual(3, len(report["label_changes"]))
        after = parse_xml(path).getroot()
        self.assertTrue(
            after.find("./application/activity-alias")
            .get(A + "label")
            .startswith("@string/camera_magic_")
        )
        self.assertEqual(
            "@string/settings",
            after.find(
                "./application/activity[@android:name='.Settings']", {"android": A[1:-1]}
            ).get(A + "label"),
        )

    def test_repeated_rename_allocates_resource_without_overwriting(self):
        first = rename_labels(self.source)
        digest = sha256(self.source / first["resource"])
        second = rename_labels(self.source, "A New Name")
        self.assertNotEqual(first["resource"], second["resource"])
        self.assertEqual(digest, sha256(self.source / first["resource"]))

    def test_invalid_labels_fail_before_writes(self):
        original = sha256(self.source / "AndroidManifest.xml")
        for label in ("", " leading", "newline\n", "x" * 201, "\x01bad"):
            with self.subTest(label=label), self.assertRaises(MagicError):
                rename_labels(self.source, label)
        self.assertEqual(original, sha256(self.source / "AndroidManifest.xml"))

    def test_android_and_xml_escaping(self):
        label = 'Mahdi\'s "Camera" & Lens'
        report = rename_labels(self.source, label)
        text = parse_xml(self.source / report["resource"]).getroot()[0].text
        self.assertEqual(android_literal(label), text)
        self.assertIn("\\'", text)
        self.assertIn('\\"', text)

    def test_split_requirements_refused_not_removed(self):
        path = self.source / "AndroidManifest.xml"
        tree = parse_xml(path)
        tree.getroot().set(A + "isSplitRequired", "true")
        tree.write(path)
        with self.assertRaisesRegex(MagicError, "split"):
            rename_labels(self.source)
        self.assertEqual("true", parse_xml(path).getroot().get(A + "isSplitRequired"))

    def test_shared_uid_refused_without_signature_migration(self):
        path = self.source / "AndroidManifest.xml"
        tree = parse_xml(path)
        tree.getroot().set(A + "sharedUserId", "vendor.shared")
        tree.write(path)
        with self.assertRaisesRegex(MagicError, "sharedUserId"):
            rename_labels(self.source)

    def test_payload_hashes_include_all_dex_native_and_assets(self):
        apk = payload_apk(self.root / "payload.apk")
        digests = payload_digests(apk)
        self.assertEqual(
            {"classes.dex", "classes2.dex", "assets/vendor.bin", "lib/arm64-v8a/libcamera.so"},
            set(digests),
        )

    @patch("camera_magic.name_only.badging")
    def test_verification_accepts_original_sdk_29_target_34(self, mocked):
        original = payload_apk(self.root / "original.apk")
        rebuilt = payload_apk(self.root / "rebuilt.apk")
        mocked.side_effect = [metadata(), metadata(DEFAULT_LABEL)]
        report = verify_preserved(original, rebuilt, None, DEFAULT_LABEL)
        self.assertTrue(report["identity_sdk_version_unchanged"])
        self.assertEqual("29", report["metadata"]["min_sdk"])

    @patch("camera_magic.name_only.badging")
    def test_sdk_version_package_or_permissions_drift_rejected(self, mocked):
        original = payload_apk(self.root / "original.apk")
        rebuilt = payload_apk(self.root / "rebuilt.apk")
        for field, value in (
            ("min_sdk", "23"),
            ("target_sdk", "35"),
            ("version_code", "99"),
            ("version_name", "fake"),
            ("application_id", "com.changed.app"),
            ("permissions", []),
            ("launchable_activity", ".Other"),
        ):
            after = metadata(DEFAULT_LABEL)
            after[field] = value
            mocked.side_effect = [metadata(), after]
            with self.subTest(field=field), self.assertRaises(MagicError):
                verify_preserved(original, rebuilt, None, DEFAULT_LABEL)

    @patch("camera_magic.name_only.badging")
    def test_asset_or_code_changes_block_release(self, mocked):
        original = payload_apk(self.root / "original.apk")
        for values in ({"asset": b"changed"}, {"dex": b"changed code"}):
            rebuilt = payload_apk(self.root / "rebuilt.apk", **values)
            mocked.side_effect = [metadata(), metadata(DEFAULT_LABEL)]
            with self.assertRaisesRegex(MagicError, "bytes changed"):
                verify_preserved(original, rebuilt, None, DEFAULT_LABEL)

    def run_mock_build(self, *, changed_payload=False):
        from tests.test_pipeline import DummyTools

        tools = DummyTools()
        tools.check_apktool = lambda: "3.0.3"
        tools.apktool = lambda: ["apktool"]
        original = payload_apk(self.root / "original.apk")
        output = self.root / "Blackmagic_Camera_Mahdi.apk"
        key = self.root / "private.jks"
        key.write_bytes(b"unit-test fake key")
        key.chmod(0o600)
        commands = []

        def invoke(args, **kwargs):
            commands.append(args)
            if args[:2] == ["apktool", "d"]:
                directory = Path(args[args.index("--output") + 1])
                shutil.copytree(self.source, directory)
            elif args[:2] == ["apktool", "b"]:
                path = Path(args[args.index("--output") + 1])
                payload_apk(path, asset=b"changed" if changed_payload else b"original asset")
            elif args[0] == "zipalign" and "-c" not in args:
                shutil.copyfile(args[-2], args[-1])
            elif args[:2] == ["apksigner", "sign"]:
                shutil.copyfile(args[-1], args[args.index("--out") + 1])
            return subprocess.CompletedProcess(args, 0, SIGS, "")

        with (
            patch.dict(
                os.environ,
                {"STORE_TEST": "not-real-password-123", "KEY_TEST": "not-real-password-456"},
            ),
            patch("camera_magic.name_only.run", side_effect=invoke),
            patch(
                "camera_magic.name_only.badging",
                side_effect=[metadata(), metadata(), metadata(DEFAULT_LABEL)],
            ),
        ):
            report = rename_apk(
                original, output, key, "mahdi", tools, store_env="STORE_TEST", key_env="KEY_TEST"
            )
        return report, commands, output

    def test_full_workflow_keeps_raw_dex_and_only_uses_password_env_names(self):
        report, commands, output = self.run_mock_build()
        self.assertEqual("name_only_signed", report["kind"])
        self.assertTrue(output.exists())
        self.assertTrue(output.with_suffix(".verification.json").exists())
        decode = next(command for command in commands if command[:2] == ["apktool", "d"])
        self.assertIn("--no-src", decode)
        self.assertNotIn("--no-res", decode)
        signing = next(command for command in commands if command[:2] == ["apksigner", "sign"])
        self.assertIn("env:STORE_TEST", signing)
        self.assertNotIn("not-real-password-123", repr(commands))
        self.assertFalse(report["package_changed"])
        self.assertFalse(report["sdk_changed"])
        self.assertFalse(report["icon_changed"])

    def test_failed_preservation_does_not_leave_a_downloadable_apk(self):
        with self.assertRaisesRegex(MagicError, "bytes changed"):
            self.run_mock_build(changed_payload=True)
        self.assertFalse((self.root / "Blackmagic_Camera_Mahdi.apk").exists())
        self.assertEqual([], list(self.root.glob(".name-only-*")))
