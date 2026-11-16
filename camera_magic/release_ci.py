"""Opt-in release input helpers. No URL/password values are printed or put in argv."""

from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import os
from pathlib import Path
import re
import sys
import tempfile
import urllib.error
import urllib.parse
import urllib.request

from .core import MagicError, ensure_new_file, inventory, read_json, sha256
from .name_only import ASSET_NAME

MAX_DOWNLOAD = 1024**3
REPO = Path(__file__).resolve().parent.parent


def require_env(name: str) -> str:
    value = os.environ.get(name, "")
    if not value:
        raise MagicError(f"Missing release setting: {name}. No APK has been published.")
    return value


def validate_url(value: str) -> None:
    try:
        parsed = urllib.parse.urlsplit(value)
    except ValueError as exc:
        raise MagicError("Invalid HTTPS source URL; no URL was logged.") from exc
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username is not None
        or parsed.password is not None
        or parsed.fragment
        or any(ord(c) < 33 for c in value)
    ):
        raise MagicError(
            "Release sources must use HTTPS URLs without embedded login credentials or fragments."
        )


class HttpsRedirectsOnly(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, msg, headers, newurl):
        validate_url(newurl)
        return super().redirect_request(request, fp, code, msg, headers, newurl)


def download(url: str, digest: str, output: Path) -> None:
    validate_url(url)
    if not re.fullmatch(r"[0-9a-fA-F]{64}", digest):
        raise MagicError("A pinned 64-character SHA-256 is required; do not trust an unpinned URL.")
    ensure_new_file(output)
    temporary = None
    try:
        opener = urllib.request.build_opener(HttpsRedirectsOnly())
        request = urllib.request.Request(url, headers={"User-Agent": "Camera-magic-release/0.2"})
        with opener.open(request, timeout=60) as response:
            validate_url(response.geturl())
            header = response.headers.get("Content-Length")
            if header is not None and int(header) > MAX_DOWNLOAD:
                raise MagicError("Release input exceeds the 1 GiB download limit.")
            actual = hashlib.sha256()
            size = 0
            with tempfile.NamedTemporaryFile(dir=output.parent, delete=False) as target:
                temporary = Path(target.name)
                while chunk := response.read(1024 * 1024):
                    size += len(chunk)
                    if size > MAX_DOWNLOAD:
                        raise MagicError("Release input exceeds the 1 GiB download limit.")
                    actual.update(chunk)
                    target.write(chunk)
        if actual.hexdigest() != digest.lower():
            raise MagicError(
                "SHA-256 mismatch: download rejected; the last published APK is unchanged."
            )
        os.replace(temporary, output)
    except (OSError, ValueError, urllib.error.URLError) as exc:
        # Download exceptions commonly include signed URLs. Never echo them in public CI logs.
        raise MagicError(
            "Release download failed. Check the configured HTTPS source and its availability; no URL was logged."
        ) from exc
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def prepare_source(output: Path) -> None:
    download(require_env("APK_SOURCE_URL"), require_env("APK_SOURCE_SHA256"), output)
    try:
        if inventory(output)["kind"] != "apk":
            raise MagicError(
                "The release source must be a complete standalone APK, not split APKs."
            )
    except Exception:
        output.unlink(missing_ok=True)
        raise


def prepare_key(output: Path) -> None:
    # Validate all input before creating a key file. Passwords remain in the environment only.
    for name in ("MAHDI_STORE_PASSWORD", "MAHDI_KEY_PASSWORD"):
        if len(require_env(name)) < 12:
            raise MagicError(f"{name} must contain at least 12 characters.")
    try:
        value = base64.b64decode(require_env("APK_KEYSTORE_B64"), validate=True)
    except (ValueError, binascii.Error) as exc:
        raise MagicError("APK_KEYSTORE_B64 is not valid base64.") from exc
    if not value or len(value) > 1024 * 1024:
        raise MagicError("Unexpected signing keystore size.")
    ensure_new_file(output)
    descriptor = os.open(output, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
    with os.fdopen(descriptor, "wb") as target:
        target.write(value)
    output.chmod(0o600)


def prepare_notes(directory: Path) -> None:
    apk = directory / ASSET_NAME
    report_path = apk.with_suffix(".verification.json")
    report = read_json(report_path)
    digest = sha256(apk)
    if (
        not re.fullmatch(r"[0-9a-f]{64}", str(report.get("source_sha256", "")))
        or report.get("kind") != "name_only_signed"
        or report.get("apk_sha256") != digest
        or report.get("code_libraries_assets_unchanged") is not True
        or report.get("identity_sdk_version_unchanged") is not True
        or report.get("permissions_unchanged") is not True
        or report.get("alignment_verified") is not True
        or report.get("signatures") != {"v1": True, "v2": True, "v3": True}
        or any(
            report.get(flag) is not False
            for flag in ("package_changed", "icon_changed", "sdk_changed", "filters_added")
        )
    ):
        raise MagicError("Refusing release: missing/invalid name-only verification evidence.")
    checksums = "".join(f"{sha256(path)}  {path.name}\n" for path in (apk, report_path))
    (directory / "SHA256SUMS.txt").write_text(checksums, encoding="utf-8")
    (directory / "release-notes.md").write_text(
        "# Blackmagic Camera Mahdi — name-only rebuild\n\n"
        "Only application/launcher display labels were changed. The input package ID, SDK and version "
        "declarations, icons, DEX/native payload, permissions and assets were preserved and checked.\n\n"
        "The APK has a new signing certificate. It is NOT an official Blackmagic release and cannot "
        "update an official installation signed by the publisher. Camera/recording functionality "
        "and device compatibility have not been tested by this workflow. No HDR filters were added.\n\n"
        "هذا التطبيق معدل للاستخدام الشخصي فقط. الحقوق الأصلية محفوظة لـ Blackmagic Design.\n\n"
        f"Source APK SHA-256: `{report['source_sha256']}`\n\n"
        f"Download SHA-256: `{digest}`\n\n"
        "See the attached verification report and SHA256SUMS.txt. Release automation must only be "
        "enabled by someone authorized to redistribute the input and derivative package.\n",
        encoding="utf-8",
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Trusted-main release helpers; never publishes fake/sample APKs."
    )
    commands = parser.add_subparsers(dest="command", required=True)
    tool = commands.add_parser("fetch-tool")
    tool.add_argument("--output", type=Path, default=Path(".cache/apktool.jar"))
    source = commands.add_parser("prepare-source")
    source.add_argument("--output", type=Path, default=Path("inputs/source.apk"))
    key = commands.add_parser("prepare-key")
    key.add_argument("--output", type=Path, default=Path("work/keys/release.jks"))
    notes = commands.add_parser("prepare-notes")
    notes.add_argument("--directory", type=Path, default=Path("artifacts"))
    args = parser.parse_args(argv)
    try:
        if args.command == "fetch-tool":
            config = read_json(REPO / "config/release-tools.json")
            download(config["apktool_url"], config["apktool_sha256"], args.output)
        elif args.command == "prepare-source":
            prepare_source(args.output)
        elif args.command == "prepare-key":
            prepare_key(args.output)
        else:
            prepare_notes(args.directory)
        print(
            f"Completed {args.command}; no source URLs, passwords or private key data were printed."
        )
        return 0
    except (MagicError, OSError) as exc:
        print(f"release-ci: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
