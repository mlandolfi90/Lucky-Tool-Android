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
