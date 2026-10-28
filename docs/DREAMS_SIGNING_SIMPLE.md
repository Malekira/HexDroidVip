# Firma de HexDroid Dreams sin instalar nada

No necesitás Termux, una computadora, copiar claves ni crear tokens personales.
Solo tenés que guardar **una contraseña** en GitHub; Actions se encarga de crear
y conservar la clave de firma permanente.

## 1. Guardar una contraseña en GitHub

Desde Chrome, con tu sesión iniciada, abrí:

[GitHub → Secrets de Actions](https://github.com/Malekira/HexDroidVip/settings/secrets/actions)

Pulsá **New repository secret** y completá:

- **Name:** `DREAMS_SIGNING_PASSWORD`
- **Secret:** una contraseña nueva de entre 20 y 128 caracteres, con letras,
  números o símbolos, sin acentos, emojis ni saltos de línea.

Guardá esa contraseña en tu gestor privado: se necesita para recuperar la firma.
Pulsá **Add secret**. No uses una variable pública ni envíes la contraseña al chat.
No hacen falta los cuatro secretos del método anterior.

## 2. Avisar que está listo

Respondé **«Listo»**, sin pegar la contraseña. Se iniciará una nueva ejecución de
la rama `feat/hexdroid-dreams-release`.

Después de aprobar las pruebas, Actions:

1. Crea una clave RSA de 3072 bits la primera vez, usando `keytool` y esa contraseña.
2. Guarda el almacén PKCS12 protegido en un **borrador privado de GitHub Releases**,
   llamado **Firma privada de Dreams — NO PUBLICAR**. No entra al repositorio Git,
   artifacts públicos ni cachés. Ese borrador no es una versión de la aplicación.
3. Registra únicamente la huella pública del certificado en el tag interno
   `dreams-signing-identity`, sin claves ni contraseñas.
4. Firma y verifica la APK final. Las ejecuciones siguientes descargan y comprueban
   esa misma clave; no crean una clave nueva por compilación.

El permiso `contents: write` del job permite gestionar ese borrador y el marcador
de identidad con el token automático de GitHub. No hace falta un token personal,
no se modifica el upstream ni se fusionan ramas.

Si la contraseña no abre el almacén, falta una copia ya registrada, cambia la
huella o el borrador deja de ser privado, el proceso se detiene. No reemplaza
silenciosamente la firma ni publica una APK con una identidad diferente.

## 3. Descargar y conservar la copia privada

El resumen de la ejecución incluirá un enlace al borrador privado. Abrilo con tu
sesión de propietario y descargá **dreams-release.p12** como respaldo privado.
Guardá ese archivo y la contraseña en lugares recuperables por separado.

Los borradores no publicados de Releases solo son visibles para quienes tienen
permisos de escritura en el repositorio. Además, el PKCS12 está protegido por la
contraseña. **No publiques ese borrador, no borres su archivo y no borres el tag
interno de identidad.** La huella pública puede compartirse; la clave y contraseña no.

El borrador no tiene el vencimiento de los artifacts temporales de Actions. La
copia personal sigue siendo necesaria por si perdés acceso al repositorio.

## 4. Instalar la APK final

En la ejecución de Actions, descargá **Artifacts → HexDroid-Dreams-release**.
Extraé el ZIP en Mis archivos de Samsung y abrí **HexDroid-Dreams-release.apk**.

Dreams se instala junto a la versión de prueba Personal. Mantené Personal mientras
exportás e importás ajustes, scripts e historiales y comprobás que no falta nada.
Ver [DREAMS_RELEASE.md](DREAMS_RELEASE.md) para el traslado de datos y la revisión
de la APK release antes de publicarla como versión definitiva.

## Métodos anteriores y validación

La importación de una clave propia con cuatro secretos sigue disponible como
alternativa avanzada. No se debe mezclar con `DREAMS_SIGNING_PASSWORD`; el workflow
rechaza la mezcla en vez de elegir o cambiar la identidad automáticamente.

`tools/test_dreams_signing_vault.py` prueba generación real con `keytool`, reutilización
de la misma clave, permisos privados, recuperación tras un fallo de metadatos y
rechazo de cambios de identidad/contraseña o pérdidas de la copia. Con `--apk` y
`--apksigner`, también firma y verifica APKs reales con una clave desechable de
prueba. El almacenamiento de GitHub se simula en esas pruebas; la operación real
del borrador se comprobará en Actions después de configurar el secreto.
