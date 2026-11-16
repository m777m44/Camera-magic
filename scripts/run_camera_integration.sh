#!/usr/bin/env bash
# This script only runs inside the disposable emulator job, never on a user's phone.
set -uo pipefail
mkdir -p work
set +e
gradle --no-daemon :camera-app:connectedDebugAndroidTest 2>&1 | tee work/camera-device.log
result=${PIPESTATUS[0]}
adb logcat -d -v threadtime -s AndroidRuntime:E MahdiCamera:I CameraDevice-JV-0:W \
  CameraService:W CameraManagerGlobal:W MediaRecorder:W > work/camera-logcat.txt 2>&1
adb pull /sdcard/Android/data/com.mahdi.camera/files/test-evidence work/ >/dev/null 2>&1
exit "$result"
