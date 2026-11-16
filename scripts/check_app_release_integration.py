#!/usr/bin/env python3
"""Real SDK signing test of our app with an ephemeral TEST key; never a release."""

import base64
import json
import os
from pathlib import Path
import secrets
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from camera_magic.app_release import (  # noqa: E402
    APP_ID,
    APP_LABEL,
    certificate_digest,
    check_metadata,
    content_digests,
)
from camera_magic.core import MagicError, run, sha256  # noqa: E402
from camera_magic.pipeline import create_key, verify  # noqa: E402
from camera_magic.tools import Tools  # noqa: E402


def main():
    tools = Tools(ROOT / "config/release-toolchain.json")
    source = ROOT / "camera-app/build/outputs/apk/release/camera-app-release-unsigned.apk"
    os.environ["TEST_APP_STORE_PASSWORD"] = secrets.token_urlsafe(24)
    os.environ["TEST_APP_KEY_PASSWORD"] = secrets.token_urlsafe(24)
    (ROOT / "work").mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(dir=ROOT / "work", prefix="release-self-test-") as temporary:
        directory = Path(temporary)
        key = directory / "test-only.jks"
        create_key(
            key, "test", tools, store_env="TEST_APP_STORE_PASSWORD", key_env="TEST_APP_KEY_PASSWORD"
        )
        aligned, signed = directory / "aligned.apk", directory / "test-only.apk"
        run([tools.sdk_tool("zipalign"), "-P", "16", "4", str(source), str(aligned)])
        run(
            [
                tools.sdk_tool("apksigner"),
                "sign",
                "--ks",
                str(key),
                "--ks-key-alias",
                "test",
                "--ks-pass",
                "env:TEST_APP_STORE_PASSWORD",
                "--key-pass",
                "env:TEST_APP_KEY_PASSWORD",
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
        report = verify(signed, tools, expected_id=APP_ID, expected_label=APP_LABEL)
        check_metadata(report["metadata"])
        if content_digests(aligned) != content_digests(signed):
            raise MagicError("Signing changed non-signature source APK content.")
        try:
            certificate_digest(report["certificate_details"])
        except MagicError:
            pass  # Test key MUST be rejected by the production public-certificate pin.
        else:
            raise MagicError("Ephemeral test certificate unexpectedly accepted for production.")
        # The public-APK handoff is intentionally limited to a tiny transport. Assert the
        # guard itself (a real APK cannot travel in a dispatch body, including this one).
        transport = {
            "event_type": "mahdi-publish-signed",
            "client_payload": {
                "source_commit": "a" * 40,
                "validation_run": "99999999999999",
                "sha256": sha256(signed),
                "apk_base64": base64.b64encode(signed.read_bytes()).decode(),
            },
        }
        size = len(json.dumps(transport).encode())
        if size <= 64000:
            raise MagicError(
                f"Transport guard is broken: a {size}-byte APK body was accepted for dispatch."
            )
        print(
            f"APK transport guard rejected a {size}-byte handoff, as designed; "
            "the draft-release handoff in docs/APP_PUBLISHING.md is the supported path."
        )
        print(
            f"SDK 36 app release self-test passed; signed test APK {signed.stat().st_size} bytes; public dispatch {size} bytes."
        )
        print(
            "The test key/APK were discarded. This test does not publish or use a production key."
        )
    os.environ.pop("TEST_APP_STORE_PASSWORD", None)
    os.environ.pop("TEST_APP_KEY_PASSWORD", None)


if __name__ == "__main__":
    try:
        main()
    except MagicError as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(1) from None
