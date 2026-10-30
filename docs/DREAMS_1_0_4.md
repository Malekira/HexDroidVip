# HexDroid Dreams 1.0.4 (43)

Trabajo autorizado el 30 de octubre de 2026 mediante «realiza los pendientes y
compila una nueva version». Rama: `feat/dreams-1.0.4`.

## Funciones y ubicación

- Hacer accesible la URL HTTPS de la foto propia por red, conservándola y
  publicándola mediante los metadatos que el servidor admita. No inventar
  soporte en redes sin esa capacidad. Ampliar fotos completas al tocarlas,
  con estado de carga, error y reintento.
- Añadir `/j sala` como equivalente de `/join #sala`, conservando claves,
  listas de canales y prefijos válidos de la red.
- Exponer una opción nativa de sonido para menciones en la sala visible,
  reutilizando la notificación única existente y respetando silencios e historial.
- Añadir controles nativos por red de los modos de usuario `+g` y `+R`, con
  estado confirmado por el servidor, sin reintroducir el addon retirado.
- Permitir avisos de intentos de PV como NOTICE resaltado en la conversación
  activa de la misma red, ventana flotante interna o ambos. Conservar Status,
  ignores y scripts del usuario; no inventar el contenido bloqueado.
- Añadir recuperación táctil del mensaje anterior con flechas, edición antes
  del envío y restauración del borrador al avanzar. No reutilizar Borrar para
  evitar acciones ambiguas. No guardar comandos con secretos en ese historial.
- Mantener el paquete, datos, créditos y firma debug elegida. Ejecutar pruebas
  y publicar la APK mediante GitHub Actions, sin fusionar ramas.

## Uso en el teléfono

- **Ajustes → Chats y mensajes → Privados y perfil por red**: elegir la red,
  cambiar `+g`/`+R` cuando estén disponibles y guardar o vaciar la URL de la foto.
  Una URL no sube un archivo: debe apuntar a una imagen ya alojada en Internet.
- **Chats y mensajes → Avisos de intentos de privado**: activar NOTICE, aviso
  flotante o ambos. Status conserva las respuestas reales; los avisos flotantes
  se cierran sin cerrar la sala. Se muestran únicamente en la red correspondiente.
- **Ajustes → Resaltado → Sonar al mencionarme en la sala visible**: activar el
  sonido. Requiere notificaciones y avisos de menciones habilitados; respeta el
  volumen, los canales de Android y No molestar. El mismo mensaje no genera una
  notificación adicional por sonido. Se mantiene el enlace al mensaje concreto.
- Tocar una foto en la lista de usuarios o la ficha del nick para ampliarla sin
  recortar sus bordes. Cerrar vuelve a la conversación. Si falla, Reintentar vuelve
  a solicitarla. Las vistas previas deben estar activadas; no se descargan avatares
  externos desde perfiles con proxy. Hasta cuatro descargas simultáneas, 2 MiB por
  imagen, miniaturas de hasta 256 px y ampliaciones de hasta 1024 px; tres
  redirecciones HTTPS como máximo, sin aceptar HTTP ni omitir validación TLS.
- Usar `/j sala`, `/j #sala`, `/j sala,otra clave1,clave2`. No se cambia `/join`.
- Flecha arriba junto al campo de escritura: recuperar el mensaje anterior.
  Flecha abajo durante la recuperación: avanzar o restaurar el borrador. Nada se
  envía hasta pulsar Enviar. Se recuerdan hasta 50 entradas de escritura por
  conversación durante la pantalla de chat; este límite no afecta al log permanente.
  No se recuerdan comandos ni entradas con contraseñas o tokens identificables.

## Límites reales del protocolo

El modo `+g` restringe privados y `+R` suele exigir identificación en servicios;
las excepciones y el significado exacto los define cada red. Los controles no
fingen éxito: se actualizan con MODE/221 y solo se habilitan con soporte anunciado
y estado conocido. No se fuerzan modos al reconectar. Un rechazo del servidor
permanece visible en Status.

El aviso de intento bloqueado usa el numeric real 718. Algunas redes no informan
al destinatario de intentos bloqueados por `+R` o `+g`: no se pueden detectar ni
leer esos mensajes. Para un privado nuevo realmente recibido, se puede mostrar
el aviso sin sustituir la conversación normal. No se agregan respuestas automáticas,
listas de pendientes ni scripts; los scripts importados mantienen sus hooks.

La foto se publica mediante `draft/metadata-2` cuando el servidor lo admite.
Sin esa capacidad se conserva localmente, pero no se promete que otros usuarios
la vean. Se guarda también el borrado de la URL para reenviarlo al reconectar.

## Verificación

- Compilación local debug y compilación de la APK de pruebas Android: correctas.
- 88 pruebas JVM ejecutadas: cero fallos, errores u omisiones.
- Las pruebas nuevas incluyen JOIN real contra servidor IRC local de prueba,
  respuestas MODE/221, publicación y borrado de metadatos, persistencia real de
  ajustes/URL, destinos e ignores, notificación Android con canal sonoro y
  deduplicación, recuperación táctil del borrador y ampliación/reintento de fotos.
- Las pruebas Android se ejecutaron y aprobaron en el emulador de GitHub Actions,
  junto con las regresiones anteriores y la comprobación de que cada prueba
  requerida fue encontrada y aprobada. El job debug final terminó correctamente.
- APK debug publicada como **HexDroid-Dreams-debug** (34,4 MB), tras las
  comprobaciones obligatorias de firma, paquete y nombre. También están publicados
  los informes **Phase3-test-reports**.
- [Ejecución verificada y descarga](https://github.com/Malekira/HexDroidVip/actions/runs/41102447808),
  código compilado `7302c375fa1a1ceb58eb85ad520f66a8b3552cfa`.

## Instalación y revisión manual

En la ejecución de GitHub Actions, descargar **Artifacts → HexDroid-Dreams-debug**,
extraer el ZIP con Mis archivos de Samsung y abrir `HexDroid-Dreams-debug.apk`.
Paquete conservado: `com.malekira.hexdroid.personal.debug`; versión 1.0.4 (43).
No se elimina el historial ni se cambia el formato de datos existente.

Comprobar en el teléfono: la URL propia en una red compatible y en otra sin
metadatos; fotos verticales/horizontales y enlace fallido; `/j` con y sin `#`;
sonido con volumen/notificaciones habilitados; activar/desactivar `+g`/`+R` y
revisar la confirmación; avisos en ambos destinos y con otra red visible;
recuperar, editar y reenviar un mensaje manteniendo el borrador.

La firma continúa siendo debug por elección del usuario. Una clave temporal de
GitHub Actions puede impedir actualizar una APK anterior. Si Android rechaza la
firma, **no desinstalar la app anterior**: sus datos privados y claves cifradas
no se recuperan solo con un TXT. No se publican claves privadas ni se fusionan ramas.
