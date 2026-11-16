"""Build/release helpers for the ORIGINAL Mahdi Camera app, not a renamed vendor APK.

The optional local-signing handoff transports only an unsigned/signed public APK.
Private keys and passwords never leave the owner's private environment in that mode.
"""

from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sys

from .core import MagicError, checked_zip, ensure_new_file, read_json, run, sha256, write_json
from .pipeline import badging, parse_signatures, password_environment, verify
from .release_ci import prepare_key, require_env
from .tools import Tools

ROOT = Path(__file__).resolve().parents[1]
APP_ID = "com.mahdi.camera"
APP_LABEL = "Mahdi Camera"
ASSET = "Mahdi_Camera.apk"
VALIDATION = "Mahdi Camera app validation"
# Deliberately no INTERNET permission. The camera stays offline; the opt-in updater is a
# separate component that is not part of this APK yet (see docs/UPDATER.md).
PERMISSIONS = {
    "android.permission.CAMERA",
    "android.permission.RECORD_AUDIO",
    "android.permission.WRITE_EXTERNAL_STORAGE",
}
BOOTSTRAP_SIGNER_SHA256 = "925fb5189d62fea563eaa24636108cb28a7281c05b63f3478f6c53e7768c7d3b"
SIGNATURE_FILE = re.compile(r"META-INF/(?:MANIFEST\.MF|[^/]+\.(?:SF|RSA|DSA|EC))$", re.I)


def event() -> dict:
    return read_json(Path(require_env("GITHUB_EVENT_PATH")))


def gh_json(*arguments: str) -> dict:
    return json.loads(run(["gh", "api", *arguments]).stdout)


def source_commit() -> str:
    value = require_env("SOURCE_COMMIT")
    if not re.fullmatch(r"[0-9a-f]{40}", value):
        raise MagicError("Expected a full source commit SHA.")
    return value


def validation_id(data: dict) -> str:
    value = str(
        data.get("workflow_run", {}).get("id")
        or data.get("client_payload", {}).get("validation_run", "")
    )
    if not re.fullmatch(r"[0-9]+", value):
        raise MagicError("A successful application validation run is required.")
    return value


def assert_current_main() -> None:
    latest = gh_json(f"repos/{require_env('GH_REPO')}/git/ref/heads/main")["object"]["sha"]
    if latest != source_commit():
        raise MagicError(
            "Main advanced; this stale APK will not replace the last successful download."
        )


def preflight() -> None:
    assert_current_main()
    if run(["git", "rev-parse", "HEAD"]).stdout.strip() != source_commit():
        raise MagicError("Checkout does not match the requested source commit.")
    data = event()
    run_info = gh_json(f"repos/{require_env('GH_REPO')}/actions/runs/{validation_id(data)}")
    if (
        run_info.get("name") != VALIDATION
        or run_info.get("conclusion") != "success"
        or run_info.get("head_sha") != source_commit()
        or run_info.get("head_branch") != "main"
        or run_info.get("head_repository", {}).get("full_name") != require_env("GH_REPO")
    ):
        raise MagicError(
            "Only the successful trusted-main application/camera tests authorize publication."
        )


def choose_mode(data: dict, has_key: bool) -> str:
    action = data.get("action")
    if action == "mahdi-build-unsigned":
        return "unsigned"
    if action == "mahdi-publish-signed":
        return "receive"
    return "sign" if has_key else "none"


def check_metadata(metadata: dict) -> None:
    for field, expected in {
        "application_id": APP_ID,
        "label": APP_LABEL,
        "min_sdk": "23",
        "target_sdk": "35",
    }.items():
        if metadata.get(field) != expected:
            raise MagicError(f"Independent app {field} does not match {expected!r}.")
    # aapt2 includes READ_EXTERNAL_STORAGE as both uses-permission and
    # uses-implied-permission when legacy WRITE_EXTERNAL_STORAGE is present.
    # Accept only that documented implication, with the same Android 9 cap.
    legacy_read = "android.permission.READ_EXTERNAL_STORAGE"
    names = metadata.get("permissions", [])
    expected = PERMISSIONS | ({legacy_read} if legacy_read in names else set())
    details = metadata.get("permission_details", [])
    expected_details = [
        {
            "name": name,
            "max_sdk": "28" if name.endswith("_EXTERNAL_STORAGE") else None,
        }
        for name in expected
    ]
    implied = (
        [
            {
                "name": legacy_read,
                "max_sdk": "28",
                "reason": "requested WRITE_EXTERNAL_STORAGE",
            }
        ]
        if legacy_read in names
        else []
    )
    if (
        set(names) != expected
        or len(names) != len(expected)
        or sorted(details, key=lambda item: str(item.get("name")))
        != sorted(expected_details, key=lambda item: item["name"])
        or metadata.get("implied_permissions", []) != implied
    ):
        raise MagicError(
            "Unexpected application permissions or legacy storage limits; no network/cloud permissions are allowed."
        )
    if metadata.get("launchable_activity") != APP_ID + ".MainActivity":
        raise MagicError("Unexpected launcher activity.")


