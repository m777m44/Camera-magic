"""The signing key must be a real JKS that apksigner and keytool can open.

There is no JDK in every environment this repository is built from, so the key is created
in Python. That makes a format mistake easy and invisible: `apksigner` would simply reject
the file at release time. These tests read the produced file back field by field.
"""

import hashlib
import os
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest

import importlib
import importlib.util

from cryptography import x509
from cryptography.hazmat.primitives import serialization

ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "scripts/create_release_key.py"
sys.path.insert(0, str(ROOT / "scripts"))

from create_release_key import (  # noqa: E402
    JKS_MAGIC,
    protect_private_key,
    unprotected_private_key,
)

# A throwaway value used only inside this test, never a real signing password.
PASSWORD = "test-only-password"  # 18 characters: above the 12-character minimum


def pytest_importorskip(name: str):
    """Import an optional verification dependency, skipping the test when it is absent."""
    if importlib.util.find_spec(name) is None:
        raise unittest.SkipTest(f"{name} is not installed (pip install pyjks)")
    return importlib.import_module(name)


def read_jks(path: Path, password: str) -> dict:
    """
    Parse a JKS file strictly, the way a JVM keystore implementation does.

    Layout (OpenJDK ``JavaKeyStore``): magic, version, entry count; then per entry
    tag(4) alias(writeUTF: 2-byte length + modified UTF-8) timestamp(8). A *private key*
    entry has tag 1, then the DER EncryptedPrivateKeyInfo, then a chain count, and each chain
    member is writeUTF("X.509") + length + DER — with no tag, alias or timestamp of its own.
    """
    data = path.read_bytes()
    magic, version = struct.unpack(">II", data[:8])
    assert magic == JKS_MAGIC, f"bad magic {magic:#x}"
    assert version == 2, version
    position = 8
    (count,) = struct.unpack(">I", data[position : position + 4])
    position += 4
    assert count == 1, f"expected one private key entry, got {count}"
    (tag,) = struct.unpack(">I", data[position : position + 4])
    position += 4
    assert tag == 1, f"private key entry tag must be 1, got {tag}"
    (alias_length,) = struct.unpack(">H", data[position : position + 2])
    position += 2
    alias = data[position : position + alias_length].decode("utf-8")
    position += alias_length
    position += 8  # entry timestamp
    (key_length,) = struct.unpack(">I", data[position : position + 4])
    position += 4
    protected = data[position : position + key_length]
    position += key_length
    pkcs8 = unprotected_private_key(protected, password)
    (chain_count,) = struct.unpack(">I", data[position : position + 4])
    position += 4
    assert chain_count == 1, f"expected one certificate, got {chain_count}"
    (type_length,) = struct.unpack(">H", data[position : position + 2])
    position += 2
    certificate_type = data[position : position + type_length].decode("ascii")
    position += type_length
    assert certificate_type == "X.509", certificate_type
    (certificate_length,) = struct.unpack(">I", data[position : position + 4])
    position += 4
    der = data[position : position + certificate_length]
    position += certificate_length
    expected = hashlib.sha1(
        password.encode("utf-16-be") + b"Mighty Aphrodite" + data[:position]
    ).digest()
    assert data[position : position + 20] == expected, "store digest does not match"
    assert len(data) == position + 20, f"{len(data) - position - 20} trailing bytes"
    return {
        "alias": alias,
        "certificate_type": certificate_type,
        "pkcs8": pkcs8,
        "der": der,
    }


class CreateReleaseKeyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory()
        cls.keystore = Path(cls.directory.name) / "release.jks"
        environment = dict(os.environ, MAHDI_STORE_PASSWORD=PASSWORD)
        cls.result = subprocess.run(
            [sys.executable, str(SCRIPT), "--keystore", str(cls.keystore)],
            env=environment,
            capture_output=True,
            text=True,
            check=False,
        )

    @classmethod
    def tearDownClass(cls):
        cls.directory.cleanup()

    def test_the_key_is_created_with_a_private_permission(self):
        self.assertEqual(self.result.returncode, 0, self.result.stderr)
        self.assertTrue(self.keystore.is_file())
        self.assertEqual(0o600, self.keystore.stat().st_mode & 0o777)

    def test_the_output_never_contains_the_password(self):
        self.assertNotIn(PASSWORD, self.result.stdout + self.result.stderr)

    def test_the_file_is_a_readable_jks_with_one_matching_key_pair(self):
        entry = read_jks(self.keystore, PASSWORD)
        self.assertEqual("mahdi", entry["alias"])
        self.assertEqual("X.509", entry["certificate_type"])
        self.assertEqual(b"\x30\x82", entry["pkcs8"][:2], "PKCS#8 DER expected")
        key = serialization.load_der_private_key(entry["pkcs8"], None)
        certificate = x509.load_der_x509_certificate(entry["der"])
        self.assertEqual(
            key.public_key().public_numbers(), certificate.public_key().public_numbers()
        )
        self.assertEqual(3072, key.key_size)

    def test_the_certificate_is_long_lived_and_self_signed(self):
        certificate = x509.load_der_x509_certificate(read_jks(self.keystore, PASSWORD)["der"])
        self.assertEqual(certificate.subject, certificate.issuer)
        self.assertEqual(
            "Mahdi Camera",
            certificate.subject.get_attributes_for_oid(x509.oid.NameOID.COMMON_NAME)[0].value,
        )
        self.assertGreater(
            (certificate.not_valid_after_utc - certificate.not_valid_before_utc).days, 9000
        )

    def test_the_reported_pin_is_the_certificate_sha256(self):
        certificate = x509.load_der_x509_certificate(read_jks(self.keystore, PASSWORD)["der"])
        pin = hashlib.sha256(certificate.public_bytes(serialization.Encoding.DER)).hexdigest()
        self.assertIn(pin, self.result.stdout)

    def test_an_independent_jks_reader_accepts_the_keystore(self):
        """
        Read the file with ``pyjks`` — a separate implementation of the format — so a shared
        misunderstanding in this repository's writer and reader cannot hide a broken key.
        """
        jks = pytest_importorskip("jks")
        store = jks.KeyStore.load(str(self.keystore), PASSWORD, try_decrypt_keys=True)
        self.assertEqual(["mahdi"], list(store.private_keys))
        entry = store.private_keys["mahdi"]
        self.assertTrue(entry.is_decrypted())
        key = serialization.load_der_private_key(entry.pkey_pkcs8, None)
        name, der = entry.cert_chain[0][:2]
        self.assertEqual("X.509", name)
        certificate = x509.load_der_x509_certificate(der)
        self.assertEqual(
            key.public_key().public_numbers(), certificate.public_key().public_numbers()
        )
        # And a wrong password must be rejected by that reader rather than silently accepted.
        with self.assertRaises(Exception):
            jks.KeyStore.load(str(self.keystore), PASSWORD + "x")

    def test_a_short_password_is_refused(self):
        target = Path(self.directory.name) / "short.jks"
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "--keystore", str(target)],
            env=dict(os.environ, MAHDI_STORE_PASSWORD="short"),
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(1, result.returncode)
        self.assertIn("12", result.stderr)
        self.assertFalse(target.exists())

    def test_an_existing_key_is_never_overwritten(self):
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "--keystore", str(self.keystore)],
            env=dict(os.environ, MAHDI_STORE_PASSWORD=PASSWORD),
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(1, result.returncode)
        self.assertIn("already exists", result.stderr)

    def test_a_wrong_password_cannot_unlock_the_key(self):
        data = self.keystore.read_bytes()
        position = 12 + 4  # entry tag
        (alias_length,) = struct.unpack(">H", data[position : position + 2])
        position += 2 + alias_length + 8
        (key_length,) = struct.unpack(">I", data[position : position + 4])
        protected = data[position + 4 : position + 4 + key_length]
        with self.assertRaises(Exception):
            unprotected_private_key(protected, "SomeOtherPassword1")

    def test_the_protector_round_trips_for_any_length(self):
        for length in (1, 15, 16, 17, 479):
            secret = bytes(range(length % 256)) or b"\x00"
            secret = (secret * ((length // max(len(secret), 1)) + 1))[:length]
            self.assertEqual(
                secret, unprotected_private_key(protect_private_key(secret, PASSWORD), PASSWORD)
            )


if __name__ == "__main__":
    unittest.main()
