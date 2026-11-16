"""Deterministic original 3D LUTs; PNG encoder uses only Python's standard library."""

from __future__ import annotations

import binascii
import math
from pathlib import Path
import re
import struct
import zlib

from .core import MagicError, read_json


def clamp(value: float) -> float:
    return max(0.0, min(1.0, value))


def grade(rgb: tuple[float, float, float], preset: dict) -> tuple[float, float, float]:
    channels = []
    for value in rgb:
        value = clamp(value * 2 ** preset["exposure"])
        # Display-referred shadow lift and highlight softening: no claim to recover clipped data.
        value += preset["shadows"] * (math.sqrt(value) - value) * 0.55
        value -= preset["highlights"] * value * value * (1.0 - value)
        channels.append(clamp(0.5 + (value - 0.5) * preset["contrast"]))
    luma = sum(c * w for c, w in zip(channels, (0.2126, 0.7152, 0.0722)))
    channels = [luma + (c - luma) * preset["saturation"] for c in channels]
    # Tint is suppressed in pure black/white to avoid unwanted color casts at the endpoints.
    mask = 4.0 * luma * (1.0 - luma)
    channels[0] += preset["warmth"] * 0.08 * mask
    channels[2] -= preset["warmth"] * 0.08 * mask
    return tuple(clamp(c) for c in channels)


def blend(original: tuple, filtered: tuple, strength: float) -> tuple:
    if not math.isfinite(strength):
        raise MagicError("Strength must be finite.")
    strength = clamp(strength)
    return tuple(a + (b - a) * strength for a, b in zip(original, filtered))


def validate_presets(data: dict) -> None:
    size, tiles = data.get("lut_size"), data.get("tiles")
    if (
        not isinstance(size, int)
        or not isinstance(tiles, int)
        or size < 2
        or size > 64
        or tiles * tiles != size
    ):
        raise MagicError("LUT size must be 4, 9, 16, 25, 36, 49, or 64 with tiles squared == size.")
    presets = data.get("presets")
    if not isinstance(presets, list) or not 5 <= len(presets) <= 64:
        raise MagicError("Expected 5–64 presets.")
    seen = set()
    bounds = {
        "exposure": (-2, 2),
        "contrast": (0, 2),
        "saturation": (0, 2),
        "shadows": (0, 1),
        "highlights": (0, 1),
        "warmth": (-1, 1),
        "clarity": (0, 1),
        "local_contrast": (0, 1),
    }
    for preset in presets:
        identity = preset.get("id", "")
        if not re.fullmatch(r"[a-z][a-z0-9_]*", identity) or identity in seen:
            raise MagicError("Preset IDs must be unique lowercase identifiers.")
        seen.add(identity)
        for key, (minimum, maximum) in bounds.items():
            value = preset.get(key)
            if (
                not isinstance(value, (int, float))
                or not math.isfinite(value)
                or not minimum <= value <= maximum
            ):
                raise MagicError(f"Preset {identity}: invalid {key}.")


def png_chunk(kind: bytes, data: bytes) -> bytes:
    return (
        struct.pack(">I", len(data))
        + kind
        + data
        + struct.pack(">I", binascii.crc32(kind + data) & 0xFFFFFFFF)
    )


def encode_png(width: int, height: int, pixels: bytes) -> bytes:
    if len(pixels) != width * height * 3:
        raise MagicError("Invalid RGB pixel buffer.")
    rows = b"".join(
        b"\x00" + pixels[row * width * 3 : (row + 1) * width * 3] for row in range(height)
    )
    return (
        b"\x89PNG\r\n\x1a\n"
        + png_chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0))
        + png_chunk(b"IDAT", zlib.compress(rows, level=9))
        + png_chunk(b"IEND", b"")
    )


def render_preset(preset: dict, size: int, tiles: int) -> tuple[str, bytes]:
    title = preset["id"]
    lines = [
        "# Camera-magic original SDR pseudo-HDR LUT; spatial detail is a separate pass.",
        f'TITLE "Mahdi {title}"',
        f"LUT_3D_SIZE {size}",
        "DOMAIN_MIN 0.0 0.0 0.0",
        "DOMAIN_MAX 1.0 1.0 1.0",
    ]
    width = size * tiles
    pixels = bytearray(width * width * 3)
    # Standard .cube convention: red changes fastest. Atlas has one blue slice per tile.
    for blue in range(size):
        for green in range(size):
            for red in range(size):
                rgb = grade((red / (size - 1), green / (size - 1), blue / (size - 1)), preset)
                lines.append(" ".join(f"{c:.7f}" for c in rgb))
                x, y = (blue % tiles) * size + red, (blue // tiles) * size + green
                index = (y * width + x) * 3
                pixels[index : index + 3] = bytes(int(c * 255 + 0.5) for c in rgb)
    return "\n".join(lines) + "\n", encode_png(width, width, bytes(pixels))


def generate(directory: Path, *, check: bool = False) -> dict:
    data = read_json(directory / "presets.json")
    validate_presets(data)
    result = []
    for preset in data["presets"]:
        cube, png = render_preset(preset, data["lut_size"], data["tiles"])
        for suffix, contents in (("cube", cube.encode("utf-8")), ("png", png)):
            path = directory / "luts" / (preset["id"] + "." + suffix)
            if check:
                if not path.is_file() or path.read_bytes() != contents:
                    raise MagicError(f"Missing/stale generated LUT: {path}")
            else:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(contents)
            result.append(str(path))
    return {
        "mode": "checked" if check else "generated",
        "preset_count": len(data["presets"]),
        "files": result,
    }
