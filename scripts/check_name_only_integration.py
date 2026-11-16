#!/usr/bin/env python3
"""Real toolchain round trip of OUR test APK, never a Blackmagic build/release."""

from pathlib import Path
import json
import os
import secrets
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from camera_magic.core import MagicError  # noqa: E402
from camera_magic.name_only import rename_apk  # noqa: E402
from camera_magic.pipeline import create_key  # noqa: E402
from camera_magic.tools import Tools  # noqa: E402


def main():
    candidates = list((ROOT / "hdr-kit/build/outputs/apk/androidTest/debug").glob("*.apk"))
    if len(candidates) != 1:
        raise MagicError("First assemble the original hdr-kit debug instrumented-test APK.")
    directory = ROOT / "work/name-only-integration"
    directory.mkdir(parents=True, exist_ok=False)
    # Ephemeral key ONLY for a synthetic CI round trip; never reused by the release workflow.
    os.environ["FIXTURE_STORE_PASSWORD"] = secrets.token_urlsafe(24)
    os.environ["FIXTURE_KEY_PASSWORD"] = secrets.token_urlsafe(24)
    tools = Tools(ROOT / "config/release-toolchain.json")
    key = directory / "fixture.jks"
    try:
        create_key(
            key,
            "fixture",
            tools,
            store_env="FIXTURE_STORE_PASSWORD",
            key_env="FIXTURE_KEY_PASSWORD",
        )
        result = rename_apk(
            candidates[0],
            directory / "fixture-renamed.apk",
            key,
            "fixture",
            tools,
            label="Camera Magic CI Name Only",
            store_env="FIXTURE_STORE_PASSWORD",
            key_env="FIXTURE_KEY_PASSWORD",
        )
        if not result["code_libraries_assets_unchanged"]:
            raise MagicError("Fixture payload changed unexpectedly.")
        print(json.dumps({"test": "our_fixture_only_not_blackmagic", "result": result}, indent=2))
    finally:
        key.unlink(missing_ok=True)
        os.environ.pop("FIXTURE_STORE_PASSWORD", None)
        os.environ.pop("FIXTURE_KEY_PASSWORD", None)


if __name__ == "__main__":
    try:
        main()
    except MagicError as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
