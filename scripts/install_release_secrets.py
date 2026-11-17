#!/usr/bin/env python3
"""
Install (or verify) the three signing secrets that publish a release, from one command.

Why this exists: creating the key is scriptable, but GitHub Secrets need a token with
*Secrets: write* — which an automation token does not have. So the owner runs this locally,
where their own `gh` login lives. Nothing private is ever printed: the keystore is read,
base64-encoded and piped to `gh secret set` on stdin.

Usage:

    python3 scripts/install_release_secrets.py            # install all three
    python3 scripts/install_release_secrets.py --verify    # only report what is missing
    python3 scripts/install_release_secrets.py --repo m777m44/Camera-magic

Exit codes: 0 everything is in place, 2 something is missing or refused (with a reason).
"""

from __future__ import annotations

import argparse
import base64
import json
from pathlib import Path
import subprocess
import sys

SECRETS = ("MAHDI_APP_KEYSTORE_B64", "MAHDI_APP_STORE_PASSWORD", "MAHDI_APP_KEY_PASSWORD")
DEFAULT_REPO = "m777m44/Camera-magic"


def gh(*arguments: str, stdin: str | None = None) -> subprocess.CompletedProcess:
    return subprocess.run(
        ["gh", *arguments],
        input=stdin,
        text=True,
        capture_output=True,
        check=False,
    )


def private_files(keystore: Path, settings: Path) -> tuple[bytes, dict]:
    """Read the private inputs, refusing anything Git or another user could read."""
    for path in (keystore, settings):
        if not path.is_file() or path.is_symlink():
            raise ValueError(f"{path} is missing; run scripts/create_release_key.py first")
        mode = path.stat().st_mode & 0o777
        if mode & 0o077:
            raise ValueError(f"{path} is mode {mode:o}; run: chmod 600 {path}")
    values = json.loads(settings.read_text(encoding="utf-8"))
    if values.get("alias") != "mahdi":
        raise ValueError("the key alias must be mahdi")
    for name in ("store_password", "key_password"):
        if len(values.get(name, "")) < 12:
            raise ValueError(f"{name} must be at least 12 characters")
    return keystore.read_bytes(), values


def existing_secrets(repo: str) -> set[str]:
    result = gh("secret", "list", "--repo", repo, "--json", "name")
    if result.returncode:
        # Never echo the transport error: it can contain request details, not secrets.
        raise PermissionError(
            "cannot list repository secrets with this token "
            "(needs an owner login: gh auth login, or set GH_TOKEN)"
        )
    try:
        return {item["name"] for item in json.loads(result.stdout)}
    except (ValueError, KeyError, TypeError):
        return set()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default=DEFAULT_REPO)
    parser.add_argument("--keystore", type=Path, default=Path("keys/mahdi-camera-release.jks"))
    parser.add_argument("--settings", type=Path, default=Path("keys/mahdi-camera-private.json"))
    parser.add_argument("--verify", action="store_true", help="report status, change nothing")
    args = parser.parse_args()

    try:
        configured = existing_secrets(args.repo)
    except PermissionError as error:
        print(f"cannot verify: {error}", file=sys.stderr)
        if args.verify:
            return 2
        configured = set()

    missing = [name for name in SECRETS if name not in configured]
    if args.verify:
        for name in SECRETS:
            print(f"{name}: {'configured' if name in configured else 'MISSING'}")
        if missing:
            print(
                f"\n{len(missing)} of 3 secrets are missing, so the signed release cannot be "
                "published.\nRun this script without --verify from a machine logged in as the "
                "repository owner.",
                file=sys.stderr,
            )
            return 2
        print("\nAll three signing secrets are configured; the next tagged run can sign.")
        return 0

    if not missing:
        print("All three signing secrets are already configured. Nothing to do.")
        return 0

    try:
        keystore_bytes, values = private_files(args.keystore, args.settings)
    except ValueError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2

    payload = {
        "MAHDI_APP_KEYSTORE_B64": base64.b64encode(keystore_bytes).decode("ascii"),
        "MAHDI_APP_STORE_PASSWORD": values["store_password"],
        "MAHDI_APP_KEY_PASSWORD": values["key_password"],
    }
    for name in missing:
        result = gh("secret", "set", name, "--repo", args.repo, stdin=payload[name])
        if result.returncode:
            print(
                f"error: GitHub refused to store {name}. The token needs Secrets: write on "
                f"{args.repo}. No private value was printed.",
                file=sys.stderr,
            )
            return 2
        print(f"installed {name}")

    print(
        "\nNext: publish a release so /releases/latest/download/Mahdi_Camera.apk resolves.\n"
        "Keep keys/mahdi-camera-release.jks and its password backed up together: losing them\n"
        "makes future updates impossible for anyone who installed this certificate."
    )
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        raise SystemExit(130) from None
