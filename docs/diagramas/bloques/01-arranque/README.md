# 01 · Arrancar el servidor

**Entrada:** un pedido de arranque (el botón «Iniciar», el encendido con el inicio automático, una
actualización de la app con el servidor prendido, una orden por adb, o Android que revive el
servicio). **Lo que se quiere:** el servidor MCP escuchando, en primer plano. **Lo que se obtiene:**
el servidor corriendo y su dirección. Hecho en nuestra versión.

## Las vistas

| Vista | La pregunta que contesta |
|---|---|
| `bloque-arranque` | La franja: ¿quién pide el arranque y quién usa lo que sale? |
| `flujo-arranque` | Paso a paso, en 21: de cualquiera de las puertas al servidor escuchando |

## Lo que dice el código y conviene tener a mano

- Las cuatro puertas mandan el mismo `ACTION_START`; un arranque sin acción (Android que revive el
  servicio con `START_STICKY`) se trata igual.
- Dos pedidos juntos dan un solo servidor: el segundo se ignora (`McpServerService.kt:219`).
- Con HTTPS prendido no hay túnel: el túnel siempre apunta a `http://localhost`.
- Si el túnel falla, el servidor sigue sin túnel; si falla el armado, el estado queda en «error».
