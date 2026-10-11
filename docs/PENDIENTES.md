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

## 2. Un ícono que se entienda

Pedido del humano el 2026-10-11: «icono visual entendible».

- Hoy: ícono adaptativo (`app/src/main/res/drawable/ic_launcher_foreground.xml` sobre
  `@color/ic_launcher_background`), ícono de la barra de notificaciones en
  `app/src/main/res/drawable/ic_notification.xml`, y el nombre bajo el ícono (`app_name`) es
  «Android Remote Control MCP», de 26 caracteres.
- Decide el humano: qué muestra el ícono y si la app cambia de nombre.

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
- El nivel de cada paso lo marca el humano al guardar la tarea (decidido el 2026-10-11). Falta
  definir en el plan cómo lo marca cuando la tarea la graba el agente.
- Decide el humano:
  - los niveles de criticidad y qué camino lleva cada uno;
  - qué hace la tarea si un paso no encuentra su elemento (la app se actualizó): parar y avisar en
    qué paso, como propuesta;
  - dónde se ven y se borran las tareas guardadas: en la app, por tools, o en las dos.
