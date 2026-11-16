#!/usr/bin/env bash
# This script only runs inside the disposable emulator job, never on a user's phone.
#
# The API 23 system image on GitHub's shared runners occasionally dies mid-suite
# ("System has crashed", only 1 of 6 tests reported). That is infrastructure, not a camera
# bug, but a red job is indistinguishable from one. So the suite is run in stages:
#
#   1. emulator health is checked first, and a dead emulator fails immediately with a clear
#      message instead of six confusing test failures;
#   2. a crashed run is retried once, after waiting for the device to come back;
#   3. a run that reports tests but fails them is NOT retried, so a real regression stays red.
#
# The exit code is the final verdict, and the log keeps every attempt.
set -uo pipefail
mkdir -p work
: > work/camera-device.log

attempts=2
result=1

emulator_alive() {
    adb wait-for-device >/dev/null 2>&1 || return 1
    booted=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r\n')
    [ "$booted" = "1" ]
}

run_suite() {
    gradle --no-daemon :camera-app:connectedDebugAndroidTest 2>&1 | tee -a work/camera-device.log
    return "${PIPESTATUS[0]}"
}

for attempt in $(seq 1 "$attempts"); do
    echo "=== camera integration attempt ${attempt}/${attempts} ===" | tee -a work/camera-device.log
    if ! emulator_alive; then
        echo "::error title=Emulator unavailable::The emulator is not running before attempt ${attempt}. This is infrastructure, not a camera failure."
        result=1
        break
    fi
    run_suite
    result=$?
    [ "$result" -eq 0 ] && break

    # A crash report means the system image died; a plain assertion means our code failed.
    if grep -q "System has crashed" work/camera-device.log; then
        echo "::warning title=Emulator crashed::The system image crashed during the suite; waiting for it to come back and retrying once."
        for _ in $(seq 1 30); do emulator_alive && break; sleep 5; done
        continue
    fi
    echo "The suite ran but failed assertions; not retrying, this is a real failure."
    break
done

adb logcat -d -v threadtime -s AndroidRuntime:E MahdiCamera:I CameraDevice-JV-0:W \
  CameraService:W CameraManagerGlobal:W MediaRecorder:W > work/camera-logcat.txt 2>&1
adb pull /sdcard/Android/data/com.mahdi.camera/files/test-evidence work/ >/dev/null 2>&1
exit "$result"