def content_digests(apk: Path) -> dict[str, str]:
    digests = {}
    with checked_zip(apk) as archive:
        for entry in archive.infolist():
            if entry.is_dir() or SIGNATURE_FILE.fullmatch(entry.filename):
                continue
            digest = hashlib.sha256()
            with archive.open(entry) as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                    digest.update(chunk)
            digests[entry.filename] = digest.hexdigest()
    return digests


def decode_apk(data: dict, output: Path) -> None:
    ensure_new_file(output)
    try:
        encoded = data["apk_base64"]
        if not isinstance(encoded, str) or len(encoded) > 120000:
            raise ValueError("unexpected APK transport size")
        payload = base64.b64decode(encoded, validate=True)
    except (KeyError, ValueError, binascii.Error) as exc:
        raise MagicError(
            "Invalid public APK transport; no private signing data is accepted."
        ) from exc
    if hashlib.sha256(payload).hexdigest() != data.get("sha256"):
        raise MagicError("APK transport checksum mismatch.")
    output.write_bytes(payload)
    try:
        with checked_zip(output) as archive:
            if not {"AndroidManifest.xml", "classes.dex"}.issubset(archive.namelist()):
                raise MagicError("Transport is not the expected application APK.")
    except Exception:
        output.unlink(missing_ok=True)
        raise


def aligned_build(directory: Path, tools: Tools) -> tuple[Path, dict]:
    unsigned = ROOT / "camera-app/build/outputs/apk/release/camera-app-release-unsigned.apk"
    output = directory / "Mahdi_Camera-unsigned.apk"
    ensure_new_file(output)
    run([tools.sdk_tool("zipalign"), "-P", "16", "-v", "4", str(unsigned), str(output)])
    run([tools.sdk_tool("zipalign"), "-c", "-P", "16", "-v", "4", str(output)])
    metadata = badging(output, tools)
    check_metadata(metadata)
    return output, {
        "schema": 1,
        "kind": "independent_camera_unsigned",
        "source_commit": source_commit(),
        "validation_run": validation_id(event()),
        "sha256": sha256(output),
        "metadata": metadata,
        "alignment_verified": True,
    }


def unsigned_stage(directory: Path, tools: Tools) -> None:
    apk, provenance = aligned_build(directory, tools)
    transport = {**provenance, "apk_base64": base64.b64encode(apk.read_bytes()).decode()}
    body = (
        "Unsigned public application for private local signing. DRAFT ONLY, never Latest.\n\n```json\n"
        + json.dumps(transport)
        + "\n```\n"
    )
    if len(body) > 120000:
        raise MagicError("APK is too large for the optional API-only local-signing handoff.")
    notes = directory / "unsigned-transport.md"
    notes.write_text(body, encoding="utf-8")
    tag = f"mahdi-unsigned-{require_env('GITHUB_RUN_ID')}-{require_env('GITHUB_RUN_ATTEMPT')}"
    run(
        [
            "gh",
            "release",
            "create",
            tag,
            "--repo",
            require_env("GH_REPO"),
            "--target",
            source_commit(),
            "--draft",
            "--title",
            "Unsigned Mahdi Camera signing handoff",
            "--notes-file",
            str(notes),
        ]
    )
    print("Prepared unsigned draft:", tag)


