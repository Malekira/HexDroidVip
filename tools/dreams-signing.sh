#!/usr/bin/env bash
# HexDroid Dreams — local signing-key setup. GPL-3.0-or-later.
# Run on the owner's phone, never in a public CI job. No passwords are printed.
set +x
set -euo pipefail
umask 077

dreams_mode=${1:-help}
case "$dreams_mode" in
  create|copy-secret|backup) ;;
  help|--help|-h)
    cat <<'HELP'
Uso: bash dreams-signing.sh create | copy-secret | backup
  create       Crea UNA clave privada permanente en el almacenamiento privado de Termux.
  copy-secret  Copia el keystore en Base64 al portapapeles para GitHub Secrets.
  backup       Abre el selector de compartir Android para guardar una copia privada.
La contraseña se introduce solo en keytool. No la pegues en chats ni comandos.
HELP
    exit 0 ;;
  *) printf '%s\n' 'Acción desconocida. Usá: create, copy-secret o backup.' >&2; exit 1 ;;
esac

dreams_dir=${DREAMS_SIGNING_DIR:-${HOME:?}/.hexdroid-dreams-signing}
[[ ! -L "$dreams_dir" ]] || { printf '%s\n' 'La carpeta privada no puede ser un enlace simbólico.' >&2; exit 1; }
mkdir -p -- "$dreams_dir"
dreams_dir=$(cd -- "$dreams_dir" && pwd -P)
dreams_scan=$dreams_dir
while :; do
  if [[ -f "$dreams_scan/.git/HEAD" || -f "$dreams_scan/.git" ]]; then
    printf '%s\n' 'No se permite guardar claves dentro de un repositorio Git. Usá la carpeta privada predeterminada.' >&2
    exit 1
  fi
  [[ "$dreams_scan" != / ]] || break
  dreams_scan=$(dirname -- "$dreams_scan")
done
chmod 700 -- "$dreams_dir"
[[ $(stat -c '%a' -- "$dreams_dir") == 700 ]] || { printf '%s\n' 'La carpeta no admite permisos privados. Usá el almacenamiento interno de Termux.' >&2; exit 1; }
dreams_key="$dreams_dir/dreams-release.p12"
dreams_alias=hexdroid-dreams

if [[ "$dreams_mode" == create ]]; then
  [[ ! -e "$dreams_key" && ! -L "$dreams_key" ]] || { printf '%s\n' 'La clave ya existe: se conserva sin reemplazarla. Usá copy-secret o backup.' >&2; exit 1; }
  command -v keytool >/dev/null || { printf '%s\n' 'Falta keytool. En Termux instalá: pkg install openjdk-21' >&2; exit 1; }
  [[ -t 0 && -t 1 ]] || { printf '%s\n' 'Ejecutá create en una terminal interactiva; no pases contraseñas por tuberías ni argumentos.' >&2; exit 1; }
  printf '%s\n' 'Elegí una contraseña larga (20 caracteres o más), guardala en tu gestor y repetila cuando keytool lo pida. No se verá mientras escribís.'
  dreams_stage=$(mktemp -d "$dreams_dir/.new-key.XXXXXX")
  trap 'rm -rf -- "$dreams_stage"' EXIT
  keytool -genkeypair -storetype PKCS12 -keystore "$dreams_stage/dreams-release.p12" \
    -alias "$dreams_alias" -keyalg RSA -keysize 3072 -sigalg SHA256withRSA \
    -validity 10950 -dname 'CN=HexDroid Dreams'
  [[ -s "$dreams_stage/dreams-release.p12" ]] || exit 1
  # Hard-link installation is atomic and refuses to replace any concurrently created key.
  ln -- "$dreams_stage/dreams-release.p12" "$dreams_key"
  chmod 600 -- "$dreams_key"
  printf 'Clave creada en tu dispositivo: %s\nAlias: %s\n' "$dreams_key" "$dreams_alias"
  printf '%s\n' 'La contraseña del almacén y la de la clave son la misma. Guardá una copia con backup antes de configurar GitHub Secrets.'
  exit 0
fi

[[ -f "$dreams_key" && ! -L "$dreams_key" && -s "$dreams_key" ]] || { printf '%s\n' 'No hay una clave local. Ejecutá create primero, o recuperá tu copia existente sin crear otra.' >&2; exit 1; }
case "$dreams_mode" in
  copy-secret)
    command -v termux-clipboard-set >/dev/null || { printf '%s\n' 'Instalá Termux:API desde F-Droid y ejecutá: pkg install termux-api' >&2; exit 1; }
    base64 < "$dreams_key" | tr -d '\r\n' | termux-clipboard-set
    printf '%s\n' 'Copiado al portapapeles. Pegalo únicamente como DREAMS_RELEASE_KEYSTORE_B64 en GitHub Secrets. No lo pegues en el chat.'
    ;;
  backup)
    command -v termux-open >/dev/null || { printf '%s\n' 'La opción backup requiere Termux en Android; el archivo indicado puede copiarse de forma privada en otros sistemas.' >&2; exit 1; }
    termux-open --send --chooser --content-type application/octet-stream "$dreams_key"
    printf '%s\n' 'Guardá el archivo en una ubicación privada recuperable y conservá su contraseña por separado. No lo publiques ni lo subas al repositorio.'
    ;;
esac
