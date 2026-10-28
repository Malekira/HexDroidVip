"""Stable Android signing via an encrypted PKCS12 in an unpublished GitHub draft.

Only the password lives in Actions Secrets. The draft asset never enters Git,
public artifacts or caches. A public certificate fingerprint tag prevents silent
key replacement if the backup is missing or changed.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess

VAULT_TAG = "dreams-signing-vault"
IDENTITY_TAG = "dreams-signing-identity"
KEY_NAME = "dreams-release.p12"
ALIAS = "hexdroid-dreams"


class VaultError(RuntimeError):
    pass


def validate_password(password):
    if not 20 <= len(password) <= 128 or any(not 32 <= ord(c) <= 126 for c in password):
        raise VaultError("DREAMS_SIGNING_PASSWORD debe tener entre 20 y 128 caracteres, sin saltos de línea, acentos ni emojis.")


def keytool(arguments, password):
    env = dict(os.environ, DREAMS_VAULT_PASSWORD=password)
    result = subprocess.run(["keytool", *arguments], env=env, capture_output=True)
    if result.returncode:
        # Never echo password-bearing environment or tool diagnostics to a public log.
        raise VaultError("No se pudo abrir o crear el almacén de firma. Si ya existe, comprobá su contraseña; no se generó una identidad de reemplazo.")
    return result.stdout


def certificate_digest(path, password):
    certificate = keytool(["-exportcert", "-storetype", "PKCS12", "-keystore", str(path),
                           "-alias", ALIAS, "-storepass:env", "DREAMS_VAULT_PASSWORD"], password)
    return hashlib.sha256(certificate).hexdigest()


class GitHubStore:
    def __init__(self, repository, source_sha):
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
            raise VaultError("Repositorio de firma inválido.")
        if not re.fullmatch(r"[0-9a-fA-F]{40}", source_sha):
            raise VaultError("Commit de origen inválido.")
        self.repository = repository
        self.source_sha = source_sha

    def gh(self, arguments, payload=None, missing=False):
        env = dict(os.environ)
        env.pop("GH_DEBUG", None)
        result = subprocess.run(["gh", *arguments], input=json.dumps(payload) if payload is not None else None,
                                capture_output=True, text=True, env=env)
        if result.returncode:
            if missing and "HTTP 404" in result.stderr:
                return None
            raise VaultError("GitHub rechazó una operación del almacén privado. Se necesita contents: write en el job; no se publicó ni reemplazó una firma.")
        return result.stdout

    def api(self, path, payload=None, missing=False):
        args = ["api", f"repos/{self.repository}/{path}"]
        if payload is not None:
            args += ["--method", "POST", "--input", "-"]
        result = self.gh(args, payload, missing)
        return None if result is None else json.loads(result)

    def release(self):
        return self.api(f"releases/tags/{VAULT_TAG}", missing=True)

    def identity(self):
        ref = self.api(f"git/ref/tags/{IDENTITY_TAG}", missing=True)
        if ref is None:
            return None
        if ref["object"]["type"] != "tag":
            raise VaultError("El marcador de identidad no es un tag anotado válido; no se alteró la firma.")
        tag = self.api("git/tags/" + ref["object"]["sha"])
        match = re.search(r"^SHA256=([0-9a-f]{64})$", tag.get("message", ""), re.MULTILINE)
        if not match:
            raise VaultError("El marcador de identidad no contiene una huella válida; no se alteró la firma.")
        return match.group(1)

    def create_release(self):
        notes = ("Almacén privado de firma de HexDroid Dreams. NO PUBLICAR ESTE BORRADOR.\n\n"
                 "El archivo PKCS12 está protegido por DREAMS_SIGNING_PASSWORD. Guardá una copia privada del archivo "
                 "y conservá la contraseña por separado. No borrar este borrador ni el tag dreams-signing-identity. "
                 "No es una versión de la aplicación. No contiene APKs.")
        return self.api("releases", {"tag_name": VAULT_TAG, "target_commitish": self.source_sha,
                                    "name": "Firma privada de Dreams — NO PUBLICAR", "body": notes, "draft": True})

    def upload(self, path):
        # No --clobber: an existing asset can never be overwritten by setup.
        self.gh(["release", "upload", VAULT_TAG, str(path), "--repo", self.repository])

    def download(self, directory):
        self.gh(["release", "download", VAULT_TAG, "--pattern", KEY_NAME, "--dir", str(directory),
                 "--repo", self.repository])

    def pin(self, digest):
        tag = self.api("git/tags", {"tag": IDENTITY_TAG, "message": "HexDroid Dreams signing identity\nSHA256=" + digest + "\n",
                                    "object": self.source_sha, "type": "commit",
                                    "tagger": {"name": "HexDroid Dreams automation",
                                               "email": "41898282+github-actions[bot]@users.noreply.github.com"}})
        self.api("git/refs", {"ref": "refs/tags/" + IDENTITY_TAG, "sha": tag["sha"]})


def prepare_signing(store, password, directory):
    validate_password(password)
    release = store.release()
    identity = store.identity()
    if release is not None and not release.get("draft"):
        raise VaultError("El almacén de firma debe seguir como borrador privado. No se generó ni reemplazó una clave.")
    assets = [] if release is None else [a for a in release.get("assets", []) if a.get("name") == KEY_NAME]
    if len(assets) > 1:
        raise VaultError("Hay varias copias de la firma; no se eligió una automáticamente.")
    if not assets and identity is not None:
        raise VaultError("Falta la copia de una firma ya registrada. Restaurá tu PKCS12 en el borrador privado; no se generará una clave diferente.")

    directory.mkdir(parents=True, mode=0o700, exist_ok=True)
    directory.chmod(0o700)
    path = directory / KEY_NAME
    if path.exists() or path.is_symlink():
        raise VaultError("El directorio temporal debe estar vacío para preparar la firma.")
    try:
        if assets:
            store.download(directory)
        else:
            keytool(["-genkeypair", "-storetype", "PKCS12", "-keystore", str(path), "-alias", ALIAS,
                     "-keyalg", "RSA", "-keysize", "3072", "-sigalg", "SHA256withRSA", "-validity", "10950",
                     "-dname", "CN=HexDroid Dreams", "-storepass:env", "DREAMS_VAULT_PASSWORD",
                     "-keypass:env", "DREAMS_VAULT_PASSWORD"], password)
        if path.is_symlink() or not path.is_file():
            raise VaultError("No se recibió un archivo de firma válido.")
        path.chmod(0o600)
        digest = certificate_digest(path, password)
        if identity is not None and identity != digest:
            raise VaultError("La huella de la clave no coincide con la identidad registrada. No se firmará ni se reemplazará la identidad.")
        if not assets:
            if release is None:
                release = store.create_release()
            # Persist the recoverable encrypted file before recording its identity.
            store.upload(path)
        if identity is None:
            store.pin(digest)
        return path, digest, release.get("html_url", "")
    except BaseException:
        path.unlink(missing_ok=True)
        raise


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser()
    parser.add_argument("--directory", type=Path, required=True)
    args = parser.parse_args()
    password = os.environ.get("DREAMS_SIGNING_PASSWORD", "")
    store = GitHubStore(os.environ.get("GITHUB_REPOSITORY", ""), os.environ.get("GITHUB_SHA", ""))
    path, digest, backup_url = prepare_signing(store, password, args.directory)
    with open(os.environ["GITHUB_ENV"], "a") as out:
        out.write(f"KEYSTORE_FILE={path}\nKEYSTORE_PASSWORD={password}\nKEY_ALIAS={ALIAS}\nKEY_PASSWORD={password}\n")
    with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as out:
        out.write("## Firma permanente de Dreams\n")
        out.write(f"Huella pública SHA-256: `{digest}`\n\n")
        out.write(f"[Copia privada de la firma: guardar el PKCS12 sin publicar el borrador]({backup_url})\n")
        out.write("Conservá la contraseña por separado. La clave nunca se incluye en la APK descargable, artifacts o cachés.\n")
    print("Identidad permanente verificada. El almacén permanece en el borrador privado; solo se distribuirá la APK.")


if __name__ == "__main__":
    try:
        main()
    except VaultError as exc:
        raise SystemExit(str(exc))
