# 05 · Configurar los perfiles de aprobación

**Entrada:** el dueño quiere ponerle reglas a una app (Ajustes → Perfiles de aprobación). **Lo que se
quiere:** que cada app tenga las reglas que el dueño quiere. **Lo que se obtiene:** un perfil guardado
que vale desde la próxima llamada del agente. Hecho en nuestra versión.

Es el lado del dueño del bloque 03: ahí el portón lee los perfiles, acá se escriben.

Este bloque se parte en **seis sub-bloques**, uno por cosa que el dueño hace. Cada uno tiene su cadena
(entrada → lo que se quiere → lo que se obtiene), sus piezas y su flujo. Se llega así: la franja
(`bloque-configurar`) → un clic en «lo que se quiere» → los seis sub-bloques (`subbloques-configurar`)
→ un clic en «lo que se quiere» de uno → su flujo. El marco de los sub-bloques abre el camino completo.

## Los sub-bloques

| N° | Sub-bloque | Flujo | Pasos | La pregunta que contesta |
|---|---|---|---|---|
| 1 | Ver y borrar perfiles | `flujo-listar` | 17 | ¿Cómo se ve lo que hay, qué pasa si el almacén falla y cómo se borra un perfil? |
| 2 | Agregar un perfil a una app | `flujo-agregar` | 11 | ¿Cómo se elige la app y la plantilla de un perfil nuevo? |
| 3 | Cambiar las reglas del perfil | `flujo-reglas` | 5 | ¿Qué pasa cuando el dueño cambia la regla de un tipo de acción? |
| 4 | Poner un tope de monto | `flujo-monto` | 10 | ¿Cómo se escribe el tope y los escalones, y qué revisa el validador? |
| 5 | Ver y quitar excepciones | `flujo-excepciones` | 9 | ¿Cómo se quita una excepción y desde cuándo deja de valer? |
| 6 | Guardar el perfil | `flujo-guardar` | 11 | ¿Qué pasa al tocar «Guardar», y por qué puede no guardarse? |
| — | El camino completo | `flujo-configurar` | 11 | De Ajustes al perfil guardado y al portón, en un solo recorrido |

## Las piezas

- Las que usa un solo sub-bloque cuelgan de él (por ejemplo, «La confirmación de borrar» es de
  «Ver y borrar»).
- Las que usan varios cuelgan directo del bloque: **la pantalla del editor**, **el cerebro del editor**
  (el que guarda el borrador y atiende cada cambio), **el borrador**, **el validador** y **el almacén de
  perfiles**.

## Lo que dice el código y conviene tener a mano

- Un perfil por app, con una regla para cada tipo de acción (leer, navegar, escribir, botón de acción).
  Las plantillas sólo dan el punto de partida; después todo se cambia. La «de dinero» exige un tope.
- Nada se escribe hasta «Guardar», salvo quitar una excepción y borrar un perfil, que valen al instante.
- El validador corre en el editor y otra vez en el almacén antes de escribir: un perfil inválido no se
  guarda aunque el botón estuviera prendido.
- Un perfil que no se puede leer se prohíbe del lado del portón (bloque 03) y acá sólo deja borrarlo.
- Borrar un perfil lo hace el cerebro de la lista: le pide al almacén que lo borre y, si se borró, lo anota en
  el registro del servidor (el almacén no anota nada).
- Un perfil nuevo se abre desde la pantalla de agregar y uno guardado, desde la lista. De la plantilla sólo
  viaja el nombre: los valores de partida los arma el cerebro del editor al abrirse.
- Las excepciones («no es un monto», «no es un botón de acción») no se crean acá: nacen de la pantalla
  de aprobación (bloque 03). Acá sólo se ven y se quitan.
- Esta misma app no se ofrece en la lista de apps, y no se puede escribir su nombre a mano.
- El portón lee la memoria del almacén, así que un cambio vale sin reiniciar el servidor.

## Lo que queda para otra vez

- La pantalla de logs: cómo se ven los cambios de perfil en el chip «Aprobaciones».
- El detalle de los textos y mensajes de cada pantalla.
