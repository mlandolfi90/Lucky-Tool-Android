# Lucky-Tool-Android en bloques de funcionalidad, en C4

Proyecto LikeC4 `lucky-tool-android-bloques`, del 2026-10-16. Separa la app en bloques de
funcionalidad, cada uno con su cadena **entrada → lo que se quiere → lo que se obtiene**, para
hablar de la app bloque por bloque. Copia la estructura de `Lucky-SecondBrian/docs/diagramas/c4-bloques/`.

Sale del código (cada pieza dice su función y su `archivo:línea`, verificados con codebase-memory y
leyendo el código) y de `docs/ARCHITECTURE.md`.

## Cómo se navega: tres niveles

Se abre en VS Code: cualquier `.c4` de esta carpeta, y Ctrl+Shift+P → «Open Preview» (LikeC4).

| Nivel | Qué muestra | Cómo se llega |
|---|---|---|
| 1 · El mapa | `index`: todas las franjas, una debajo de la otra; y dos vistas más, `dependencias` (cómo se apoyan los bloques entre sí y con lo de afuera) y `dependencias-datos` (qué datos toca cada bloque) | es la vista de entrada |
| 2 · La franja | un bloque visto desde afuera: quién lo pide, su cadena y quién usa lo que sale; sin piezas | un clic en la franja, en el mapa |
| 3 · El flujo | paso a paso, numerado, con las piezas del código y los datos que tocan: cajas unidas por flechas numeradas. Si pasa de ~22 pasos se parte en varios flujos que se enlazan; un bloque con muchas cosas distintas se parte en sub-bloques (el 05: una vista `subbloques-configurar` entre la franja y los flujos) | un clic en «lo que se quiere», en el mapa o en la franja |

En la barra lateral de LikeC4 las vistas están en carpetas con el mismo número que las carpetas de
acá: «00 · Mapa», «01 · Arrancar el servidor»… Cada bloque tiene la suya, así que sumar un bloque
no mueve a los demás.

## Los bloques

| N° | Bloque | Carpeta | Estado | Flujos |
|---|---|---|---|---|
| 01 | Arrancar el servidor | [`01-arranque/`](01-arranque/) | hecho | `flujo-arranque` (21 pasos) |
| 02 | Atender un pedido del agente | [`02-atender/`](02-atender/) | hecho | `flujo-atender` (20 pasos) |
| 03 | Aprobar una acción | [`03-aprobar/`](03-aprobar/) | hecho | `flujo-aprobar-decidir` (19 pasos), `flujo-aprobar-chat` (13), `flujo-aprobar-avisar` (20) |
| 04 | Conectar un agente | [`04-conectar/`](04-conectar/) | hecho (versión simple) | `flujo-conectar` (19 pasos) |
| 05 | Configurar los perfiles de aprobación | [`05-configurar/`](05-configurar/) | hecho, en 6 sub-bloques | `flujo-listar` (17), `flujo-agregar` (11), `flujo-reglas` (5), `flujo-monto` (10), `flujo-excepciones` (9), `flujo-guardar` (11) y el camino completo, `flujo-configurar` (11) |
| 06 | Decidir con la IA local | [`06-juez-local/`](06-juez-local/) | idea, en 5 sub-bloques | `flujo-juez-firmar` (10), `flujo-juez-ciclo` (15), `flujo-juez-decidir` (13), `flujo-juez-ia` (16) y `flujo-juez-detener` (13) |

## Cómo se lee

El color dice el estado del bloque (o la pieza) en nuestra versión: la punta de la pila de ramas del
fork, no `main`. Es una etiqueta en el `.c4`:

| Color | Etiqueta | Quiere decir |
|---|---|---|
| verde, borde continuo | `#hecho` | hecho en nuestra versión |
| violeta, borde punteado | `#plan` | plan escrito en `docs/plans/`, sin implementar |
| gris, borde de puntos | `#idea` | diseño o idea, sin plan |

La franja dibuja sólo la capa de afuera (pedido del humano, 2026-10-16): quién pide el bloque, la
cadena y quién usa lo que sale. Las piezas aparecen recién en el flujo. Las flechas punteadas son
«usa»: una pieza toca unos datos o algo de afuera. Las piezas sin etiqueta tienen el estado de su bloque. En los flujos del 06, que es una idea, las piezas grises de puntos son las nuevas y las de color normal ya existen: son de los bloques 02, 03 y 05.

## Qué hay en cada carpeta

| Archivo | Qué tiene |
|---|---|
| `00-mapa/specification.c4` | el vocabulario (bloque, entrada, objetivo, resultado, datos, pieza) y el grupo de estilo `estados` |
| `00-mapa/comun.c4` | quién usa la app y lo de afuera (quien usa el teléfono, quien conversa con el agente, el agente, Android, la PC por adb, las apps del teléfono, el túnel), los datos (los ajustes, el certificado, los agentes conectados, los perfiles de aprobación, los planes firmados —una idea—, el registro del servidor) y la caja `app`, vacía |
| `00-mapa/vistas.c4` | `index`, `dependencias` y `dependencias-datos` |
| `NN-bloque/bloque.c4` | el bloque (se suma a `app` con `extend app`), sus piezas, qué datos toca, quién lo pide desde afuera, y su franja |
| `NN-bloque/flujos.c4` | sus flujos, cuando estén dibujados |
| `NN-bloque/README.md` | lo que hay que saber del bloque, cuando tenga flujos |

## Cómo se suma un bloque

1. Una carpeta `NN-nombre/` con su `bloque.c4`: `extend app { nombre = bloque '…' { … } }`, con la
   cadena (`entrada`, `quiere`, `obtiene`), sus piezas (cada una con función y `archivo:línea`) y
   su vista `bloque-nombre`, dentro de `views 'NN · Nombre' { … }`.
2. En `00-mapa/vistas.c4`, la cadena del bloque en `index`, arriba de las anteriores (LikeC4 apila de
   abajo hacia arriba).
3. Acá, su fila en la tabla de bloques.

Un flujo se dibuja como en SecondBrian: una `dynamic view flujo-<bloque>-<qué>` sin
`variant sequence` (cajas con flechas numeradas, `autoLayout LeftRight`), con la condición de cada
rama al principio de su etiqueta y el número de cada paso en un comentario.

Se valida con `likec4 validate --json .` (LikeC4 1.59.4): sin errores el 2026-10-16, con veintinueve vistas.
Los PNG no se guardan acá: se exportan a pedido (`likec4 export png -o <carpeta> .`).
