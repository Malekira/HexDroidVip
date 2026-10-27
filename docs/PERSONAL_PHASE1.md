# HexDroid Personal — Fase 1

## Plan previo a la implementación

Inspección: aplicación Kotlin/Compose, AGP 9.2.1, Gradle 9.4.1, SDK 37,
JDK daemon JetBrains 21. El checkout está limpio. WHOIS usa
`pendingWhoisBufferByNick` en IrcCore y `ServerText` pasa por el hook NUMERIC.
NOTICE se procesa en IrcViewModelMessageEvents con filtros de ignorados,
replay, referencias a mensajes y reglas de bouncer/servicios.

Cambios previstos:
- Identidad instalable separada: com.malekira.hexdroid.personal; nombre HexDroid Personal.
- Ajustes persistentes independientes de destinos WHOIS y NOTICE, con selección múltiple.
- Enrutamiento por red, deduplicación de destinos y conservación del hook de scripts.
- Ventanas WHOIS/Notices, PV solo con nick válido, respaldo en Status para otra red.
- Agrupación WHOIS por nick hasta 318; idle 317 legible sin fabricar datos.
- Solicitud opcional WHOIS nick nick y pruebas de regresión.

Validación prevista: pruebas unitarias de enrutamiento y protocolo, suite existente
y APK debug. Si la red sigue bloqueando el JDK, registrar el error real y no
presentar la compilación como completada. Sin cambios al upstream ni merge automático.
GPLv3 y créditos originales se conservan. Fases 2 y 3 pendientes.

## Ajustes y comportamiento implementado

En **Ajustes → IRC → Presentación — WHOIS y NOTICE** hay dos grupos de casillas
independientes. Cada grupo admite Status, ventana propia, PV del nick y ventana
activa de la misma red. Debe quedar al menos una casilla marcada.

WHOIS usa inicialmente Status + ventana WHOIS. NOTICE conserva inicialmente el
enrutamiento original; su quinta casilla permite combinar las reglas existentes
(servicios, sala de origen, respuestas a mensajes, conversación abierta y bienvenida
tras JOIN) con cualquiera de los otros cuatro destinos. Para probar el caso pedido,
seleccionar explícitamente Status + PV + Activa en NOTICE.

Las ventanas locales aparecen como **WHOIS** y **Notices**, tienen claves internas
`*whois*` y `*notices*`, y no son conversaciones IRC. No envían texto como PRIVMSG,
no solicitan historial remoto ni marcadores de lectura para esas claves.

Los destinos se deduplican después de resolver CASEMAPPING. Si Activa pertenece a
otra red, o PV no tiene un nick válido, se usa Status de la red emisora. Los notices
de servidor siempre conservan una copia en ese Status y no abren un falso PV.
Los filtros de ignorados y hooks NOTICE/SNOTICE se ejecutan antes de distribuir
copias. Cada copia mantiene msgid, timestamp, marcas de replay, reply y cifrado para
usar la deduplicación/historial ya existentes. Se conserva la política original de
no generar notificaciones Android por NOTICE; replicarlo no añade alertas.

WHOIS conserva nick e identificador de consulta. Las respuestas de usuarios distintos
se acumulan por separado y se publican como líneas contiguas al recibir 318. Los hooks
NUMERIC se ejecutan una vez por numeric antes de la distribución y pueden modificar
o silenciar líneas; silenciar 318 no impide cerrar la consulta. Los destinos se fijan
al recibir la primera respuesta. Si falta 318, se muestran las líneas recibidas al
cabo de 30 segundos o al desconectar. No se inventa una respuesta final.
Las consultas repetidas al mismo nick conservan el orden de petición; sin etiquetas
específicas del servidor no se puede distinguir un servidor que mezcle dos respuestas
del mismo nick fuera de ese orden.

317 muestra idle en días/horas/minutos/segundos, fecha de inicio y duración de conexión
calculada desde el timestamp suministrado. Los datos ausentes o inválidos no se
sustituyen por cero. La opción **Solicitar idle remoto (WHOIS nick nick)** está
apagada inicialmente: se puede activar en servidores compatibles. Solo transforma
consultas nativas de un único nick; conserva consultas explícitas a servidores,
listas de nicks, aliases y comandos registrados por scripts. También se puede usar
`/whois Nick Nick` manualmente en una red concreta.

