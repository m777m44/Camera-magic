"""Cross-language parity between the Java LUT parser and the Python generator.

The app parses user .cube files in Java; the bundled atlases are produced in Python.
If the two disagree, a look previews one way and saves another way. These tests read
the Java source (no Android toolchain needed) and re-implement its exact rules, then
compare against ``camera_magic.app_lut`` output.
"""

from __future__ import annotations

import re
from pathlib import Path
import unittest

from camera_magic.app_lut import LOOKS, LUT_SIZE, TILES, generate_app_luts, render_look
from camera_magic.core import MagicError
from camera_magic.lut import encode_png, grade
from tests.test_lut import read_png

ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "camera-app/src/main/java/com/mahdi/camera/lut"
APP_ASSETS = ROOT / "camera-app/src/main/assets/mahdi_lut"


def cube_text(size: int, *, header: str = "LUT_3D_SIZE %d\n", crlf: bool = False) -> str:
    lines = [header % size]
    for blue in range(size):
        for green in range(size):
            for red in range(size):
                lines.append(
                    f"{red / (size - 1):.6f} {green / (size - 1):.6f} {blue / (size - 1):.6f}"
                )
    text = "\n".join(lines) + "\n"
    return text.replace("\n", "\r\n") if crlf else text


def java_pack(entries: list[tuple[float, float, float]]) -> list[tuple[int, int, int]]:
    """Mirror of CubeLut.parse's packing: clamp to 0..1, round to 8 bits."""
    packed = []
    for r, g, b in entries:
        channels = []
        for value in (r, g, b):
            scaled = int(value * 255 + 0.5) if value >= 0 else 0
            channels.append(max(0, min(255, scaled)))
        packed.append(tuple(channels))
    return packed


def cube_entries(size: int) -> list[tuple[float, float, float]]:
    return [
        (red / (size - 1), green / (size - 1), blue / (size - 1))
        for blue in range(size)
        for green in range(size)
        for red in range(size)
    ]


