from pathlib import Path
import stat
import struct
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from camera_magic.core import (
    MagicError,
    checked_zip,
    elf_load_alignments,
    inventory,
    run,
    version_tuple,
)
from tests.helpers import tiny_apk


class CoreTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.addCleanup(self.tmp.cleanup)

    def test_semantic_versions(self):
        self.assertGreater(version_tuple("v3.0.3"), version_tuple("2.11.1"))
        self.assertEqual((1, 5, 1), version_tuple("jadx version: 1.5.1"))
        with self.assertRaises(MagicError):
            version_tuple("unknown")

    def test_inventory_dex_and_digest(self):
        result = inventory(tiny_apk(self.root / "app.apk"))
        self.assertEqual("apk", result["kind"])
        self.assertEqual("dex\n039\x00", result["dex"][0]["magic"])
        self.assertEqual(64, len(result["sha256"]))
        self.assertEqual([], result["abis"])

    def test_split_archive_not_installable(self):
        path = self.root / "bundle.apks"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("base.apk", b"fixture")
            archive.writestr("splits/arm64.apk", b"fixture")
        result = inventory(path)
        self.assertEqual("split_archive", result["kind"])
        self.assertFalse(result["installable"])
        self.assertEqual(2, len(result["entries"]))

    def test_bad_archives(self):
        path = self.root / "bad.apk"
        path.write_bytes(b"not zip")
        with self.assertRaises(MagicError):
            inventory(path)
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("README", b"not an APK")
        with self.assertRaises(MagicError):
            inventory(path)

    def test_unsafe_paths(self):
        for name in ("../evil", "/absolute", "a/../../evil", "a\\evil", "C:/evil"):
            with self.subTest(name=name):
                path = self.root / "unsafe.zip"
                with zipfile.ZipFile(path, "w") as archive:
                    archive.writestr(name, b"bad")
                with self.assertRaises(MagicError):
                    checked_zip(path)

    def test_symlink_rejected(self):
        path = self.root / "link.zip"
        entry = zipfile.ZipInfo("link")
        entry.external_attr = (stat.S_IFLNK | 0o777) << 16
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr(entry, "../target")
        with self.assertRaises(MagicError):
            checked_zip(path)

    def test_duplicate_entries_rejected(self):
        path = self.root / "dup.zip"
        import warnings

        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            with zipfile.ZipFile(path, "w") as archive:
                archive.writestr("same", "one")
                archive.writestr("same", "two")
        with self.assertRaises(MagicError):
            checked_zip(path)

    def test_elf_64_alignment(self):
        header = bytearray(120)
        header[:6] = b"\x7fELF\x02\x01"
        struct.pack_into("<Q", header, 32, 64)
        struct.pack_into("<HH", header, 54, 56, 1)
        struct.pack_into("<I", header, 64, 1)
        struct.pack_into("<Q", header, 112, 16384)
        self.assertEqual([16384], elf_load_alignments(header))
        self.assertEqual([], elf_load_alignments(header[:80]))
        path = self.root / "native.apk"
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("AndroidManifest.xml", b"fixture")
            archive.writestr("lib/arm64-v8a/libcamera.so", header)
        result = inventory(path)
        self.assertTrue(result["native_libraries"][0]["elf_16k_alignment_candidate"])
        self.assertEqual(["arm64-v8a"], result["abis"])

    def test_elf_32_alignment(self):
        header = bytearray(96)
        header[:6] = b"\x7fELF\x01\x01"
        struct.pack_into("<I", header, 28, 64)
        struct.pack_into("<HH", header, 42, 32, 1)
        struct.pack_into("<I", header, 64, 1)
        struct.pack_into("<I", header, 92, 4096)
        self.assertEqual([4096], elf_load_alignments(header))
        self.assertEqual([], elf_load_alignments(b"not ELF"))

    @patch("camera_magic.core.subprocess.run")
    def test_run_no_shell(self, mocked):
        mocked.return_value = subprocess.CompletedProcess([], 0, "ok", "")
        run(["program", "with spaces", "$(untrusted)"])
        self.assertNotIn("shell", mocked.call_args.kwargs)
        self.assertEqual("$(untrusted)", mocked.call_args.args[0][2])

    @patch("camera_magic.core.subprocess.run", side_effect=FileNotFoundError)
    def test_run_missing_tool(self, _):
        with self.assertRaisesRegex(MagicError, "Tool not found"):
            run(["missing"])

    @patch("camera_magic.core.subprocess.run", side_effect=subprocess.TimeoutExpired("tool", 1))
    def test_run_timeout_never_success(self, _):
        with self.assertRaisesRegex(MagicError, "no success"):
            run(["tool"], timeout=1)
