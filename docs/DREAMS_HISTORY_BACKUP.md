# HexDroid Dreams 1.0.5 (44): respaldo opcional del historial

Rama: `fix/dreams-history-backup`, basada en la versión publicada 1.0.4
(`dd86fbd`). Pedido autorizado: «Implementa», referido al historial permanente y
la pregunta para incluirlo en el respaldo. Los cambios locales de Retroceso y
los demás pedidos pendientes no forman parte de esta rama.

## Uso desde el teléfono

1. Abrí **Ajustes → Copia de seguridad y restauración → Exportar copia de seguridad**.
2. Elegí **¿Incluir también el historial de conversaciones?**. Se pregunta cada
   vez; comienza desactivado por privacidad. No afecta a la opción independiente
   de incluir contraseñas cifradas.
3. Si lo activás, Android permite guardar un ZIP con la configuración y todas las
   conversaciones. Si no, guarda el JSON de configuración compatible anterior.
4. Para recuperar la copia, elegí **Restaurar copia de seguridad** y seleccioná el
   ZIP o un JSON anterior. Confirmá el reemplazo de redes y ajustes. Las
   conversaciones existentes se conservan y se añaden las líneas que faltan;
   restaurar nuevamente la misma copia no duplica las líneas.

Exportar copia archivos: no los mueve, elimina ni recorta. No hay límite de
líneas ni antigüedad del historial en disco. El tamaño de los mensajes cargados en
pantalla sigue siendo independiente. Se retiraron los métodos antiguos de
borrado por vencimiento, que ya no se invocaban. Desactivar el registro tampoco
borra lo ya guardado. La eliminación manual con confirmación permanece disponible.

El ZIP contiene `settings.json` (el formato existente, con sus contraseñas
cifradas opcionales) e `history.zip` (TXT UTF-8 por red/conversación, compatible
con la exportación de Historial permanente). No carga todos los mensajes en RAM.
Un historial vacío también permite respaldar y restaurar la configuración.
Los archivos y sus rutas se validan en almacenamiento temporal privado antes de
restaurar. No se admiten rutas fuera de la carpeta ni contenido binario o UTF-8
inválido. Los temporales se retiran incluso al cancelar o fallar la contraseña.

## Privacidad y conservación

El historial exportado es texto legible. La frase de paso de las contraseñas no
cifra el historial. El diálogo lo explica, especialmente si se habilitó el registro
de conversaciones descifradas. Se conserva el filtrado de secretos del registro.
Los certificados TLS, claves del Android Keystore y solicitudes privadas cifradas
no se exportan mediante esta copia.

Android elimina el almacenamiento privado si se desinstala la app o se borran sus
datos desde el sistema. El respaldo debe guardarse fuera de ese almacenamiento
mediante el selector de archivos. Este cambio no evita acciones del sistema ni
pérdidas por daño o falta de espacio. No se solicita acceso completo al almacenamiento.
La firma sigue siendo debug según la elección del usuario. Si Android rechaza
una actualización por firma distinta, no desinstalar la versión anterior sin
exportar primero los datos necesarios.

## Verificación

- Pruebas JVM: exportación de 6.123 líneas sin alterar el original, JSON anterior,
  historial vacío, rechazo de archivos/rutas inválidos y fallo del destino sin
  pérdida de archivos.
- Pruebas Android: conservación de archivos, combinación sin duplicados,
  conversaciones ajenas intactas, reapertura del índice SQLite, copia vacía y JSON.
- Pruebas de interfaz: pregunta visible, selección de incluir/no incluir y cancelar.
- GitHub Actions exige todas estas pruebas y las anteriores de Fases 1–3/Dreams
  antes de verificar la firma debug y publicar el artifact de la APK.

Comprobar manualmente en el teléfono: ambas opciones de exportación, cancelación
  del selector de archivos, restauración de una copia con contraseñas cifradas,
  restauración repetida sin duplicados y consulta del historial tras reiniciar.
Verificación local ejecutada: `:app:assembleDebug :app:testDebugUnitTest
:app:assembleDebugAndroidTest`, correcta; 93 pruebas JVM, sin fallos ni omisiones.
APK debug firmada, paquete `com.malekira.hexdroid.personal.debug`, nombre HexDroid
Dreams y versión 1.0.5 (44), comprobados con apksigner y aapt2. Las pruebas Android
se compilaron localmente; su ejecución se realizará en GitHub Actions porque este
entorno no tiene emulador ni KVM. El resultado de esa ejecución y su enlace de
descarga se registran por separado al terminar.


## Entrega verificada — 30 de octubre de 2026

- Código de la APK: `bb6268613c61635553a4265da20024d0ec568f68` (reintento sin
  cambios de código respecto de `79a610d`). Versión 1.0.5 (44).
- [Ejecución y descarga de la APK](https://github.com/Malekira/HexDroidVip/actions/runs/41124129174).
- Las etapas de compilación/pruebas JVM, `connectedDebugAndroidTest`, comprobación
  obligatoria de todas las suites, verificación de firma/identidad y subida de la
  APK terminaron correctamente. Los informes están en `Phase3-test-reports`.
- Artifact publicado: **HexDroid-Dreams-debug**, **34,5 MB**, con
  `HexDroid-Dreams-debug.apk` y `SHA256SUMS.txt`.
- Desde Samsung: iniciar sesión en GitHub, abrir la ejecución, descargar ese
  artifact en **Artifacts**, extraer el ZIP en Mis archivos y abrir la APK.
- Un intento anterior, [41121846370](https://github.com/Malekira/HexDroidVip/actions/runs/41121846370),
  falló por **Timeout waiting for emulator to boot**: no se ejecutaron las pruebas
  Android de ese intento. Se reintentó sin omitir comprobaciones; las pruebas
  Android y su control obligatorio pasaron en la ejecución enlazada de entrega.
- Firma debug elegida por el usuario; GitHub confirmó que no hay firma debug
  estable configurada. Esta APK puede no actualizar la instalación anterior.
  **Si Android rechaza la firma, no desinstalar la app anterior ni borrar sus datos.**
  Primero exportar ajustes, scripts e historial con las herramientas de la versión
  instalada. El ZIP de historial es independiente de la copia JSON en esa versión.
- Rama separada, sin fusiones automáticas. No se incluyen otros cambios pendientes.
