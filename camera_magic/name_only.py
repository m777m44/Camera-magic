"""Label-only APK rebuild. No package, icon, SDK, DEX, native or HDR modifications."""

from __future__ import annotations

import hashlib
from pathlib import Path
import re
import shutil
import tempfile
import xml.etree.ElementTree as ET

from .core import (
    MagicError,
    checked_zip,
    ensure_new_file,
    inventory,
    run,
    sha256,
    utc_now,
    write_json,
)
from .manifest import A, DEFAULT_LABEL, LAUNCHERS, parse_xml, save_xml, split_blockers
from .pipeline import badging, parse_signatures, password_environment
from .tools import Tools

ASSET_NAME = "Blackmagic_Camera_Mahdi.apk"


def android_literal(label: str) -> str:
    if not label.strip() or label != label.strip() or len(label) > 200:
        raise MagicError(
            "Use a non-empty app label of at most 200 characters, without edge whitespace."
        )
    if any(ord(c) < 32 or ord(c) == 127 for c in label):
        raise MagicError("Control characters are not allowed in the app label.")
    # Android resource escaping is separate from XML escaping (handled by ElementTree).
    return '"' + label.replace("\\", "\\\\").replace('"', '\\"').replace("'", "\\'") + '"'


def rename_labels(decoded: Path, label: str = DEFAULT_LABEL) -> dict:
    literal = android_literal(label)
    tree = parse_xml(decoded / "AndroidManifest.xml")
    root = tree.getroot()
    blockers = split_blockers(root)
    if blockers:
        raise MagicError(
            "A complete standalone APK is required; split dependencies remain: "
            + "; ".join(blockers)
        )
    if root.get(A + "sharedUserId"):
        raise MagicError(
            "sharedUserId cannot safely be kept with a new signing certificate; a name-only rebuild is refused."
        )
    app = root.find("application")
    if app is None or not root.get("package"):
        raise MagicError("Not an application manifest with a package name.")
    # Do not alter existing localized strings or in-app branding. Use a fresh fallback resource.
    names = set()
    for values in (decoded / "res").glob("values*"):
        for path in values.glob("*.xml"):
            names.update(node.get("name") for node in parse_xml(path).getroot())
    index = 0
    while True:
        suffix = f"_{index}" if index else ""
        key = "camera_magic_display_name" + suffix
        resource = decoded / "res/values" / (key + ".xml")
        if key not in names and not resource.exists():
            break
        index += 1
    resources = ET.Element("resources")
    ET.SubElement(
        resources, "string", name=key, translatable="false", formatted="false"
    ).text = literal
    changes = [{"component": "application", "previous_label": app.get(A + "label")}]
    app.set(A + "label", "@string/" + key)
    for component in app:
        if component.tag not in ("activity", "activity-alias"):
            continue
        launcher = any(
            category.get(A + "name") in LAUNCHERS
            for category in component.findall("./intent-filter/category")
        )
        if launcher:
            changes.append(
                {
                    "component": component.get(A + "name"),
                    "previous_label": component.get(A + "label"),
                }
            )
            component.set(A + "label", "@string/" + key)
    save_xml(ET.ElementTree(resources), resource)
    save_xml(tree, decoded / "AndroidManifest.xml")
    return {
        "application_id": root.get("package"),
        "label": label,
        "label_changes": changes,
        "resource": str(resource.relative_to(decoded)),
        "package_changed": False,
        "icon_changed": False,
        "sdk_changed": False,
        "filters_added": False,
    }


def payload_digests(apk: Path) -> dict[str, str]:
    """Hash uncompressed executable payload and every asset, not ZIP alignment/padding."""
    result = {}
    with checked_zip(apk) as archive:
        for entry in archive.infolist():
            name = entry.filename
            if entry.is_dir() or not (
                re.fullmatch(r"classes(?:\d+)?\.dex", name) or name.startswith(("lib/", "assets/"))
            ):
                continue
            digest = hashlib.sha256()
            with archive.open(entry) as stream:
                for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                    digest.update(chunk)
            result[name] = digest.hexdigest()
    return result


def verify_preserved(original: Path, rebuilt: Path, tools: Tools, label: str) -> dict:
    before, after = badging(original, tools), badging(rebuilt, tools)
    for field in (
        "application_id",
        "min_sdk",
        "target_sdk",
        "version_code",
        "version_name",
        "launchable_activity",
    ):
        if before[field] != after[field]:
            raise MagicError(f"Name-only verification failed: {field} changed.")
    if sorted(before["permissions"]) != sorted(after["permissions"]):
        raise MagicError("Name-only verification failed: permissions changed.")
    if after["label"] != label:
        raise MagicError("The rebuilt APK does not have the requested display label.")
    original_payload, rebuilt_payload = payload_digests(original), payload_digests(rebuilt)
    if original_payload != rebuilt_payload:
        changed = sorted(
            name
            for name in original_payload.keys() | rebuilt_payload.keys()
            if original_payload.get(name) != rebuilt_payload.get(name)
        )
        raise MagicError(
            "Name-only verification failed: executable/asset bytes changed: "
            + ", ".join(changed[:15])
        )
    return {
        "metadata": after,
        "payload_files_verified": len(original_payload),
        "code_libraries_assets_unchanged": True,
        "permissions_unchanged": True,
        "identity_sdk_version_unchanged": True,
    }


