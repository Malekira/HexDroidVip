# Pedidos pendientes de HexDroid Dreams

Estado: entregado hasta 1.0.6; los nuevos pedidos están implementados en 1.0.7
y en verificación Android antes de publicar la APK.
Rama de trabajo: `feat/dreams-1.0.6`, desde la entrega de historial y respaldo.

## Pendientes posteriores a 1.0.6: autorización «Aplica todos los pendientes»

Rama `feat/dreams-1.0.7`, versión 1.0.7 (46), basada en 1.0.6.

- Herramientas reales de formato IRC en el editor de topic existente y retirada
  del lápiz junto a la barra; colores de texto/fondo, estilos y vista previa.
- Apartado Scripts independiente en Ajustes que abre el gestor real.
- URL de foto en el perfil de identidad y en el respaldo JSON/ZIP, con migración
  de las direcciones guardadas y actualización de foto al restaurar.
- PART sin repetir la sala, conservando colores, máscara y motivo.
- Recuperación de diez entradas únicamente desde ↤; el teclado borra normalmente.
- Desactivar autocorrección del campo y verificar composición sin duplicados,
  conservando Tab y sugerencias por elección explícita.
- Compilación local correcta; 106 pruebas JVM aprobadas. Android y publicación
  pendientes de la ejecución de GitHub Actions.
- Detalle: [DREAMS_1_0_7.md](DREAMS_1_0_7.md). Sin fusiones automáticas.

## Todos los pendientes: autorización «Implementa todo lo pendiente»

- Recuperación con un único botón ⌫ y Retroceso del teclado: hasta diez entradas
  propias por conversación; retirar las dos flechas y conservar el borrador.
- Edición visible del topic en la barra y menú, conexión/membresía/permisos reales,
  modo +t, edición de topic vacío y eliminación real del texto.
- Contraste del topic y controles en modo oscuro y temas personalizados.
- JOIN sin repetir la sala, conservando nick, ident@host disponible y formato.
- Se conserva el respaldo opcional con historial de 1.0.5 y las funciones anteriores.
- Compilación local correcta, 102 pruebas JVM aprobadas y pruebas Android
  ejecutadas y aprobadas en GitHub Actions, sin omitir comprobaciones.
