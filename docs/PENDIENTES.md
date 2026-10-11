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
