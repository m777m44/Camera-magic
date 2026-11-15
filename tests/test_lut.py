import binascii
import hashlib
import math
from pathlib import Path
import struct
import tempfile
import unittest
import zlib

from camera_magic.core import MagicError, read_json
from camera_magic.lut import blend, encode_png, generate, grade, render_preset, validate_presets

ASSETS = Path(__file__).resolve().parents[1] / "assets/mahdi_hdr"


def read_png(data: bytes):
    assert data[:8] == b"\x89PNG\r\n\x1a\n"
    index, compressed, chunks = 8, b"", []
    width = height = 0
    while index < len(data):
        size = struct.unpack_from(">I", data, index)[0]
        kind = data[index + 4 : index + 8]
        content = data[index + 8 : index + 8 + size]
        crc = struct.unpack_from(">I", data, index + 8 + size)[0]
        assert crc == binascii.crc32(kind + content) & 0xFFFFFFFF
        chunks.append(kind)
        if kind == b"IHDR":
            width, height, depth, color, _, _, _ = struct.unpack(">IIBBBBB", content)
            assert (depth, color) == (8, 2)
        if kind == b"IDAT":
            compressed += content
        index += size + 12
    decompressed = zlib.decompress(compressed)
    stride = width * 3
    pixels = bytearray()
    for row in range(height):
        assert decompressed[row * (stride + 1)] == 0
        pixels.extend(decompressed[row * (stride + 1) + 1 : (row + 1) * (stride + 1)])
    return width, height, pixels, chunks


class LutTests(unittest.TestCase):
    def setUp(self):
        self.data = read_json(ASSETS / "presets.json")

    def test_ten_valid_distinct_presets(self):
        validate_presets(self.data)
        self.assertEqual(10, len(self.data["presets"]))
        hashes = {
            hashlib.sha256(render_preset(p, 16, 4)[1]).hexdigest() for p in self.data["presets"]
        }
        self.assertEqual(10, len(hashes))

    def test_committed_luts_reproduce_exactly(self):
        result = generate(ASSETS, check=True)
        self.assertEqual(20, len(result["files"]))

    def test_cube_size_order_and_ranges(self):
        preset = self.data["presets"][0]
        cube, _ = render_preset(preset, 16, 4)
        rows = [
            tuple(map(float, line.split()))
            for line in cube.splitlines()
            if line and line[0] not in "#TLD"
        ]
        self.assertEqual(16**3, len(rows))
        for row in rows:
            self.assertTrue(all(math.isfinite(v) and 0 <= v <= 1 for v in row))
        for index, rgb in ((1, (1 / 15, 0, 0)), (16, (0, 1 / 15, 0)), (256, (0, 0, 1 / 15))):
            for actual, expected in zip(rows[index], grade(rgb, preset)):
                self.assertAlmostEqual(actual, expected, places=6)

    def test_png_atlas_layout_and_crc(self):
        preset = self.data["presets"][1]
        _, png = render_preset(preset, 16, 4)
        width, height, pixels, chunks = read_png(png)
        self.assertEqual((64, 64), (width, height))
        self.assertNotIn(b"sRGB", chunks)
        self.assertNotIn(b"iCCP", chunks)
        for red, green, blue in ((0, 0, 0), (15, 15, 15), (3, 8, 5), (7, 2, 12)):
            x, y = blue % 4 * 16 + red, blue // 4 * 16 + green
            index = (y * width + x) * 3
            expected = grade((red / 15, green / 15, blue / 15), preset)
            for value, reference in zip(pixels[index : index + 3], expected):
                self.assertLessEqual(abs(value / 255 - reference), 0.5 / 255 + 1e-8)

    def test_mono_is_neutral(self):
        mono = self.data["presets"][-1]
        for rgb in ((0.1, 0.8, 0.4), (1, 0, 0), (0, 0, 1)):
            value = grade(rgb, mono)
            self.assertAlmostEqual(value[0], value[1])
            self.assertAlmostEqual(value[1], value[2])

    def test_intensity_zero_identity_one_full_and_clamped(self):
        original, filtered = (0.1, 0.4, 0.8), (0.2, 0.3, 0.7)
        self.assertEqual(original, blend(original, filtered, 0))
        self.assertEqual(filtered, blend(original, filtered, 1))
        self.assertEqual(original, blend(original, filtered, -1))
        self.assertEqual(filtered, blend(original, filtered, 2))
        self.assertAlmostEqual(0.15, blend(original, filtered, 0.5)[0])
        with self.assertRaises(MagicError):
            blend(original, filtered, float("nan"))

    def test_bad_config_and_png_buffer(self):
        self.data["presets"][0]["id"] = "../escape"
        with self.assertRaises(MagicError):
            validate_presets(self.data)
        with self.assertRaises(MagicError):
            encode_png(2, 2, b"short")

    def test_stale_lut_detected(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary)
            (path / "presets.json").write_bytes((ASSETS / "presets.json").read_bytes())
            with self.assertRaisesRegex(MagicError, "Missing/stale"):
                generate(path, check=True)
            generate(path)
            (path / "luts/balanced.png").write_bytes(b"corrupt")
            with self.assertRaises(MagicError):
                generate(path, check=True)
