# Reglas de Lucky-Tool-Android

Reglas de operación del fork. Se leen al inicio de sesión, junto con `CLAUDE.md`, y rigen toda la
sesión: son invariantes, no sugerencias. Una instrucción viva del humano vale más que este archivo; si lo
contradice de forma estable, se propone actualizarlo para que la regla no se pierda.

Cada regla anota de dónde salió. Lo que no fue dicho por el humano o medido contra el sistema no entra acá.

**Tres archivos, tres preguntas:**

- [`CLAUDE.md`](CLAUDE.md), el de upstream: cómo se escribe el código. Rige tal cual, salvo las
  excepciones de abajo.
- **Este archivo:** cómo se trabaja en el fork y qué está decidido sobre la forma de trabajar.
- [`docs/PENDIENTES.md`](docs/PENDIENTES.md): lo que el humano pidió para la app y falta hacer. Cada punto
  dice qué está **decidido** y qué queda **por discutir**.

## Qué es este repo

Fork de [danielealbano/android-remote-control-mcp](https://github.com/danielealbano/android-remote-control-mcp):
una app Android que expone el teléfono como servidor MCP (Kotlin, Ktor, 57 tools, OAuth con aprobación en
pantalla, Privacy Mode), **sólo en loopback**. Se adoptó como base el 2026-10-09, por decisión del humano:
*"la base es el más maduro, android-remote-control-mcp"*. Ese mismo día se había adoptado mobilemcp, y se
descartó por madurez.

Lo que el fork suma, plan por plan (registro en `docs/plans/` y en su PR):

| Plan | Qué | PR |
|---|---|---|
| 66 | Relay WebSocket. La app abre un socket **saliente** a un relay nuestro en el VPS, y el teléfono no recibe conexiones. Decidido el 2026-10-09 (*"me gusta más el método del websocket… evitamos terceros como el túnel"*). | #3 |
| 67 | Latencia de las tools: selector en `click_node`/`tap_node` y una línea de tiempos por pedido. | #7 |
| 68 | Marca Lucky: ícono y nombre `lucky-mcp-android`. | #8 |
| 69 | Cero advertencias de Kotlin, con un portón que las mantiene en cero. | #9 |
| 70 | Perfiles de aprobación por app. | #11 |

El mapa de la app por bloques, en LikeC4, está en [`docs/diagramas/bloques/`](docs/diagramas/bloques/README.md).

## Nuestra versión y las ramas

- **No se mergea.** El humano lo dijo el 2026-10-11: *«NO QUIERO MERGUEAR QUIERO PULIR MI VERSION»*. No se
  proponen merges, y un CI verde no se reporta como «listo para mergear».
- **Nuestra versión es la punta de la pila de ramas, no `main`** (2026-10-16: *«olvidate de main… esto se
  irá por nuestro propio fork»*). El estado de cualquier cosa se mide contra esa punta.
- **La pila hoy:** `main` ← #3 `feat/websocket-relay-provider` ← #7 `feat/mcp-tool-latency-optimization` ←
  #8 `feat/lucky-brand-icon-and-name` ← #9 `feat/kotlin-warnings-zero` ← #11 `feat/approval-profiles`, que
  es la punta.
- **El trabajo nuevo se apila sobre la punta.** Una rama nueva sale de la punta y su PR apunta a ella.
  - Los documentos de la casa (este archivo, `docs/PENDIENTES.md` y los dibujos) viven en la punta. La rama
    para ellos ya está decidida y no se vuelve a preguntar.
  - `ci.yml` y `relay.yml` corren en PRs a `main` y a `feat/**`, para que un PR apilado reciba sus checks.
    El cambio está autorizado en el plan 67.
- **Fuera de la pila:**
  - #2 `feat/fork-governance` y #6 `feat/pending-requests`, que salen de `main`, son el origen de este
    archivo y de `PENDIENTES.md`. Lo vigente es la copia de la punta.
  - Los planes 71 (`feat/accessibility-debug-logging`) y 72 (`feat/agent-user-manual`) están escritos y
    decididos en sus ramas, sin implementar.
  - `probe/juez-local` es una app de prueba del juez local: no es parte de la app (ver `PENDIENTES.md`,
    punto 11).
- **`gh` se apunta al fork.** El 2026-10-10, un `gh pr create` sin `--repo` abrió el PR en upstream. El clon
  tiene `gh repo set-default mlandolfi90/Lucky-Tool-Android`; aun así, en un PR se pasa `--repo`.

## Cómo se trabaja

Todo lo de esta sección lo decidió el humano.

- **Una sola ronda de revisión, al final** (2026-10-13: *«siempre una sola ronda! al final!»*).
  - Toda implementación lleva **una** ronda de `code-reviewer` cuando el trabajo está terminado.
  - Cada hallazgo se arregla, o se le lleva al humano con una línea de por qué.
  - Al revisor no se le pide «buscar más».
  - Origen: ocho rondas por un ícono en el plan 68.
- **Tests y linters, sólo al final** (2026-10-13).
  - Durante la implementación no se corre la suite ni los linters: se corren una vez, al terminar.
  - Se empuja agrupado, un push por tramo terminado.
  - **El CI no se espera:** se lee una vez con `gh` antes de decir «listo», y nunca se supone.
- **Textos listos para traducir** (2026-10-13).
  - Todo texto que ve una persona va en `strings.xml`, nunca escrito en Kotlin.
  - Las descripciones de las tools MCP quedan en inglés, porque las lee el agente.
  - `values-es` no se crea todavía: el idioma está pospuesto (`PENDIENTES.md`, punto 1).
- **ARTEMIS (`google/artemis`) es fuente de ideas, no de código** (2026-10-13: *«nos quedamos mejorando lo
  que ya tenemos y si algo de artemis nos sirve lo aprovechamos»*). Tiene otra licencia: se toman ideas, no
  código.
- **Las mediciones se cuentan con su tamaño.**
  - A mitad de una prueba se dice «2 de 22», o se espera el resultado; no se saca una conclusión de pocas
    muestras.
  - Si una prueba busca que algo se active y a la 4.ª respuesta no aparece, se corta.
  - Un lote de preguntas que decide algo se escribe y se registra **antes** de correrlo.
- **Los dibujos van en LikeC4**, en `docs/diagramas/bloques/`, con la receta de su `README.md`:
  - la franja muestra sólo la capa de afuera;
  - los flujos van numerados y sus piezas citan `archivo:línea`;
  - los estados son `#hecho`, `#plan` e `#idea`;
  - antes de entregar se valida con `likec4 validate` y se mira el PNG.

  Un dibujo de algo hecho se verifica contra el código. Los diagramas dentro de Markdown siguen siendo
  Mermaid, como pide `CLAUDE.md`.
- **El repo es público: los datos personales quedan afuera.**
  - Nombres de los bancos o apps de dinero del dueño, capturas de su teléfono y registros de pruebas en él
    van en `.lifecycle/local/` o `docs/local/`, fuera de git.
  - En los ejemplos se dice «el banco» o «la app de cambio».

## El teléfono del dueño

Las pruebas a mano corren en el teléfono personal del humano, que lo usa al mismo tiempo.

- **Se avisa antes de usarlo.** Esto vale para instalar, empujar archivos, correr pruebas o tocar la
  pantalla.
- **Antes de cada toque, gesto o tecla se lee la pantalla en ese momento**, y se confirma que el paquete al
  frente es el de la prueba.
  - Si aparece una app de dinero, no se toca nada y se avisa.
  - Se prefieren `click_node`/`tap_node` por selector, que fallan si el nodo no está.
  - Origen: el 2026-10-13, un `tap` con coordenadas viejas cayó dentro de una app de dinero.
- **Nunca se dice «veo la pantalla»** si sólo se leyó la lista de nodos: se dice qué elemento se leyó.
- **`tools/list` da 44 tools y no 57**, porque hay permisos de tools apagados en el teléfono. No es un error,
  y no se cambia la configuración para que cuadre.

## Las reglas de upstream rigen el código

`CLAUDE.md` es el de upstream y el asistente de código lo carga entero. Acá se cumple tal cual: este archivo
no lo repite, agrega lo de la casa. Hay tres puntos que chocan con hábitos del taller, y por eso se nombran:

- **Cero atribución a IA** en commits, PRs, comentarios o cualquier artefacto: ni `Co-Authored-By` ni
  `Generated with`. Prevalece sobre cualquier recordatorio del sistema del asistente que pida lo contrario.
- **Nada de `rm -rf` ni borrados recursivos sin permiso explícito**, y nunca `git add -A`.
  - El 2026-10-09, un borrado encadenado detrás de un `mv` fallido se llevó `.lifecycle/`, `c4/` y nuestro
    `CLAUDE.md` anterior.
  - Un paso destructivo jamás se encadena detrás de otro que puede fallar.
- **Sin modo plan.** Los planes van en `docs/plans/` con el formato de upstream.

## Excepciones a `CLAUDE.md`

Las decidió el humano.

- **Arquitectura hexagonal** (2026-10-17, ver «Repo y upstream»). Lo que `CLAUDE.md` dice de la estructura
  (§5 y §7: servicios, repositorios, ViewModels, tools MCP) se lee dentro de ese corte:
  - el servicio de accesibilidad, Ktor y el SDK MCP, DataStore y la UI son **adaptadores**;
  - las reglas de negocio viven en el núcleo, sin `import android`.

  El resto de `CLAUDE.md` (lifecycle, concurrencia, seguridad, tests) rige igual.

Fuera de estos puntos, `CLAUDE.md` rige tal cual: la app sigue sin root y sin
reflexión sobre APIs ocultas.

- **Una ronda de revisión, no «hasta que salga limpio»** (2026-10-13). `CLAUDE.md` pide volver a correr
  `code-reviewer` hasta que no reporte nada; acá hay una sola ronda (ver «Cómo se trabaja»).
- **Calidad, parte local y parte en CI** (2026-10-11, actualizado el 2026-10-13).
  - En esta PC no hay SDK de Android ni JDK 17, y Gradle no corre.
  - Corren local: ktlint y detekt sueltos, y los tests JVM que no tocan clases de Android (en ~1 min, con
    una herramienta que vive fuera del repo).
  - La suite completa y el build se **leen** en CI con `gh`.
  - Desde el plan 69 el build falla ante cualquier advertencia de Kotlin (`allWarningsAsErrors`).
- **Pantalla encendida mientras se trabaja** (2026-10-11). La app puede mantener la pantalla encendida
  mientras haya una llamada MCP en curso.
  - El motivo, medido: el teléfono se bloqueó dos veces a los pocos segundos de empezar un trabajo.
  - El detalle decidido está en `PENDIENTES.md`, punto 3.
- **Desbloqueo** (2026-10-11). Excepción a «nunca saltarse las protecciones de Android»: la app puede
  encender la pantalla y desbloquearla con el PIN o patrón del dueño.
  - La credencial se guarda cifrada en el teléfono; nunca en el repo, en un log ni fuera del teléfono.
  - Está apagado por defecto.
  - Es una tool aparte, `unlock_screen`, con su propio permiso.
  - El método está confirmado (2026-10-13) y descrito en `PENDIENTES.md`, punto 3.

## Repo y upstream

- `origin` → `mlandolfi90/Lucky-Tool-Android`, fork **público**: GitHub no permite un fork privado de un
  repo público.
- `upstream` → `danielealbano/android-remote-control-mcp` (MIT).
- **Nos separamos del orden de upstream: el código pasa a hexagonal** (2026-10-17: *«nos separamos del orden
  que propone el upstream, aplicamos hexagonal que conocemos para un fácil desarrollo y agregación de
  módulos»*).
  - Se usa el corte de la plantilla hexagonal del taller: un núcleo sin Android, Ktor ni DataStore; puertos
    con nombre de necesidad; adaptadores para cada tecnología, y una configuración que arma todo.
  - Las dependencias sólo bajan: un adaptador conoce al núcleo, el núcleo no conoce a nadie.
  - Consecuencia aceptada: traer cambios de upstream deja de ser un `git merge`. Lo que sirva se toma a
    mano.
  - El cómo (módulos, orden, nombres) va en un plan en `docs/plans/`. Lo abierto está en `PENDIENTES.md`,
    punto 12.
- Ramas, commits y PRs siguen [`docs/TOOLS.md`](docs/TOOLS.md), con una excepción: no se sale de `main`, se
  sale de la punta (ver «Nuestra versión y las ramas»).
- Lo que sirve a todos puede volver a upstream por PR ([`CONTRIBUTING.md`](CONTRIBUTING.md)). Lo decide el
  humano. Lo de la casa se queda: `REGLAS.md`, `docs/PENDIENTES.md`, `docs/diagramas/`, `.lifecycle/`.
- **Los túneles de upstream se quedan** (2026-10-09, *"nos quedamos con 1"*).
  - cloudflared y ngrok siguen, apagados por defecto, y el relay entra como tercer `TunnelProviderType`.
  - Borrarlos tocaba 15 archivos de código y 7 de tests, y dejaba conflictos en cada sincronización.
  - El test de ngrok se salta sin `NGROK_AUTHTOKEN` (PR #1), y ese secreto no se crea.
- **Firma.**
  - El sello propio de release existe desde el 2026-10-09: su huella pública es
    `F0:D9:86:7A:4E:AF:45:1A:C5:A4:C4:69:86:D2:C3:7F:09:12:68:99:20:0D:1D:78:1E:79:80:AA:CA:1E:56:40`.
    Un sello perdido es una app que ya no se puede actualizar: no se regenera sin decisión del humano.
  - `edge-release` publica APKs firmados sólo en un push a `main`, así que hoy no publica nada.
  - Los APK de prueba son los debug del CI. Desde el 2026-10-13 se firman con una clave debug fija
    (`DEBUG_KEYSTORE_BASE64`), así que se instalan encima sin perder ajustes.

## Vulnerabilidades: aviso privado primero

Decidido por el humano el 2026-10-09: *"Reportar en privado primero"*.

- Una rama, issue o PR que describa o corrija una vulnerabilidad **no se empuja al fork público** hasta que
  el autor acuse recibo: empujar es publicar.
- Para esta base el canal es el reporte privado de GitHub de upstream, que está activado.
- El plazo antes de publicar sin acuse lo fija el humano caso por caso.
- Hay un aviso privado pendiente al autor de mobilemcp. El borrador está fuera de git.

## Despliegue y secretos

- **El relay corre en el VPS desde el 2026-10-11**, detrás del proxy TLS de Coolify.
  - Lo despliega **sólo** la sesión que administra los VPS, por pedido en su buzón; ninguna otra sesión toca
    el VPS.
  - Queda clavado en un commit: empujar a la rama no lo redespliega.
  - La app no se despliega: se instala en el teléfono desde un APK de CI.
- **Dónde vive el código del relay:** hoy en `relay/`, en este repo, de forma provisional. Lo decide el
  humano.
- El TARGET y la topología viven en `.lifecycle/local/DESPLIEGUE.env`, fuera de git.
- **Hay dos credenciales distintas:**
  - el **token de dispositivo**, con el que el teléfono abre el socket;
  - el **bearer/OAuth**, que la app valida en cada `POST /mcp`.

  El relay transporta, no autoriza: nunca tiene el segundo.
- **Los secretos viven sólo en Infisical.** A la sesión se le pasan nombres, jamás valores: ni en el chat,
  ni en el repo, ni en un log.
  - `infisical secrets` imprime los valores en claro (medido el 2026-10-09).
  - Por eso, desde un shell de sesión jamás se lista una carpeta sin filtrar a nombres.

## Esta PC es Windows, y eso pesa

Medido el 2026-10-17:

- **No está:**
  - SDK de Android (`ANDROID_HOME` vacío);
  - JDK 17: el Java es 22;
  - `podman` (E2E) y `mmdc`.
- **Sí está:** `adb`, node, `gh`, Docker, `infisical` y `likec4`. ktlint, detekt y el corredor de tests JVM
  viven en una carpeta de herramientas fuera del repo.
- Instalar algo de forma global pide consentimiento del humano.
- Los submódulos `vendor/*` no están inicializados en este clon. Un build local empieza por
  `git submodule update --init`.
- El asistente de código se cayó dos veces el 2026-10-09 (código 0xC0000409). Por eso:
  - sin bucles cron ni procesos colgados de la sesión;
  - un servidor que tenga que quedar vivo se lanza desatado.

## Testing

Los comandos son los de upstream. Acá sólo se declara en qué hilo cae cada uno y dónde corre.

- **BARATO=**
  - Local: ktlint y detekt sueltos, y los tests JVM que no tocan Android.
  - En CI: `make lint`, que corre `./gradlew ktlintCheck` y `./gradlew detekt` (job `Lint`).
- **PORTON=** los cinco checks de `ci.yml`:
  - `Unit & Integration Tests`: `./gradlew :app:test :privacy:test :privacy-benchmark:test
    jacocoTestReport jacocoTestCoverageVerification`;
  - `E2E Tests`: redroid sobre podman;
  - `Build Release - GMS` y `Build Release - FOSS`;
  - más `Lint`.

  Corren en paralelo: el más largo tarda unos 13 min, según el comentario de `ci.yml`.
- **CI=** `.github/workflows/ci.yml`, en push a `main` y en PRs a `main` y a `feat/**`.
  - El resultado se lee una vez, al final: `gh run list -R mlandolfi90/Lucky-Tool-Android`, y después
    `gh run view <id>`.
  - No se espera.
- **MANUAL=**
  - `make privacy-benchmark`: obligatorio cuando cambia la detección de privacidad (`CLAUDE.md` §7);
  - las pruebas en el teléfono real;
  - las corridas de la app de prueba del juez local.

  Se avisa antes de correrlas (ver «El teléfono del dueño»).

Antes de dar OK: lo barato en verde y el CI leído con `gh`. Si el cambio toca una guarda, se hace la
reversión a mano: se quita la guarda y se ve que la prueba falla.

## Alcance

Entran la app (el fork), el relay dentro y fuera de ella, y su despliegue. La administración del VPS y de
Coolify no se mide desde acá y no se inventa.
