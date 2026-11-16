"""Conservative APK rebranding: separate application identity from Java class identity."""

from __future__ import annotations

from pathlib import Path
import re
import shutil
import tempfile
import xml.etree.ElementTree as ET

import yaml

from .core import MagicError, read_json, utc_now, version_tuple, write_json

ANDROID = "http://schemas.android.com/apk/res/android"
A = "{" + ANDROID + "}"
ET.register_namespace("android", ANDROID)
DEFAULT_ID = "com.mahdi.blackmagic.camera"
DEFAULT_LABEL = "Blackmagic Camera Mahdi"
PACKAGE_RE = re.compile(r"[a-z][a-z0-9_]*(?:\.[a-z][a-z0-9_]*)+")

# Only these attributes denote class/component names. Metadata and system library names do not.
CLASS_ATTRIBUTES = {
    "application": ("name", "backupAgent", "appComponentFactory", "manageSpaceActivity"),
    "activity": ("name", "parentActivityName"),
    "activity-alias": ("name", "targetActivity", "parentActivityName"),
    "service": ("name",),
    "receiver": ("name",),
    "provider": ("name",),
    "instrumentation": ("name",),
}
LAUNCHERS = {"android.intent.category.LAUNCHER", "android.intent.category.LEANBACK_LAUNCHER"}

ICON = """<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp" android:viewportWidth="108" android:viewportHeight="108">
    <path android:fillColor="#10172A" android:pathData="M0,0h108v108h-108z"/>
    <path android:fillColor="#36D6CE" android:pathData="M24,34h14l5,-7h22l5,7h14v43h-60z"/>
    <path android:fillColor="#10172A" android:pathData="M54,40a15,15 0,1 0,0 30a15,15 0,1 0,0 -30"/>
    <path android:fillColor="#FFFFFF" android:pathData="M45,62v-15h4l5,7l5,-7h4v15h-4v-9l-5,7l-5,-7v9z"/>
</vector>
"""
ADAPTIVE_ICON = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/mahdi_icon_background"/>
    <foreground android:drawable="@mipmap/mahdi_camera_foreground"/>
