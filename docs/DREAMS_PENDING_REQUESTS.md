# Pedidos pendientes de HexDroid Dreams

Estado: implementación autorizada por el usuario para la nueva versión HexDroid Dreams.
Rama de trabajo: `feat/hexdroid-dreams-release`, basada en la Fase 3 aprobada.

Plan: aplicar los cuatro pedidos, ejecutar pruebas JVM y Android, compilar y
publicar la APK en GitHub Actions sin fusionar ramas.

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
- Pendiente: aclarar en la interfaz la separación entre mensajes cargados en
  pantalla y archivo permanente, para que el valor de scrollback no parezca un
  límite del log; conservar consulta y exportación del historial completo.
- La aclaración de interfaz no altera los archivos ni elimina conversaciones.

## Disposición del selector de color

- Colocar el selector de color debajo de la ventana de ejemplo/vista previa.
- Usar un selector circular, más pequeño y cómodo para la pantalla del teléfono.
- Mantener código hexadecimal y ajuste de brillo/RGB.
