"""Explicit-device smoke test; never equates process survival with working camera/HDR."""

from __future__ import annotations

from pathlib import Path
import re
import shlex
import subprocess
import time

from .core import MagicError, inventory, run, utc_now, write_json
from .manifest import DEFAULT_ID, DEFAULT_LABEL
from .pipeline import verify
from .tools import Tools

SUPPORTED_TEST_APIS = (23, 29, 33, 35)


def shell(adb: str, serial: str, *args: str, check: bool = True):
    # adb shell concatenates arguments on the device: explicitly quote each one.
    return run([adb, "-s", serial, "shell", shlex.join(args)], check=check)


def process_alive(ps_output: str, application_id: str) -> bool:
    return any(
        line.split() and line.split()[-1] == application_id for line in ps_output.splitlines()
    )


def relevant_crash(log: str, application_id: str) -> bool:
    escaped = re.escape(application_id)
    return bool(
        re.search(r"Process:\s*" + escaped + r"(?:,|\s|$)", log)
        or re.search(r">>>\s*" + escaped + r"\s*<<<", log)
    )


def smoke_test(
    apk: Path,
    tools: Tools,
    *,
    serial: str,
    expected_api: int,
    report_dir: Path,
    observe_seconds: int = 10,
    application_id: str = DEFAULT_ID,
    label: str = DEFAULT_LABEL,
    min_sdk: int = 23,
    target_sdk: int = 35,
) -> dict:
    if expected_api not in SUPPORTED_TEST_APIS:
        raise MagicError("Test APIs are 23, 29, 33 and 35.")
    if not 1 <= observe_seconds <= 120:
        raise MagicError("Observation time must be 1–120 seconds.")
    if report_dir.exists():
        raise MagicError("Use a fresh report directory for each run.")
    report_dir.mkdir(parents=True)
    report = {
        "started_at": utc_now(),
        "serial": serial,
        "expected_api": expected_api,
        "stages": {},
        "launch_survival": "not_tested",
        "camera_preview": "not_tested",
        "hdr_in_preview": "not_tested",
        "hdr_in_recording": "not_tested",
        "vendor_device_compatibility": "not_tested",
    }
    adb = tools.binary("adb")
    try:
        verified = verify(
            apk,
            tools,
            expected_id=application_id,
            expected_label=label,
            min_sdk=min_sdk,
            target_sdk=target_sdk,
        )
        if expected_api < min_sdk:
            raise MagicError("Requested device API is below the APK minimum SDK.")
        report["apk_sha256"] = verified["apk_sha256"]
        metadata = verified["metadata"]
        launcher = metadata["launchable_activity"]
        if not launcher:
            raise MagicError("No launchable activity found in aapt2 badging.")
        api = shell(adb, serial, "getprop", "ro.build.version.sdk").stdout.strip()
        report["actual_api"] = api
        if api != str(expected_api):
            raise MagicError(f"Device API is {api}, not the requested {expected_api}.")
        report["model"] = shell(adb, serial, "getprop", "ro.product.model").stdout.strip()
        report["manufacturer"] = shell(
            adb, serial, "getprop", "ro.product.manufacturer"
        ).stdout.strip()
        device_abis = (
            shell(adb, serial, "getprop", "ro.product.cpu.abilist").stdout.strip().split(",")
        )
        apk_abis = inventory(apk)["abis"]
        report["device_abis"], report["apk_abis"] = device_abis, apk_abis
        if apk_abis and not (set(device_abis) & set(apk_abis)):
            raise MagicError(
                "APK native ABIs do not match this device. An ARM-only APK needs an ARM device/image."
            )
        result = run([adb, "-s", serial, "install", "-r", "--no-streaming", str(apk)], timeout=300)
        (report_dir / "install.txt").write_text(result.stdout + result.stderr, encoding="utf-8")
        report["stages"]["install"] = "passed"
        for permission in ("android.permission.CAMERA", "android.permission.RECORD_AUDIO"):
            if permission in metadata["permissions"]:
                shell(adb, serial, "pm", "grant", application_id, permission)
        shell(adb, serial, "am", "force-stop", application_id)
        timestamp = shell(adb, serial, "date", "+%m-%d %H:%M:%S.000").stdout.strip()
        started = shell(
            adb, serial, "am", "start", "-W", "-n", f"{application_id}/{launcher}", check=False
        )
        launch_text = started.stdout + started.stderr
        (report_dir / "launch.txt").write_text(launch_text, encoding="utf-8")
        if (
            started.returncode
            or "Status: ok" not in launch_text
            or re.search(r"\bError(?:\s|:)", launch_text)
        ):
            raise MagicError("Activity launch failed; inspect launch.txt.")
        time.sleep(observe_seconds)
        ps_result = shell(adb, serial, "ps", "-A", check=False)
        if ps_result.returncode:  # Older API-23 toolbox builds do not accept -A.
            ps_result = shell(adb, serial, "ps")
        alive = process_alive(ps_result.stdout, application_id)
        log = run(
            [adb, "-s", serial, "logcat", "-d", "-v", "threadtime", "-T", timestamp], timeout=60
        ).stdout
        (report_dir / "logcat.txt").write_text(log, encoding="utf-8")
        screenshot = subprocess.run(
            [adb, "-s", serial, "exec-out", "screencap", "-p"],
            capture_output=True,
            timeout=30,
            check=False,
        )
        if screenshot.returncode == 0 and screenshot.stdout.startswith(b"\x89PNG"):
            (report_dir / "screen.png").write_bytes(screenshot.stdout)
        if not alive or relevant_crash(log, application_id):
            raise MagicError("Process did not survive or a crash was detected. Inspect logcat.txt.")
        report["launch_survival"] = "passed"
        report["observed_seconds"] = observe_seconds
        report["warning"] = (
            "Launch survival only. Inspect the screenshot, camera output and saved media manually; "
            "emulators do not establish Xiaomi/Huawei/Samsung compatibility."
        )
        return report
    except (MagicError, OSError, subprocess.TimeoutExpired) as exc:
        report["launch_survival"] = "failed_or_blocked"
        report["error"] = str(exc)
        raise MagicError(
            f"Smoke test failed/blocked: {exc}. Report: {report_dir / 'smoke.json'}"
        ) from exc
    finally:
        report["finished_at"] = utc_now()
        write_json(report_dir / "smoke.json", report)
