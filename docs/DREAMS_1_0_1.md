# HexDroid Dreams 1.0.1 (40)

Continuación de la Fase 3 aprobada, en `feat/hexdroid-dreams-release`.
Se mantienen GPLv3, los créditos originales, WHOIS flotante, menciones, scripts,
solicitudes de privados y los datos existentes. No se fusionan ramas.

## Redes

En **Redes**, tocá una red y elegí **Duplicar red**. Se abre el formulario con
otro nombre y un identificador nuevo. Podés editarlo, guardar o cancelar; cancelar
no crea una red. No se conecta automáticamente ni modifica la red de origen.

Las contraseñas se cargan desde el almacén cifrado del teléfono y se guardan
para la copia mediante ese mismo almacén, sin incluirse en la configuración
normal. Un certificado cliente importado debe importarse de nuevo para la red
nueva. Su certificado automático y la confianza TLS se gestionan por separado;
no se copian identidades criptográficas ni el estado aprendido de la conexión.

## Eventos de sala

- `→ JOIN DemoNick01 (demo@host.example.invalid) #sala-demo`: flecha verde.
- `← PART DemoNick01 (demo@host.example.invalid) #sala-demo [motivo]`: violeta.
- `← QUIT DemoNick01 (demo@host.example.invalid) [motivo]`: roja.

El resto de cada línea es gris. Se conservan filtros, ignores, motivos e
historial. La opción de colorear eventos y el renderizado mIRC siguen disponibles.
Los colores de mensajes recibidos no se alteran. Si falta la máscara, se omite;
no se muestran máscaras inventadas. Un host virtual puede ocultar la IP real:
solo se muestra el host o dirección completa que entregue el servidor.

Los cambios de nick siguen visibles. CHGHOST, ACCOUNT y el anuncio automático
396 de host virtual dejan de generar avisos visuales. Tampoco se añade la cuenta
al JOIN. La autenticación y las consultas WHOIS mantienen sus datos y errores.

## Historial

En Ajustes, **Mensajes cargados en pantalla** reemplaza la etiqueta confusa de
líneas máximas de historial. Limita la memoria de la conversación, sin recortar
los TXT guardados. El archivo permanente sigue sin límite de líneas ni fecha de
vencimiento; depende del espacio disponible. Se consulta y exporta desde
**Historial permanente**. No se migran ni eliminan datos por este cambio.

## Selector de color

En **Ajustes → Temas personalizados → Crear tema/Editar**, la vista previa
aparece antes del selector circular de 160 dp. Tocá o arrastrá por la rueda para
elegir tono e intensidad, y usá el control de brillo para aclarar u oscurecer.
Se conservan el hexadecimal, RGB y las paletas claras y oscuras independientes.

## Pruebas y descarga

Se añadieron pruebas de formato/máscaras, independencia de la copia de red,
ViewModel real, renderizado de colores, rueda circular y exportación de 5005
líneas. Las pruebas de las fases anteriores siguen exigidas en Actions.

Compilación local: `:app:assembleDebug :app:testDebugUnitTest
:app:assembleDebugAndroidTest`. La ejecución de las pruebas Android se realiza
en el emulador API 35 del workflow; compilar el APK de pruebas no equivale a
ejecutarlas. Consultá sus resultados en la ejecución del commit correspondiente.

El usuario eligió APK **debug**. Descargá el artifact **HexDroid-Dreams-debug**
desde GitHub Actions, extraé el ZIP con Mis archivos de Samsung y abrí la APK.
El paquete debug sigue siendo `com.malekira.hexdroid.personal.debug`.

La clave debug de un runner puede diferir de una instalación anterior. Si Android
rechaza la actualización por firma, conservá la app instalada y exportá ajustes,
scripts e historiales antes de considerar desinstalar. Las identidades de cifrado
y solicitudes privadas requieren atención aparte; no se trasladan mediante un TXT.
La firma definitiva estable continúa siendo opcional y no se exige para esta APK.

Comprobar en el teléfono: duplicar/cancelar/guardar una red; cambiar de nick y ver
JOIN/PART/QUIT; editar un tema con la rueda; consultar/exportar historial antiguo;
probar WHOIS, scripts de saludo, Tab y avisos de privados con +g.
