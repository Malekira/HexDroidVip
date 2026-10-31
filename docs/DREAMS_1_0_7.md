# HexDroid Dreams 1.0.7 (46)

Autorización: «Aplica todos los pendientes». Rama `feat/dreams-1.0.7`, desde 1.0.6.

## Cambios implementados

- Topic: retirar lápiz de la barra; añadir herramientas reales de formato al editor
  existente de Herramientas de canal y al diálogo: colores IRC de texto/fondo,
  negrita, cursiva, subrayado, quitar formato, seleccionar texto y vista previa.
- Ajustes: sección Scripts independiente que abre el gestor real de scripts .hex.
- Perfil: URL HTTPS de foto junto al nick/ident/real name; conservar fotos existentes,
  guardar y restaurar esa URL con la configuración, tanto JSON como ZIP con historial.
- PART: omitir la sala redundante manteniendo flecha violeta, máscara y motivo.
- Mensajes anteriores: solamente botón ↤, hasta diez; el teclado borra normalmente.
- Entrada: desactivar autocorrección automática del campo y comprobar composición
  IME sin duplicaciones; Tab y sugerencias de nick requieren elección explícita.
- Conservar funciones anteriores, almacenamiento, identidad de paquete, GPLv3 y créditos.

Las pruebas, resultados y descarga de APK debug se registrarán al terminar.


## Ubicación y uso

- **Herramientas de canal → Tema → Herramientas de texto**: seleccionar todo o un
  fragmento, elegir negrita/cursiva/subrayado y color de texto o fondo/sombreado.
  Se muestran 16 colores básicos y se puede ampliar a la paleta IRC (0–98).
  «Quitar formato» conserva el texto. Sin selección, los códigos se insertan en el
  cursor para el texto siguiente. La vista previa muestra los códigos reales que
  se enviarán al aplicar; los estilos de escritura globales permanecen independientes.
  El diálogo de Editar topic usa el mismo componente. La barra del topic conserva
  lectura/contraste y el lápiz se retiró. Permisos y límites IRC siguen comprobándose.
- **Ajustes → Scripts → Administrar** abre el gestor real de scripts: archivos .hex,
  importación, edición y controles ya existentes. Se retiró la tarjeta duplicada
  de IRC; no se reinstalan scripts de saludos ni de solicitudes de PV.
- **Redes → Editar red → Identidad**: URL de foto junto al nick, ident y real name.
  Al guardar, se persisten perfil y metadatos en una operación. Las direcciones
  anteriores se recuperan desde el almacén antiguo sin exigir volver a escribirlas.
  Se publican solamente si la red admite metadatos; se mantienen las restricciones
  de descarga de imágenes y proxies anteriores.
- Las copias JSON y ZIP con historial incluyen `avatarUrl` en la red. Restaurar la
  copia actualiza también la foto de la sesión abierta; vaciar el campo conserva
  la eliminación tras reiniciar. El esquema de respaldo es v7 (acepta los anteriores).
  Una copia antigua sin foto conserva la ya configurada en ese dispositivo; no
  puede recuperar una URL que nunca se incluyó en esa copia.
- **↤ junto al mensaje** recupera hasta diez entradas propias; mantenerlo pulsado
  devuelve el borrador. La tecla de borrar, tanto táctil como física, queda libre
  de recuperación de mensajes y borra normalmente, incluso después de usar ↤.
  Se eliminaron la conexión IME de recuperación y la captura de Backspace.
- El campo pide al teclado desactivar la autocorrección automática. La aplicación
  conserva la composición de palabras del IME; completar nicks requiere Tab o
  seleccionar una sugerencia. No se desactivan acentos, emojis ni selección de texto.
- PART conserva flecha violeta, gris, máscara completa disponible y motivo,
  omitiendo únicamente el nombre redundante de la sala. JOIN y QUIT conservan
  los cambios ya entregados. Los registros anteriores se conservan íntegros.

## Verificación local

`gradle :app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest
--no-daemon --max-workers=4`: **BUILD SUCCESSFUL**. **105 pruebas JVM** ejecutadas,
  cero fallos, errores u omisiones. La APK de pruebas Android se compiló; su ejecución
  requiere GitHub Actions porque este entorno no tiene emulador ni KVM.

Las comprobaciones nuevas cubren formato/posición de selección, colores de texto
  y fondo, preview y envío desde Herramientas de canal; ubicación de Scripts y
  perfil real; migración de la foto, respaldo JSON/ZIP y restauración en el ViewModel;
  metadatos concurrentes sin pérdida; teclado/IME sin recuperación ni duplicados.
  El workflow exige estas suites y las de Fases 1–3/Dreams 1.0.4–1.0.6 antes de
  firmar/verificar y publicar el artifact.

## Comprobaciones en Samsung

1. Escribir palabras con composición, acentos y emojis; comprobar que no se
   completan ni duplican solas. Tab y sugerencias deben completar solo al elegirlos.
2. Recuperar texto con ↤ y borrarlo inmediatamente con ⌫; comprobar diez entradas
   y la vuelta al borrador manteniendo el botón. El teclado vacío no recupera textos.
3. Abrir Herramientas de canal → Tema, aplicar color/fondo y estilos a un fragmento,
   revisar la vista previa y confirmar el topic real en la sala; quitar formato.
4. Entrar en Ajustes → Scripts y abrir el gestor; comprobar scripts personales.
5. Guardar la foto en Identidad, reiniciar y exportar/restaurar JSON y ZIP; verificar
   la URL, imagen local y conservación de conversaciones.
6. Confirmar PART sin #sala y con máscara/motivo, además de WHOIS flotante,
   notificaciones, colores IRC y funciones previas.

Se conserva la firma debug elegida. Para actualizar sin perder datos se necesita
la misma clave que firmó la instalación anterior. Si Android rechaza la firma,
no desinstalar ni borrar sus datos. Exportar antes configuración, scripts e historial;
si la versión anterior no incluía la foto en el respaldo, copiar su URL por separado.
Los resultados de CI y el enlace de descarga se registran al completar la ejecución.
