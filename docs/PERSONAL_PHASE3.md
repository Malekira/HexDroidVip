# HexDroid Personal — Fase 3

Base: `feat/personal-phase2`, commit `ecd4849`. Rama: `feat/personal-phase3`.
La revisión se dirige a Fase 2; no se fusionan ni se cambian las ramas anteriores.
Se conserva GPLv3, los créditos originales, el nombre HexDroid Personal y el paquete
`com.malekira.hexdroid.personal.debug` de la APK debug. Versión: `1.7.8-personal3.1` (38).

## Temas

En **Ajustes → Apariencia**, debajo de los temas originales, está **Temas personalizados**.
Podés crear, nombrar, guardar, aplicar, editar, duplicar y eliminar temas. El editor
incluye dos paletas independientes, clara y oscura, un selector visual HSV (superficie
táctil de saturación/brillo y barra de tono), controles RGB, código
`#RRGGBB`, vista previa y restauración de colores. No se aplica hasta guardar.
El botón **Restaurar tema original** desactiva el tema personalizado sin borrarlo.
Para cambiar la base clara/oscura, elegí el modo original y luego pulsá **Usar** en
tu tema. Con el modo Sistema la paleta sigue la preferencia de Android.

Se configuran fondos de aplicación, salas/privados, barras (también en Ajustes, redes y scripts), pestañas, botones,
menús, panel lateral, lista de usuarios, mensajes, nicks, avisos, texto de interfaz
y barra de escritura. El texto de los temas se ajusta a un contraste mínimo 4,5:1
cuando la combinación elegida no resulta legible. Los menús recuperan su propia paleta
al abrirse desde una cabecera con un fondo diferente. Los iconos de estado/navegación de
Android también se adaptan al fondo elegido para conservar el contraste. Las preferencias de formato IRC
y los colores explícitos recibidos dentro de los mensajes siguen siendo independientes:
no se reescriben sus códigos mIRC/ANSI. Se conserva la prioridad de los colores
propios de nick y de metadatos que ya admitía la app.

Los temas y la selección se guardan en el DataStore existente y se incluyen en la
copia de ajustes de la aplicación. La paleta de vista previa usa el mismo tema real.

## Formato de escritura persistente

En el botón de formato de la barra superior, el selector existente recuerda el color
de primer plano y fondo mIRC (0–98), negrita, cursiva, subrayado e inversión.
Se comparte entre salas, privados y redes, y se guarda en DataStore para el siguiente
inicio de la app o del teléfono. **Restablecer formato normal** elimina la preferencia
y los controles del borrador actual. El formato aplicado a una selección concreta
sigue siendo propio de esa selección.

Los comandos conservan la barra inicial; no se anteponen controles a `/whois`, `/msg`,
comandos de scripts u otros comandos. El formateador protege los números y comas al
principio del texto para que no se interpreten como parte de un color mIRC. Los temas
no alteran este formato enviado a IRC.

## Historial permanente

En **Ajustes → Registro/Historial → Consultar, buscar, exportar y restaurar historiales**:

- Activar/desactivar el registro con el control existente. Las instalaciones nuevas
  lo tienen activado; se conserva un valor explícitamente desactivado de versiones anteriores.
- Consultar los archivos por conversación. Cada sala y privado dispone de su propio `.txt` UTF-8.
- Buscar palabras, nick o fecha ISO (`AAAA-MM-DD`) dentro de la conversación. Varios términos
  deben aparecer en la misma línea. Los resultados se recorren con **Siguiente**, sin un límite
  total de registros ni cargar todo el archivo en memoria. **Inicio** vuelve a la primera página.
- Seleccionar y copiar texto con el gesto habitual de Android; exportar el TXT de la conversación.
- Seleccionar varias conversaciones o todas y exportar un ZIP compatible.
- Restaurar ese ZIP fusionando líneas que falten, sin sustituir las conversaciones existentes.
- Eliminar una selección o todas (seleccionar todas), siempre mediante confirmación explícita.
- Ver el espacio que ocupan los archivos TXT.

