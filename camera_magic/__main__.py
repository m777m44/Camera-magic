"""Run with `python -m camera_magic --help` from the repository checkout."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

from . import __version__
from .app_lut import generate_app_luts
from .core import MagicError, inventory, write_json
from .device import smoke_test
from .lut import generate
from .manifest import DEFAULT_ID, DEFAULT_LABEL, audit_tree, patch_tree
from .name_only import ASSET_NAME, rename_apk
from .pipeline import build, create_key, decode, sign, verify
from .tools import Tools

REPO = Path(__file__).resolve().parent.parent


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(
        description="Local APK rebrand toolkit. HDR integration/runtime support are NOT automatic."
    )
    root.add_argument("--version", action="version", version=__version__)
    root.add_argument(
        "--tools", type=Path, help="Local toolchain JSON (default: toolchain.json if present)"
    )
    root.add_argument("--hdr-assets", type=Path, default=REPO / "assets/mahdi_hdr")
    commands = root.add_subparsers(dest="command", required=True)
    doctor = commands.add_parser(
        "doctor", help="Check Java/Apktool/SDK versions, without downloading anything"
    )
    doctor.add_argument("--report", type=Path, default=Path("work/reports/doctor.json"))
    analyze = commands.add_parser(
        "inventory", help="Inspect APK/DEX/ABI/ELF or list a split archive"
    )
    analyze.add_argument("apk", type=Path)
    analyze.add_argument("--report", type=Path, default=Path("work/reports/inventory.json"))
    full_decode = commands.add_parser(
        "decode", help="Full resources + Smali decode, optionally jadx analysis"
    )
    full_decode.add_argument("apk", type=Path)
    full_decode.add_argument("--output", type=Path, default=Path("work/decompiled"))
    full_decode.add_argument("--jadx-output", type=Path)
    audit = commands.add_parser(
        "audit", help="Review manifest/split/SDK/exported/hardcoded-ID issues"
    )
    audit.add_argument("source", type=Path)
    audit.add_argument("--report", type=Path, default=Path("work/reports/audit.json"))
    patch = commands.add_parser(
        "patch", help="Create a separate rebranded tree, leaving the decoded source intact"
    )
    patch.add_argument("source", type=Path)
    patch.add_argument("--output", type=Path, default=Path("work/patched"))
    patch.add_argument("--application-id", default=DEFAULT_ID)
    patch.add_argument("--label", default=DEFAULT_LABEL)
    patch.add_argument("--min-sdk", type=int, default=23)
    patch.add_argument("--target-sdk", type=int, default=35)
    patch.add_argument("--acknowledge-unsupported-sdk", action="store_true")
    patch.add_argument(
        "--optional-feature",
        action="append",
        default=[],
        help="Exact uses-feature name, repeatable; no blanket removal",
    )
    patch.add_argument("--remove-max-sdk", action="store_true")
    compile_apk = commands.add_parser(
        "build", help="Rebuild a rebrand-only APK; does not activate HDR"
    )
    compile_apk.add_argument("source", type=Path)
    compile_apk.add_argument("--rebrand-only", action="store_true", required=True)
    compile_apk.add_argument(
        "--output",
        type=Path,
        default=Path("artifacts/Blackmagic_Camera_Mahdi_REBRAND_ONLY-unsigned.apk"),
    )
    keygen = commands.add_parser("keygen", help="Generate a private local signing key with keytool")
    keygen.add_argument("--keystore", type=Path, default=Path("keys/mahdi-release.jks"))
    keygen.add_argument("--alias", default="mahdi")
    signing = commands.add_parser(
        "sign", help="16 KiB ZIP align, sign v1/v2/v3, and verify before publishing locally"
    )
    signing.add_argument("apk", type=Path)
    signing.add_argument("--rebrand-only", action="store_true", required=True)
    signing.add_argument(
        "--output", type=Path, default=Path("artifacts/Blackmagic_Camera_Mahdi_REBRAND_ONLY.apk")
    )
    signing.add_argument("--keystore", type=Path, default=Path("keys/mahdi-release.jks"))
    signing.add_argument("--alias", default="mahdi")
    signing.add_argument("--application-id", default=DEFAULT_ID)
    signing.add_argument("--label", default=DEFAULT_LABEL)
    rename = commands.add_parser(
        "rename-apk",
        help="Change display labels only; preserve package, SDK, icons and executable/asset bytes",
    )
    rename.add_argument("apk", type=Path)
    rename.add_argument("--output", type=Path, default=Path("artifacts") / ASSET_NAME)
    rename.add_argument("--label", default=DEFAULT_LABEL)
    rename.add_argument("--keystore", type=Path, default=Path("keys/mahdi-release.jks"))
    rename.add_argument("--alias", default="mahdi")
    for command in (keygen, signing, rename):
        command.add_argument(
            "--store-pass-env",
            default="MAHDI_STORE_PASSWORD",
            help="Environment variable NAME, not a password",
        )
        command.add_argument(
            "--key-pass-env",
            default="MAHDI_KEY_PASSWORD",
            help="Environment variable NAME, not a password",
        )
    verification = commands.add_parser(
        "verify", help="Verify alignment, v1/v2/v3 signatures and manifest identity"
    )
    verification.add_argument("apk", type=Path)
    verification.add_argument("--application-id", default=DEFAULT_ID)
    verification.add_argument("--label", default=DEFAULT_LABEL)
    verification.add_argument("--report", type=Path, default=Path("work/reports/verification.json"))
    luts = commands.add_parser(
        "generate-luts", help="Regenerate/check ten original LUTs without Android tools"
    )
    luts.add_argument("--check", action="store_true")
    app_luts = commands.add_parser(
        "app-luts", help="Regenerate/check the LUT assets bundled inside the camera app"
    )
    app_luts.add_argument("--check", action="store_true")
    app_luts.add_argument("--root", type=Path, default=REPO)
    smoke = commands.add_parser(
        "smoke-test", help="Install and launch on one explicit adb device; not an HDR test"
    )
    smoke.add_argument("apk", type=Path)
    smoke.add_argument("--serial", required=True)
    smoke.add_argument("--api", type=int, choices=(23, 29, 33, 35), required=True)
    smoke.add_argument("--report-dir", type=Path, required=True)
    smoke.add_argument("--observe-seconds", type=int, default=10)
    smoke.add_argument("--application-id", default=DEFAULT_ID)
    smoke.add_argument("--label", default=DEFAULT_LABEL)
    for command in (signing, verification, smoke):
        command.add_argument("--min-sdk", type=int, default=23)
        command.add_argument("--target-sdk", type=int, default=35)
    return root


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        config = args.tools or (
            Path("toolchain.json") if Path("toolchain.json").is_file() else None
        )
        tools = Tools(config)
        if args.command == "doctor":
            result = tools.doctor()
            write_json(args.report, result)
        elif args.command == "inventory":
            result = inventory(args.apk)
            write_json(args.report, result)
        elif args.command == "decode":
            result = decode(args.apk, args.output, tools, jadx_output=args.jadx_output)
        elif args.command == "audit":
            result = audit_tree(args.source)
            write_json(args.report, result)
        elif args.command == "patch":
            result = patch_tree(
                args.source,
                args.output,
                hdr_assets=args.hdr_assets,
                new_id=args.application_id,
                label=args.label,
                min_sdk=args.min_sdk,
                target_sdk=args.target_sdk,
                acknowledge_downgrade=args.acknowledge_unsupported_sdk,
                optional_features=args.optional_feature,
                remove_max_sdk=args.remove_max_sdk,
            )
        elif args.command == "build":
            result = build(args.source, args.output, tools)
        elif args.command == "keygen":
            result = create_key(
                args.keystore,
                args.alias,
                tools,
                store_env=args.store_pass_env,
                key_env=args.key_pass_env,
            )
        elif args.command == "sign":
            result = sign(
                args.apk,
                args.output,
                args.keystore,
                args.alias,
                tools,
                store_env=args.store_pass_env,
                key_env=args.key_pass_env,
                expected_id=args.application_id,
                expected_label=args.label,
                min_sdk=args.min_sdk,
                target_sdk=args.target_sdk,
            )
        elif args.command == "rename-apk":
            result = rename_apk(
                args.apk,
                args.output,
                args.keystore,
                args.alias,
                tools,
                label=args.label,
                store_env=args.store_pass_env,
                key_env=args.key_pass_env,
            )
        elif args.command == "verify":
            result = verify(
                args.apk,
                tools,
                expected_id=args.application_id,
                expected_label=args.label,
                min_sdk=args.min_sdk,
                target_sdk=args.target_sdk,
            )
            write_json(args.report, result)
        elif args.command == "generate-luts":
            result = generate(args.hdr_assets, check=args.check)
        elif args.command == "app-luts":
            result = generate_app_luts(args.root, check=args.check)
        else:
            result = smoke_test(
                args.apk,
                tools,
                serial=args.serial,
                expected_api=args.api,
                report_dir=args.report_dir,
                observe_seconds=args.observe_seconds,
                application_id=args.application_id,
                label=args.label,
                min_sdk=args.min_sdk,
                target_sdk=args.target_sdk,
            )
        print(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True))
        return 1 if args.command == "doctor" and not result["ready_for_rebrand_build"] else 0
    except (MagicError, OSError) as exc:
        print(f"camera-magic: {exc}", file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