def rename_apk(
    original: Path,
    output: Path,
    keystore: Path,
    alias: str,
    tools: Tools,
    *,
    label: str = DEFAULT_LABEL,
    store_env: str = "MAHDI_STORE_PASSWORD",
    key_env: str = "MAHDI_KEY_PASSWORD",
) -> dict:
    """Rebuild and re-sign only after proving payload/identity invariants. Never publishes."""
    android_literal(label)
    if original.resolve() == output.resolve():
        raise MagicError(
            "Input and output must be different files; the original is never overwritten."
        )
    ensure_new_file(output)
    ensure_new_file(output.with_suffix(".verification.json"))
    if not keystore.is_file() or keystore.is_symlink() or keystore.stat().st_mode & 0o077:
        raise MagicError("Use an existing private non-symlink keystore (chmod 600).")
    password_environment(store_env, key_env)
    info = inventory(original)
    if info["kind"] != "apk":
        raise MagicError("A standalone APK, not an archive of split APKs, is required.")
    version = tools.check_apktool()
    source_metadata = badging(original, tools)
    try:
        source_min = int(source_metadata["min_sdk"])
    except (TypeError, ValueError) as exc:
        raise MagicError("The original APK must declare a numeric minSdkVersion.") from exc
    if source_min < 23:
        raise MagicError(
            "This name-only pipeline supports inputs with minSdk 23+. It will not raise the original SDK."
        )
    # A valid signature is not proof of publisher identity; independently trust/verify your input.
    run([tools.sdk_tool("apksigner"), "verify", "--print-certs", str(original)])
    with tempfile.TemporaryDirectory(prefix=".name-only-", dir=output.parent) as stage:
        stage = Path(stage)
        decoded = stage / "decoded"
        frameworks = stage / "frameworks"
        # Crucial: copy original DEX bytes instead of disassembling/reassembling Smali.
        run(
            tools.apktool()
            + [
                "d",
                "--no-src",
                "--frame-path",
                str(frameworks),
                "--output",
                str(decoded),
                str(original.resolve()),
            ],
            timeout=1800,
        )
        changes = rename_labels(decoded, label)
        unsigned, aligned, signed = (
            stage / "unsigned.apk",
            stage / "aligned.apk",
            stage / "signed.apk",
        )
        run(
            tools.apktool()
            + [
                "b",
                "--frame-path",
                str(frameworks),
                "--aapt",
                tools.apktool_aapt2(stage / "aapt-tool"),
                "--output",
                str(unsigned),
                str(decoded),
            ],
            timeout=1800,
        )
        run([tools.sdk_tool("zipalign"), "-P", "16", "-v", "4", str(unsigned), str(aligned)])
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
                # This sets the cryptographic signing range, NOT the manifest's minimum SDK.
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
                str(aligned),
            ]
        )
        checked = run(
            [
                tools.sdk_tool("apksigner"),
                "verify",
                "--verbose",
                "--print-certs",
                "--min-sdk-version",
                "23",
                "--max-sdk-version",
                "35",
                str(signed),
            ]
        )
        schemes = parse_signatures(checked.stdout + checked.stderr)
        if not all(schemes.values()):
            raise MagicError(
                f"The rebuilt APK did not verify all three signature schemes: {schemes}"
            )
        run([tools.sdk_tool("zipalign"), "-c", "-P", "16", "-v", "4", str(signed)])
        preserved = verify_preserved(original, signed, tools, label)
        if sha256(original) != info["sha256"]:
            raise MagicError(
                "Original input changed during the build; refusing to publish the result."
            )
        report = {
            "kind": "name_only_signed",
            "created_at": utc_now(),
            "apktool_version": version,
            "source_sha256": info["sha256"],
            "apk_sha256": sha256(signed),
            "signatures": schemes,
            "alignment_verified": True,
            **changes,
            **preserved,
            "runtime_compatibility": "not_tested",
            "warning": "Display labels and signing certificate changed. No claim of publisher signature, "
            "unchanged integrity-check behavior, or tested camera/recording functionality.",
        }
        shutil.move(signed, output)
    write_json(output.with_suffix(".verification.json"), report)
    return report