Los mensajes nuevos se guardan en almacenamiento privado, bajo `files/logs/`, en carpetas
`network-<identificador derivado del ID de red>/`, con los nombres normalizados según
CASEMAPPING. La pantalla muestra el nombre legible del perfil correspondiente. Redes con
el mismo nombre tienen carpetas diferentes. Se preservan los registros internos antiguos:
cuando una conversación vuelve a registrar mensajes se traslada su archivo anterior reconocido al directorio nuevo
si el nombre de red identifica un único perfil. Si hay varios perfiles con ese nombre no se
adivina a cuál pertenecía el archivo antiguo; permanece disponible para consulta/exportación.
Las carpetas externas configuradas antes de Fase 3 se conservan como fuente de consulta
antigua; las escrituras nuevas van al almacenamiento privado. Los TXT antiguos pueden
importarse como ZIP de dos niveles `<red>/<conversación>.txt`.

Cada línea conserva fecha, hora, remitente, acciones y contenido sin códigos visuales IRC.
Se mantiene el formato ISO anterior: `AAAA-MM-DD HH:mm:ss<TAB><DemoNick01> contenido`.
Los mensajes con saltos de línea o barras invertidas llevan el marcador `!hexdroid-v3!`
y escapes legibles `\n`, `\r`, `\\`; el lector de scrollback los recupera sin perder contenido.
Acentos, caracteres especiales y emojis se conservan en UTF-8.

Un índice SQLite persistente guarda huellas de identidad de mensajes, separado del scrollback.
Se aprovechan msgid y la huella de líneas reproducidas para no volver a guardar el mismo evento.
Dos mensajes en vivo sin msgid, con el mismo texto y segundo, se conservan como dos mensajes;
la restauración conserva esa multiplicidad y sigue siendo idempotente al importar la misma copia otra vez;
no se descarta uno por una coincidencia temporal. Sin identidad ni hora de servidor fiable,
ningún cliente puede distinguir con certeza todos los reenvíos de mensajes legítimos iguales.
La app mantiene además su tratamiento existente de echo-message, bouncers y CHATHISTORY.

**No hay purga automática ni vencimiento**, incluso al actualizar un ajuste anterior de retención.
El scrollback mantiene su límite independiente para que la pantalla no agote la memoria.
El tamaño real disponible en el teléfono sigue siendo el límite de almacenamiento; un error
de escritura se informa en Status. Un fallo no se presenta como registro exitoso.

Se excluyen los buffers de protocolo/RAW y las conversaciones con servicios de autenticación.
Se omiten comandos de autenticación y se redactan asignaciones reconocibles de contraseña,
token, secret, API key, Bearer y credenciales en URL. Nunca se registran los secretos del almacén
seguro de la aplicación. Como cualquier mensaje de conversación puede contener un secreto
sin etiqueta reconocible, desactivá el registro de conversaciones sensibles y revisá el contenido
antes de compartir exportaciones.

El registro de mensajes cifrados está **desactivado por separado** de forma predeterminada.
Si activás **Guardar conversaciones cifradas como texto descifrado**, esos TXT y sus copias
contendrán el texto legible, aunque el transporte IRC siga cifrado. El ajuste no borra registros
anteriores: eliminarlos requiere una confirmación. La restauración valida todas las rutas y el
UTF-8 antes de fusionar, rechaza archivos ajenos/traversal y vuelve a filtrar credenciales.
Los ZIP son copias de texto, no copias de claves criptográficas.

## Solicitudes de privados

En **Ajustes → Notificaciones → Solicitudes de privados** se activa el sistema (inicialmente
apagado para conservar el comportamiento anterior), se configuran los textos de espera,
aceptación y rechazo, se habilitan/deshabilitan respuestas automáticas y se elige si los
aceptados quedan autorizados entre sesiones. También se accede desde el menú del chat,
**Privados pendientes**, con contador.

- **Aceptar:** recupera los mensajes recibidos, abre un único privado normal y autoriza
  localmente al contacto. No se vuelve a ejecutar el hook TEXT de los scripts al mostrarlos.
