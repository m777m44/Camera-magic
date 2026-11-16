#!/usr/bin/env python3
"""Owner-only: configure persistent app signing via gh stdin. Never logs private values."""

import argparse
import base64
import json
from pathlib import Path
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--repo", default="m777m44/Camera-magic")
    parser.add_argument("--keystore", type=Path, default=Path("keys/mahdi-camera-release.jks"))
    parser.add_argument("--settings", type=Path, default=Path("keys/mahdi-camera-private.json"))
    args = parser.parse_args()
    for path in (args.keystore, args.settings):
        if not path.is_file() or path.is_symlink() or path.stat().st_mode & 0o077:
            raise ValueError("Use existing private signing files (chmod 600), never files in Git.")
    values = json.loads(args.settings.read_text())
    if values.get("alias") != "mahdi":
        raise ValueError("The application signing alias must be mahdi.")
    settings = {
        "MAHDI_APP_KEYSTORE_B64": base64.b64encode(args.keystore.read_bytes()).decode(),
        "MAHDI_APP_STORE_PASSWORD": values["store_password"],
        "MAHDI_APP_KEY_PASSWORD": values["key_password"],
    }
    if min(len(settings[name]) for name in settings if "PASSWORD" in name) < 12:
        raise ValueError("Signing passwords must contain at least 12 characters.")
    for name, value in settings.items():
        result = subprocess.run(
            ["gh", "secret", "set", name, "--repo", args.repo],
            input=value,
            text=True,
            capture_output=True,
            check=False,
        )
        if result.returncode:
            print(
                f"Could not configure {name}. Check GitHub Secrets permissions; no private value was logged.",
                file=sys.stderr,
            )
            return 2
        print("Configured " + name + " securely.")
    print("Back up the original key privately. Do not generate another key for updates.")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, KeyError):
        print(
            "Signing setup failed. Check private input files and settings; no private data was logged.",
            file=sys.stderr,
        )
        raise SystemExit(2) from None
