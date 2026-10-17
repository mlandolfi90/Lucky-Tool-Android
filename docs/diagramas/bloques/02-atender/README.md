# 02 · Atender un pedido del agente

**Entrada:** un pedido del agente (un `tools/call` por la red a `/mcp`, con su credencial). **Lo que
se quiere:** hacer en el teléfono lo que pide, sólo si está permitido. **Lo que se obtiene:** la
respuesta (lo que pasó, o lo que se ve con la advertencia de contenido no confiable), o un error.
Hecho en nuestra versión.

## Las vistas

| Vista | La pregunta que contesta |
|---|---|
| `bloque-atender` | La franja: ¿quién pide, sobre qué actúa y quién recibe la respuesta? |
| `flujo-atender` | Paso a paso, en 20: de la credencial a la respuesta, con un ejemplo que actúa (`tap`) y uno que lee (`get_screen_state`) |

## Lo que dice el código y conviene tener a mano

- Cada herramienta corre envuelta en tres capas, en este orden: el registro de la llamada, el portón
  de aprobación y el aviso en pantalla (`logged(gate(indicator(handler)))`,
  `LoggedToolRegistration.kt:121`). Una herramienta sin clasificar no deja arrancar el servidor.
- El aviso en pantalla nunca se ve mientras se espera una aprobación: va adentro del portón.
- Una herramienta que devuelve contenido del teléfono antepone la advertencia de contenido no
  confiable; con el modo privacidad prendido, tacha los datos personales antes (y si falla, da error).
- El portón tiene su propia lógica (perfiles, aprobaciones en el chat, aviso al dueño con espera de
  hasta 25 s): irá en su propio bloque.
