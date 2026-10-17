# 03 · Aprobar una acción

**Entrada:** una llamada del agente a una herramienta que llegó hasta el portón de aprobación. **Lo
que se quiere:** dejar correr sólo lo que el dueño permitió, según el perfil de cada app destino.
**Lo que se obtiene:** la herramienta corre, o vuelve un resultado de error o de espera
(`forbidden`, `needs_human_confirmation`, `awaiting_approval`, `denied`, `expired`, `not_required`,
`already_used`, `too_many_pending`). Hecho en nuestra versión.

Abre por dentro la caja «El portón de aprobación» del bloque 02 (`ToolPolicyGateImpl.guard`).

## Las vistas

| Vista | La pregunta que contesta |
|---|---|
| `bloque-aprobar` | La franja: ¿quién lo pide, a quién se recurre y quién recibe lo que sale? |
| `flujo-aprobar-decidir` | Paso a paso, en 19: ¿corre, se prohíbe o hace falta aprobación? Del catálogo al autorizador |
| `flujo-aprobar-chat` | En 13: el modo «preguntar en el chat»; el agente le pregunta a la persona y repite la llamada |
| `flujo-aprobar-avisar` | En 20: el modo «avisar al dueño»; notificación, espera de 25 s y, si no responde, el seguimiento |

Son tres flujos porque juntos pasan de 50 pasos. Se enlazan: en el primero, «Pedir confirmación en el
chat» y «Avisar al dueño» abren el segundo y el tercero; en esos dos, «El autorizador» y «El portón de
aprobación» vuelven al primero (la llamada repetida entra por el portón y sigue en los pasos 16 y 17).

## Cómo se lee el dibujo

- Dentro del portón, una flecha quiere decir «la llamada pasa de una pieza a la siguiente», en el
  orden en que la usa el código. El portón las llama a todas; se dibujan en cadena para que se lea de
  izquierda a derecha.
- El portón, el registro y el aviso en pantalla son del bloque 02. Lo que frena el portón vuelve al
  registro (así está armado: `logged(gate(indicator(handler)))`, `LoggedToolRegistration.kt:121`).

## Lo que dice el código y conviene tener a mano

- Una herramienta sin clase en el catálogo no deja ni registrarse. Las que no actúan sobre una app
  (archivos, cámara, ubicación, las dos tools de aprobación) y las salidas libres (inicio, recientes…)
  nunca leen los perfiles: un almacén roto no las frena.
- Si el almacén de perfiles no se puede leer, no se lee como «sin perfiles»: toda acción que no sea
  lectura se prohíbe, y la próxima llamada vuelve a intentarlo.
- Con algún perfil, las acciones que no son lecturas se evalúan y corren de a una (la cola). Una
  acción larga, como escribir, hace esperar a las demás.
- Tocar esta misma app se prohíbe siempre, aunque no haya perfiles; si no, el agente podría aprobarse
  a sí mismo.
- Un permiso sirve una vez, para la llamada idéntica (misma huella) y dura 2 min. El que da el dueño
  en el teléfono vale para las dos clases de aprobación; el que da el chat, sólo para «preguntar en
  el chat». Una pendiente dura 5 min y puede haber 10 a la vez.
- «Preguntar en el chat» se apoya en el modelo: la app no puede comprobar que se le preguntó a la
  persona. Donde haga falta garantía, el perfil debe usar «avisar al dueño» o «prohibir».
- La notificación al dueño no tiene botones (un agente podría apretarlos): el dueño aprueba o deniega
  en la pantalla de aprobación, que ningún agente puede tocar.
- Tras la aprobación del dueño, el permiso se gasta recién justo antes de correr, con la cola otra vez
  tomada. Si la revisión falla porque la app no está en pantalla, el permiso queda para la repetición.

## Una discrepancia con la documentación

`docs/ARCHITECTURE.md`, en la secuencia de `NOTIFY`, dibuja el gasto del permiso antes de volver a
tomar la cola. El código (`ToolPolicyGateImpl.kt:122-165`) toma la cola, vuelve a resolver y evaluar, y
recién después gasta el permiso, igual que dice `docs/PROJECT.md`. El flujo `flujo-aprobar-avisar` sigue
el código.
