import contextlib
import io
import subprocess
import unittest
from unittest.mock import patch

from camera_magic.__main__ import main, parser
from camera_magic.device import process_alive, relevant_crash, shell


class CliDeviceTests(unittest.TestCase):
    def test_missing_apk_is_actionable(self):
        with contextlib.redirect_stderr(io.StringIO()) as error:
            result = main(["inventory", "/not-present/original.apk"])
        self.assertEqual(2, result)
        self.assertIn("Not a readable APK", error.getvalue())

    def test_build_requires_rebrand_ack(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as error:
            parser().parse_args(["build", "work/patched"])
        self.assertEqual(2, error.exception.code)

    def test_sign_requires_rebrand_ack(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            parser().parse_args(["sign", "test.apk"])

    def test_smoke_requires_explicit_serial(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            parser().parse_args(
                ["smoke-test", "test.apk", "--api", "23", "--report-dir", "work/test"]
            )

    def test_main_process_alive_exact_match(self):
        self.assertTrue(
            process_alive(
                "USER PID NAME\nu0_a1 42 com.mahdi.blackmagic.camera", "com.mahdi.blackmagic.camera"
            )
        )
        self.assertFalse(
            process_alive(
                "u0_a1 42 com.mahdi.blackmagic.camera:worker", "com.mahdi.blackmagic.camera"
            )
        )
        self.assertFalse(
            process_alive(
                "u0_a1 42 com.mahdi.blackmagic.camera.other", "com.mahdi.blackmagic.camera"
            )
        )

    def test_crash_attribution(self):
        package = "com.mahdi.blackmagic.camera"
        self.assertTrue(
            relevant_crash("FATAL EXCEPTION: main\nProcess: " + package + ", PID: 42", package)
        )
        self.assertTrue(relevant_crash(">>> " + package + " <<<", package))
        self.assertFalse(relevant_crash("Process: " + package + ".other, PID: 42", package))
        self.assertFalse(relevant_crash("FATAL EXCEPTION: another app", package))

    @patch("camera_magic.device.run")
    def test_adb_remote_arguments_are_quoted(self, mocked):
        mocked.return_value = subprocess.CompletedProcess([], 0, "", "")
        shell("adb", "emulator-5554", "am", "start", "-n", "com.example/.Screen$Inner")
        self.assertIn("'com.example/.Screen$Inner'", mocked.call_args.args[0][-1])

    def test_generate_check_cli_works_without_android(self):
        with contextlib.redirect_stdout(io.StringIO()):
            self.assertEqual(0, main(["generate-luts", "--check"]))
