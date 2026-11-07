# HexDroid Dreams 1.0.12

Base confirmada por la captura de «Acerca de»: Dreams 1.0.11, rama
`feat/dreams-1.0.11`, commit `71095e9`. La aplicación conserva su nombre, paquete,
temas, árboles de conversaciones, fotos, historial organizado y demás funciones.
Versión nueva: 1.0.12, código 51.

## Cambios solicitados

- Pestañas de conversaciones visibles sobre el campo de escritura por defecto.
- Retroceso no navega fuera del chat mientras haya texto, incluidos espacios.
  Con el campo vacío se conserva el comportamiento habitual de Android. El sistema
  puede ocultar primero el teclado.
- Un deslizamiento horizontal sobre los mensajes abre la pestaña siguiente hacia
  la izquierda o la anterior hacia la derecha, sin saltar entre extremos.
  Los gestos verticales y los gestos sobre el campo de escritura conservan su uso.
  Cada conversación mantiene su propio borrador al cambiar con el dedo o las pestañas.
- Status y WHOIS pueden guardarse en el historial. Las consultas WHOIS mostradas
  únicamente en el panel flotante también se archivan. El registro WHOIS no se duplica
  cuando ya se presenta en su propia ventana.

Las pestañas y el registro de Status se activan también al actualizar desde 1.0.11.
Las elecciones posteriores del usuario se respetan. Se conserva el interruptor global
de historial, su filtrado de datos y las opciones de organización en carpetas y por días.
No se envían los buffers internos al servidor para solicitar CHATHISTORY.

## Comprobación

`bash ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:connectedDebugAndroidTest`

El workflow existente incluye esta rama y exige la ejecución de las regresiones de
Dreams anteriores y las nuevas de Retroceso, gestos, borradores e historial WHOIS/Status.
La APK se entrega como artefacto debug, con el nombre HexDroid Dreams.
