# HexDroid Dreams — preparación de la versión final

Base validada por el usuario: HexDroid Personal `1.7.8-personal3.1` (38), commit
`887d9ba`. Rama de preparación: `feat/hexdroid-dreams-release`.

## Identidad y alcance

- Nombre visible: **HexDroid Dreams**, también en los recursos de otros idiomas.
- Versión preparada: **1.0.3**, código interno **42**.
- Paquete final existente: `com.malekira.hexdroid.personal`.
- Paquete de prueba existente: `com.malekira.hexdroid.personal.debug`.
- Se conservan el código probado, la GPLv3 y los créditos originales.

El nombre comercial cambia; los identificadores de paquete permanecen como estaban
configurados. La APK final se instala junto a la APK de prueba, lo que permite
trasladar datos sin borrar primero la instalación que funciona. Android mantiene
separados los datos de ambos paquetes: no se copian por cambiar el nombre visible.

Este cambio prepara la publicación. No fusiona PRs, no crea un tag de versión ni publica una
GitHub Release automáticamente. El método simple de firma usa únicamente un borrador privado
y un tag interno con la huella pública del certificado. Una compilación debug con el nuevo nombre sigue
siendo una versión de prueba, no una APK final firmada con identidad estable.

## Firma permanente: requisito pendiente de configuración

Las versiones finales deben firmarse siempre con la misma clave privada. Una
versión nueva usará además un código interno mayor para poder actualizarse. La
clave temporal de las compilaciones debug anteriores no se puede recuperar desde
una APK; no debe confundirse con una identidad de publicación permanente.

El propietario eligió una clave nueva y un proceso sin instalar aplicaciones.
Seguir [DREAMS_SIGNING_SIMPLE.md](DREAMS_SIGNING_SIMPLE.md): solo se configura
`DREAMS_SIGNING_PASSWORD` en GitHub Secrets. Actions crea la clave una vez, guarda
su PKCS12 protegido en un borrador privado y comprueba su huella en cada ejecución.
No requiere Termux ni tokens personales. Conservar la copia y contraseña fuera de Git.
No enviar claves ni contraseñas por chat, PRs, issues o logs.

En el repositorio, configurar mediante la interfaz de GitHub:
**Settings → Secrets and variables → Actions → New repository secret**.

Método simple: un único secreto **`DREAMS_SIGNING_PASSWORD`**, de 20 a 128 caracteres,
sin acentos, emojis ni saltos de línea. El job gestiona el borrador privado con
`contents: write`, usando el token automático de GitHub. El archivo de firma nunca
se incluye en los artifacts de APK ni cachés; el tag `dreams-signing-identity` solo
contiene una huella pública. No publicar el borrador de firma. Descargar una copia
privada del PKCS12 y conservar la contraseña por separado.

Como alternativa avanzada, para importar una clave ya existente se mantienen los
cuatro secretos siguientes. No mezclarlos con el método simple:

| Secreto | Contenido |
| --- | --- |
| `DREAMS_RELEASE_KEYSTORE_B64` | Keystore privado codificado en Base64. Base64 no cifra la clave: debe ser un secreto, nunca un archivo de Git. |
| `DREAMS_RELEASE_KEYSTORE_PASSWORD` | Contraseña del almacén. |
| `DREAMS_RELEASE_KEY_ALIAS` | Alias de la clave de firma. |
| `DREAMS_RELEASE_KEY_PASSWORD` | Contraseña de esa clave. Puede coincidir con la del almacén si así se creó. |

Los cuatro son obligatorios juntos. La página de Secrets se puede abrir desde
Chrome en el Samsung; estos valores deben proceder de una clave guardada de forma
segura. La guía de Termux queda como alternativa opcional; no es necesaria para
el método simple. La clave nueva se crea en Actions solo después de configurar
la contraseña y aprobar las pruebas; nunca se publica su contenido privado.

No basta con guardar la clave únicamente en Secrets: GitHub no permite descargar
su contenido después. Hay que conservar una copia privada recuperable por separado.

