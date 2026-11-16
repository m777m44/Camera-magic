"""Shared I/O. No shell execution, archive extraction, or network downloads."""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import struct
import subprocess
import tempfile
import zipfile
from datetime import datetime, timezone


class MagicError(RuntimeError):
    """An actionable, expected tool failure."""


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_json(path: Path, value: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=path.parent, delete=False) as tmp:
        temporary = Path(tmp.name)
        json.dump(value, tmp, ensure_ascii=False, indent=2, sort_keys=True)
        tmp.write("\n")
    try:
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def read_json(path: Path) -> dict:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        if not isinstance(data, dict):
            raise ValueError("expected an object")
        return data
    except (OSError, ValueError) as exc:
        raise MagicError(f"Cannot read JSON {path}: {exc}") from exc


def run(
    command: list[str], *, timeout: int = 300, check: bool = True
) -> subprocess.CompletedProcess:
    """Passwords must be referenced by environment-variable name, never passed as values."""
    try:
        result = subprocess.run(
            [str(arg) for arg in command],
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=timeout,
            check=False,
        )
    except FileNotFoundError as exc:
        raise MagicError(
            f"Tool not found: {command[0]}. Run `doctor` and configure toolchain.json."
        ) from exc
    except subprocess.TimeoutExpired as exc:
        raise MagicError(
            f"Tool timed out after {timeout}s: {command[0]}; no success was recorded."
        ) from exc
    if check and result.returncode:
        # Deliberately omit command arguments: third-party tools can contain sensitive paths.
        raise MagicError(
            f"{Path(command[0]).name} exited {result.returncode}:\n"
            f"{(result.stdout + result.stderr)[-16000:]}"
        )
    return result


def version_tuple(text: str) -> tuple[int, ...]:
    match = re.search(r"(?<!\d)(\d+)\.(\d+)(?:\.(\d+))?", text)
    if not match:
        raise MagicError(f"Unrecognized tool version: {text[:150]!r}")
    return tuple(int(part or 0) for part in match.groups())


def ensure_new_file(path: Path) -> None:
    if path.exists() or path.is_symlink():
        raise MagicError(f"Refusing to overwrite {path}. Choose a new output path.")
    path.parent.mkdir(parents=True, exist_ok=True)


def checked_zip(path: Path) -> zipfile.ZipFile:
    """Validate paths and resource limits before handing an untrusted APK to other tools."""
    try:
        archive = zipfile.ZipFile(path)
    except (OSError, zipfile.BadZipFile) as exc:
        raise MagicError(f"Not a readable APK/ZIP: {path}: {exc}") from exc
    try:
        infos = archive.infolist()
        if len(infos) > 100_000 or sum(i.file_size for i in infos) > 8 * 1024**3:
            raise MagicError("Archive exceeds the 100,000-entry / 8 GiB expanded safety limit.")
        names = set()
        for entry in infos:
            name = PurePosixPath(entry.filename)
            if (
                name.is_absolute()
                or ".." in name.parts
                or "\\" in entry.filename
                or "\x00" in entry.filename
                or re.match(r"^[A-Za-z]:", entry.filename)
            ):
                raise MagicError(f"Unsafe archive path: {entry.filename!r}")
            if stat.S_ISLNK(entry.external_attr >> 16):
                raise MagicError(f"Symlink in archive: {entry.filename!r}")
            if entry.filename in names:
                raise MagicError(f"Duplicate ZIP entry: {entry.filename!r}")
            if entry.flag_bits & 1:
                raise MagicError("Encrypted APK/ZIP entries are not supported.")
            if entry.file_size > 1024**3:
                raise MagicError(f"Entry exceeds 1 GiB safety limit: {entry.filename}")
            names.add(entry.filename)
        return archive
    except Exception:
        archive.close()
        raise


def elf_load_alignments(header: bytes) -> list[int]:
    """Read PT_LOAD p_align from a bounded ELF prefix; not an ABI/loader certification."""
    if header[:4] != b"\x7fELF" or len(header) < 64 or header[5] not in (1, 2):
        return []
    endian = "<" if header[5] == 1 else ">"
    elf_class = header[4]
    if elf_class == 1:
        offset = struct.unpack_from(endian + "I", header, 28)[0]
        entry_size, count = struct.unpack_from(endian + "HH", header, 42)
        minimum, align_offset, align_format = 32, 28, "I"
    elif elf_class == 2:
        offset = struct.unpack_from(endian + "Q", header, 32)[0]
        entry_size, count = struct.unpack_from(endian + "HH", header, 54)
        minimum, align_offset, align_format = 56, 48, "Q"
    else:
        return []
    if entry_size < minimum or offset + count * entry_size > len(header):
        return []
    return [
        struct.unpack_from(endian + align_format, header, offset + i * entry_size + align_offset)[0]
        for i in range(count)
        if struct.unpack_from(endian + "I", header, offset + i * entry_size)[0] == 1
    ]


def inventory(path: Path) -> dict:
    with checked_zip(path) as archive:
        names = archive.namelist()
        nested = [n for n in names if n.lower().endswith(".apk")]
        if "AndroidManifest.xml" not in names:
            if nested:
                return {
                    "kind": "split_archive",
                    "sha256": sha256(path),
                    "entries": nested,
                    "installable": False,
                    "warning": "Do not rename/concatenate these files. Supply a complete standalone APK "
                    "or perform a reviewed split-resource merge outside this toolkit.",
                }
            raise MagicError("Archive has no AndroidManifest.xml and no nested APKs.")
        native = []
        dex = []
        for name in names:
            if re.fullmatch(r"classes(?:\d+)?\.dex", name):
                with archive.open(name) as stream:
                    dex.append({"path": name, "magic": stream.read(8).decode("ascii", "replace")})
            if re.fullmatch(r"lib/[^/]+/[^/]+\.so", name):
                with archive.open(name) as stream:
                    alignment = elf_load_alignments(stream.read(65536))
                native.append(
                    {
                        "path": name,
                        "abi": name.split("/")[1],
                        "load_alignments": alignment,
                        "elf_16k_alignment_candidate": bool(alignment)
                        and all(a >= 16384 for a in alignment),
                    }
                )
        return {
            "kind": "apk",
            "sha256": sha256(path),
            "size": path.stat().st_size,
            "dex": dex,
            "native_libraries": native,
            "abis": sorted({lib["abi"] for lib in native}),
            "warning": "Inventory is not signature, completeness, runtime, or 16 KiB compatibility verification.",
        }