def certificate_digest(text: str) -> str:
    found = re.findall(r"Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F]{64})", text)
    expected = read_json(ROOT / "config/mahdi-camera-signing.json")["certificate_sha256"]
    if len(found) != 1 or found[0].lower() != expected:
        raise MagicError(
            "APK signing certificate does not match this application's pinned public certificate."
        )
    return found[0].lower()


def verified_report(unsigned: Path, signed: Path, tools: Tools) -> dict:
    report = verify(signed, tools, expected_id=APP_ID, expected_label=APP_LABEL)
    check_metadata(report["metadata"])
    if content_digests(unsigned) != content_digests(signed):
        raise MagicError(
            "Signed APK content differs from a fresh release build of the validated main source."
        )
    fingerprint = certificate_digest(report["certificate_details"])
    return {
        "kind": "independent_camera_signed",
        "source_commit": source_commit(),
        "validation_run": validation_id(event()),
        "validation_url": f"https://github.com/{require_env('GH_REPO')}/actions/runs/{validation_id(event())}",
        "apk_sha256": sha256(signed),
        "unsigned_sha256": sha256(unsigned),
        "metadata": report["metadata"],
        "signatures": report["signatures"],
        "certificate_sha256": fingerprint,
        "alignment_verified": True,
        "rebuilt_source_payload_unchanged": True,
        "automated_runtime_tests": "Emulated Camera2 preview, real H.264/AAC MP4 encoding/decoding and pause/resume on API 23 and 35.",
        "physical_devices": "not_tested",
        "not_blackmagic": True,
    }


def save_release_files(directory: Path, report: dict) -> None:
    apk = directory / ASSET
    if (
        report.get("kind") != "independent_camera_signed"
        or report.get("apk_sha256") != sha256(apk)
        or report.get("signatures") != {"v1": True, "v2": True, "v3": True}
        or report.get("alignment_verified") is not True
        or report.get("rebuilt_source_payload_unchanged") is not True
        or report.get("not_blackmagic") is not True
        or report.get("certificate_sha256")
        != read_json(ROOT / "config/mahdi-camera-signing.json")["certificate_sha256"]
    ):
        raise MagicError("Missing independent application verification evidence.")
    check_metadata(report.get("metadata", {}))
    write_json(apk.with_suffix(".verification.json"), report)
    files = (apk, apk.with_suffix(".verification.json"))
    (directory / "SHA256SUMS.txt").write_text(
        "".join(f"{sha256(path)}  {path.name}\n" for path in files), encoding="utf-8"
    )
    (directory / "release-notes.md").write_text(
        "# Mahdi Camera — independent, open-source, offline\n\n"
        "An original Camera2 application, NOT Blackmagic Camera, not a renamed vendor APK and not affiliated "
        "with Blackmagic Design. No vendor code, artwork or cloud services are included. Source is MIT licensed.\n\n"
        "Features: live camera preview, available lens/resolution selection (up to 1080p), H.264 MP4 with "
        "optional AAC microphone audio, device-supported manual ISO/shutter/focus, white-balance presets, "
        "exposure compensation, digital zoom and screen-only composition guides. Videos save to DCIM/MahdiCamera. "
        "No Internet permission, account, ads or analytics.\n\n"
        "Android 6+ (API 23), camera hardware required. Unsupported manual controls are disabled. "
        "Emulator tests verify actual video/audio samples and a decodable frame on API 23/35; physical "
        "Samsung/Xiaomi/Huawei and image quality/long-recording/rotation behavior are NOT yet device-tested. "
        "No HDR/ProRes/LUT/Blackmagic Cloud equivalence is claimed.\n\n"
        f"Source: `{report['source_commit']}`\n\n"
        f"[Validation]({report['validation_url']})\n\n"
        f"APK SHA-256: `{report['apk_sha256']}`\n\n"
        f"Signing certificate SHA-256: `{report['certificate_sha256']}`\n\n"
        "تطبيق Mahdi Camera مستقل وليس تطبيق Blackmagic. ملفات الفيديو تبقى على الجهاز. "
        "احتفظ بمفتاح التوقيع نفسه لكل تحديث. لا تزل النسخة السابقة أو بياناتك دون نسخة احتياطية.\n",
        encoding="utf-8",
    )