- **Rechazar:** descarta los mensajes pendientes y, con respuestas automáticas activadas,
  envía el texto de rechazo mediante NOTICE, con motivo opcional. Aunque se personalice el
  texto, se indica que el privado fue rechazado.
- **Ignorar:** descarta la solicitud sin contestar, sin añadir ningún ignore, ban ni bloqueo.

Los mensajes automáticos son NOTICE para evitar ciclos entre respondedores y no crean un
buffer de privado. Sus ecos `echo-message` se suprimen para no crear privados propios.
Son avisos sin cifrar: no incluyen el contenido del privado, pero los textos
personalizados y el motivo opcional de rechazo tampoco deben contener información sensible. El aviso de espera se reclama una sola vez por contacto/red y no se reenvía
por cada mensaje o reconexión. Las identidades de mensajes evitan duplicar los pendientes.
Una persona puede volver a solicitar un privado después de un rechazo/ignorar; no queda bloqueada.
Los ignores se comprueban antes de los scripts y de la solicitud, y de nuevo antes de responder.
Los privados descifrados +AGE pasan también por las solicitudes; sus avisos de negociación
aparecen en Status mientras el contacto no esté aceptado, sin abrir un PV automáticamente.
Los callbacks y señales de los scripts +AGE se conservan sin volver a disparar TEXT.
Los scripts pueden transformar o suprimir el mensaje antes de que se guarde la solicitud.

La lista y los mensajes pendientes se guardan en SQLite dentro del almacenamiento privado;
los cuerpos se cifran con AES-GCM mediante Android Keystore, incluso si el historial TXT está
apagado. No se borran al navegar, minimizar, desconectar o reiniciar. Al aceptar, solo se escribe
el historial permanente si su política lo permite. Estos pendientes no se incluyen en el ZIP TXT
ni en la copia de ajustes; resolvelos antes de una desinstalación. La clave Keystore pertenece a
esa instalación y no se exporta. **Revocar autorizaciones locales** elimina los permisos locales
recordados; no modifica la lista ACCEPT del servidor.

Las notificaciones Android de solicitudes abren la lista pendiente y no un privado vacío.
El texto del mensaje no se expone en esas notificaciones. Los mensajes repetidos reconocidos
no producen nuevas alertas. Al desactivar el sistema siguen disponibles los pendientes anteriores;
los mensajes nuevos vuelven a seguir el flujo normal de privados.

### Modo +g / CALLERID

Se reconoce `CALLERID=g` en ISUPPORT 005, su retirada `-CALLERID`, y el numeric 718 con el
nick real del intento. Este caso aparece como **bloqueado por el servidor**: no se inventa el
texto que el servidor no entregó. Los numerics de información/error (716/717/718 y respuestas
ACCEPT) continúan visibles en Status.

Solo para redes que anuncian `CALLERID=g` aparecen **Pedir +g**, **Pedir -g** y **Lista ACCEPT**.
Se envían `MODE <nick propio> +g/-g` o `ACCEPT *`; el resultado real lo determina el servidor.
Al aceptar un aviso bloqueado se solicita `ACCEPT +<nick>` y se concede autorización local.
La app indica expresamente que se envió la solicitud, que debe revisarse la respuesta en Status
y que el remitente tendrá que reenviar el mensaje retenido. No se finge una confirmación ni se
crea texto del mensaje bloqueado. Las redes sin esa señal de capacidad o con dialectos distintos
conservan el flujo de comandos/Status existente; no se presupone que admitan ACCEPT.

El aviso real 718 también aparece como **NOTICE local resaltado** en la ventana activa,
si pertenece a la misma red. Se conserva en Status y pendientes; si Status ya está activo,
se muestra una sola vez allí. Los avisos repetidos de una misma solicitud pendiente se
consolidan. No se envía un NOTICE por IRC para mostrarlo, no se crea un PV, no se escribe
en otra red y la copia visual no dispara otra notificación Android. Se respetan los ignores
y la supresión o transformación del numeric por los scripts.

## Autocompletado

