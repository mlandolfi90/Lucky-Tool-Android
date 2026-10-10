# c4/ — modelos LikeC4 de este repo

Dos proyectos LikeC4 separados:

- `propuesta/` — **la propuesta de implementación** (declarado por el humano el
  2026-10-09). Proyecto propio (`likec4.config.json`, id `propuesta`): no comparte
  modelo con lo demás. Arrancó con un solo nodo, el Operador, y se dibuja paso a
  paso con lo que el humano dice (en chat o desde la página, por el canal de feedback).
  Estado al 2026-10-09: Operador → Agente AI → litellm → {MCP Android, otros MCP}.
  **Transporte elegido para el MCP Android: WebSocket propio (relay en el VPS +
  TunnelProvider Kotlin en android-remote-control-mcp)**; chisel queda como
  alternativa no elegida, documentada en su propia vista.
- `conexion.c4` (proyecto `default`) — la exploración previa: diagrama de conexión
  con las dos opciones que se compararon (A: mobilemcp con hub propio; B:
  android-remote-control-mcp + chisel). Es material de decisión, no la propuesta.

Se ve con `likec4 start c4` (http://localhost:5173/; la propuesta en
`/project/propuesta/view/index/`) o por el MCP `likec4`.