def signed_build(directory: Path, tools: Tools, mode: str) -> None:
    unsigned, provenance = aligned_build(directory, tools)
    signed = directory / ASSET
    ensure_new_file(signed)
    if mode == "receive":
        payload = event().get("client_payload", {})
        if (
            payload.get("source_commit") != source_commit()
            or str(payload.get("validation_run")) != provenance["validation_run"]
        ):
            raise MagicError("Signed handoff does not match the currently validated main commit.")
        decode_apk(payload, signed)
    else:
        key = ROOT / "work/keys/mahdi-app.jks"
        prepare_key(key)
        password_environment("MAHDI_STORE_PASSWORD", "MAHDI_KEY_PASSWORD")
        run(
            [
                tools.sdk_tool("apksigner"),
                "sign",
                "--ks",
                str(key),
                "--ks-key-alias",
                "mahdi",
                "--ks-pass",
                "env:MAHDI_STORE_PASSWORD",
                "--key-pass",
                "env:MAHDI_KEY_PASSWORD",
                "--min-sdk-version",
                "23",
                "--v1-signing-enabled",
                "true",
                "--v2-signing-enabled",
                "true",
                "--v3-signing-enabled",
                "true",
                "--v4-signing-enabled",
                "false",
                "--out",
                str(signed),
                str(unsigned),
            ]
        )
    try:
        report = verified_report(unsigned, signed, tools)
        save_release_files(directory, report)
    except Exception:
        signed.unlink(missing_ok=True)
        raise


def publish(directory: Path) -> None:
    assert_current_main()
    report = read_json((directory / ASSET).with_suffix(".verification.json"))
    save_release_files(directory, report)
    tag = f"mahdi-{require_env('GITHUB_RUN_ID')}-{require_env('GITHUB_RUN_ATTEMPT')}"
    run(
        [
            "gh",
            "release",
            "create",
            tag,
            "--repo",
            require_env("GH_REPO"),
            "--target",
            source_commit(),
            "--draft",
            "--title",
            "Mahdi Camera " + report["metadata"]["version_name"],
            "--notes-file",
            str(directory / "release-notes.md"),
            str(directory / ASSET),
            str((directory / ASSET).with_suffix(".verification.json")),
            str(directory / "SHA256SUMS.txt"),
        ]
    )
    assert_current_main()
    run(
        [
            "gh",
            "release",
            "edit",
            tag,
            "--repo",
            require_env("GH_REPO"),
            "--draft=false",
            "--latest",
        ]
    )
    print(
        f"Published: https://github.com/{require_env('GH_REPO')}/releases/latest/download/{ASSET}"
    )


def extract_transport(release: dict, output: Path) -> None:
    if release.get("draft") is not True or not re.fullmatch(
        r"mahdi-unsigned-[0-9]+-[0-9]+", release.get("tag_name", "")
    ):
        raise MagicError("Expected our unsigned signing-handoff draft, not a public release.")
    try:
        data = json.loads(release["body"].split("```json\n", 1)[1].split("\n```", 1)[0])
    except (KeyError, IndexError, ValueError) as exc:
        raise MagicError("Malformed unsigned handoff.") from exc
    if data.get("kind") != "independent_camera_unsigned" or not re.fullmatch(
        r"[0-9a-f]{40}", str(data.get("source_commit", ""))
    ):
        raise MagicError("Unexpected unsigned application provenance.")
    check_metadata(data.get("metadata", {}))
    decode_apk(data, output)
    data.pop("apk_base64")
    write_json(output.with_suffix(".provenance.json"), data)