Con tres caracteres aparecen los nicks de la red/sala actual, sin exigir `@`.
Una o varias coincidencias se ofrecen en una lista vertical desplazable sobre el compositor.
El borrador no cambia automáticamente al hacer una pausa: se completa solo al tocar un nick
o pulsar Tab táctil/físico (Shift+Tab retrocede).
La lista conserva todas las coincidencias; no se limita a las primeras 16. Se eliminan prefijos
de operador/voz únicamente al insertar el nick y se conserva el texto a ambos lados del cursor.
Al cambiar de sala se reinicia el ciclo y se usa la nueva lista de usuarios.

## Compatibilidad con Fases 1 y 2

Se mantienen WHOIS flotante interno, movimiento/minimización/cierre, idle 317, agrupamiento 318,
destinos simultáneos WHOIS/NOTICE, notificaciones y resaltado de menciones, motor `.hex`,
`$members()`/`$membernames()`, importación de scripts, colores IRC y emojis.
Por la corrección solicitada tras probar Fase 3, se retira la interceptación nativa de
`/buenosdias`: los saludos se resuelven mediante los alias del script `.hex` instalado,
incluidas las variantes con argumentos y mayúsculas/minúsculas. No se añaden saludos fijos
ni se reemplazan los scripts del usuario. En Ajustes se ofrece **Configurar saludos en
Scripts**; sus exclusiones y textos se administran en el script. Se conserva el antiguo
valor de `greetingBots` en los datos para no perder configuraciones, aunque ya no controla
un saludo nativo. Sin un script que lo defina, el comando no produce un saludo integrado.

## Compilación, pruebas y artifact

El workflow `.github/workflows/personal-debug.yml` se ejecuta al publicar en `feat/personal-phase3`:

1. Compila la APK debug y ejecuta las pruebas unitarias.
2. Ejecuta la instrumentación en un emulador Android API 35: WHOIS anterior, persistencia real
   de ajustes/SQLite/Keystore, restauración de historial, flujo real de privados en el ViewModel
   y pantallas de temas, privados y nicks.
3. Comprueba que se ejecutaron las pruebas requeridas; no basta con un proceso sin tests.
4. Verifica firma, paquete y etiqueta, y publica **HexDroid-Personal-debug** mediante
   `actions/upload-artifact`. El ZIP contiene APK y SHA256SUMS; nunca una clave privada.
5. Publica los informes de pruebas y deja enlaces en el resumen de la ejecución.
6. Intenta abrir el PR hacia `feat/personal-phase2`, sin merge. Si la configuración del repositorio
   impide crear PR desde Actions, el error queda explícito en el job independiente del APK.

Comandos locales (requieren JDK 21, Gradle 9.4.1 y SDK 37):

```bash
bash ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest
bash ./gradlew :app:connectedDebugAndroidTest
```

Se separaron los bloques de numerics y BATCH del bucle de recepción para evitar el coste
excesivo de transformación de esa corrutina en Kotlin. Se conserva su orden y contenido, y
las pruebas del protocolo se ejecutan sobre sockets locales.

La segunda orden requiere dispositivo/emulador; compilar los tests Android no equivale a ejecutarlos.
Los resultados efectivos están en los informes del run y en el resumen de entrega. No se afirma
haber probado una red IRC de producción ni un Samsung físico desde este entorno.

## Firma y actualización sin perder datos

Mantener el mismo paquete conserva los datos **si la firma también coincide**. Una APK debug
firmada con otra clave no puede actualizar la instalación anterior: Android lo rechaza. La clave
temporal de los runners anteriores no se publicó ni se puede reconstruir a partir de la APK.
No se promete una actualización directa de aquella APK.

El workflow admite firma debug estable únicamente mediante los secretos de repositorio:

- `PERSONAL_DEBUG_KEYSTORE_B64`: keystore privado codificado en Base64.
- `PERSONAL_DEBUG_KEYSTORE_PASSWORD`: contraseña del almacén y de la clave.
- `PERSONAL_DEBUG_KEY_ALIAS`: alias.