## Compilación y descarga desde Actions

El workflow `personal-debug.yml`, ahora mostrado como **HexDroid Dreams - APK**,
acepta los commits de esta rama. Primero exige las pruebas unitarias y Android de
las fases anteriores y de las cuatro correcciones ya validadas.

- **Sin firma configurada:** publica `HexDroid-Dreams-debug` y avisa que la APK
  final está pendiente. El job de release queda omitido, sin producir un APK sin
  firmar ni usar como sustituto la firma debug temporal.
- **Con el secreto simple o una firma importada completa:** después de aprobar las pruebas, compila el release con
  R8/reducción de recursos y `-PrequireSigning=true`, verifica firma, nombre,
  paquete y que no sea depurable, y publica **HexDroid-Dreams-release**.
- **Con firma parcial:** falla la comprobación e indica que deben configurarse los
  cuatro secretos; no se publica una APK final.

Durante el build, el keystore solo se escribe con permisos privados en `RUNNER_TEMP` y se elimina
incluso si falla la compilación. No se sube a artifacts ni a cachés. La caché de
configuración de Gradle está desactivada durante la firma. El artifact final solo
contiene la APK, SHA256SUMS y datos del certificado público en SIGNATURE.txt.
En el método simple se conserva únicamente la copia protegida del PKCS12 en el
borrador privado de Releases, para poder reutilizarla después de limpiar el runner.

Tras configurar los secretos, volver a ejecutar todos los jobs de la ejecución de
esta rama en Actions, o publicar un nuevo commit. El artifact dura 30 días. Para
una descarga permanente se podrá publicar una GitHub Release después de revisar
la APK firmada y decidir la integración de las ramas.

En Samsung, iniciar sesión en GitHub, abrir la ejecución y descargar
**Artifacts → HexDroid-Dreams-release**. Extraer el ZIP en Mis archivos y abrir
`HexDroid-Dreams-release.apk`. Autorizar esa fuente si Android lo solicita.

## Trasladar los datos desde la versión de prueba

1. Mantener instalada HexDroid Personal mientras se prepara y prueba Dreams.
2. Desde la versión anterior, exportar ajustes/redes; usar copia cifrada si se
   necesita trasladar contraseñas y conservar su contraseña fuera de la app.
3. Exportar historiales a ZIP y scripts `.hex`. Comprobar que los archivos estén
   fuera del almacenamiento privado y se puedan abrir desde Documentos/Descargas.
4. Respaldar claves de cifrado con las herramientas específicas de la app. Resolver
   o conservar por separado las solicitudes pendientes: sus cuerpos cifrados con
   Android Keystore no se trasladan mediante el ZIP TXT ni la copia de ajustes.
5. Instalar la APK release de Dreams sin desinstalar la de prueba. Restaurar ajustes,
   scripts e historial desde sus selectores Android y volver a conceder accesos a
   carpetas elegidas anteriormente.
6. Verificar redes, scripts, temas, formato, registros y cifrado. No borrar la
   instalación anterior hasta comprobar que no falta ningún dato importante.

Las próximas APK release firmadas con esa misma clave y el mismo paquete deben
actualizar Dreams conservando sus datos. No prometer actualización directa de una
APK de prueba con otro paquete o firma.

## Verificación antes de publicar como final

Las compilaciones y pruebas efectivamente ejecutadas se informan en la entrega y
en Actions. Una compilación release local sin secretos es intencionalmente unsigned:
sirve para validar R8/recursos, pero no es una APK instalable para distribuir.

Probar la APK release firmada en el teléfono antes de publicar una versión final:
nombre e icono, arranque/restauración de datos, redes, temas, scripts de saludos,
Tab sin sustitución automática, WHOIS, menciones, avisos +g e historial/exportación.
La instrumentación de Actions se ejecuta sobre debug; la prueba física de release
comprueba además el comportamiento tras la optimización de R8.
