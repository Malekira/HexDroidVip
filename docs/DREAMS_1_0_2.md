# HexDroid Dreams 1.0.2 (41)

Rama revisable: `feat/hexdroid-dreams-release`. Se aplican los pedidos pendientes
autorizados mediante «Implementa todo». No se fusionan ramas.

Plan de cambio y validación:

- Sustituir la política integrada de solicitudes de PV por el script editable
  `solicitudes-pv.hex`. Sin un manejador activo no se retienen nuevos mensajes.
  El almacén cifrado, las notificaciones y las acciones Android siguen siendo
  servicios del cliente que usa el script. Migrar la activación anterior una vez,
  conservar mensajes pendientes y configuraciones, y no sobrescribir scripts.
- Añadir una rueda de color para menciones, vista previa y entrada hexadecimal;
  usar textos genéricos sin nombres de personas ni nicks particulares en Ajustes.
- Colocar Tab como botón cuadrado pequeño con flecha de tabulación a la izquierda
  del campo de escritura; mover herramientas de sala inmediatamente a la izquierda
  del menú superior general. Conservar las acciones y los permisos de la sala.
- Ejecutar pruebas JVM y Android, compilar y publicar la APK debug mediante Actions.

La firma elegida por el usuario sigue siendo debug. No se requieren nuevos
secretos, permisos ni aplicaciones para descargar el artifact.

## Uso en el teléfono

En Ajustes, la sección de solicitudes enlaza a **Scripts**. Habilitá
**solicitudes-pv.hex** allí. Si la opción anterior estaba activada, la actualización
lo habilita una sola vez. Desactivarlo o eliminarlo deja pasar nuevos privados;
no borra la lista existente. Las autorizaciones, textos automáticos y mensajes
pendientes conservan el almacén anterior. Los scripts importados se mantienen.

Con el script habilitado, la pantalla Scripts y el menú de scripts de la sala ofrecen **Solicitudes de
privados** y **Configurar solicitudes de privados**. También funcionan
`/privados` y `/configurarprivados`. La segunda abre controles de avisos NOTICE,
textos, autorizaciones entre sesiones y acceso a los pendientes. No se envía una
respuesta automática desde el cuerpo del evento del script: el servicio del
cliente se encarga de enviarla una sola vez, sin revelar el contenido del privado.

La política real está en el archivo `.hex`: `on PRIVATE_REQUEST { halt }` retiene
una solicitud. Un `return` deja pasar el mensaje. El evento incluye `$nick`,
`$network`, `$text`, `$history` y `$serverblocked`. También se consulta ante un
numeric 718; el contenido retenido por +g no llegó al cliente y nunca se inventa.
Los ignores, mensajes cifrados y respuestas suprimidas por otros scripts siguen
respetándose. Si no hay manejador activo, no funciona como filtro de solicitudes.
Los mensajes aceptados en la sesión o autorizados usan el mismo servicio seguro.

Servicios del host para scripts: `private.open` abre la lista y
`private.settings` abre la configuración. La lista persistente, el cifrado con
Android Keystore, las notificaciones y la ejecución de ACCEPT compatible con la
red son servicios Android usados por el script, no datos simulados dentro de él.
La opción antigua se conserva en los datos únicamente para migración.

En **Ajustes → Menciones**, el hexadecimal y la rueda modifican el mismo color;
la vista previa muestra el resultado. La etiqueta **Resaltar mi propio nick**
explica la opción sin usar nombres particulares. La vista previa de temas usa
un remitente genérico. Los nombres que el usuario configure por su cuenta o los
remitentes reales de conversaciones no se sustituyen.

Tab aparece a la izquierda de la entrada, cuadrado de 36 dp con flecha de
tabulación y descripción accesible. Se mantiene visible en salas y privados,
desactivado mientras no haya nicks disponibles. Las herramientas de sala están
a la izquierda del menú general superior, cuando los permisos permiten usarlas.

## Validación y descarga

Se amplían las pruebas reales del intérprete y del ViewModel para comprobar
migración, reglas editadas, ignorados, cifrado, +g, desactivación y pendientes.
Las pruebas UI cubren sincronización rueda/hexadecimal, colocación de botones,
autocompletado y apertura de herramientas. Se mantienen las pruebas anteriores.

El workflow publica **HexDroid-Dreams-debug** después de aprobar las pruebas JVM y
Android. Descargá el ZIP desde la ejecución del commit, extraelo en Mis archivos
de Samsung y abrí la APK. Requiere iniciar sesión en GitHub. La firma debug puede
diferir de la instalada: si Android rechaza la actualización, conservá la app y
respaldá ajustes, scripts, historial e identidades antes de desinstalar.
