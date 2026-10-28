"""Local keytool/PTY integration tests. All generated keys are disposable test keys.

Requires pexpect. Optionally pass --apk and --apksigner to exercise real APK signing.
Nothing under the temporary test directory is uploaded or retained.
"""
import argparse
import base64
import hashlib
import io
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import tempfile
import unittest

import pexpect

SCRIPT = Path(__file__).with_name("dreams-signing.sh").resolve()
OPTIONS = argparse.Namespace(apk=None, apksigner=None)


class DreamsSigningTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="dreams-signing-test-")
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        cls.directory = cls.root / "private"
        cls.key = cls.directory / "dreams-release.p12"
        cls.password = secrets.token_urlsafe(30)
        cls.env = dict(os.environ, DREAMS_SIGNING_DIR=str(cls.directory), LC_ALL="C", LANG="C")
        cls.env.pop("JAVA_TOOL_OPTIONS", None)
        output = io.StringIO()
        child = pexpect.spawn("bash", [str(SCRIPT), "create"], env=cls.env, encoding="utf-8", timeout=90)
        child.logfile_read = output
        try:
            child.expect("Enter keystore password:")
            child.sendline(cls.password)
            child.expect("Re-enter new password:")
            child.sendline(cls.password)
            child.expect(pexpect.EOF)
            child.close()
            if child.exitstatus != 0:
                raise AssertionError("Interactive test-key creation failed")
            if cls.password in output.getvalue():
                raise AssertionError("A password was echoed by the interactive flow")
        finally:
            child.close(force=True)

    def run_helper(self, mode, **env):
        return subprocess.run(["bash", str(SCRIPT), mode], env=dict(self.env, **env),
                              stdin=subprocess.DEVNULL, capture_output=True, text=True)

    def test_private_permissions_and_no_extra_secret_files(self):
        self.assertEqual(0o700, self.directory.stat().st_mode & 0o777)
        self.assertEqual(0o600, self.key.stat().st_mode & 0o777)
        self.assertEqual(["dreams-release.p12"], sorted(p.name for p in self.directory.iterdir()))

    def test_keytool_recognizes_rsa3072_private_entry(self):
        result = subprocess.run(["keytool", "-list", "-v", "-storetype", "PKCS12", "-keystore", str(self.key),
                                 "-alias", "hexdroid-dreams", "-storepass:env", "DREAMS_TEST_PASSWORD"],
                                env=dict(self.env, DREAMS_TEST_PASSWORD=self.password), capture_output=True, text=True, check=True)
        self.assertIn("PrivateKeyEntry", result.stdout)
        self.assertIn("3072-bit RSA", result.stdout)
        self.assertNotIn(self.password, result.stdout + result.stderr)

    def test_existing_key_is_never_replaced(self):
        before = hashlib.sha256(self.key.read_bytes()).digest()
        result = self.run_helper("create")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("ya existe", result.stderr)
        self.assertEqual(before, hashlib.sha256(self.key.read_bytes()).digest())

    def test_creation_requires_an_interactive_terminal(self):
        directory = self.root / "noninteractive"
        result = self.run_helper("create", DREAMS_SIGNING_DIR=str(directory))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("terminal interactiva", result.stderr)
        self.assertFalse((directory / "dreams-release.p12").exists())

    def test_git_directory_is_rejected(self):
        repo = self.root / "fake-repository"
        (repo / ".git").mkdir(parents=True)
        (repo / ".git/HEAD").write_text("ref: refs/heads/main\n")
        result = self.run_helper("create", DREAMS_SIGNING_DIR=str(repo / "private"))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("repositorio Git", result.stderr)
        self.assertFalse((repo / "private/dreams-release.p12").exists())

    def test_symlinked_directory_is_rejected(self):
        link = self.root / "link"
        link.symlink_to(self.directory, target_is_directory=True)
        result = self.run_helper("copy-secret", DREAMS_SIGNING_DIR=str(link))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("enlace", result.stderr)

    def test_clipboard_contains_exact_base64_without_logging_key(self):
        bindir = self.root / "clipboard-bin"
        bindir.mkdir()
        command = bindir / "termux-clipboard-set"
        command.write_text('#!/bin/sh\ncat > "$DREAMS_TEST_CLIPBOARD"\n')
        command.chmod(0o700)
        clipboard = self.root / "clipboard"
        result = self.run_helper("copy-secret", PATH=str(bindir) + os.pathsep + os.environ["PATH"], DREAMS_TEST_CLIPBOARD=str(clipboard))
        self.assertEqual(0, result.returncode)
        encoded = clipboard.read_text()
        self.assertEqual(self.key.read_bytes(), base64.b64decode(encoded, validate=True))
        self.assertNotIn(encoded, result.stdout + result.stderr)
        self.assertNotIn(self.password, result.stdout + result.stderr)

    def test_backup_opens_chooser_for_existing_encrypted_file(self):
        bindir = self.root / "share-bin"
        bindir.mkdir()
        command = bindir / "termux-open"
        command.write_text('#!/bin/sh\nprintf "%s\\n" "$@" > "$DREAMS_TEST_SHARE"\n')
        command.chmod(0o700)
        share = self.root / "share-args"
        result = self.run_helper("backup", PATH=str(bindir) + os.pathsep + os.environ["PATH"], DREAMS_TEST_SHARE=str(share))
        self.assertEqual(0, result.returncode)
        self.assertEqual(["--send", "--chooser", "--content-type", "application/octet-stream", str(self.key)], share.read_text().splitlines())

    def test_real_apksigner_accepts_key_and_keeps_certificate_between_builds(self):
        if not (OPTIONS.apk and OPTIONS.apksigner):
            self.skipTest("Pass --apk and --apksigner for APK integration")
        digests = []
        for index in range(2):
            signed = self.root / f"test-signed-{index}.apk"
            subprocess.run([OPTIONS.apksigner, "sign", "--ks", str(self.key), "--ks-key-alias", "hexdroid-dreams",
                            "--ks-pass", "env:DREAMS_TEST_PASSWORD", "--key-pass", "env:DREAMS_TEST_PASSWORD",
                            "--out", str(signed), OPTIONS.apk], env=dict(self.env, DREAMS_TEST_PASSWORD=self.password),
                           capture_output=True, check=True)
            result = subprocess.run([OPTIONS.apksigner, "verify", "--print-certs", str(signed)], capture_output=True, text=True, check=True)
            digest = re.search(r"certificate SHA-256 digest: ([0-9a-fA-F]+)", result.stdout)
            self.assertIsNotNone(digest, "Public signature report: " + result.stdout + result.stderr)
            digests.append(digest.group(1))
        self.assertEqual(digests[0], digests[1])


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--apk")
    parser.add_argument("--apksigner", default=shutil.which("apksigner"))
    OPTIONS, remaining = parser.parse_known_args()
    unittest.main(argv=[__file__, *remaining], verbosity=2)
