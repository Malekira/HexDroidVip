# HexDroid Dreams 1.0.6 (45)

Orden expresa: «Implementa todo lo pendiente».
Rama `feat/dreams-1.0.6`, desde la entrega 1.0.5 (`699e3cc`).

## Cambios implementados y alcance

- Un solo botón ⌫ junto al mensaje y Retroceso del teclado: recuperar sucesivamente
  hasta diez textos propios de la conversación, sin enviarlos ni borrar el borrador.
  Retirar las dos flechas anteriores. Volver al borrador manteniendo pulsado ⌫.
- Menú visible para editar el topic, incluso si está vacío; respetar conexión,
  pertenencia a la sala, modo +t y permisos. Enviar TOPIC real, sin fingir confirmación.
- Corregir contraste del topic, enlaces, «más/menos» e iconos en modo oscuro y
  temas personalizados. Respetar los códigos IRC recibidos.
- Quitar la sala redundante en JOIN, manteniendo flecha verde y nick/ident@host
  disponible; conservar PART y QUIT con sus colores.
- Conservar el respaldo opcional con historial de 1.0.5, configuraciones y datos,
  WHOIS, NOTICE, notificaciones, scripts personales, GPLv3 y créditos originales.

No hay merge automático ni nuevas claves publicadas; se mantiene la firma debug
elegida. Verificación y descarga registradas al final de este documento.


## Uso en el teléfono

- **Mensajes anteriores**: pulsar el botón ⌫ junto a Mensaje. Cada pulsación
  retrocede una entrada propia, hasta diez de esa conversación. Mantenerlo
  pulsado restaura el borrador. Con el campo vacío, ⌫ del teclado inicia el mismo
  recorrido; tocar o editar el campo devuelve al teclado su borrado normal.
  No se recuperan comandos ni textos con credenciales. El límite de diez es de
  escritura en memoria, no del historial permanente ni del respaldo.
- **Editar topic**: usar el lápiz visible en la barra del topic o **menú general →
  Editar topic** (también disponible si está vacío o se oculta la barra). El menú
  queda deshabilitado si no estás conectado, no figuras como miembro o el modo
  +t exige un rango que no tenés. Sin +t, los miembros pueden editar. Se respeta
  el PREFIX de la red para operadores/halfops; el servidor tiene la decisión final.
  El editor conserva el texto IRC y los espacios interiores; vaciarlo envía una
  eliminación real. No se muestra un cambio hasta recibir la respuesta del servidor.
  Se impiden saltos de línea, NUL y textos que excedan TOPICLEN o la línea IRC.
- **Lectura del topic**: el texto, enlaces, «más/menos» y lápiz usan contraste sobre
  el fondo de su barra, aunque la sala tenga un fondo claro en modo oscuro.
  Los colores IRC explícitos permanecen intactos y no se modifica el texto guardado.
- **Entradas**: `→ JOIN DemoNick01 (ident@host.example.invalid)`, sin repetir #sala.
  Se conservan el gris, las flechas verde/roja/violeta y la ocultación de hosts
  elegida en Ajustes. No se inventan direcciones que el servidor no suministre.

## Verificación local ejecutada

`gradle :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest
--no-daemon --max-workers=4`: BUILD SUCCESSFUL. **102 pruebas JVM**, sin fallos,
errores ni omisiones. Se compiló también la APK de pruebas Android, pero no se
  ejecuta localmente porque el entorno no tiene emulador ni KVM.

GitHub Actions exige las pruebas nuevas de teclado real/IME, topic, permiso y
contraste, así como las de WHOIS, Fases 1–3, Dreams 1.0.4 y respaldo 1.0.5 antes
  de verificar firma y publicar `HexDroid-Dreams-debug`.

## Comprobación manual recomendada

1. Samsung Keyboard: ⌫ con campo vacío recupera mensajes; las diez pulsaciones
   del botón recorren los diez anteriores; editar después borra normalmente.
2. Cambiar de sala/red/PV: las entradas son de la conversación correspondiente.
3. Editar y vaciar topics con permisos; comprobar el rechazo del servidor si la
   red impone restricciones adicionales, sin cambiar visualmente el topic antes.
4. Leer topic, enlaces y controles con modo oscuro/claro y un tema personalizado.
5. Ver JOIN sin nombre de sala y con el host disponible; PART/QUIT conservan formato.
6. Abrir WHOIS flotante, probar menciones y Tab, scripts personales y respaldo
   con historial de 1.0.5; comprobar que siguen disponibles tras actualizar.

La firma sigue siendo debug. La actualización conservando datos requiere la misma
clave que la instalación anterior. Si Android rechaza la firma, no desinstalar ni
borrar los datos de la app anterior: exportar antes configuración, scripts e
historial con las herramientas de esa versión.


## Entrega verificada — 31 de octubre de 2026

- Versión: **HexDroid Dreams 1.0.6 (45)**, código de la APK
  `23e07f7069ad5105d01c35ec06348d5d14e6b249`.
- [Ejecución completa correcta y descarga](https://github.com/Malekira/HexDroidVip/actions/runs/41130954531):
  **SUCCESS**, 6 min 58 s. Ejecutada el 30 de octubre; publicación comprobada de
  nuevo el 31 de octubre al retomar el pedido de reintentar.
- Pruebas unitarias aprobadas, **102 ejecutadas localmente**, sin fallos ni
  omisiones. También aprobaron en CI el comando `connectedDebugAndroidTest` y el
  control obligatorio de todas las pruebas nuevas y anteriores: teclado táctil,
  IME real (borrado normal y por puntos de código), teclado físico, topic,
  permisos, contraste, JOIN, WHOIS y almacenamiento/respaldo.
- Verificación de firma, identificador y nombre aprobada antes de la subida.
- Artifact **HexDroid-Dreams-debug** publicado, **34,5 MB**. Contiene la APK
  `HexDroid-Dreams-debug.apk` y `SHA256SUMS.txt`. Informes en **Phase3-test-reports**.
- Desde Samsung: iniciar sesión en GitHub, abrir la ejecución, ir a **Artifacts →
  HexDroid-Dreams-debug**, descargar el ZIP, extraerlo en Mis archivos y abrir la APK.
- El primer intento [41129042105](https://github.com/Malekira/HexDroidVip/actions/runs/41129042105)
  falló porque una prueba anterior todavía exigía `#sala` en JOIN. Se corrigió
  para exigir el nuevo formato sin sala, conservar las máscaras IPv4/IPv6 y
  mantener el resto de comprobaciones. No se omitieron pruebas: el reintento
  completo enlazado arriba pasó.
- Los archivos originales del historial, preferencias, identificador de paquete,
  scripts personales y respaldo opcional de 1.0.5 se conservan. Las actualizaciones
  siguen sujetas a la compatibilidad de firma explicada arriba; no desinstalar ni
  borrar datos si Android rechaza la firma.
- Rama de revisión `feat/dreams-1.0.6`; no se fusionaron ramas anteriores.
