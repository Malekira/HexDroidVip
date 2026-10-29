# HexDroid Dreams 1.0.3 (42)

Actualización autorizada mediante «Bien, actualiza la app», sobre la rama
revisable `feat/hexdroid-dreams-release`. No se fusionan ramas anteriores.

Cambios de esta versión:

- Colocar Exportar y Restaurar copias de seguridad en botones de ancho completo
  y filas distintas, conservando selector de archivos y confirmaciones.
- Dar al icono de sala el aspecto de los demás iconos superiores, sin fondo:
  negro en el tema claro y con contraste legible en oscuro/paletas personalizadas.
- Retirar la entrada fija de privados pendientes, las referencias en Ajustes y
  el script incluido `solicitudes-pv.hex`; no reinstalarlo ni activarlo al iniciar.
  Conservar los datos cifrados existentes y los demás scripts del usuario.
- Quitar los textos/acceso de saludos de Ajustes. El script independiente del
  usuario permanece intacto.
- Mostrar HexDroid Dreams en la bienvenida junto con los créditos originales,
  agradecimientos y licencia GPLv3, usando la información real de Acerca de.
- Ejecutar pruebas JVM y Android y publicar la APK debug en GitHub Actions.

Se conserva el paquete personalizado, el historial completo, las redes, los
temas, la escritura, el WHOIS flotante, las menciones y Tab.

## Copias de seguridad y herramientas

En **Ajustes → Copia de seguridad y Restauración**, Exportar y Restaurar tienen
su propia fila de ancho completo. Se conservan el selector de archivos Android,
la frase de paso para contraseñas y la confirmación antes de reemplazar ajustes.

El icono de herramientas mantiene su ubicación a la izquierda del menú superior.
Es negro en el tema claro, sin fondo coloreado; en oscuro y temas personalizados
conserva el contraste y los colores de interfaz elegidos por el usuario.

## Retirada de solicitudes de privados

Se retiran el script incluido y todos sus accesos predeterminados: menú general,
Ajustes y acciones registradas por ese script. No se instala en versiones nuevas
ni se reactiva por la antigua preferencia. Los privados nuevos se reciben como
conversaciones normales, salvo que el usuario instale su propio script de filtro.

Al actualizar, el archivo identificado como nuestro addon se desactiva y archiva
una sola vez en `files/retired-scripts/`, fuera del directorio ejecutable. Incluso
si fue editado, se conserva una copia. Un archivo independiente del usuario con
el mismo nombre y sin huella de instalación del addon se conserva. El almacén
cifrado de pendientes y las autorizaciones no se borran. La app ya no ofrece
accesos fijos a esa lista; los servicios internos se mantienen por compatibilidad
con scripts que el usuario elija importar expresamente.

Los avisos reales 718 de +g siguen apareciendo como NOTICE resaltado en la ventana
activa de la misma red y en Status. No crean pendientes, no responden automáticamente
ni inventan el contenido de mensajes retenidos por el servidor.

Los saludos quedan exclusivamente en el script del usuario: se quitan los textos,
el acceso y el resultado de búsqueda de Ajustes. No se añade un saludo integrado
ni se modifica el script personal.

## Bienvenida

El título incorpora **HexDroid Dreams** en las 16 traducciones existentes.
Los agradecimientos usan la lista real de Acerca de: desarrollo original de eck
(boxlabs) y la contribución de traducción de cyberdyne-sys, además del agradecimiento
a colaboradores y bibliotecas. Se conserva GPLv3 o posterior y Acerca de original.
La bienvenida sigue siendo la del primer inicio; no se fuerza a repetir la
configuración de instalaciones que ya la completaron.

## Verificación y descarga

Las pruebas cubren botones en teléfono estrecho con letra ampliada, bienvenida,
menú sin pendientes, herramientas monocromas y retirada del addon tanto original
como editado, conservando mensajes cifrados y el script de saludo. Se mantienen
las pruebas anteriores de WHOIS, menciones, Tab, historial y protocolos.

El artifact **HexDroid-Dreams-debug** se publica desde GitHub Actions solo si
compilación y pruebas JVM/Android aprueban. En Samsung: iniciar sesión en GitHub,
descargar el ZIP de Artifacts, extraer con Mis archivos y abrir la APK.

La firma continúa siendo debug. Si Android rechaza una actualización por firma,
no desinstalar la versión anterior: las solicitudes e identidades cifradas no se
recuperan mediante un TXT del historial. El código no borra configuraciones ni
historiales al actualizar; no modifica ni fusiona las ramas anteriores.