</adaptive-icon>
"""


def parse_xml(path: Path) -> ET.ElementTree:
    try:
        text = path.read_text(encoding="utf-8")
        if "<!DOCTYPE" in text.upper() or "<!ENTITY" in text.upper():
            raise MagicError(f"DTD/entity declarations are not accepted: {path}")
        return ET.ElementTree(
            ET.fromstring(text, parser=ET.XMLParser(target=ET.TreeBuilder(insert_comments=True)))
        )
    except (OSError, UnicodeError, ET.ParseError) as exc:
        raise MagicError(
            f"Expected decoded XML at {path}; run a full decode (not -r/-s): {exc}"
        ) from exc


def save_xml(tree: ET.ElementTree, path: Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tree.write(path, encoding="utf-8", xml_declaration=True)


def load_yaml(path: Path) -> dict:
    try:
        result = yaml.safe_load(path.read_text(encoding="utf-8"))
        if not isinstance(result, dict) or not isinstance(result.get("sdkInfo", {}), dict):
            raise ValueError("expected mapping with sdkInfo mapping")
        return result
    except (OSError, ValueError, yaml.YAMLError) as exc:
        raise MagicError(f"Invalid apktool.yml: {exc}") from exc


def sdk_values(root: ET.Element, metadata: dict) -> dict:
    element = root.find("uses-sdk")
    result = dict(metadata.get("sdkInfo", {}))
    if element is not None:
        for key in ("minSdkVersion", "targetSdkVersion", "maxSdkVersion"):
            if element.get(A + key) is not None:
                result[key] = element.get(A + key)
    return result


def split_blockers(root: ET.Element) -> list[str]:
    result = []
    for key, value in root.attrib.items():
        if (
            key.rsplit("}", 1)[-1]
            in ("split", "configForSplit", "requiredSplitTypes", "splitTypes")
            and value
        ):
            result.append(f"manifest {key}={value}")
        if key == A + "isSplitRequired" and value.lower() not in ("false", "0"):
            result.append("android:isSplitRequired")
    if root.find("uses-split") is not None:
        result.append("uses-split dependency")
    for item in root.iter("meta-data"):
        if item.get(A + "name") == "com.android.vending.splits.required" and item.get(
            A + "value", "true"
        ) not in ("false", "0"):
            result.append("com.android.vending.splits.required")
    return result


def missing_exported(root: ET.Element) -> list[str]:
    app = root.find("application")
    if app is None:
        return []
    return [
        f"{item.tag}:{item.get(A + 'name', '?')}"
        for item in app
        if item.tag in ("activity", "activity-alias", "receiver", "service")
        and item.find("intent-filter") is not None
        and item.get(A + "exported") is None
    ]


def scan_strings(source: Path, old_id: str) -> dict:
    hits: dict[str, list] = {"self_id_literals": [], "hardware_checks": []}
    # Do not dump proprietary method bodies into reports. Bound both I/O and report size.
    for directory in source.glob("smali*"):
        if not directory.is_dir():
            continue
        for file in directory.rglob("*.smali"):
            if file.stat().st_size > 8 * 1024 * 1024:
                continue
            for number, line in enumerate(
                file.read_text(encoding="utf-8", errors="replace").splitlines(), 1
            ):
                category = None
                if "const-string" in line and f'"{old_id}' in line:
                    category = "self_id_literals"
                elif any(
                    needle in line
                    for needle in ("Build;->MODEL", "->isDeviceSupported(", "->checkHardware(")
                ):
                    category = "hardware_checks"
                if category and len(hits[category]) < 100:
                    hits[category].append({"file": str(file.relative_to(source)), "line": number})
    return hits


def audit_tree(source: Path) -> dict:
    root = parse_xml(source / "AndroidManifest.xml").getroot()
    metadata = load_yaml(source / "apktool.yml")
    package = root.get("package", "")
    return {
        "package": package,
        "sdk": sdk_values(root, metadata),
        "split_blockers": split_blockers(root),
        "shared_user_id": root.get(A + "sharedUserId"),
        "missing_exported": missing_exported(root),
        "features": [dict(node.attrib) for node in root.findall("uses-feature")],
        "services": [
            {
                "name": node.get(A + "name"),
                "foregroundServiceType": node.get(A + "foregroundServiceType"),
            }
            for node in root.findall("./application/service")
        ],
        "review": scan_strings(source, package),
        "runtime_compatibility": "not_tested",
        "warning": "Hardware checks and self-ID literals require manual review; none are automatically bypassed.",
    }


def qualify(value: str, old_id: str) -> str:
    if value.startswith("."):
        return old_id + value
    return old_id + "." + value if "." not in value else value


def rebase(value: str, old_id: str, new_id: str) -> str:
    if value == old_id or value.startswith(old_id + ".") or value.startswith(old_id + ":"):
        return new_id + value[len(old_id) :]
    return value


def patch_manifest(
    root: ET.Element,
    metadata: dict,
    *,
    new_id: str,
    label: str,
    min_sdk: int,
    target_sdk: int,
    acknowledge_downgrade: bool,
    optional_features: list[str],
    remove_max_sdk: bool,
) -> dict:
    """Preflight all structural blockers before modifying any files."""
    old_id = root.get("package", "")
    if not PACKAGE_RE.fullmatch(new_id) or not old_id or old_id == new_id:
        raise MagicError(
            "Use a distinct, lowercase application ID, e.g. com.mahdi.blackmagic.camera."
        )
    if not label.strip() or any(c in label for c in "\n\r\x00"):
        raise MagicError("App label must be a non-empty single line.")
    if min_sdk < 23 or target_sdk < 35 or min_sdk > target_sdk:
        raise MagicError(
            "This pipeline requires minSdk >= 23, targetSdk >= 35 and minSdk <= targetSdk."
        )
    blockers = split_blockers(root)
    if blockers:
        raise MagicError(
            "Incomplete/split APK: "
            + "; ".join(blockers)
            + ". Supply a reviewed standalone APK; isSplitRequired will not be blindly removed."
        )
    if root.get(A + "sharedUserId"):
        raise MagicError(
            "sharedUserId requires a signature/data migration review; automatic patching refused."
        )
    absent = missing_exported(root)
    if absent:
        raise MagicError(
            "targetSdk 35 requires an explicit exported-policy review for: " + ", ".join(absent)
        )
    sdk = sdk_values(root, metadata)
    try:
        original_min = max(
            int(sdk.get("minSdkVersion", 1)),
            int(metadata.get("sdkInfo", {}).get("minSdkVersion", 1)),
        )
    except (ValueError, TypeError) as exc:
        raise MagicError("Preview/codename minSdk is not supported by this patcher.") from exc
    if original_min > min_sdk and not acknowledge_downgrade:
        raise MagicError(
            f"Lowering minSdk {original_min} -> {min_sdk} cannot backport APIs/native libraries. "
            "Pass --acknowledge-unsupported-sdk only after accepting that runtime support is unverified."
        )
    if "maxSdkVersion" in sdk and not remove_max_sdk:
        raise MagicError(
            "maxSdkVersion is present. Review it and explicitly pass --remove-max-sdk if appropriate."
        )
    app = root.find("application")
    if app is None:
        raise MagicError("Manifest has no application node.")
    found = {node.get(A + "name") for node in root.findall("uses-feature")}
    unknown = set(optional_features) - found
    if unknown:
        raise MagicError("Requested optional feature(s) not found: " + ", ".join(sorted(unknown)))
    declared = {}
    for tag in ("permission", "permission-tree", "permission-group"):
        for node in root.findall(tag):
            name = node.get(A + "name", "")
            declared[name] = rebase(name, old_id, new_id)
    previous_label = app.get(A + "label", "")
    for node in root.iter():
        for attr in CLASS_ATTRIBUTES.get(node.tag, ()):
            value = node.get(A + attr)
            if value:
                node.set(A + attr, qualify(value, old_id))
        for attr in ("permission", "readPermission", "writePermission", "permissionGroup"):
            value = node.get(A + attr)
            if value in declared:
                node.set(A + attr, declared[value])
        if node.tag in (
            "permission",
            "permission-tree",
            "permission-group",
            "uses-permission",
            "uses-permission-sdk-23",
        ):
            value = node.get(A + "name")
            if value in declared:
                node.set(A + "name", declared[value])
        if node.tag == "provider" and node.get(A + "authorities"):
            authorities = node.get(A + "authorities").split(";")
            node.set(
                A + "authorities", ";".join(rebase(v.strip(), old_id, new_id) for v in authorities)
            )
        for attr in ("taskAffinity", "process"):
            if node.get(A + attr):
                node.set(A + attr, rebase(node.get(A + attr), old_id, new_id))
        if node.tag == "instrumentation" and node.get(A + "targetPackage") == old_id:
            node.set(A + "targetPackage", new_id)
        if node.tag == "uses-feature" and node.get(A + "name") in optional_features:
            node.set(A + "required", "false")
    root.set("package", new_id)
    sdk_node = root.find("uses-sdk")
    if sdk_node is None:
        sdk_node = ET.SubElement(root, "uses-sdk")
    sdk_node.set(A + "minSdkVersion", str(min_sdk))
    sdk_node.set(A + "targetSdkVersion", str(target_sdk))
    if remove_max_sdk:
        sdk_node.attrib.pop(A + "maxSdkVersion", None)
    metadata.setdefault("sdkInfo", {}).update(
        minSdkVersion=str(min_sdk), targetSdkVersion=str(target_sdk)
    )
    if remove_max_sdk:
        metadata["sdkInfo"].pop("maxSdkVersion", None)
    # Apktool 2.x uses packageInfo; 3.x uses resourcesInfo and the manifest package directly.
    tool_version = str(metadata.get("version", "2.11.1"))
    if version_tuple(tool_version)[0] < 3:
        metadata.setdefault("packageInfo", {})["renameManifestPackage"] = new_id
    app.set(A + "label", "@string/mahdi_app_name")
    app.set(A + "icon", "@mipmap/mahdi_camera")
    app.set(A + "roundIcon", "@mipmap/mahdi_camera")
    for node in app:
        if node.tag in ("activity", "activity-alias"):
            if any(
                category.get(A + "name") in LAUNCHERS
                for category in node.findall("./intent-filter/category")
            ):
                node.set(A + "label", "@string/mahdi_app_name")
                if node.get(A + "icon"):
                    node.set(A + "icon", "@mipmap/mahdi_camera")
    return {
        "original_id": old_id,
        "application_id": new_id,
        "label": label,
        "previous_label": previous_label,
        "original_sdk": sdk,
        "requested_sdk": {"min": min_sdk, "target": target_sdk},
        "optional_features": optional_features,
        "class_packages_preserved": True,
        "custom_permissions": declared,
    }


def brand_resources(directory: Path, previous_label: str, label: str) -> None:
    old_name = previous_label.split("/", 1)[1] if previous_label.startswith("@string/") else None
    for values in (directory / "res").glob("values*"):
        if not values.is_dir():
            continue
        for file in values.glob("*.xml"):
            tree = parse_xml(file)
            changed = False
            for item in tree.getroot():
                if item.get("name") in ("mahdi_app_name", "mahdi_icon_background"):
                    raise MagicError(f"Branding resource collision in {file}.")
                if old_name and item.tag == "string" and item.get("name") == old_name:
                    for child in list(item):
                        item.remove(child)
                    item.text = label
                    changed = True
            if changed:
                save_xml(tree, file)
    resources = ET.Element("resources")
    ET.SubElement(resources, "string", name="mahdi_app_name", translatable="false").text = label
    ET.SubElement(resources, "color", name="mahdi_icon_background").text = "#10172A"
    save_xml(ET.ElementTree(resources), directory / "res/values/mahdi_branding.xml")
    for relative, content in {
        "mipmap-anydpi/mahdi_camera.xml": ICON,
        "mipmap-anydpi/mahdi_camera_foreground.xml": ICON,
        "mipmap-anydpi-v26/mahdi_camera.xml": ADAPTIVE_ICON,
    }.items():
        path = directory / "res" / relative
        if path.exists():
            raise MagicError(f"Icon resource collision: {path}")
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")


def patch_tree(source: Path, destination: Path, *, hdr_assets: Path, **options) -> dict:
    source, destination = source.resolve(), destination.resolve()
    if not source.is_dir():
        raise MagicError(f"Decoded input directory not found: {source}")
    if destination.exists():
        raise MagicError(
            "Patched output already exists; choose a new directory. Original decode is never overwritten."
        )
    if destination.is_relative_to(source) or source.is_relative_to(destination):
        raise MagicError("Source and destination must not contain one another.")
    if any(path.is_symlink() for path in source.rglob("*")):
        raise MagicError("Symlinks in decoded source are not accepted.")
    origin = read_json(source / ".camera-magic/source.json")
    if origin.get("full_decode") is not True or origin.get("inventory", {}).get("kind") != "apk":
        raise MagicError(
            "Use `decode` to create a complete, provenance-recorded source tree first."
        )
    tree = parse_xml(source / "AndroidManifest.xml")
    metadata = load_yaml(source / "apktool.yml")
    report = patch_manifest(tree.getroot(), metadata, **options)
    if not (hdr_assets / "presets.json").is_file():
        raise MagicError(
            "HDR assets missing; run `python -m camera_magic generate-luts` in the repository."
        )
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=".mahdi-patch-", dir=destination.parent))
    try:
        shutil.copytree(source, temporary, dirs_exist_ok=True)
        save_xml(tree, temporary / "AndroidManifest.xml")
        (temporary / "apktool.yml").write_text(
            yaml.safe_dump(metadata, sort_keys=False, allow_unicode=True), encoding="utf-8"
        )
        brand_resources(temporary, report["previous_label"], options["label"])
        assets_target = temporary / "assets/mahdi_hdr"
        if assets_target.exists():
            raise MagicError("assets/mahdi_hdr already exists; review it instead of overwriting.")
        shutil.copytree(hdr_assets, assets_target)
        report.update(
            {
                "created_at": utc_now(),
                "source_sha256": origin["inventory"]["sha256"],
                "kind": "rebrand_only",
                "hdr_assets_present": True,
                "hdr_pipeline_integrated": False,
                "runtime_compatibility": "not_tested",
                "review": scan_strings(temporary, report["original_id"]),
                "warning": "Copying LUTs into assets does NOT activate filters. No hardware checks, "
                "licensing, integrity checks or native code were patched.",
            }
        )
        write_json(temporary / ".camera-magic/patch.json", report)
        temporary.rename(destination)
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)
    return report
