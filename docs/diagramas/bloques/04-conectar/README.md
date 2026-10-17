# 04 · Conectar un agente

Versión simple: lo justo para ver cómo un agente consigue una credencial. Si hace falta más detalle
(renovar el token, revocar un agente, las pantallas de ajustes), se agrega después.

**Entrada:** un agente que todavía no tiene credencial para llamar a `/mcp`. **Lo que se quiere:** que
tenga una credencial válida. **Lo que se obtiene:** el token fijo, o un token de acceso de OAuth (24 h)
con su token de refresco (90 d). Hecho en nuestra versión.

Abre por dentro lo que el bloque 02 da por hecho en «La puerta con llave»: de dónde sale el token que
ella deja pasar.

## Las vistas

| Vista | La pregunta que contesta |
|---|---|
| `bloque-conectar` | La franja: ¿quién lo pide, a quién se recurre y quién recibe la credencial? |
| `flujo-conectar` | Paso a paso, en 19: el camino del token fijo (3 pasos) y el de OAuth (16 pasos) |

## Lo que dice el código y conviene tener a mano

- Hay dos caminos y se prenden por separado. Con los dos apagados, el servidor queda abierto (la app
  avisa). Las rutas de OAuth sólo existen si OAuth está prendido (`McpServer.kt:200`).
- El token fijo se crea solo, una vez, al primer arranque. En la pantalla del servidor viene tapado,
  con un botón para verlo y otro para copiar.
- En OAuth el agente se registra, pide permiso y el dueño lo aprueba en el teléfono comparando un código
  de 2 dígitos. El código es una confirmación, no un secreto: la única puerta es tocar «Aprobar» en la
  app; la página del agente no tiene botón de aprobar.
- Sólo se registran agentes que vuelven a `claude.ai`, `chatgpt.com` o `localhost`.
- Una pendiente dura 5 min y puede haber 10 a la vez. El código que se le da al agente dura 60 s y sirve
  una vez.
- Si un agente sale de la lista de agentes conectados, su token deja de valer al instante (lo comprueba
  la puerta con llave en cada llamada).

## Lo que queda para otra vez

- Renovar el token con el de refresco y revocar un agente desde los ajustes.
- La pantalla de acceso (prender y apagar cada camino, regenerar el token).
- El resto de la verificación del token que hace la puerta con llave (firma, destino).