Se configuran en **GitHub → Settings → Secrets and variables → Actions**. No deben pegarse en
issues, PRs, código o logs. Los tres son necesarios juntos. El workflow escribe el keystore con
permisos privados en `RUNNER_TEMP`, lo usa solo para firmar y lo elimina; no lo incorpora al APK,
artifacts o cachés. La contraseña nunca se imprime. Para firmar localmente se emplean las
variables `PERSONAL_DEBUG_KEYSTORE_FILE`, `PERSONAL_DEBUG_KEYSTORE_PASSWORD` y `PERSONAL_DEBUG_KEY_ALIAS`.
La clave debe crearse/guardarse en un entorno de confianza y conservar una copia segura fuera de Git.
No se crea un secreto remoto sin autorización o capacidad administrativa. Si no hay secretos
configurados, el run produce una APK instalable con firma debug temporal y avisa de ello.

**Antes de desinstalar una APK anterior por incompatibilidad de firma, desde el teléfono:**

1. Exportá la copia de ajustes y redes en Ajustes. Si necesitás incluir contraseñas, usá su opción
   de copia cifrada con una contraseña que conserves; una copia sin contraseñas no las recupera.
2. Exportá los historiales de almacenamiento interno a ZIP. Para registros guardados en una carpeta
   elegida antes de Fase 3, copiá también esos TXT mediante Mis archivos.
3. Exportá/copiá los `.hex` desde la sección de scripts; verificá que los archivos estén fuera de
   la carpeta privada de la app. Guardá las claves/identidades de cifrado con las herramientas
   específicas que ya admitía la app: una copia de ajustes no garantiza incluirlas.
4. Si ya usás solicitudes de Fase 3, resolvé o conservá por separado sus mensajes antes de
   desinstalar: su Keystore no es transferible por el ZIP del historial.
5. Verificá que las copias existen en Documentos/Descargas y se pueden abrir. Conservá la APK
   anterior mientras probás la nueva. No desinstales si falta respaldar algún dato importante.
6. Solo si Android confirma un conflicto de firma, desinstalá **HexDroid Personal**, instalá la
   nueva APK y restaurá ajustes, scripts e historial con sus selectores. Volvé a conceder acceso a
   carpetas elegidas anteriormente. HexDroid oficial permanece separado por su applicationId.

Para descargar en Samsung: abrir la ejecución de Actions en Chrome con sesión GitHub iniciada,
bajar a **Artifacts → HexDroid-Personal-debug**, descargar el ZIP, extraer en **Mis archivos** y abrir
`HexDroid-Personal-debug.apk`. Autorizar esa fuente de instalación si Android lo solicita.

## Comprobación manual en el teléfono

- Crear/editar/duplicar/eliminar un tema, probar paleta clara/oscura, reiniciar y verificar selección.
- Elegir fondo/color y estilos de escritura; cambiar sala/red/PV y reiniciar antes de enviar.
- Comprobar que un mensaje remoto con colores IRC conserva esos colores.
- Enviar/recibir texto con acentos y emojis; reconectar con bouncer, buscar, copiar, exportar TXT,
  exportar ZIP y restaurarlo dos veces sin duplicar líneas.
- Probar la política de cifrado apagada/encendida y revisar el contenido real del TXT exportado.
- Desde otro cliente de prueba, iniciar un PV, aceptar/rechazar con motivo/ignorar, cerrar y volver
  a abrir la app con solicitudes pendientes, probar respuestas desactivadas y usuarios ignorados.
- En una red que anuncie CALLERID=g, probar +g y un intento bloqueado; comprobar Status y el
  reenvío tras ACCEPT. No confundir la aceptación local con la confirmación del servidor.
- Escribir un prefijo que coincida con varios nicks con @/+; elegir en la lista y recorrer con Tab.
- Con una sola coincidencia, hacer una pausa y seguir escribiendo una palabra; no debe sustituirse.
- Ejecutar los saludos del script importado, con argumentos y distintas mayúsculas, sin duplicados.
- Elegir colores tocando/arrastrando la superficie del selector y su barra de tono; comprobar hex,
  vista previa, cancelación y paletas guardadas.
- Con +g, comprobar el NOTICE resaltado en una sala/PV/Status de la misma red y su ausencia en otra red.
- Repetir WHOIS 317/flotante, destinos múltiples, NOTICE, mención con sala visible y scripts `.hex`.
