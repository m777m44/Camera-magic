from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from camera_magic.core import MagicError, write_json
from camera_magic.tools import Tools


class ToolTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def sdk(self, revision):
        directory = self.root / "build-tools" / revision
        directory.mkdir(parents=True)
        (directory / "source.properties").write_text("Pkg.Revision = " + revision)
        return directory

    def tools(self, **extra):
        config = self.root / "tools.json"
        write_json(config, {"sdk_root": str(self.root), **extra})
        return Tools(config)

    def test_highest_stable_sdk_selected(self):
        self.sdk("34.0.0")
        self.sdk("35.0.0")
        self.sdk("36.0.0")
        self.sdk("37.0.0-rc1")
        self.assertEqual("36.0.0", self.tools().build_dir()[1])

    def test_old_sdk_rejected(self):
        self.sdk("34.0.0")
        with self.assertRaises(MagicError):
            self.tools().build_dir()

    def test_explicit_build_tools(self):
        directory = self.sdk("35.0.0")
        self.sdk("36.0.0")
        (directory / "zipalign").touch()
        tools = self.tools(build_tools="35.0.0")
        self.assertEqual(str(directory / "zipalign"), tools.sdk_tool("zipalign"))
        with self.assertRaises(MagicError):
            tools.sdk_tool("missing")

    @patch("camera_magic.tools.run")
    def test_java_minimum(self, run):
        run.return_value = subprocess.CompletedProcess([], 0, "", 'openjdk version "17.0.13"')
        self.assertIn("17.0.13", self.tools().check_java())
        run.return_value = subprocess.CompletedProcess([], 0, "", 'java version "1.8.0_332"')
        with self.assertRaises(MagicError):
            self.tools().check_java()

    @patch("camera_magic.tools.Tools.check_java", return_value="17")
    @patch("camera_magic.tools.run")
    def test_apktool_minimum_and_new_major(self, run, _):
        for version, accepted in (("2.9.3", False), ("2.11.1", True), ("3.0.3", True)):
            run.return_value = subprocess.CompletedProcess([], 0, version, "")
            if accepted:
                self.assertEqual(version, self.tools().check_apktool())
            else:
                with self.assertRaises(MagicError):
                    self.tools().check_apktool()

    @patch("camera_magic.tools.Tools.check_java", return_value="17")
    @patch("camera_magic.tools.run")
    def test_jadx_minimum(self, run, _):
        run.return_value = subprocess.CompletedProcess([], 0, "1.5.1", "")
        self.assertEqual("1.5.1", self.tools().check_jadx())
        run.return_value = subprocess.CompletedProcess([], 0, "1.4.7", "")
        with self.assertRaises(MagicError):
            self.tools().check_jadx()

    @patch("camera_magic.tools.run", side_effect=MagicError("Tool not installed"))
    def test_doctor_missing_tools_does_not_claim_readiness(self, _):
        result = self.tools().doctor()
        self.assertFalse(result["ready_for_rebrand_build"])
        self.assertEqual("not_tested", result["runtime_compatibility"])
        self.assertFalse(result["required"]["apksigner"]["ok"])

    @patch("camera_magic.tools.run")
    def test_java_options_digits_do_not_mask_old_version(self, run):
        run.return_value = subprocess.CompletedProcess(
            [], 0, "", 'Picked up JAVA_TOOL_OPTIONS: -Xmx2048m\njava version "1.8.0_332"'
        )
        with self.assertRaisesRegex(MagicError, "found 8"):
            self.tools().check_java()

    def test_invalid_config_keys_and_types(self):
        for values in ({"unknown_tool": "path"}, {"sdk_root": 35}):
            path = self.root / "invalid.json"
            write_json(path, values)
            with self.assertRaises(MagicError):
                Tools(path)
