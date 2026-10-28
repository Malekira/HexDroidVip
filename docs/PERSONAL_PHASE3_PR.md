HexDroid Personal conserva la Fase 2 y añade personalización persistente de temas y formato IRC,
historial privado permanente con consulta/exportación/restauración, solicitudes de privados
persistentes y elección de múltiples coincidencias de nick desde Android.

- Temas nombrados, paletas clara/oscura, selector táctil HSV y RGB/hex, vista previa, edición, duplicación, eliminación y restauración.
- Formato global persistente: colores mIRC, negrita, cursiva, subrayado e inversión; reset y protección de comandos.
- TXT UTF-8 por red/conversación, índice SQLite de deduplicación independiente del scrollback, sin purga automática,
  búsqueda paginada, copia, exportación TXT/ZIP, restauración y eliminación confirmada. Control separado para texto descifrado.
- Privados pendientes con cuerpos AES-GCM/Keystore, aceptar/rechazar/ignorar sin añadir bloqueos, textos configurables,
  autorización por sesión o persistente y notificaciones dirigidas a la lista pendiente.
- CALLERID anunciado y numeric 718: diferencia entre intentos bloqueados y mensajes recibidos; comandos +g/-g/ACCEPT
  condicionados a la capacidad real del servidor, sin simular confirmaciones.
- Los privados +AGE descifrados respetan solicitudes, ignores y política separada de registro; sus avisos de negociación no crean un PV pendiente.
- Cabeceras, pestañas y menús mantienen su propia paleta y contraste, incluidas combinaciones de fondos opuestos.
- Lista vertical de coincidencias sin límite de 16 y ciclo Tab compartido; ninguna coincidencia se inserta sin elección explícita.
- Los saludos se resuelven por scripts `.hex`, sin interceptación nativa de `/buenosdias` ni sustitución de scripts importados.
- Los intentos de PV bloqueados por +g se muestran como NOTICE local resaltado en la ventana activa de su red,
  además de Status y pendientes, sin duplicar buffers ni enviar un NOTICE a IRC.
- Workflow de APK debug con pruebas unitarias e instrumentación Android, comprobación de tests ejecutados,
  verificación de firma/identidad y artifact descargable. Firma estable opcional mediante GitHub Secrets, sin material privado en Git.

Base: `feat/personal-phase2`. No hacer merge automático. Identidad debug: `com.malekira.hexdroid.personal.debug`.
GPLv3 y créditos originales conservados. Ver `docs/PERSONAL_PHASE3.md` para ajustes, protocolo, privacidad,
limitaciones de firma y respaldo, instalación en Samsung y comprobaciones manuales.

Validación local: APK debug y APK de instrumentación generadas; 78 pruebas unitarias ejecutadas, sin fallos ni omisiones.
La instrumentación se exige en el emulador de GitHub Actions antes de publicar el APK.

Los resultados efectivamente ejecutados aparecen en el resumen y los informes del run de GitHub Actions.
La instrumentación en emulador no sustituye pruebas de una red de producción ni de un Samsung físico.
Una firma debug nueva puede impedir actualizar la APK anterior: respaldar ajustes, scripts, historial y claves
con sus herramientas antes de considerar una desinstalación. Los pendientes Keystore no están en el ZIP TXT.
