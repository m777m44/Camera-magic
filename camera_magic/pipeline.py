"""Local decode/build/sign workflow. A successful build is never a runtime/HDR claim."""

from __future__ import annotations

import os
from pathlib import Path
import re
import shutil
import tempfile

from .core import (
    MagicError,
    ensure_new_file,
    inventory,
    read_json,
    run,
    sha256,
    utc_now,
    write_json,
)
from .manifest import (
    A,
    DEFAULT_ID,
    DEFAULT_LABEL,
    audit_tree,
    load_yaml,
    missing_exported,
    parse_xml,
    split_blockers,
)
from .tools import Tools


def decode(apk: Path, output: Path, tools: Tools, *, jadx_output: Path | None = None) -> dict:
    if output.exists():
        raise MagicError(
            f"Decode output already exists: {output}. Original inputs are never overwritten."
        )
    info = inventory(apk)
    if info["kind"] != "apk":
        raise MagicError(info["warning"])
    version = tools.check_apktool()
    if jadx_output:
        if jadx_output.exists():
            raise MagicError("jadx output already exists; choose a fresh directory.")
        tools.check_jadx()
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".mahdi-decode-", dir=output.parent) as stage:
        decoded = Path(stage) / "decoded"
        # Full resource AND Smali decode. -r/-s would prevent the subsequent edits/audit.
        result = run(
            tools.apktool()
            + [
                "d",
                "--frame-path",
                str(output.parent / "frameworks"),
                "--output",
                str(decoded),
                str(apk.resolve()),
            ],
            timeout=1800,
        )
        parse_xml(decoded / "AndroidManifest.xml")
        report = {
            "created_at": utc_now(),
            "original_filename": apk.name,
            "inventory": info,
            "apktool_version": version,
            "full_decode": True,
        }
        write_json(decoded / ".camera-magic/source.json", report)
        (decoded / ".camera-magic/decode.log").write_text(
            result.stdout + result.stderr, encoding="utf-8"
        )
        decoded.rename(output)
    if jadx_output:
        run(
            [tools.binary("jadx"), "--output-dir", str(jadx_output), str(apk.resolve())],
            timeout=1800,
        )
    return {"decoded": str(output), "source_sha256": info["sha256"], "audit": audit_tree(output)}


def require_rebrand_name(output: Path) -> None:
    if "REBRAND_ONLY" not in output.name:
        raise MagicError(
            "This automated pipeline does not integrate HDR. Use REBRAND_ONLY in the output filename."
        )


def build(decoded: Path, output: Path, tools: Tools) -> dict:
    require_rebrand_name(output)
    ensure_new_file(output)
    patch = read_json(decoded / ".camera-magic/patch.json")
    root = parse_xml(decoded / "AndroidManifest.xml").getroot()
    if root.get("package") != patch.get("application_id") or split_blockers(root):
        raise MagicError("Patched manifest and provenance disagree, or split requirements remain.")
    sdk = root.find("uses-sdk")
    requested = patch.get("requested_sdk", {})
    if (
        sdk is None
        or sdk.get(A + "minSdkVersion") != str(requested.get("min"))
        or sdk.get(A + "targetSdkVersion") != str(requested.get("target"))
    ):
        raise MagicError("SDK declarations changed after patching; rerun the patch and review.")
    yaml_sdk = load_yaml(decoded / "apktool.yml").get("sdkInfo", {})
    if (
        str(yaml_sdk.get("minSdkVersion")) != str(requested.get("min"))
        or str(yaml_sdk.get("targetSdkVersion")) != str(requested.get("target"))
        or "maxSdkVersion" in yaml_sdk
        or sdk.get(A + "maxSdkVersion") is not None
    ):
        raise MagicError("SDK declarations in apktool.yml disagree with the patch report.")
    if missing_exported(root):
        raise MagicError("Exported-policy review is required before rebuilding.")
    tools.check_apktool()
    with tempfile.TemporaryDirectory(prefix=".mahdi-build-", dir=output.parent) as stage:
        apk = Path(stage) / "unsigned.apk"
        aapt2 = tools.apktool_aapt2(Path(stage) / "aapt-tool")
        result = run(
            tools.apktool()
            + [
                "b",
                "--frame-path",
                str(decoded.parent / "frameworks"),
                "--aapt",
                aapt2,
                "--output",
                str(apk),
                str(decoded),
            ],
            timeout=1800,
        )
        info = inventory(apk)
        if info["kind"] != "apk":
            raise MagicError("Builder did not produce a standalone APK.")
        shutil.move(apk, output)
        log_path = output.with_suffix(".build.log")
        log_path.write_text(result.stdout + result.stderr, encoding="utf-8")
    report = {
        "created_at": utc_now(),
        "kind": "rebrand_only_unsigned",
        "apk_sha256": sha256(output),
        "original_sha256": patch["source_sha256"],
        "application_id": patch["application_id"],
        "hdr_pipeline_integrated": False,
        "runtime_compatibility": "not_tested",
        "signature_verified": False,
    }
    write_json(output.with_suffix(".build.json"), report)
    return report


