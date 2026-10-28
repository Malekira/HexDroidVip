# HexDroid Personal — errores reportados de Fase 3

Este registro conserva los reportes y las correcciones de la versión
`1.7.8-personal3.1` (38). Los cambios están implementados; la validación efectivamente
ejecutada se informa en GitHub Actions y en la entrega. Las comprobaciones en un
teléfono físico siguen siendo parte de la revisión del usuario.

## P3-001 — El autocompletado sustituye palabras mientras se escribe

- Estado: implementado en 1.7.8-personal3.1; ver pruebas de regresión abajo.
- Origen: reporte del usuario al probar la APK de Fase 3 del PR #3.
- Impacto: impide escribir libremente cuando una palabra comparte el comienzo de
  un nick de la sala.
- Comportamiento reportado: una coincidencia única se inserta automáticamente
  antes de terminar de escribir, sustituyendo el texto que se quería introducir.
- Referencia: la documentación inicial describía la inserción de una coincidencia
  única tras una breve pausa; ese comportamiento se ha retirado.

Corrección implementada: mostrar las sugerencias desde tres caracteres, sin modificar
el borrador por el mero transcurso del tiempo. Insertar un nick únicamente al
tocar una sugerencia o pulsar Tab, tanto táctil como físico. Mantener la selección
entre varias coincidencias y conservar el texto anterior y posterior al cursor.

Comprobaciones de regresión con un nick ficticio:

1. En una sala con `DemoNick01` como única coincidencia del prefijo `dem`, escribir
   `dem`, hacer una pausa y continuar hasta escribir `demora`.
2. Comprobar que las sugerencias no sustituyen el texto ni desplazan el cursor.
3. Comprobar que tocar una sugerencia o pulsar Tab sí inserta el nick.
4. Repetir con varias coincidencias y con texto a ambos lados del cursor.

Se retiró la inserción automática tras 300 ms y se muestran también las coincidencias únicas.

## P3-002 — Los saludos deben ejecutarse mediante scripts

- Estado: implementado en 1.7.8-personal3.1; el saludo nativo ya no intercepta el script.
- Alcance: `/Buenosdias`, `/buenosdias todos` y los demás saludos configurados.
- Comportamiento requerido: los saludos se definen y ejecutan mediante scripts
  `.hex`, conservando los scripts importados y su configuración.

Se retiró la rama nativa de `/buenosdias` y su función de envío: el comando llega al
motor de scripts mediante la resolución habitual de alias. No se reemplazan los
scripts del usuario ni se introducen nuevos saludos fijos. Los ajustes enlazan a
Scripts y se conservan los datos antiguos de exclusiones sin usarlos como un
control nativo del saludo.

Comprobaciones de regresión: con un script de prueba, ejecutar los saludos configurados
y comprobar que cada invocación se resuelve una sola vez mediante el script,
incluidas las variantes con argumentos. Verificar que importar, editar y conservar
scripts al reiniciar sigue funcionando.

## P3-003 — Falta un selector visual de color en los temas

- Estado: implementado en 1.7.8-personal3.1 con selector táctil HSV.
- Comportamiento requerido: disponer de un selector visual de color accesible
  desde cada color editable del tema y cómodo de usar mediante toque en el teléfono.
- Se conservan los controles RGB y el campo hexadecimal junto a la superficie
  táctil de saturación/brillo y la barra de tono.

Corrección implementada: se incorporó una superficie visual para elegir el
color, sincronizada con los controles RGB, el código hexadecimal y la vista previa.
Conservar las paletas clara/oscura, el guardado de temas y el contraste; no cambiar
los colores IRC recibidos ni el formato de escritura independiente del tema.

Comprobaciones de regresión: elegir colores visualmente, comprobar su código y vista
previa, cancelar sin aplicar, guardar y recuperar el tema tras reiniciar. Probar
ambas paletas y los distintos elementos personalizables.
## P3-004 — Aviso resaltado de solicitud de PV con +g en la ventana activa

- Estado: implementado en 1.7.8-personal3.1 como NOTICE local resaltado.
- Comportamiento requerido: cuando el servidor avisa de un intento de privado
  bloqueado por `+g`, mostrar también la solicitud en la ventana activa de esa
  misma red, con presentación de NOTICE resaltado e identificación del nick
  solicitante cuando el servidor lo proporcione.
- El aviso debe verse sin tener que cambiar a Status ni abrir la lista de
  pendientes. Conservar el registro en Status y la solicitud pendiente existentes.

Usar el aviso real del servidor (por ejemplo, numeric 718 en redes compatibles).
Indicar que el servidor bloqueó el mensaje y no entregó su contenido. Es una
presentación local: no enviar un NOTICE a la sala ni al solicitante para mostrar
este aviso, no abrir un PV y no simular una aceptación o confirmación del servidor.
Respetar ignores, scripts, la red del evento y la deduplicación existente. La copia
visual no debe producir una segunda notificación Android del mismo evento.

Comprobaciones de regresión:

1. Con `+g` activo en una red compatible y una sala de esa red visible, provocar un
   intento de PV desde un cliente de prueba y comprobar el aviso NOTICE resaltado
   en la sala, el estado de pendiente y la información real en Status.
2. Repetir con un PV y con Status como ventana activa; no duplicar el aviso en un
   mismo buffer ni crear una conversación con el solicitante.
3. Mantener visible una ventana de otra red y comprobar que el aviso no se inserta
   allí; conservar la solicitud en su red de origen.
4. Verificar ignores, supresión por scripts y una sola notificación Android por
   evento; comprobar que no se envía ningún mensaje IRC por replicar el aviso.

Pruebas añadidas o ampliadas:

- `PersonalPhase3UiTest.phase3RealComposerNeverCompletesASingleNickWithoutAnExplicitChoice`:
  compositor real, pausa mayor que el antiguo temporizador, palabra libre, selección y Tab.
- `PersonalPhase3UiTest.phase3VisualColorPickerUpdatesHexPreviewAndBothSavedPalettes`:
  selector táctil real, hex, dos paletas, guardado y cancelación.
- `PersonalPhase3UiTest.phase3CallerIdNoticeIsVisiblyHighlightedInTheRealChat`:
  renderizado real y color de fondo del NOTICE.
- `PersonalPhase3PrivateFlowTest.phase3RealPrivateFlowRetainsPendingAndAcceptsRejectsIgnoresWithoutBlocking`:
  flujo real del ViewModel con 718, Status/sala/otra red, deduplicación, ignores,
  supresión por NUMERIC y un script importado de saludos tras recargar.

El workflow exige estas pruebas antes de publicar la APK. No se afirma haber probado
un Samsung físico ni una red IRC de producción desde el entorno de desarrollo.
