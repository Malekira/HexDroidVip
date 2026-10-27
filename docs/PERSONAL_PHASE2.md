# HexDroid Personal — Fase 2

Plan previo: continuar sobre el commit de la Fase 1 y su workflow, sin merge.
- WHOIS: destino flotante interno por defecto; datos estructurados por consulta/nick,
  arrastre, minimizar/restaurar/cerrar, desplazamiento y copia. Conservar destinos
  simultáneos de la Fase 1 y numeric 317. Sin permisos de superposición ni falsos PV.
- Menciones: notificar también con sala visible mediante opción independiente;
  resaltado opcional y color configurable; conservar anclaje al mensaje y deduplicación.
- Entrada: botón Tab con el mismo motor que el teclado; sugerencias desde tres
  caracteres, sin exigir @ y conservando texto a ambos lados del cursor.
- Scripts: exponer miembros reales por red/sala, incluidos operadores y voces.
- Completar /buenosdias y /buenosdias todos de la Fase 2 original, con exclusiones
  configurables y rechazo explícito si excede el presupuesto de la línea IRC.
- Pruebas relevantes, APK debug por GitHub Actions y PR revisable. Fase 3 pendiente.

## Uso y compatibilidad

WHOIS es ahora un panel Compose interno. Se dibuja encima del chat sin cambiar
la sala seleccionada, no registra una ventana del sistema y no solicita permisos
de superposición. Arrastrar su cabecera lo mueve; «−» lo minimiza; el botón WHOIS
inferior lo recupera en su posición; «×» o «Cerrar WHOIS» lo eliminan. Se conservan
por separado las consultas de distintos nicks/redes. Los paneles y su posición
sobreviven a la recomposición, navegación y rotación mientras el proceso siga vivo.

El cuerpo tiene desplazamiento vertical, selección de texto y «Copiar todo».
Los campos proceden de los numerics del servidor: nick, usuario, host, nombre real,
canales, servidor, cuenta, away, idle 317, fecha de conexión UTC y duración calculada.
No se muestran valores inventados. Las líneas adicionales siguen disponibles.
Se conserva la agrupación hasta 318 y el flush incompleto por timeout/desconexión.
Los hooks NUMERIC pueden silenciar o reescribir cada línea: las líneas alteradas
por scripts se muestran como texto adicional y no se vuelven a extraer datos del
numeric original que el script pueda haber querido ocultar.

En Ajustes → IRC → Presentación, WHOIS permite combinar Panel flotante, Status,
pestaña WHOIS y ventana activa de la misma red. La configuración inicial antigua
Status+pestaña se migra al panel; otras selecciones se conservan. El antiguo destino
PV de WHOIS se migra al panel para evitar abrir conversaciones reales como salida de
una consulta. NOTICE conserva íntegramente sus destinos y el PV real del remitente.
La pestaña WHOIS sigue siendo local/de solo lectura, nunca un supuesto nick WHOIS.

En Ajustes → Resaltados se encuentran «Notificar menciones incluso viendo la sala»,
«Resaltar menciones» y color #RRGGBB. La presentación es independiente del aviso:
desactivar resaltado no desactiva las notificaciones. Siguen aplicándose los permisos
Android, el interruptor global de notificaciones/menciones, mute, ignores y la
política de replay. Se reutiliza la misma ruta de notificación, su deduplicación
con Web Push y su anclaje al mensaje; no se crea una segunda notificación visual.
La opción de avisar en la sala visible está desactivada inicialmente.

El botón Tab usa el mismo ciclo que Tab físico (Shift+Tab recorre hacia atrás).
Las sugerencias aceptan prefijos sin @ desde tres caracteres; también mantienen
las menciones con @. Al elegir se reemplaza solo el token alrededor del cursor,
conservando el texto anterior y posterior, incluidos operadores/voces al buscar.

## Scripts y saludo

- `$members(#canal)` devuelve los miembros reales conocidos en la red del evento
  o comando, separados por espacios, con sus prefijos de rango (@, +, etc.).
- `$membernames(#canal)` devuelve los mismos nicks sin esos prefijos.
- Sin argumento, ambos identificadores usan la sala del evento/comando. Una sala
  desconocida devuelve una cadena vacía; no se consulta otra red ni se inventan usuarios.

Ejemplo `.hex`:

```text
alias integrantes {
  echo -a $members($chan)
  echo -a $membernames($chan)
}
```

`/buenosdias` envía «Buenos días»; `/buenosdias todos` añade los miembros de la sala,
incluidos operadores y voces. Excluye el nick propio y los bots configurados en
Ajustes → Resaltados. Se comprueba el presupuesto de envío antes de enviarlo:
si no cabe en una sola línea, se informa y no se envía una lista truncada ni dividida.
El saludo reutiliza los filtros de entrada, conexión y cifrado de la aplicación.

## Entrega y límites

Se conservan ID personal, GPLv3 y créditos originales. No se añaden secretos ni
claves privadas al repositorio. La rama parte de la Fase 1 sin fusionarla en main.
El workflow de APK debug también se activa en feat/personal-phase2, ejecuta las
pruebas unitarias y las pruebas de interfaz de Android y verifica firma/identidad
antes de publicar el artifact durante 30 días. El ZIP se descarga desde GitHub
iniciando sesión y se extrae con Mis archivos de Samsung para instalar la APK.
La firma debug se crea en el runner y no se publica su clave privada; entre runners
puede cambiar el certificado, por lo que Android puede requerir desinstalar una
APK debug anterior antes de instalar otra (se pierden los datos internos al hacerlo).

La Fase 3 (historial y solicitudes de PV) permanece pendiente.

## Validación local

`gradle :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest --no-daemon --max-workers=4`
terminó con BUILD SUCCESSFUL: 67 pruebas unitarias, 0 fallos, 0 errores y 0 omitidas.
Se compilaron tanto la APK debug como la APK de pruebas instrumentadas. La firma
v2 de la APK debug se verificó con apksigner. En esta máquina no hay /dev/kvm;
la ejecución de las pruebas de interfaz se delega al emulador de GitHub Actions.
No se presenta la compilación de esa APK de pruebas como ejecución en Android.

La construcción de datos numéricos y el saludo se aislaron de las corrutinas grandes
para reducir el trabajo del compilador Kotlin. No cambian el procesamiento de
numerics ajenos a WHOIS ni el envío normal de mensajes.

La revisión adicional pasó 68 pruebas unitarias, sin fallos, errores ni omitidas.
Incluye la regresión de un numeric 317 tardío tras un timeout: actualiza el mismo
panel sin perder campos anteriores, posición o minimización y sin duplicar su ID.
El panel también respeta el espacio ocupado por el teclado dentro de la aplicación.
