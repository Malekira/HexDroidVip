# Crear la firma permanente de Dreams desde Samsung

La clave de publicación se crea **en tu teléfono**, con `keytool` de OpenJDK.
No se genera una clave de producción en un runner temporal ni se envían claves o
contraseñas por chat. No hace falta una computadora ni desinstalar HexDroid Personal.

## 1. Instalar las herramientas

Instalá ambas aplicaciones desde F-Droid, para que sus firmas sean compatibles:

- [Termux](https://f-droid.org/packages/com.termux/).
- [Termux:API](https://f-droid.org/packages/com.termux.api/).

Abrí Termux y ejecutá estos comandos. Los bloques se pueden copiar y pegar:

```bash
pkg update
pkg install -y openjdk-21 curl termux-api
```

Termux:API permite copiar al portapapeles sin mostrar la clave en pantalla. La
herramienta no necesita habilitar acceso general al almacenamiento del teléfono.

## 2. Descargar y verificar el asistente

```bash
curl -fL 'https://raw.githubusercontent.com/Malekira/HexDroidVip/feat/hexdroid-dreams-release/tools/dreams-signing.sh' -o "$HOME/dreams-signing.sh"
printf '%s  %s\n' '86279627685ebc2153aa63f75722465f7af6405becf5e317593e8d0c5d0bde6b' "$HOME/dreams-signing.sh" | sha256sum -c -
```

Continuá solo si la comprobación dice **OK**. Si el archivo no se descarga o el hash
no coincide, no lo ejecutes; no se debe saltar esta comprobación.

## 3. Crear la clave una sola vez

```bash
bash "$HOME/dreams-signing.sh" create
```

Cuando aparezca `Enter keystore password`, escribí una contraseña larga y única,
de al menos 20 caracteres, y repetila en `Re-enter new password`. No se muestra al
escribir. Guardala en tu gestor de contraseñas: no la pegues en este chat ni como
parte de un comando. Esta contraseña también será la de la clave dentro del almacén.

El asistente crea una clave RSA de 3072 bits, con alias **`hexdroid-dreams`**, dentro
de un almacén PKCS12 protegido por esa contraseña. El archivo queda en:

```text
~/.hexdroid-dreams-signing/dreams-release.p12
```

La carpeta es privada y el asistente rechaza ubicaciones dentro de un repositorio
Git. Si ya existe la clave, no la reemplaza. **No crear una clave diferente para
cada versión:** todas las actualizaciones de Dreams deben usar esta misma identidad.

## 4. Guardar una copia privada recuperable

```bash
bash "$HOME/dreams-signing.sh" backup
```

Se abre el selector de compartir de Android. Guardá `dreams-release.p12` en una
ubicación privada que puedas recuperar si perdés el teléfono o desinstalás Termux,
por ejemplo tu almacenamiento personal protegido. No lo publiques ni lo envíes a
otras personas. Guardá la contraseña por separado, en tu gestor, y comprobá que
la copia exista. Conservá también el alias `hexdroid-dreams`.

No alcanza con guardar la clave únicamente en GitHub Secrets: GitHub no permite
volver a descargar el contenido de un secreto. El PKCS12 está cifrado, pero sigue
siendo material privado de publicación.

## 5. Configurar GitHub Secrets desde Chrome

Primero copiá el almacén en Base64 directamente al portapapeles:

```bash
bash "$HOME/dreams-signing.sh" copy-secret
```

No se imprime su contenido ni se crea otro archivo con el secreto. Abrí en Chrome,
con tu sesión de GitHub iniciada:

[Secrets de Actions de HexDroidVip](https://github.com/Malekira/HexDroidVip/settings/secrets/actions)

Pulsá **New repository secret** y creá estos cuatro secretos (no variables públicas):

| Name | Secret |
| --- | --- |
| `DREAMS_RELEASE_KEYSTORE_B64` | Pegá el contenido recién copiado por el asistente. |
| `DREAMS_RELEASE_KEYSTORE_PASSWORD` | La contraseña que elegiste en el paso 3. |
| `DREAMS_RELEASE_KEY_ALIAS` | `hexdroid-dreams` |
| `DREAMS_RELEASE_KEY_PASSWORD` | La misma contraseña del paso 3. |

Después de guardar los cuatro, vaciá el portapapeles y su historial si tu teclado
lo conserva. Para vaciar el contenido actual desde Termux:

```bash
printf '' | termux-clipboard-set
```

No envíes el contenido de los secretos, capturas con ellos ni el archivo PKCS12 al
chat. Para continuar solo hace falta avisar que los cuatro secretos están guardados.

## 6. Generar la APK final

Se puede volver a ejecutar **todos los jobs** de la compilación de Dreams en Actions,
o publicar un nuevo commit de su rama. El flujo exige pruebas antes de firmar y
comprobar el release. El resultado esperado es el artifact
**`HexDroid-Dreams-release`**, que contiene la APK final y comprobaciones públicas,+nunca la clave privada.

La clave se mantendrá como secreto de firma para las siguientes actualizaciones.
La APK final se probará en el Samsung antes de publicarla como versión definitiva.
Ver [DREAMS_RELEASE.md](DREAMS_RELEASE.md) para instalarla junto a Personal y
trasladar los datos sin borrar primero la instalación existente.

## Comprobaciones del asistente

Las pruebas de `tools/test_dreams_signing.py` generan claves **desechables de prueba**
en un directorio temporal y las eliminan. Comprueban permisos privados, contraseña
sin eco, rechazo de sobrescritura y de carpetas Git/enlaces, el contenido Base64,
los argumentos del selector de copia y la compatibilidad con `apksigner`.

Con `--apk` y `--apksigner`, firman dos copias temporales de una APK real y verifican
que ambas son válidas y mantienen el mismo certificado. No se publica ninguna de
esas APK ni se usa esa clave de prueba para Dreams. Las interfaces Android de
portapapeles/compartir se simulan en esas pruebas; el uso real en Samsung lo realiza
el propietario siguiendo los pasos anteriores.