def local_sign(args: argparse.Namespace) -> None:
    for path in (args.keystore, args.private_settings):
        if not path.is_file() or path.is_symlink() or path.stat().st_mode & 0o077:
            raise MagicError("Use private non-symlink signing files with chmod 600.")
    if sha256(args.apksigner_jar) != BOOTSTRAP_SIGNER_SHA256:
        raise MagicError(
            "Unexpected bootstrap signer JAR. Use the checksum-pinned tool or normal CI signing."
        )
    provenance = read_json(args.apk.with_suffix(".provenance.json"))
    if sha256(args.apk) != provenance.get("sha256"):
        raise MagicError("Unsigned APK changed after the validated build.")
    private = read_json(args.private_settings)
    os.environ["MAHDI_STORE_PASSWORD"] = private["store_password"]
    os.environ["MAHDI_KEY_PASSWORD"] = private["key_password"]
    ensure_new_file(args.output)
    command = [args.java, "-jar", str(args.apksigner_jar)]
    run(
        command
        + [
            "sign",
            "--ks",
            str(args.keystore),
            "--ks-key-alias",
            "mahdi",
            "--ks-pass",
            "env:MAHDI_STORE_PASSWORD",
            "--key-pass",
            "env:MAHDI_KEY_PASSWORD",
            "--min-sdk-version",
            "23",
            "--v1-signing-enabled",
            "true",
            "--v2-signing-enabled",
            "true",
            "--v3-signing-enabled",
            "true",
            "--v4-signing-enabled",
            "false",
            "--out",
            str(args.output),
            str(args.apk),
        ]
    )
    checked = run(
        command
        + [
            "verify",
            "--verbose",
            "--print-certs",
            "--min-sdk-version",
            "23",
            "--max-sdk-version",
            "35",
            str(args.output),
        ]
    )
    if not all(parse_signatures(checked.stdout).values()):
        raise MagicError("Local v1/v2/v3 signature verification failed.")
    certificate_digest(checked.stdout)
    if content_digests(args.apk) != content_digests(args.output):
        raise MagicError("Signing changed non-signature APK content.")
    payload = {
        "event_type": "mahdi-publish-signed",
        "client_payload": {
            "source_commit": provenance["source_commit"],
            "validation_run": provenance["validation_run"],
            "sha256": sha256(args.output),
            "apk_base64": base64.b64encode(args.output.read_bytes()).decode(),
        },
    }
    encoded = json.dumps(payload)
    if len(encoded.encode()) > 64000:
        raise MagicError("Signed application exceeds the optional dispatch handoff size limit.")
    args.dispatch_output.parent.mkdir(parents=True, exist_ok=True)
    args.dispatch_output.write_text(encoded, encoding="utf-8")
    print("Signed locally. The dispatch file contains only the PUBLIC signed APK, no key/password.")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "command",
        choices=(
            "mode",
            "preflight",
            "unsigned",
            "sign",
            "receive",
            "publish",
            "extract",
            "local-sign",
        ),
    )
    parser.add_argument("--directory", type=Path, default=Path("artifacts"))
    parser.add_argument("--release-json", type=Path)
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--output", type=Path, default=Path("artifacts/Mahdi_Camera.apk"))
    parser.add_argument("--keystore", type=Path, default=Path("keys/mahdi-camera-release.jks"))
    parser.add_argument(
        "--private-settings", type=Path, default=Path("keys/mahdi-camera-private.json")
    )
    parser.add_argument("--apksigner-jar", type=Path, default=Path(".tools/apksigner-33.0.2.jar"))
    parser.add_argument("--java", default=shutil.which("java") or "java")
    parser.add_argument("--dispatch-output", type=Path, default=Path("work/mahdi-dispatch.json"))
    args = parser.parse_args(argv)
    try:
        tools = Tools(ROOT / "config/release-toolchain.json")
        if args.command == "mode":
            mode = choose_mode(event(), bool(os.environ.get("APK_KEYSTORE_B64")))
            with Path(require_env("GITHUB_OUTPUT")).open("a") as output:
                output.write("mode=" + mode + "\n")
            print("Application signing mode:", mode)
            if mode == "none":
                print(
                    "No APK replaced. Configure persistent MAHDI_APP_* signing secrets or use the secure local-signing handoff."
                )
        elif args.command == "preflight":
            preflight()
        elif args.command == "extract":
            if args.release_json is None or args.apk is None:
                raise MagicError("extract requires --release-json and --apk.")
            extract_transport(read_json(args.release_json), args.apk)
        elif args.command == "local-sign":
            if args.apk is None:
                raise MagicError("local-sign requires --apk.")
            local_sign(args)
        else:
            args.directory.mkdir(parents=True, exist_ok=True)
            if args.command == "unsigned":
                unsigned_stage(args.directory, tools)
            elif args.command in ("sign", "receive"):
                signed_build(args.directory, tools, args.command)
            else:
                publish(args.directory)
        return 0
    except (MagicError, OSError, ValueError) as exc:
        print(f"app-release: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
