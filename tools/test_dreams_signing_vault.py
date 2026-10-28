"""Real keytool/APK tests with an in-memory stand-in for private GitHub storage.

All generated keys and APKs are disposable and removed with the temporary folder.
No production key, GitHub credential or network write is involved.
"""
import argparse
import copy
import hashlib
import os
from pathlib import Path
import re
import secrets
import subprocess
import tempfile
import unittest
from unittest.mock import patch

from dreams_signing_vault import ALIAS, KEY_NAME, GitHubStore, VaultError, certificate_digest, prepare_signing

OPTIONS = argparse.Namespace(apk=None, apksigner=None)


class FakeStore:
    def __init__(self):
        self.record = None
        self.pinned = None
        self.key = None
        self.events = []
        self.fail_pin = False

    def release(self):
        return copy.deepcopy(self.record)

    def identity(self):
        return self.pinned

    def create_release(self):
        self.events.append("create-private-draft")
        self.record = {"draft": True, "assets": [], "html_url": "https://github.com/owner-demo/repo-demo/releases/tag/dreams-signing-vault"}
        return self.release()

    def upload(self, path):
        if self.key is not None:
            raise AssertionError("The signing key must never be overwritten")
        self.events.append("upload-encrypted-key")
        self.key = path.read_bytes()
        self.record["assets"] = [{"name": KEY_NAME}]

    def download(self, directory):
        self.events.append("download-existing-key")
        (directory / KEY_NAME).write_bytes(self.key)

    def pin(self, digest):
        if self.fail_pin:
            raise VaultError("Simulated identity-record failure")
        self.events.append("pin-public-certificate")
        if self.pinned is not None:
            raise AssertionError("A certificate identity must not be overwritten")
        self.pinned = digest


class DreamsVaultTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="dreams-vault-test-")
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.password = secrets.token_urlsafe(30)
        self.store = FakeStore()

    def first_key(self):
        return prepare_signing(self.store, self.password, self.root / "first")

    def test_creation_is_private_and_saved_before_identity_pin(self):
        path, digest, url = self.first_key()
        self.assertEqual(["create-private-draft", "upload-encrypted-key", "pin-public-certificate"], self.store.events)
        self.assertTrue(self.store.record["draft"])
        self.assertEqual(0o700, path.parent.stat().st_mode & 0o777)
        self.assertEqual(0o600, path.stat().st_mode & 0o777)
        self.assertEqual(certificate_digest(path, self.password), digest)
        self.assertNotIn(self.password.encode(), self.store.key)
        self.assertIn("dreams-signing-vault", url)

    def test_repeat_uses_exact_same_key_and_identity(self):
        first, digest, _ = self.first_key()
        second, repeated, _ = prepare_signing(self.store, self.password, self.root / "second")
        self.assertEqual(first.read_bytes(), second.read_bytes())
        self.assertEqual(digest, repeated)
        self.assertEqual(1, self.store.events.count("upload-encrypted-key"))
        self.assertEqual(1, self.store.events.count("pin-public-certificate"))

    def test_missing_backup_never_silently_rotates_an_established_key(self):
        self.first_key()
        original_identity = self.store.pinned
        backup = self.store.key
        self.store.key = None
        self.store.record["assets"] = []
        with self.assertRaisesRegex(VaultError, "Falta la copia"):
            prepare_signing(self.store, self.password, self.root / "missing")
        self.assertEqual(original_identity, self.store.pinned)
        self.assertFalse((self.root / "missing" / KEY_NAME).exists())
        self.assertEqual(1, self.store.events.count("upload-encrypted-key"))
        self.store.key = backup
        self.store.record["assets"] = [{"name": KEY_NAME}]
        recovered, digest, _ = prepare_signing(self.store, self.password, self.root / "restored-backup")
        self.assertEqual(backup, recovered.read_bytes())
        self.assertEqual(original_identity, digest)

    def test_publication_of_vault_blocks_signing(self):
        self.first_key()
        self.store.record["draft"] = False
        with self.assertRaisesRegex(VaultError, "borrador privado"):
            prepare_signing(self.store, self.password, self.root / "published")

    def test_wrong_password_keeps_remote_identity_and_cleans_local_copy(self):
        self.first_key()
        original = hashlib.sha256(self.store.key).digest()
        with self.assertRaisesRegex(VaultError, "contraseña"):
            prepare_signing(self.store, secrets.token_urlsafe(30), self.root / "wrong-password")
        self.assertEqual(original, hashlib.sha256(self.store.key).digest())
        self.assertFalse((self.root / "wrong-password" / KEY_NAME).exists())

    def test_substituted_key_fails_certificate_pin(self):
        self.first_key()
        original_identity = self.store.pinned
        other = FakeStore()
        prepare_signing(other, self.password, self.root / "other-key")
        self.store.key = other.key
        with self.assertRaisesRegex(VaultError, "huella"):
            prepare_signing(self.store, self.password, self.root / "substituted")
        self.assertEqual(original_identity, self.store.pinned)
        self.assertFalse((self.root / "substituted" / KEY_NAME).exists())

    def test_identity_write_failure_recovers_the_already_saved_key(self):
        self.store.fail_pin = True
        with self.assertRaises(VaultError):
            self.first_key()
        stored = self.store.key
        self.assertIsNotNone(stored)
        self.assertFalse((self.root / "first" / KEY_NAME).exists())
        self.store.fail_pin = False
        recovered, _, _ = prepare_signing(self.store, self.password, self.root / "recovered")
        self.assertEqual(stored, recovered.read_bytes())
        self.assertEqual(1, self.store.events.count("upload-encrypted-key"))

    def test_short_or_nonportable_passwords_are_rejected_before_setup(self):
        for password in ["short", "x" * 20 + "\n", "á" * 20]:
            with self.subTest(case="invalid-password"):
                with self.assertRaises(VaultError):
                    prepare_signing(self.store, password, self.root / "invalid")
        self.assertIsNone(self.store.record)

    def test_github_contract_creates_draft_and_never_clobbers_key(self):
        store = GitHubStore("owner-demo/repo-demo", "a" * 40)
        with patch.object(store, "gh", return_value='{"draft": true, "assets": []}') as request:
            store.create_release()
            payload = request.call_args.args[1]
            self.assertIs(payload["draft"], True)
            self.assertEqual("dreams-signing-vault", payload["tag_name"])
        with patch.object(store, "gh", return_value="") as request:
            store.upload(Path("/tmp") / KEY_NAME)
            self.assertNotIn("--clobber", request.call_args.args[0])

    def test_real_apk_signing_reuses_certificate_across_runs(self):
        if not (OPTIONS.apk and OPTIONS.apksigner):
            self.skipTest("Pass --apk and --apksigner for APK integration")
        _, expected, _ = self.first_key()
        for index in range(2):
            key, digest, _ = prepare_signing(self.store, self.password, self.root / f"sign-run-{index}")
            apk = self.root / f"signed-test-{index}.apk"
            env = dict(os.environ, DREAMS_TEST_PASSWORD=self.password)
            subprocess.run([OPTIONS.apksigner, "sign", "--ks", str(key), "--ks-key-alias", ALIAS,
                            "--ks-pass", "env:DREAMS_TEST_PASSWORD", "--key-pass", "env:DREAMS_TEST_PASSWORD",
                            "--out", str(apk), OPTIONS.apk], env=env, capture_output=True, check=True)
            result = subprocess.run([OPTIONS.apksigner, "verify", "--print-certs", str(apk)], capture_output=True, text=True, check=True)
            actual = re.search(r"certificate SHA-256 digest: ([0-9a-fA-F]+)", result.stdout).group(1).lower()
            self.assertEqual(expected, digest)
            self.assertEqual(expected, actual)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk")
    parser.add_argument("--apksigner")
    OPTIONS, remaining = parser.parse_known_args()
    unittest.main(argv=[__file__, *remaining], verbosity=2)