def badging(apk: Path, tools: Tools) -> dict:
    output = run([tools.sdk_tool("aapt2"), "dump", "badging", str(apk)]).stdout

    def match(pattern: str) -> str | None:
        found = re.search(pattern, output, re.MULTILINE)
        return found[1] if found else None

    def permissions(kind: str) -> list[dict]:
        details = []
        for name, attributes in re.findall(
            rf"^{kind}(?:-sdk-23)?: name='([^']+)'([^\n]*)", output, re.MULTILINE
        ):
            cap = re.search(r"\bmaxSdkVersion='([^']*)'", attributes)
            item = {"name": name, "max_sdk": cap[1] if cap else None}
            if kind == "uses-implied-permission":
                reason = re.search(r"\breason='([^']*)'", attributes)
                item["reason"] = reason[1] if reason else None
            details.append(item)
        return details

    declared = permissions("uses-permission")
    return {
        "application_id": match(r"^package: name='([^']+)'"),
        "version_code": match(r"^package: .*\bversionCode='([^']*)'"),
        "version_name": match(r"^package: .*\bversionName='([^']*)'"),
        # Newer aapt2 prints minSdkVersion; older Build-Tools used sdkVersion.
        "min_sdk": match(r"^(?:minSdkVersion|sdkVersion):'([^']+)'"),
        "target_sdk": match(r"^targetSdkVersion:'([^']+)'"),
        "label": match(r"^application-label:'(.*)'\s*$"),
        "launchable_activity": match(r"^launchable-activity: name='([^']+)'"),
        "permissions": [item["name"] for item in declared],
        "permission_details": declared,
        "implied_permissions": permissions("uses-implied-permission"),
    }


def parse_signatures(output: str) -> dict:
    schemes = {}
    for version in ("v1", "v2", "v3"):
        found = re.search(
            r"Verified using " + version + r" scheme[^\n]*:\s*(true|false)", output, re.IGNORECASE
        )
        schemes[version] = bool(found and found[1].lower() == "true")
    return schemes


def verify(
    apk: Path,
    tools: Tools,
    *,
    expected_id: str = DEFAULT_ID,
    min_sdk: int = 23,
    target_sdk: int = 35,
    expected_label: str = DEFAULT_LABEL,
) -> dict:
    if min_sdk < 23 or target_sdk < 35 or min_sdk > target_sdk:
        raise MagicError("Expected minSdk >= 23, targetSdk >= 35 and minSdk <= targetSdk.")
    # No mutation: alignment is checked, not rewritten, after signing.
    info = inventory(apk)
    if info["kind"] != "apk":
        raise MagicError("Cannot verify an archive of split APKs as one installable package.")
    aligned = run([tools.sdk_tool("zipalign"), "-c", "-P", "16", "-v", "4", str(apk)])
    signature = run(
        [
            tools.sdk_tool("apksigner"),
            "verify",
            "--verbose",
            "--print-certs",
            "--min-sdk-version",
            "23",  # Verify v1/v2/v3 across the full supported signing range, not runtime API availability.
            "--max-sdk-version",
            str(target_sdk),
            str(apk),
        ]
    )
    schemes = parse_signatures(signature.stdout + signature.stderr)
    if not all(schemes.values()):
        raise MagicError(f"v1 (Android 6), v2 and v3 signatures must all verify: {schemes}")
    metadata = badging(apk, tools)
    for key, expected in {
        "application_id": expected_id,
        "min_sdk": str(min_sdk),
        "target_sdk": str(target_sdk),
        "label": expected_label,
    }.items():
        if metadata[key] != expected:
            raise MagicError(f"APK {key} is {metadata[key]!r}, expected {expected!r}.")
    return {
        "created_at": utc_now(),
        "apk_sha256": info["sha256"],
        "signatures": schemes,
        "alignment_verified": aligned.returncode == 0,
        "metadata": metadata,
        "certificate_details": signature.stdout,
        "native_libraries": info["native_libraries"],
        "runtime_compatibility": "not_tested",
        "hdr_pipeline_integrated": "not_verified",
        "warning": "ZIP alignment does not repair ELF PT_LOAD alignment or backport native libraries.",
    }


