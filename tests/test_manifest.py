from pathlib import Path
import tempfile
import unittest
import xml.etree.ElementTree as ET

from camera_magic.core import MagicError, sha256
from camera_magic.manifest import (
    A,
    DEFAULT_ID,
    DEFAULT_LABEL,
    audit_tree,
    load_yaml,
    parse_xml,
    patch_tree,
)
from tests.helpers import ORIGINAL_ID, decoded_fixture

ASSETS = Path(__file__).resolve().parents[1] / "assets/mahdi_hdr"


class ManifestTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.source = decoded_fixture(self.root / "decoded")
        self.dest = self.root / "patched"

    def options(self, **overrides):
        options = dict(
            hdr_assets=ASSETS,
            new_id=DEFAULT_ID,
            label=DEFAULT_LABEL,
            min_sdk=23,
            target_sdk=35,
            acknowledge_downgrade=True,
            optional_features=[],
            remove_max_sdk=False,
        )
        options.update(overrides)
        return options

    def patch(self, **overrides):
        return patch_tree(self.source, self.dest, **self.options(**overrides))

    def edit(self, transform):
        path = self.source / "AndroidManifest.xml"
        tree = parse_xml(path)
        transform(tree.getroot())
        tree.write(path, encoding="utf-8", xml_declaration=True)

    def test_identity_and_class_names_preserved(self):
        report = self.patch()
        root = parse_xml(self.dest / "AndroidManifest.xml").getroot()
        app = root.find("application")
        self.assertEqual(DEFAULT_ID, root.get("package"))
        self.assertEqual(ORIGINAL_ID + ".App", app.get(A + "name"))
        self.assertEqual(
            "androidx.core.app.CoreComponentFactory", app.get(A + "appComponentFactory")
        )
        self.assertEqual(ORIGINAL_ID + ".MainActivity", app.find("activity").get(A + "name"))
        self.assertEqual(
            ORIGINAL_ID + ".Parent", app.find("activity").get(A + "parentActivityName")
        )
        self.assertEqual(
            ORIGINAL_ID + ".MainActivity", app.find("activity-alias").get(A + "targetActivity")
        )
        self.assertFalse(report["hdr_pipeline_integrated"])
        self.assertEqual("not_tested", report["runtime_compatibility"])
        self.assertEqual("com.vendor.camera.KEY", app.find("meta-data").get(A + "name"))

    def test_source_and_smali_untouched(self):
        files = [p for p in self.source.rglob("*") if p.is_file()]
        original = {p.relative_to(self.source): sha256(p) for p in files}
        self.patch()
        self.assertEqual(original, {p.relative_to(self.source): sha256(p) for p in files})
        path = Path("smali/com/vendor/camera/App.smali")
        self.assertEqual((self.source / path).read_bytes(), (self.dest / path).read_bytes())

    def test_authorities_permissions_and_processes(self):
        self.patch()
        root = parse_xml(self.dest / "AndroidManifest.xml").getroot()
        app = root.find("application")
        self.assertEqual(
            DEFAULT_ID + ".files;other.authority", app.find("provider").get(A + "authorities")
        )
        self.assertEqual(DEFAULT_ID + ".PRIVATE", app.find("provider").get(A + "permission"))
        self.assertEqual(DEFAULT_ID + ".PRIVATE", root.find("permission").get(A + "name"))
        self.assertEqual(
            DEFAULT_ID + ".PRIVATE", root.findall("uses-permission")[0].get(A + "name")
        )
        self.assertEqual(
            "android.permission.CAMERA", root.findall("uses-permission")[1].get(A + "name")
        )
        self.assertEqual(":record", app.find("service").get(A + "process"))
        self.assertEqual(DEFAULT_ID + ":sync", app.find("receiver").get(A + "process"))
        self.assertEqual(DEFAULT_ID, app.get(A + "taskAffinity"))

    def test_sdk_written_to_manifest_and_yaml_v3(self):
        self.patch()
        root = parse_xml(self.dest / "AndroidManifest.xml").getroot()
        metadata = load_yaml(self.dest / "apktool.yml")
        self.assertEqual("23", root.find("uses-sdk").get(A + "minSdkVersion"))
        self.assertEqual("35", root.find("uses-sdk").get(A + "targetSdkVersion"))
        self.assertEqual({"minSdkVersion": "23", "targetSdkVersion": "35"}, metadata["sdkInfo"])
        self.assertNotIn("packageInfo", metadata)
        self.assertEqual(127, metadata["resourcesInfo"]["packageId"])
        self.assertIn("resources.arsc", metadata["doNotCompress"])

    def test_yaml_v2_manifest_package(self):
        import shutil

        shutil.rmtree(self.source)
        decoded_fixture(self.source, version="2.11.1")
        self.patch()
        info = load_yaml(self.dest / "apktool.yml")
        self.assertEqual(DEFAULT_ID, info["packageInfo"]["renameManifestPackage"])
        self.assertEqual(127, info["packageInfo"]["forcedPackageId"])

    def test_downgrade_requires_acknowledgement(self):
        with self.assertRaisesRegex(MagicError, "cannot backport"):
            self.patch(acknowledge_downgrade=False)
        self.assertFalse(self.dest.exists())

    def test_localized_label_and_icons(self):
        self.patch()
        for locale in ("values", "values-ar", "values-en"):
            resources = parse_xml(self.dest / "res" / locale / "strings.xml").getroot()
            self.assertEqual(DEFAULT_LABEL, resources.find("string[@name='app_name']").text)
            self.assertEqual("Settings", resources.find("string[@name='settings']").text)
        app = parse_xml(self.dest / "AndroidManifest.xml").getroot().find("application")
        self.assertEqual("@string/mahdi_app_name", app.find("activity").get(A + "label"))
        self.assertEqual("@string/settings", app.findall("activity")[1].get(A + "label"))
        self.assertTrue((self.dest / "res/mipmap-anydpi/mahdi_camera.xml").exists())
        self.assertTrue((self.dest / "res/mipmap-anydpi-v26/mahdi_camera.xml").exists())

    def test_optional_features_are_explicit(self):
        self.patch(optional_features=["android.hardware.camera.level.full"])
        root = parse_xml(self.dest / "AndroidManifest.xml").getroot()
        features = root.findall("uses-feature")
        self.assertEqual("false", features[0].get(A + "required"))
        self.assertEqual("true", features[1].get(A + "required"))

    def test_unknown_optional_feature_fails(self):
        with self.assertRaisesRegex(MagicError, "not found"):
            self.patch(optional_features=["typo"])
        self.assertFalse(self.dest.exists())

    def test_split_required_not_bypassed(self):
        self.edit(lambda root: root.set(A + "isSplitRequired", "true"))
        with self.assertRaisesRegex(MagicError, "isSplitRequired"):
            self.patch()

    def test_split_manifest_attribute_blocked(self):
        self.edit(lambda root: root.set("split", "config.arm64_v8a"))
        with self.assertRaisesRegex(MagicError, "split APK"):
            self.patch()

    def test_uses_split_blocked(self):
        self.edit(lambda root: ET.SubElement(root, "uses-split", {A + "name": "base"}))
        with self.assertRaisesRegex(MagicError, "uses-split"):
            self.patch()

    def test_split_metadata_blocked(self):
        self.edit(
            lambda root: ET.SubElement(
                root.find("application"),
                "meta-data",
                {A + "name": "com.android.vending.splits.required", A + "value": "true"},
            )
        )
        with self.assertRaisesRegex(MagicError, "splits.required"):
            self.patch()

    def test_shared_uid_blocked(self):
        self.edit(lambda root: root.set(A + "sharedUserId", "com.vendor.shared"))
        with self.assertRaisesRegex(MagicError, "sharedUserId"):
            self.patch()

    def test_missing_exported_requires_review(self):
        self.edit(lambda root: root.find("./application/activity").attrib.pop(A + "exported"))
        with self.assertRaisesRegex(MagicError, "exported-policy review"):
            self.patch()

    def test_max_sdk_removal_explicit(self):
        self.edit(lambda root: root.find("uses-sdk").set(A + "maxSdkVersion", "34"))
        with self.assertRaisesRegex(MagicError, "maxSdkVersion"):
            self.patch()
        self.patch(remove_max_sdk=True)
        sdk = parse_xml(self.dest / "AndroidManifest.xml").getroot().find("uses-sdk")
        self.assertNotIn(A + "maxSdkVersion", sdk.attrib)

    def test_missing_uses_sdk_created_from_yaml(self):
        self.edit(lambda root: root.remove(root.find("uses-sdk")))
        self.patch()
        self.assertIsNotNone(
            parse_xml(self.dest / "AndroidManifest.xml").getroot().find("uses-sdk")
        )

    def test_invalid_package_ids_rejected(self):
        for identity in (ORIGINAL_ID, "UPPER.case", "invalid-package", "single"):
            with self.subTest(identity=identity), self.assertRaises(MagicError):
                self.patch(new_id=identity)

    def test_existing_destination_not_overwritten(self):
        self.dest.mkdir()
        (self.dest / "keep").write_text("keep")
        with self.assertRaises(MagicError):
            self.patch()
        self.assertEqual("keep", (self.dest / "keep").read_text())

    def test_recursive_destination_blocked(self):
        with self.assertRaises(MagicError):
            patch_tree(self.source, self.source / "child", **self.options())

    def test_symlink_source_blocked(self):
        (self.source / "link").symlink_to(self.root / "elsewhere")
        with self.assertRaisesRegex(MagicError, "Symlinks"):
            self.patch()

    def test_resource_collision_rolls_back(self):
        file = self.source / "res/values/collision.xml"
        file.write_text('<resources><string name="mahdi_app_name">taken</string></resources>')
        with self.assertRaisesRegex(MagicError, "collision"):
            self.patch()
        self.assertFalse(self.dest.exists())
        self.assertEqual([], list(self.root.glob(".mahdi-patch-*")))

    def test_full_decode_provenance_required(self):
        (self.source / ".camera-magic/source.json").unlink()
        with self.assertRaises(MagicError):
            self.patch()

    def test_asset_copy_is_not_integration(self):
        report = self.patch()
        self.assertEqual(10, len(list((self.dest / "assets/mahdi_hdr/luts").glob("*.png"))))
        self.assertTrue(report["hdr_assets_present"])
        self.assertFalse(report["hdr_pipeline_integrated"])
        self.assertEqual("rebrand_only", report["kind"])
        self.assertEqual(1, len(report["review"]["self_id_literals"]))
        self.assertEqual(1, len(report["review"]["hardware_checks"]))

    def test_audit_exposes_blockers_without_changing_code(self):
        before = sha256(self.source / "AndroidManifest.xml")
        result = audit_tree(self.source)
        self.assertEqual(ORIGINAL_ID, result["package"])
        self.assertEqual("not_tested", result["runtime_compatibility"])
        self.assertEqual(before, sha256(self.source / "AndroidManifest.xml"))

    def test_dtd_rejected(self):
        file = self.source / "res/values/strings.xml"
        file.write_text('<!DOCTYPE resources [<!ENTITY x "evil">]><resources/>')
        with self.assertRaisesRegex(MagicError, "DTD"):
            parse_xml(file)

    def test_yaml_minimum_also_requires_acknowledgement(self):
        self.edit(lambda root: root.find("uses-sdk").set(A + "minSdkVersion", "23"))
        with self.assertRaisesRegex(MagicError, "cannot backport"):
            self.patch(acknowledge_downgrade=False)
