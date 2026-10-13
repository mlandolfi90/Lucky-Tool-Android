# Pendientes

Lo que el humano pidió agregar a la app y dejó para después. Solo lo abierto: un punto hecho sale de
acá, y su registro queda en su plan (`docs/plans/`) y en su PR. Cada punto trae lo medido y lo que
decide el humano.

## Cuándo

«Luego lo hacemos» (humano, 2026-10-11): después de mergear el PR #3, para que entre la pantalla del
relay, y de probar el relay por internet. Cada punto se implementa con su plan, como pide `CLAUDE.md`.

## 1. La app en español

Pedido del humano el 2026-10-11: «idioma español».

- Medido en `1e820e0` (rama del PR #3): 299 textos en `app/src/main/res/values/strings.xml` y 5 en
  `app/src/gms/res/values/strings.xml`; no hay `values-es`.
- Hay texto escrito en el código que primero tiene que pasar a recursos:
  - pantallas: `McpToolsSettingsScreen` (unos 60 nombres de herramientas y parámetros),
    `ChannelSettingsScreen`, `WifiMonitorScreen`, `NotificationFilterScreen`, `ServerStatusCard`;
  - mensajes de validación en `MainViewModel`, `RelaySettingsImpl` y `EventChannelSettingsImpl`;
  - notificaciones: el estado del servidor en `McpServerService` y «Event Channel active» en
    `EventChannelService`;
  - el overlay «MCP controlling» de `McpAccessibilityService`;
  - la página de consentimiento OAuth (`OAuthConsentPage`), que se ve en el navegador de quien conecta.
- Propuesta: las descripciones de las tools MCP quedan en inglés, porque las lee el agente y no la
  persona.
- Decide el humano: español cuando el teléfono está en español, o siempre.
- **Decidido por el humano el 2026-10-13: se pospone.** Mientras tanto, todo lo nuevo se construye listo
  para cambiar de idioma: los textos que ve una persona van en `strings.xml`, nunca escritos en el código.
  No se crea `values-es` todavía.

## 2. Un ícono que se entienda

**Hecho** el 2026-10-12/13: ícono Lucky y nombre `lucky-mcp-android`. Registro en el plan 68
(`docs/plans/68_lucky_brand_icon_and_name_20261012180915.md`) y en el PR #8. Quedan declaradas y sin tomar
las verificaciones manuales MB1–MB4 (mirar el ícono en el teléfono).

## 3. Encender y desbloquear la pantalla para trabajar solo

Pedido del humano el 2026-10-11: si el teléfono queda trabajando y la pantalla se apaga o se
bloquea, la app tiene que poder encenderla y desbloquearla sola, «con el truco de desbloqueo» que
otros programas ya usan (entrada automática).

- Medido el 2026-10-11: el teléfono se bloqueó dos veces a los pocos segundos de que un agente abriera
  una app y tocara un botón. Desde ahí las tools solo ven la pantalla de bloqueo y el trabajo se corta
  hasta que una persona desbloquea.
- Medido en `1e820e0` (rama del PR #3): en `app/src` no hay `WakeLock`, `FLAG_KEEP_SCREEN_ON`,
  `setTurnScreenOn` ni `requestDismissKeyguard`. La app no enciende la pantalla ni la mantiene
  encendida mientras recibe llamadas.
- Sin verificar: que los gestos que manda el servicio de accesibilidad no reinician el contador de
  apagado de Android, como sí lo hace un toque real.
- Son dos partes:
  - mantener la pantalla encendida mientras haya llamadas MCP en curso, y apagarla al terminar;
  - encender y desbloquear cuando llega una llamada con la pantalla apagada o bloqueada.
- Decidido por el humano el 2026-10-11 (excepciones a `CLAUDE.md` en `REGLAS.md`, PR #2):
  - la app puede mantener la pantalla encendida mientras haya llamadas MCP en curso;
  - la app puede desbloquear con el PIN o patrón del dueño, guardado cifrado en el teléfono
    (nunca en el repo, en un log ni fuera del teléfono), apagado por defecto y encendido con un
    ajuste a propósito.
- Falta que el humano describa el método de desbloqueo (el «truco»).
- **El «truco», confirmado el 2026-10-13:** la app enciende la pantalla, Android muestra el teclado del
  PIN (o el patrón) y la app lo teclea con el servicio de accesibilidad, como hace AutoInput de Tasker.
- **Decidido por el humano el 2026-10-13:**
  - la pantalla se enciende **sólo cuando la tool la necesita** (mirar o tocar una app); notificaciones,
    archivos, wifi, ubicación y datos del teléfono trabajan con la pantalla apagada;
  - **desbloquear es una tool aparte** (`unlock_screen`), con su propio permiso en los permisos de tools;
    ninguna otra llamada desbloquea;
  - si una tool que necesita la pantalla llega con el teléfono bloqueado, devuelve un error claro que dice
    que la pantalla está bloqueada y que se use `unlock_screen`.
- Tomado por defecto, ajustable: la pantalla queda encendida 60 s después de la última llamada; si fue la
  app la que desbloqueó, vuelve a bloquear; un solo intento por desbloqueo y, si falla, la función se
  apaga sola.
- Investigado el 2026-10-13 (código y AOSP, **sin medir en el teléfono**):
  - mantener encendida: una ventana `TYPE_ACCESSIBILITY_OVERLAY` de 1×1 con `FLAG_KEEP_SCREEN_ON`, sin APIs
    deprecadas y sin `WAKE_LOCK`;
  - `click_node` y `set_text` usan `performAction`, que **no** cuenta como actividad del usuario: por eso
    el teléfono se bloqueaba a los segundos. Los gestos (`tap`, `swipe`) sí cuentan;
  - encender: una Activity transparente con `setTurnScreenOn` y `setShowWhenLocked`, lanzada desde el
    servicio de accesibilidad; mostrar el teclado con `requestDismissKeyguard`;
  - el PIN se guarda cifrado con una clave del Android Keystore **no** atada a autenticación (si no, no se
    puede usar con el teléfono bloqueado), en un DataStore propio, fuera del log de ajustes;
  - **imposible para cualquier app:** desbloquear después de un reinicio, antes del primer desbloqueo.
- Falta medir en el teléfono (Samsung, Android 16) antes de planificar: la ventana 1×1 retiene la
  pantalla; `tap` contra `click_node` en el contador de apagado; encender desde el servicio; si el
  teclado del PIN de Samsung se ve y se toca por accesibilidad; el patrón por gesto; qué aparece primero
  con huella registrada; StrongBox y descifrado con el teléfono bloqueado; el borrado automático por
  intentos fallidos de Samsung. Y medir, tool por tool, cuáles andan con el teléfono bloqueado.

## 4. Grabar tareas comunes y repetirlas en una llamada

Pedido del humano el 2026-10-11: «grabar una tarea común» en la app, para no rehacer la búsqueda
en cada pedido.

- Medido el 2026-10-11, consultando precios en una app de finanzas: 54 llamadas MCP la primera vez
  y 12 cuando la ruta ya se conocía. Cada llamada cuesta unos 1,2 s de red.
- Medido ese día en esa app: el `node_id` de un mismo botón cambia al reabrir la app, porque sale
  de la posición del nodo y la de sus contenedores. El `resource_id` y la posición en pantalla se
  repiten. Una receta tiene que apoyarse en `resource_id` o texto, nunca en `node_id`.
- Medido en `1e820e0`: ninguna de las 57 tools graba ni repite tareas, y no hay tool que ejecute
  varios pasos en una llamada.
- Decidido por el humano el 2026-10-11:
  - se graba de dos formas: el agente guarda con un nombre los pasos de una tarea que salió bien,
    y la persona graba en el teléfono con un botón «Grabar» (los campos de contraseña nunca se
    graban);
  - se ejecuta en una sola llamada: una tool nueva corre todos los pasos en el teléfono, con
    parámetros (por ejemplo el monto), y devuelve el resultado;
  - en apps de dinero, los pasos que solo leen corren solos; si un paso toca un botón de acción
    (vender, pagar, enviar), la tarea se detiene y pide aprobación en el teléfono.
- Aprobación, decidido por el humano el 2026-10-11: tres caminos, que pueden asignarse por nivel de
  criticidad del paso:
  1. **Lo presiona el humano.** La tarea corre hasta justo antes del paso de acción, deja la app en
     esa pantalla y se detiene; el humano presiona el botón real (por ejemplo «Vender»).
  2. **Pantalla de aprobación** de la app. Ya existe una, la de OAuth (`ApprovalScreen`), que puede
     servir de base.
  3. **Huella dactilar** del dueño antes de ejecutar el paso.
- El nivel de cada paso lo marca el humano al guardar la tarea y lo puede cambiar después en la
  tarea guardada: no es rígido (decidido el 2026-10-11). Falta definir en el plan cómo lo marca
  cuando la tarea la graba el agente.
- Si un paso falla (por ejemplo, no encuentra su elemento porque la app se actualizó), la tarea se
  detiene y avisa con un informe (decidido el 2026-10-11).
- Las tareas guardadas se ven y se borran en las dos partes: en la app y por tools (decidido el
  2026-10-11).
- Cada ejecución deja su rastro en el log de la app, como las tools (ver punto 5).
- Decide el humano: los niveles de criticidad y qué camino de aprobación lleva cada uno.
- **Decidido por el humano el 2026-10-13: no hay niveles fijos, hay perfiles granulares.**
  - El perfil se pega a **la app**: todo lo que pase dentro de la app del banco usa el perfil «Banco»,
    venga de una tarea o de un agente.
  - Vale **para todo**, también para un agente que usa las tools sueltas, no sólo para las tareas
    grabadas.
  - Por tipo de acción: leer, navegar, escribir, botón de acción (vender, pagar, enviar). Cada una puede
    ser corre sola, Confirmar (pantalla de la app), Huella, Parar (la persona toca el botón real) o
    Prohibido. El humano lo edita en la app.
  - **Monto máximo** por tramos dentro del perfil (por ejemplo: hasta X corre solo, más arriba Confirmar,
    más arriba Huella, más arriba Prohibido).
- **Monto, decidido por el humano el 2026-10-13 («A + excepciones»):** en una app con perfil, **todo número
  escrito cuenta como monto** (por lo seguro, venga de una tarea o de un agente suelto; `1.500,50` también
  es número; los campos de contraseña nunca cuentan). La pantalla de aprobación ofrece «este campo no es un
  monto», que excluye ese campo para siempre en esa app. Un monto que no se escribe (deslizador, botones
  «+1000») no lo ve el límite: la red real es la columna botón de acción, que en apps de dinero nunca va
  en «corre sola».
- Fases: plan 70, perfiles por app (el motor, vale para todo); plan 71, tareas (guardar y repetir en una
  llamada); plan 72, botón «Grabar» en el teléfono.
- Tomado por defecto: si una tarea espera aprobación, la llamada espera hasta 60 s y después devuelve
  «esperando aprobación» con un id para consultar (el relay corta a los ~100 s). En el primer plan, el
  agente guarda la tarea mandando pasos explícitos; el botón «Grabar» del teléfono va en un plan posterior.
- Investigado el 2026-10-13: el ejecutor puede llamar a las tools dentro de la app, sin HTTP; las tareas se
  guardan en un DataStore propio con JSON, como los clientes OAuth (sin dependencias nuevas); la aprobación
  copia el esquema de `OAuthApprovalCoordinator`; la huella usa el `BiometricPrompt` del sistema.

## 5. Logs: mantener la disciplina y mejorarla

Pedido del humano el 2026-10-11: el MCP registra lo que hace en logs; esa disciplina se mantiene en
todo lo nuevo y, si se puede, se mejora.

- Medido en `1e820e0`: el log de la app (pantalla Logs) guarda entradas `TOOL_CALL` (nombre de la
  tool, duración en ms y marca de fallo), `TUNNEL`, `SERVER` y `OAUTH`
  (`data/model/ServerLogEntry.kt`), en segmentos de hasta 1000 entradas, con 20 segmentos como
  máximo (`ServerLogSegmentedStore.kt`). Cada llamada se registra en
  `mcp/tools/LoggedToolRegistration.kt`.
- Medido el 2026-10-11 en el build debug: el servicio de accesibilidad escribe en logcat una línea
  por cada cambio de contenido de cualquier app (`McpAccessibilityService.kt:103`); había 2326 de
  esas líneas en el buffer, y tapan las líneas útiles.
- Ya planificado: el plan 67 (US4) agrega una línea de tiempos por pedido en el relay y en el
  teléfono, con un id común para cruzarlas.
- Propuestas, que decide el humano:
  - las tareas grabadas registran inicio, cada paso con su duración, el camino de aprobación usado
    y su resultado, y el informe de fallo;
  - la línea por cada evento de accesibilidad se escribe solo cuando se depura a propósito; fuera
    de eso no se escriben (decidido por el humano el 2026-10-11). Hoy el teléfono tiene instalado
    el build debug, así que la propuesta es un ajuste de depuración, apagado por defecto, y no
    depender del tipo de build. Queda abierto si, con la depuración apagada, se escribe un resumen
    por minuto (cuántos eventos y de qué apps) o nada;
  - lo que `CLAUDE.md` prohíbe registrar (el token, el árbol de accesibilidad completo, datos
    sensibles) rige igual para todo lo nuevo, incluidos los parámetros de las tareas.
- **Decidido por el humano el 2026-10-13:** con la depuración apagada **no se escribe nada** (ni resumen
  por minuto): las llamadas a tools ya quedan en la pantalla Logs de la app.

## 6. Las advertencias del compilador Kotlin

**Hecho** el 2026-10-13: las 43 advertencias, a cero, y el build falla ante cualquier advertencia nueva
(`allWarningsAsErrors` en los cinco módulos). Una sola supresión, aprobada por el humano, en
`MODE_BACKGROUND_ACTIVITY_START_ALLOWED` hasta que el mínimo sea Android 16. Registro en el plan 69
(`docs/plans/69_kotlin_warnings_zero_20261013011443.md`) y en el PR #9; el portón se probó con un PR de prueba
(#10) que CI frenó.

## 7. Despertar al agente con el canal de eventos

Pedido del humano el 2026-10-12: «guarda como pendiente lo de despertar al agente. con el event
chanel». Surgió al preguntar qué es el canal y cómo se hace que un agente se entere o se dispare.

- Medido el 2026-10-12 en `1e3ef93`, leyendo el código del canal:
  - el teléfono hace `POST <endpointUrl>/event` con JSON `{type, timestamp, data}` y
    `Authorization: Bearer <token>` si hay (`services/channel/EventDispatcherImpl.kt:109`);
  - además le pega `GET <endpointUrl>/health` cada 30 s, y si no contesta la app se pone en estado
    Error (`EventChannelService.kt:208`). El receptor tiene que contestar las dos rutas;
  - timeouts: 5 s de pedido, 3 s de conexión (`EventDispatcherImpl.kt:163`);
  - eventos hoy: `notification` (paquete, título, texto, categoría y las acciones del aviso) y
    `wifi` (ssid, `discovered` / `lost`); en el sabor gms hay además geocercas
    (`GeofenceChannelController.kt`);
  - apagado por defecto, endpoint por defecto `http://localhost:9090`
    (`data/model/EventChannelConfig.kt`);
  - **sin cola y sin reintento**: cada evento se despacha una sola vez y, si el receptor no está,
    se pierde y sólo queda una línea en el log.
- El problema de fondo: el canal le habla a un servidor HTTP, y un agente no es un servidor. Hace
  falta algo en el medio que conteste `/event` y `/health`.
- Los tres caminos, de más simple a más inmediato:
  1. **El agente pregunta.** Un receptor chico en VPS-A, al lado del relay, que guarda los eventos;
     la sesión los lee cada tanto. Robusto: si el agente está dormido, los eventos no se pierden.
     La demora es el intervalo de consulta.
  2. **El receptor despierta al agente.** Claude Code tiene rutinas con disparadores por webhook,
     pero las fuentes que se ven son del tipo GitHub. Sin verificar: si un receptor propio puede
     dispararlas directo, o si hay que encadenar receptor → evento de GitHub → rutina. Es el único
     camino con reacción en segundos.
  3. **Por Slack.** El receptor publica en el canal que ya usan las sesiones; un agente lo lee
     cuando está, o avisa al humano.
- Propuesta: el camino 1, y agregarle el push del camino 2 sólo si la demora molesta de verdad.
- **Dos cosas a resolver antes de construir, no después:**
  - **Inyección.** El texto de una notificación es contenido no confiable. Si un agente lee
    `data.text` y actúa, cualquiera que mande una notificación al teléfono le está escribiendo
    instrucciones al agente. El lado MCP ya pone la advertencia de contenido no confiable en cada
    respuesta (regla de `CLAUDE.md`), pero **el canal manda JSON crudo sin ninguna advertencia** y
    eso no está escrito en ningún lado. Quien lo consuma tiene que tratarlo como dato, nunca como
    instrucción.
  - **Privacidad.** Esto saca del teléfono los títulos y textos de todas las notificaciones, las de
    apps de dinero incluidas, hacia el VPS. Pide filtro por app y pasar por Privacy Mode.
- Decide el humano: qué eventos salen del teléfono, con qué filtro, y si la reacción tiene que ser
  en segundos o alcanza con que el agente pregunte cada tanto.
- Decidido por el humano el 2026-10-13: va **después** del punto 4.