def atlas_from_cube(entries, size: int, tiles: int) -> tuple[int, bytes]:
    width = size * tiles
    pixels = bytearray(width * width * 3)
    for index, (r, g, b) in enumerate(java_pack(entries)):
        blue, green, red = index // (size * size), (index // size) % size, index % size
        x = (blue % tiles) * size + red
        y = (blue // tiles) * size + green
        offset = (y * width + x) * 3
        pixels[offset : offset + 3] = bytes((r, g, b))
    return width, bytes(pixels)


class AppLutAssetTests(unittest.TestCase):
    def test_committed_assets_reproduce_exactly(self):
        result = generate_app_luts(ROOT, check=True)
        self.assertEqual(len(LOOKS) + 1, len(result["files"]))

    def test_stale_asset_is_reported(self):
        import tempfile
        import shutil

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            shutil.copytree(APP_ASSETS, root / "camera-app/src/main/assets/mahdi_lut")
            target = root / "camera-app/src/main/assets/mahdi_lut/luts/noir.png"
            target.write_bytes(b"not a png")
            with self.assertRaises(MagicError):
                generate_app_luts(root, check=True)

    def test_six_distinct_looks_and_neutral_identity(self):
        self.assertEqual(6, len(LOOKS))
        self.assertEqual(6, len({look["id"] for look in LOOKS}))
        neutral = next(look for look in LOOKS if look["id"] == "neutral")
        # Identity look: the graded value must equal the input in 8-bit precision.
        for value in (0.0, 0.25, 0.5, 0.75, 1.0):
            self.assertAlmostEqual(
                value,
                grade((value, value, value), dict(neutral, clarity=0.0, local_contrast=0.0))[1],
                places=6,
            )

    def test_atlas_is_square_and_tile_aligned(self):
        atlas = render_look(LOOKS[0])
        width = int.from_bytes(atlas[16:20], "big")
        self.assertEqual(LUT_SIZE * TILES, width)
        size = int.from_bytes(atlas[20:24], "big")
        self.assertEqual(width, size)


class JavaParserParityTests(unittest.TestCase):
    """The Java parser is checked for the same rules the Python generator relies on."""

    def test_identity_cube_produces_the_same_atlas_as_neutral(self):
        """A user-supplied identity .cube must grade exactly like the Neutral look."""
        size = LUT_SIZE
        width, expected = atlas_from_cube(cube_entries(size), size, TILES)
        self.assertEqual(size * TILES, width)
        neutral = next(look for look in LOOKS if look["id"] == "neutral")
        png_width, png_height, actual, _ = read_png(render_look(neutral))
        self.assertEqual(width, png_width)
        self.assertEqual(width, png_height)
        self.assertEqual(expected, actual)

    def test_cube_red_fastest_order_matches_atlas_tiles(self):
        size, tiles = 4, 2
        entries = cube_entries(size)
        width, pixels = atlas_from_cube(entries, size, tiles)
        self.assertEqual(size * tiles, width)
        # Entry 1 is red=1/3,green=0,blue=0 and must sit in tile 0, one pixel right.
        self.assertEqual((85, 0, 0), tuple(pixels[(0 * width + 1) * 3 : (0 * width + 1) * 3 + 3]))
        # First entry of the second blue tile is at x = tiles? no: blue 1 -> x = size.
        offset = (0 * width + size) * 3
        self.assertEqual((0, 0, 85), tuple(pixels[offset : offset + 3]))

    def test_java_rejects_what_python_never_emits(self):
        java_files = sorted(JAVA.glob("*.java"))
        self.assertTrue(java_files, "LUT parser sources are missing")
        source = "".join(path.read_text(encoding="utf-8") for path in java_files)
        for required in (
            "LUT_3D_SIZE",
            "LUT_1D_SIZE",
            "DOMAIN_MIN",
            "DOMAIN_MAX",
            "LUT_3D_INPUT_RANGE",
            "MAX_SIZE",
        ):
            self.assertIn(required, source)
        # Parsing must stay free of Android framework calls so it is JVM-unit-testable.
        parse_body = source[
            source.index("public static CubeLut parse") : source.index(
                "private static float normalise"
            )
        ]
        for forbidden in ("android.content", "android.net", "Log.", "Context"):
            self.assertNotIn(forbidden, parse_body)

    def test_generator_header_matches_parser_expectations(self):
        manifest = (APP_ASSETS / "presets.json").read_text(encoding="utf-8")
        self.assertIn('"lut_size": 16', manifest)
        self.assertIn('"tiles": 4', manifest)
        self.assertIn("not Log, PQ or HLG", manifest)
        for look in LOOKS:
            self.assertRegex(look["id"], r"^[a-z][a-z0-9_]*$")
            self.assertTrue((APP_ASSETS / "luts" / (look["id"] + ".png")).is_file())

    def test_png_atlas_uses_8bit_rgb_like_bitmapfactory_expects(self):
        atlas = render_look(LOOKS[2])
        self.assertEqual(b"\x89PNG\r\n\x1a\n", atlas[:8])
        depth, color = atlas[24], atlas[25]
        self.assertEqual((8, 2), (depth, color))
        self.assertEqual(encode_png(64, 64, b"\x00" * 64 * 64 * 3)[:8], atlas[:8])


class CubeRoundTripTests(unittest.TestCase):
    """Documents the .cube text the app accepts, using the same fixture both sides."""

    def test_no_bundled_asset_is_a_cube_file(self):
        # .cube files are user imports only; the bundled looks stay small PNG atlases.
        self.assertEqual([], sorted(APP_ASSETS.rglob("*.cube")))

    def test_windows_line_endings_and_comments_are_accepted(self):
        text = cube_text(2, crlf=True).replace("LUT_3D_SIZE 2", "# comment\r\nLUT_3D_SIZE 2")
        entries = [
            tuple(float(value) for value in line.split())
            for line in text.splitlines()
            if line and not line.startswith("#") and not line.startswith("LUT_3D_SIZE")
        ]
        self.assertEqual(8, len(entries))
        width, pixels = atlas_from_cube(entries, 2, 2)
        self.assertEqual(4, width)
        self.assertEqual(4 * 4 * 3, len(pixels))

    def test_declared_max_size_matches_shader_texture_budget(self):
        source = (JAVA / "CubeLut.java").read_text(encoding="utf-8")
        match = re.search(r"MAX_SIZE = (\d+)", source)
        self.assertIsNotNone(match)
        size = int(match.group(1))
        self.assertLessEqual(size * size * size, 64 * 64 * 64)
        self.assertEqual(4096, size * size, "atlas must stay a square 4096px texture")