def password_environment(store_env: str, key_env: str) -> None:
    for name in (store_env, key_env):
        if not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", name):
            raise MagicError("Password arguments must be environment-variable NAMES, not values.")
        if len(os.getenv(name, "")) < 12:
            raise MagicError(
                f"Set {name} to a password of at least 12 characters. Values are never written to reports."
            )


def create_key(path: Path, alias: str, tools: Tools, *, store_env: str, key_env: str) -> dict:
    ensure_new_file(path)
    password_environment(store_env, key_env)
    tools.check_java()
    old_umask = os.umask(0o077)
    try:
        run(
            [
                tools.binary("keytool"),
                "-genkeypair",
                "-keystore",
                str(path),
                "-storetype",
                "JKS",
                "-alias",
                alias,
                "-keyalg",
                "RSA",
                "-keysize",
                "3072",
                "-validity",
                "10000",
                "-dname",
                "CN=Camera Magic Local Development",
                "-storepass:env",
                store_env,
                "-keypass:env",
                key_env,
            ]
        )
        path.chmod(0o600)
    finally:
        os.umask(old_umask)
    return {
        "keystore": str(path),
        "alias": alias,
        "warning": "Keep the key and passwords private and backed up; never commit them.",
    }


def sign(
    apk: Path,
    output: Path,
    keystore: Path,
    alias: str,
    tools: Tools,
    *,
    store_env: str,
    key_env: str,
    expected_id: str = DEFAULT_ID,
    expected_label: str = DEFAULT_LABEL,
    min_sdk: int = 23,
    target_sdk: int = 35,
) -> dict:
    require_rebrand_name(output)
    ensure_new_file(output)
    if apk.resolve() == output.resolve():
        raise MagicError("Unsigned input and signed output must be different files.")
    if not keystore.is_file() or keystore.is_symlink():
        raise MagicError("A private, existing, non-symlink keystore is required.")
    if keystore.stat().st_mode & 0o077:
        raise MagicError("Keystore permissions must be private (chmod 600).")
    password_environment(store_env, key_env)
    inventory(apk)
    with tempfile.TemporaryDirectory(prefix=".mahdi-sign-", dir=output.parent) as stage:
        aligned, signed = Path(stage) / "aligned.apk", Path(stage) / "signed.apk"
        run([tools.sdk_tool("zipalign"), "-P", "16", "-v", "4", str(apk), str(aligned)])
        run(
            [
                tools.sdk_tool("apksigner"),
                "sign",
                "--ks",
                str(keystore),
                "--ks-key-alias",
                alias,
                "--ks-pass",
                "env:" + store_env,
                "--key-pass",
                "env:" + key_env,
                "--min-sdk-version",
                str(min_sdk),
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
                str(aligned),
            ]
        )
        report = verify(
            signed,
            tools,
            expected_id=expected_id,
            expected_label=expected_label,
            min_sdk=min_sdk,
            target_sdk=target_sdk,
        )
        report.update(kind="rebrand_only_signed", hdr_pipeline_integrated=False)
        shutil.move(signed, output)
    write_json(output.with_suffix(".verification.json"), report)
    return report
