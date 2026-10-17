# 06 · Decidir con la IA local

**Entrada:** el agente pide una acción en una app protegida, con un plan que el dueño ya firmó con la
huella. **Lo que se quiere:** dejar correr sin huella sólo lo que está en ese plan. **Lo que se obtiene:**
la acción corre, o sigue la rama «si no» del plan, o se detiene y el dueño decide.

**Es una idea** (gris, de puntos): el diseño del «plan firmado» (`borradorFlujos/flujo-plan-firmado-v4`,
versión 4.2 del 2026-10-14), sin plan escrito en `docs/plans/` y sin código en la app. El 05 y los perfiles
quedan como están hoy: este bloque dibuja lo que vendría encima.

Lo que ya existe y se reusa (piezas de color normal, de los bloques 02, 03 y 05) no se dibuja de nuevo:
el registro de la llamada, el portón, el catálogo, la cola, el resolutor, el clasificador del toque, el
coordinador de aprobaciones, la notificación, la pantalla de aprobación, el aviso de herramienta en uso, el
servicio de accesibilidad y los perfiles.

Este bloque se parte en **cinco sub-bloques**, uno por cosa que pasa. Cada uno tiene su cadena (entrada →
lo que se quiere → lo que se obtiene), sus piezas y su flujo. Se llega así: la franja (`bloque-juez`) → un
clic en «lo que se quiere» → los cinco sub-bloques (`subbloques-juez`) → un clic en «lo que se quiere» de
uno → su flujo. El marco de los sub-bloques abre el principal, `flujo-juez-decidir`. Desde el flujo de
decidir, «Los dos jueces» abre el de la IA; desde el de la IA, «El freno por salirse del plan» abre el último.

## Los sub-bloques

| N° | Sub-bloque | Flujo | Pasos | La pregunta que contesta |
|---|---|---|---|---|
| 1 | Firmar un plan | `flujo-juez-firmar` | 10 | ¿Quién escribe el plan, qué ve el dueño antes de firmar y qué pasa con la huella? |
| 2 | Empezar y cerrar un plan | `flujo-juez-ciclo` | 15 | ¿Cuándo corre un plan, qué pasa a las 2 horas y cómo se cierra? |
| 3 | Decidir cada acción | `flujo-juez-decidir` | 13 | ¿Qué pasa con cada acción del agente hasta llegar a los jueces? |
| 4 | Los dos jueces | `flujo-juez-ia` | 16 | ¿Cómo lee la IA, cómo compara la app y quién dice sí? |
| 5 | Salirse del plan | `flujo-juez-detener` | 13 | ¿Qué pasa cuando el agente hace algo que no está en el plan? |

## Cómo decide, en corto

- **Libre o protegida.** Una app libre corre como hoy. Una protegida sin plan sólo deja leer y navegar, y un
  paso clave pide que el agente escriba un plan (un pedido suelto es un plan de un solo paso).
- **Con plan en marcha.** Lo que se deshace (navegar, atrás, teclado, pegar, desplazar, la cortina) corre sin
  IA. Lo dudoso o irreversible es un **paso clave**.
- **Paso clave: dos jueces, los dos tienen que decir sí.** El **juez 2** es la IA local (Gemma 4 E4B en la
  GPU): no compara; lee la acción y la pantalla, copia APP, MONTO y DESTINATARIO y dice «ES EL PASO: SI/NO».
  El **juez 1** es la app: compara lo copiado, dato por dato, con lo que el plan acepta en ese paso.
- **Resultado.** Los dos sí: corre y avanza el plan, sin huella. Falló sólo la condición del plan: sigue la
  rama «si no» (esperar o avisar). Otra cosa, o sin IA: el paso se detiene, el agente espera (no se cancela) y
  el dueño elige **CORTAR** o **AMPLIAR** el plan con la huella.
- **Huella sólo para firmar o ampliar un plan.** Si todo va según el plan, cero huellas más.
- **Vida del plan.** Firmado, queda en espera y no corre solo; empieza cuando el dueño dice «ejecutá los
  planes aprobados» (sin huella) y se cancela si no empieza en 2 horas. Se cierra al cumplirse, al decir el
  agente «terminé», con CORTAR TODO, a los 15 min sin actividad o al vencer el tope programado (por defecto,
  24 h), y pasa al historial.

## Lo medido (prototipo en la rama `probe/juez-local`, no en la app)

Sale de `probes/juez-local/tools/REGISTRO.md` de esa rama (punta `4d1e54a`), en un Galaxy S25 Ultra. Las
piezas de la IA citan su archivo:línea del prototipo.

- Modelo: el E4B genérico de Google en la GPU, con decodificación especulativa. El archivo «-gpu» y el kit
  de Qualcomm (NPU) se midieron y se descartaron. Gemini Nano, descartado.
- Modo datos (v7, 44 preguntas): texto 35 de 35 y capturas 8 de 9, sin ningún sí peligroso; unos 1,6 s por
  respuesta con texto y 2,4 s con captura. Memoria: unos 3,8 GB con texto, de 4,2 a 5,3 GB con capturas.
- Sirve en segundo plano: con un servicio en primer plano la prioridad no bajó con otra app al frente.
- **Falta lo importante:** v7 se ajustó mirando esas mismas 44 preguntas. El lote ciego v8 (100 preguntas,
  escrito aparte) está preregistrado en `4d1e54a` y sus corridas esperan al teléfono. Preregistro: aun con la
  copia perfecta, la app frenaría 2 de las 40 que debían pasar.

## Lo que está abierto (no es «hecho»)

- Si el modelo ve sólo «datos ordenados (nunca el chat libre)», como dice el diseño, o texto y capturas de la
  pantalla, chat incluido, como hizo la prueba. **Es una diferencia entre el diseño y lo medido.**
- Qué se le manda al modelo, texto o captura, y cuándo.
- Si se le esconden los datos del plan al modelo (lo sugirió la revisión de código de la prueba).
- «NINGUNO sólo si la acción no muestra ningún monto» (también de la revisión): choca con los pasos de sólo
  mirar que muestran montos. Se mantiene lo que hay. Se mantiene también «sin signo» en MONTO: un monto
  copiado con signo no pasa.
- Que LIBRE y PROTEGIDA reemplacen a las cuatro reglas por app (por confirmar).
- Cómo se corta desde la notificación: la de hoy no tiene botones, porque un agente podría apretarlos.
- Cuánto espera el agente si el dueño no responde (hoy, 5 minutos).
- Si el revisor rechaza un plan con los límites incompletos.
- Cómo se mide la condición de un paso (pago del BDV = monto de la orden): la prueba no la midió.
- Los nombres y argumentos de las herramientas para proponer y empezar planes.
- Borrar del historial desde el agente: propuesta de pedir el OK del dueño en el chat, y que los Logs de
  auditoría no se borren.
- Que un aviso (por ejemplo, que llegó el pago al BDV) arranque el plan solo: depende de despertar al agente
  (punto 7 de `PENDIENTES.md`).
- El riesgo que queda, dicho en el diseño: dentro de los números del plan, una IA engañada podría aceptar un
  paso equivocado. Lo acotan el juez 1, la contraparte fijada al crear la orden y la condición de pago antes
  de liberar.

## Lo que queda para otra vez

- Cómo se ven los planes y el historial en la app (pantallas), y cómo se leen en los Logs.
- Los planes anidados con más detalle (un paso que es otro plan, cada uno con sus límites).
