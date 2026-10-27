El WHOIS deja de abrir una pestaña como destino inicial y aparece en un panel flotante **dentro de HexDroid**, sobre la sala actual. Se puede mover, minimizar, recuperar en su posición y cerrar; tiene desplazamiento vertical, selección de texto y copia. No utiliza permisos de superposición, no abre un PV ni tiene acciones para enviar mensajes.

- Datos organizados por consulta/nick/red: usuario, host, nombre real, canales, servidor, cuenta, away, idle 317, fecha y duración de conexión, solo cuando el servidor los proporciona. Se mantienen 318, timeouts, scripts NUMERIC y destinos simultáneos. Pestaña WHOIS, Status y activa siguen disponibles; NOTICE conserva la Fase 1.
- Opción de notificar menciones con la sala visible, resaltado independiente y color configurable. Se conserva la única ruta de notificación, deduplicación Web Push/msgid, ignores/mute y anclaje al mensaje.
- Botón Tab que comparte el ciclo del teclado físico, sugerencias sin @ desde tres caracteres y reemplazo del token alrededor del cursor conservando el resto.
- Scripts: `$members(#canal)` con prefijos @/+ y `$membernames(#canal)` sin prefijos, con la red del evento/comando.
- `/buenosdias` y `/buenosdias todos`, exclusión del propio nick y bots configurados, y rechazo explícito si no cabe en una línea o no llegó la lista de miembros.

Esta rama está apilada sobre **feat/personal-phase1-whois-notice (PR #1)**: no fusiona la Fase 1 ni hace merge automático en main. Se conservan ID/nombre personales, GPLv3 y créditos. No se incluyen claves privadas ni credenciales.

Validación: pruebas unitarias de rutas, migración, 317, autocompletado, avisos y scripts; prueba instrumentada del panel (sala detrás, arrastre, minimizar/restaurar/cerrar, scroll y copia). Los resultados de la revisión actual están en los checks de GitHub Actions; el workflow genera y verifica la APK debug y la sube como artifact. No se afirma validación manual en redes IRC reales ni en Samsung físico.

Instrucciones completas en [docs/PERSONAL_PHASE2.md](docs/PERSONAL_PHASE2.md). La Fase 3 sigue pendiente. Revisar antes de hacer merge.