## Identidad e instalación

- Release: `com.malekira.hexdroid.personal`.
- Debug: `com.malekira.hexdroid.personal.debug`.
- Nombre del lanzador: **HexDroid Personal**, independiente del idioma.
- Namespace, GPLv3, archivos de licencia, autores y créditos originales conservados.
- Preferencias/datos están separados de la aplicación oficial. No se migran claves
  ni cuentas automáticamente.

Para compilar con JDK daemon JetBrains 21, SDK 37.0 y las herramientas requeridas por
AGP 9.2.1:

```sh
bash ./gradlew :app:assembleDebug :app:testDebugUnitTest --no-daemon --max-workers=4
```

La APK debug se genera en `app/build/outputs/apk/debug/app-debug.apk`. Si se genera
correctamente, instalarla desde Android autorizando la fuente elegida, o con:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

La clave debug es local y queda fuera del repositorio. No sirve como clave de
publicación; no se han añadido secretos ni configurado firma release.

## Comprobación manual pendiente en dispositivo

1. Conectar dos redes. Elegir Status + WHOIS y lanzar `/whois Nick` desde una sala.
   Verificar ambas copias, un solo bloque por nick y cierre 318.
2. En un servidor compatible probar `/whois Nick Nick` y comprobar idle 317; en otro
   que omita 317, comprobar que no aparece un valor inventado.
3. Elegir Status + PV + Activa para NOTICE y recibir uno desde otro cliente estando
   en una sala. Debe verse en tres buffers; con el PV ya activo, solo en dos.
4. Cambiar a la segunda red y repetir: ninguna copia debe aparecer en ella.
5. Recibir un notice de servidor: no debe abrir un PV con su hostname ni con pseudo-nicks
   del bouncer. Probar NickServ, ignorados, un script NOTICE y un hook NUMERIC que
   modifique o silencie una línea.
6. Reconectar con bouncer/historial: comprobar msgid y replay sin copias repetidas ni
   nuevas alertas. Mantener la misma prueba con el enrutamiento original activado.
7. Reiniciar la aplicación y comprobar persistencia independiente de ambas selecciones.

Fases 2 y 3: **no implementadas** en esta entrega.

## Resultado de validación en la nube

Comando ejecutado: `gradle :app:assembleDebug :app:testDebugUnitTest --no-daemon --max-workers=4`
con la distribución Gradle 9.4.1 y el JDK JetBrains 21 del proyecto.

- **BUILD SUCCESSFUL**: APK debug generada.
- **58 pruebas ejecutadas, 0 fallos, 0 errores, 0 omitidas**.
- ReplyRoutingTest: 19; IrcClientRepliesTest: 22; IrcClientSojuTest: 14;
  RedactRawLineTest: 2; ExampleUnitTest: 1.
- Cobertura nueva: destinos combinados, misma/otra red, CASEMAPPING, remitentes
  inválidos y de servidor, replay con/sin msgid, WHOIS intercalados/FIFO, 317,
  extensiones numéricas, ausencia de idle, cierre 318 e IDs únicos al reconectar.
- `aapt2 dump badging`: ID `com.malekira.hexdroid.personal.debug`, etiqueta
  `HexDroid Personal`.
- `apksigner verify --verbose`: firma APK válida, esquema v2, un firmante.
- `git diff --check`: sin errores.

Durante la preparación se corrigieron un import de pruebas y el caso de IDs
reutilizados al reconectar; la prueba de regresión primero reprodujo ese fallo y
pasó tras aplicar el contador compartido entre clientes. La validación final
corresponde a esa corrección.

La herramienta de métricas de Android avisó que `/home/agent/.android` no es
escribible; no impidió compilar ni ejecutar las pruebas. No se ejecutaron pruebas
instrumentadas ni se instaló la APK en un teléfono desde esta máquina.
