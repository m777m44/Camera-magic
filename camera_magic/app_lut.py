"""Bundled looks for the Mahdi Camera app module.

The app ships a small set of LUTs as PNG atlases plus a manifest. The atlases are
generated here so they are reproducible: the Python side of the parity test and the
Java parser in ``camera-app`` must produce byte-identical atlases, otherwise a LUT
could preview differently from the saved photo.

The app assets are deliberately a separate, smaller set from ``assets/mahdi_hdr``
(the reference library used by ``hdr-kit``). Nothing here claims sensor HDR, 10-bit
output or log-domain grading: these are original display-referred SDR looks.
"""

from __future__ import annotations

import json
from pathlib import Path

from .core import MagicError
from .lut import encode_png, grade

APP_ASSETS = Path("camera-app/src/main/assets/mahdi_lut")
LUT_SIZE = 16
TILES = 4

LOOKS = [
    {
        "id": "neutral",
        "name": "Neutral",
        "exposure": 0.0,
        "contrast": 1.0,
        "saturation": 1.0,
        "shadows": 0.0,
        "highlights": 0.0,
        "warmth": 0.0,
    },
    {
        "id": "natural",
        "name": "Natural",
        "exposure": 0.0,
        "contrast": 1.04,
        "saturation": 1.03,
        "shadows": 0.18,
        "highlights": 0.15,
        "warmth": 0.0,
    },
    {
        "id": "punch",
        "name": "Punch",
        "exposure": 0.04,
        "contrast": 1.18,
        "saturation": 1.22,
        "shadows": 0.12,
        "highlights": 0.20,
        "warmth": 0.02,
    },
    {
        "id": "cinema",
        "name": "Cinema",
        "exposure": -0.08,
        "contrast": 1.12,
        "saturation": 0.86,
        "shadows": 0.14,
        "highlights": 0.30,
        "warmth": 0.08,
    },
    {
        "id": "soft",
        "name": "Soft light",
        "exposure": 0.02,
        "contrast": 0.92,
        "saturation": 0.98,
        "shadows": 0.35,
        "highlights": 0.45,
        "warmth": 0.0,
    },
    {
        "id": "noir",
        "name": "Noir",
        "exposure": -0.04,
        "contrast": 1.22,
        "saturation": 0.0,
        "shadows": 0.10,
        "highlights": 0.22,
        "warmth": 0.0,
    },
]

MANIFEST = {
    "schema": 1,
    "generator": "python -m camera_magic app-luts",
    "color_space": "SDR display-referred RGB; not Log, PQ or HLG",
    "lut_size": LUT_SIZE,
    "tiles": TILES,
    "cube_order": "red fastest, then green, then blue",
    "atlas_layout": "size tiles per row, one tile per blue slice",
    "strength_range": [0.0, 1.0],
    "presets": LOOKS,
    "warning": (
        "Original display-referred looks. They do not add sensor dynamic range, "
        "do not recover clipped highlights, and are not a substitute for RAW."
    ),
}


# "Noir" is the bundled look the instrumented test grades a still with. A greyscale look
# with zero saturation makes the check unambiguous: if the GPU path is bypassed, the saved
# still keeps the emulator's colour chart instead of turning grey.
NOIR_ID = "noir"


def render_look(look: dict) -> bytes:
    """Render one look as an 8-bit RGB PNG atlas (tiles laid out left-to-right, top-to-bottom)."""
    size, tiles = LUT_SIZE, TILES
    width = size * tiles
    pixels = bytearray(width * width * 3)
    for blue in range(size):
        for green in range(size):
            for red in range(size):
                rgb = grade(
                    (red / (size - 1), green / (size - 1), blue / (size - 1)),
                    dict(look, clarity=0.0, local_contrast=0.0),
                )
                x = (blue % tiles) * size + red
                y = (blue // tiles) * size + green
                index = (y * width + x) * 3
                pixels[index : index + 3] = bytes(min(255, max(0, int(c * 255 + 0.5))) for c in rgb)
    return encode_png(width, width, bytes(pixels))


def expected_files() -> dict[str, bytes]:
    files = {"presets.json": json.dumps(MANIFEST, indent=2).encode("utf-8") + b"\n"}
    for look in LOOKS:
        files[f"luts/{look['id']}.png"] = render_look(look)
    return files


def generate_app_luts(root: Path, *, check: bool = False) -> dict:
    """Write or verify the app's bundled LUT assets under ``root``."""
    directory = root / APP_ASSETS
    files = expected_files()
    written: list[str] = []
    stale: list[str] = []
    for relative, contents in files.items():
        target = directory / relative
        if check:
            if not target.is_file() or target.read_bytes() != contents:
                stale.append(str(target))
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(contents)
            written.append(str(target))
    if stale:
        raise MagicError("Stale or missing application LUT assets: " + ", ".join(sorted(stale)))
    return {
        "mode": "checked" if check else "generated",
        "preset_count": len(LOOKS),
        "files": sorted(written) if written else sorted(str(directory / name) for name in files),
    }