- [APK debug 1.0.6 publicada](https://github.com/Malekira/HexDroidVip/actions/runs/41130954531):
  artifact **HexDroid-Dreams-debug** (34,5 MB). Verificada el 31 de octubre al
  retomar «Reintenta todo». Ejecución completa **SUCCESS**.
- Detalles: [DREAMS_1_0_6.md](DREAMS_1_0_6.md). Sin fusiones automáticas.

## Cambio autorizado: historial y respaldo opcional

- Orden expresa «Implementa»: conservar el historial del dispositivo y preguntar
  si se quiere incluir en cada respaldo.
- Rama separada `fix/dreams-history-backup`, basada en la versión publicada 1.0.4.
- Nueva opción en Ajustes → Copia de seguridad y restauración → Exportar.
- La copia incluye redes, ajustes e historial si se elige; restaurar combina las
  conversaciones sin borrar las existentes ni duplicar las líneas recuperadas.
- Sin vencimiento ni borrado automático; el original no se mueve al exportar.
- Detalles y comprobaciones: [DREAMS_HISTORY_BACKUP.md](DREAMS_HISTORY_BACKUP.md).
- Entrega: **HexDroid Dreams 1.0.5 (44)**; 93 pruebas JVM locales aprobadas,
  pruebas Android y controles obligatorios aprobados en GitHub Actions.
- [APK debug publicada](https://github.com/Malekira/HexDroidVip/actions/runs/41124129174),
  artifact **HexDroid-Dreams-debug** (34,5 MB). Reintento tras un fallo de arranque
  del emulador, sin omitir pruebas ni modificar funcionalidades.
- Esta entrega 1.0.5 no incluyó Retroceso, topics y avisos JOIN; se completaron
  después en 1.0.6, según el registro anterior.

## Registro de entrega — 30 de octubre de 2026

- Versión entregada: **HexDroid Dreams 1.0.4 (43)**; código compilado `7302c37`.
- 88 pruebas JVM ejecutadas localmente, sin fallos ni omisiones; pruebas Android
  y comprobaciones obligatorias aprobadas en GitHub Actions.
- [Ejecución correcta y APK publicada](https://github.com/Malekira/HexDroidVip/actions/runs/41102447808):
  artifact **HexDroid-Dreams-debug** (34,4 MB).
- Funciones, ubicación de ajustes, límites de la red y pruebas manuales en
  [DREAMS_1_0_4.md](DREAMS_1_0_4.md). Sin fusionar ramas.
- No se iniciarán nuevas modificaciones sin otra indicación expresa del usuario.

## Registro de entrega — 29 de octubre de 2026

- Versión entregada: **HexDroid Dreams 1.0.3 (42)**, commit `6243360`.
- Cambios documentados en [DREAMS_1_0_3.md](DREAMS_1_0_3.md).
- Pruebas ejecutadas: 82 pruebas JVM y las pruebas Android requeridas, aprobadas.
- APK debug firmada y publicada como artifact **HexDroid-Dreams-debug** en
  [GitHub Actions](https://github.com/Malekira/HexDroidVip/actions/runs/40925673513).
- Firma elegida por el usuario: debug. Si Android rechaza la actualización por
  firma, conservar la instalación anterior para proteger sus datos.
- Sin fusionar ramas. A petición «Anotalo», se registra la entrega sin nuevos
  cambios en la aplicación.

## Duplicar red

Añadir una opción «Duplicar red» para crear una copia editable de la configuración
existente, con otro nombre y sin modificar la red original.

## Eventos de sala

- Mostrar los cambios de nick.
- Ocultar los avisos de cambios de ident, host virtual y cuenta autenticada.
- Mostrar JOIN, PART y QUIT con el texto en gris.
- Usar flecha verde en JOIN, roja en QUIT y violeta en PART.
- Mostrar nick e ident@host completo, o la IP cuando el servidor la proporcione,
  sin inventar ni intentar descubrir direcciones ocultas por la red.
- Conservar el funcionamiento de la autenticación y de WHOIS.

## Historial permanente sin límite de líneas

- El log guardado en disco no debe tener un máximo de líneas ni caducidad o
  borrado automático; el límite real es el espacio disponible.
- La captura muestra «Líneas máximas de historial: 5000», una etiqueta confusa.
  En el código actual ese campo modifica `maxScrollbackLines`: los mensajes
  cargados en la conversación, no el archivo de historial permanente.
- Implementado en 1.0.1: aclarar en la interfaz la separación entre mensajes cargados en
  pantalla y archivo permanente, para que el valor de scrollback no parezca un
  límite del log; conservar consulta y exportación del historial completo.
- La aclaración de interfaz no altera los archivos ni elimina conversaciones.

## Disposición del selector de color

- Colocar el selector de color debajo de la ventana de ejemplo/vista previa.
- Usar un selector circular, más pequeño y cómodo para la pantalla del teléfono.
- Mantener código hexadecimal y ajuste de brillo/RGB.

## Nuevo pedido: solicitudes de privados mediante script

- El usuario indica: «La solicitud de PV es un script».
- Gestionar las solicitudes de privados mediante un script `.hex`.
- Implementado en 1.0.2: política mediante script editable, con migración y conservación de pendientes.

## Nuevos pedidos: color de menciones y textos de Ajustes

- Añadir un selector visual para el color de las menciones; el campo hexadecimal
  por sí solo no es suficiente. Conservar la entrada hexadecimal como alternativa.
- Usar textos genéricos en Ajustes, sin mencionar nicks particulares ni personas
  concretas. Esto no implica eliminar el resaltado del nick propio.
- Implementados en 1.0.2: rueda de menciones, vista previa y etiquetas genéricas.

## Nuevo pedido: ubicación de Tab y herramientas de sala

- Mover Tab al extremo izquierdo de la barra de escritura, antes del campo de
  mensaje, como un botón cuadrado pequeño con icono de flecha de tabulación (↹).
- Mover el botón de herramientas de la sala a la barra superior derecha,
  inmediatamente a la izquierda del menú general.
- Conservar la función de completar nicks y el acceso a las herramientas.
- Implementado en 1.0.2: Tab izquierdo con icono y herramientas junto al menú superior.

## Actualización 1.0.3: botón de restaurar copia de seguridad

- Corregir el botón «Restaurar copia de seguridad»: en la captura queda demasiado
  estrecho y divide palabras en muchas líneas, deformando el botón.
- Darle ancho suficiente y adaptar la disposición al teléfono; si ambos botones
  no caben, colocar Restaurar debajo de Exportar con ancho completo.
- Conservar la función y las confirmaciones de restauración.
- Implementado en 1.0.3 por autorización «Bien, actualiza la app».

## Actualización 1.0.3: aspecto del icono de ajustes de sala

- Mostrar el icono de herramientas/ajustes de sala en negro y sin fondo,
  acorde con los demás iconos de la barra superior de la captura.
- Quitar el fondo naranja/degradado y armonizar su tamaño y alineación.
- Mantener su ubicación junto al menú general y su función actual.
- Implementado en 1.0.3 por autorización «Bien, actualiza la app».

## Actualización 1.0.3: quitar privados pendientes del menú general

- Retirar la entrada fija «Privados pendientes (n)» del menú general de la sala.
- Pedido actualizado: quitar también el script de solicitudes de PV y sus accesos; ya no mantener esa alternativa.
- No eliminar las solicitudes guardadas ni modificar las decisiones sobre ellas.
- Implementado en 1.0.3 por autorización «Bien, actualiza la app».

## Actualización 1.0.3: retirar también el script de solicitudes de PV

- Quitar `solicitudes-pv.hex`, añadido por Dreams, y sus accesos de pendientes y
  configuración. No volver a instalarlo o activarlo automáticamente al iniciar.
- Este pedido reemplaza la indicación anterior de conservar el acceso por script.
- Conservar los demás scripts del usuario y no borrar solicitudes guardadas ni
  otros datos personales como parte de retirar la función de la interfaz.
- Implementado en 1.0.3 por autorización «Bien, actualiza la app».

## Actualización 1.0.3: quitar el apartado de saludos

- Retirar de Ajustes las referencias a saludos y sus exclusiones, incluido el
  acceso «Configurar saludos en Scripts».
- Confirmado por el usuario: los saludos quedan únicamente en su script independiente; no incorporar comandos o scripts de saludo integrados.
- No borrar archivos de scripts importados por el usuario al limpiar estos
  textos y accesos de la interfaz.
- Implementado en 1.0.3 por autorización «Bien, actualiza la app».

## Actualización 1.0.3: bienvenida y agradecimientos

- Mostrar «HexDroid Dreams» de forma visible en la pantalla de bienvenida.
- Incluir los créditos y agradecimientos correspondientes a los autores originales
  de HexDroid y a las contribuciones y dependencias acreditadas en el proyecto.
- Conservar los créditos originales y la licencia GPLv3; usar la información real
  del repositorio, sin inventar autores o atribuciones.
- Implementado en 1.0.3 por autorización «Bien, actualiza la app».


## Actualización 1.0.4 autorizada el 30 de octubre de 2026

Indicación expresa: «realiza los pendientes y compila una nueva version».
Rama de revisión: `feat/dreams-1.0.4`; no se modifica la rama anterior.

- Foto propia mediante URL HTTPS por red, guardada localmente y compartida solo
  cuando el servidor admite metadatos. Tocar una foto abre la imagen completa,
  con carga, error y reintento. Mejora de descarga con límites y redirecciones HTTPS.
- `/j sala` equivale a `/join #sala`, conservando claves y prefijos de la red.
- Opción nativa de sonido al recibir menciones en la sala visible.
- Controles nativos `+g`/`+R`, con soporte anunciado y confirmación del servidor.
- Avisos de PV como NOTICE resaltado en la ventana activa de la misma red,
  aviso flotante interno o ambos. No se reinstala el script retirado ni se
  inventa contenido bloqueado por el servidor.
- Flechas táctiles para recuperar mensajes anteriores, editarlos antes de enviar
  y volver al borrador. Se eligen botones propios; no se cambia Borrar sin una
  elección explícita. Historial de escritura solo en memoria, sin comandos con claves.

Detalle, límites y verificación: [DREAMS_1_0_4.md](DREAMS_1_0_4.md).
