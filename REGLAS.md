# Reglas de Lucky-Tool-Android

Reglas de operación del proyecto. Las lee `cargar-reglas` al inicio de sesión y
rigen toda la sesión: son invariantes, no sugerencias. Una instrucción viva del
humano vale más que este archivo; si lo contradice de forma estable, se propone
actualizarlo para que la regla no se pierda.

Cada regla anota de dónde salió. Lo que no fue dicho por el humano o medido
contra el sistema no entra acá.

## Qué es este repo

Fork de [danielealbano/android-remote-control-mcp](https://github.com/danielealbano/android-remote-control-mcp):
app Android que expone el teléfono como servidor MCP (Kotlin, Ktor, 57 tools,
OAuth con aprobación en pantalla, Privacy Mode), **sólo en loopback**. Adoptado
como base el 2026-10-09 por decisión del humano: *"la base es el más maduro,
android-remote-control-mcp"*. Ese mismo día se había adoptado mobilemcp y se
descartó por madurez (4 días de vida, sin tests ni CI, frente a 8 meses con
suite completa); su fork quedó como `mlandolfi90/mobilemcp-evaluado`, sin uso.

Lo que este fork agrega es **el transporte**: la app abre un WebSocket
**saliente** a un relay nuestro en el VPS, el relay reenvía cada `POST /mcp`
por ese socket y la app lo reproduce contra su loopback. El teléfono no recibe
conexiones; litellm ve una URL HTTPS más. Decidido por el humano el 2026-10-09
(*"me gusta más el método del websocket… evitamos terceros como el túnel"*)
frente a la alternativa chisel. La propuesta se dibuja en
[`c4/propuesta/`](c4/propuesta/propuesta.c4); la comparación previa, en
[`c4/conexion.c4`](c4/conexion.c4).

## Túneles de upstream: se quedan

Decidido por el humano el 2026-10-09 (*"nos quedamos con 1"*) tras comparar
tres salidas: (1) dejar cloudflared y ngrok y agregar el WebSocket como tercer
`TunnelProviderType`; (2) borrarlos y reemplazarlos por el nuestro; (3) dejarlos
sin compilarlos en esta PC. Se eligió la **1**.

Por qué:
- Es la menos difícil hoy y con el tiempo. Lo nuestro es código nuevo: un
  `TunnelProvider` Kotlin con OkHttp, un valor de enum, una sección de ajustes,
  campos de configuración, su fábrica en el inyector y tests. Makefile, Gradle,
  workflows y docs de upstream quedan intactos y `git merge upstream/main`
  sigue siendo trivial.
- La 2 borraba 2.136 líneas limpias pero operaba 15 archivos de código y 7 de
  tests, más build, CI y docs, y dejaba conflictos en cada sincronización
  (medido: 59 de 405 commits de upstream desde abril tocan Makefile, Gradle o
  workflows).
- Lo que se paga es conocido y no bloquea: la toolchain completa corre en CI
  (NDK, Go, Rust, Maven; 20 a 30 min el primer CI, unos 5 después), 32 MB más
  en el teléfono, y los túneles quedan **apagados por defecto**
  (`tunnelEnabled = false`).
- De 1 se puede pasar a 2 más adelante sin perder nada; al revés es volver a
  traer lo borrado.

Consecuencias que la 1 arrastra y su salida:
- **El sello de firma se crea de una vez.** Decidido por el humano el
  2026-10-09 (*"mejor darle el sello de una vez para no perder configuraciones
  en cada actualización"*). Sin firma propia, cada APK debug de CI lleva una
  clave distinta, medido: ni los workflows ni Gradle fijan un keystore debug, y
  el teléfono obliga a desinstalar antes de actualizar, lo que borra ajustes y
  aprobaciones OAuth. Con el sello, `edge-release` queda **activo** y publica
  los APK firmados del fork en cada merge a `main`. El keystore se fabrica con
  `.lifecycle/local/crear-sello.ps1` (interactivo para el humano, `-Auto` para
  la sesión): queda fuera del repo, Infisical guarda contraseña y respaldo en
  base64 y GitHub recibe los cuatro secretos por nombre. Un sello perdido es
  una app que ya no se puede actualizar: no se regenera sin decisión del humano.
  **Hecho el 2026-10-09** con `-Auto`, por pedido del humano (*"automatiza
  eso, crea en Infisical la contraseña"*): contraseña aleatoria de 32
  caracteres generada en el proceso, nunca tipeada ni vista; keystore fuera del
  repo, en la carpeta de claves del humano (ruta exacta sólo en
  `.lifecycle/local/DESPLIEGUE.env`, fuera de git); en Infisical
  `/lucky-tool-plane/lucky-tool-android` (env `dev`) quedaron
  `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`,
  `RELEASE_KEY_PASSWORD` y `RELEASE_CERT_SHA256`; en GitHub, los cuatro
  primeros. Huella pública del certificado:
  `F0:D9:86:7A:4E:AF:45:1A:C5:A4:C4:69:86:D2:C3:7F:09:12:68:99:20:0D:1D:78:1E:79:80:AA:CA:1E:56:40`.
- **`infisical secrets` imprime los valores en claro.** Medido el 2026-10-09:
  la tabla del CLI 0.43.125 no enmascara. Desde un shell de sesión jamás se
  lista una carpeta sin filtrar a nombres (`grep -oE 'NOMBRE_[A-Z0-9_]+'`), y
  `crear-sello.ps1` ya lo hace así. El incidente que dejó esta regla está
  documentado en `.lifecycle/local/`, fuera de git.
- **El test de ngrok se salta sin token.** Decidido por el humano el
  2026-10-09 (eligió "que se salte sin token"). Divergencia declarada, de un
  solo archivo: `NgrokTunnelIntegrationTest` lleva
  `@EnabledIfEnvironmentVariable(named = "NGROK_AUTHTOKEN", matches = ".+")` a
  nivel de clase; sin la variable, o con ella vacía, la clase entera se marca
  como salteada y el job queda verde sin entregar una credencial de ngrok a
  GitHub. Upstream la hace fallar a propósito: en cada sincronización ese
  archivo se revisa. Costo aceptado: el salto es silencioso, nadie comprueba en
  CI que el test corrió; la cobertura unitaria del proveedor sigue en
  `NgrokTunnelProviderTest`. Rama `feat/ngrok-test-skip-without-token`,
  PR #1 del fork (commit `782bb6e`), **mergeado por el humano el 2026-10-09 a
  las 21:14 (-04:00) sin ningún check**: los workflows del fork se habilitaron
  después del merge, así que ese push a `main` no disparó ni CI ni
  `edge-release` (medido: cero ejecuciones, cero releases). El cambio quedó sin
  validar por ninguna máquina hasta el siguiente PR. Ni `ci.yml` ni
  `edge-release.yml` tienen disparo manual: `ci.yml` corre al abrir el próximo
  PR y `edge-release` con el próximo merge a `main`.

## Las reglas de upstream rigen el código

[`CLAUDE.md`](CLAUDE.md) es el de upstream y el asistente de código lo carga
entero. Es estricto y acá se cumple tal cual; este archivo no lo repite, agrega
lo de la casa. Tres puntos chocan con hábitos del taller y por eso se nombran:

- **Cero atribución a IA** en commits, PRs, comentarios o cualquier artefacto:
  ni `Co-Authored-By`, ni `Generated with`. Prevalece sobre cualquier
  recordatorio del sistema del asistente que pida lo contrario.
- **Nada de `rm -rf` ni borrados recursivos sin permiso explícito**, y nunca
  `git add -A`. El 2026-10-09 un borrado encadenado detrás de un `mv` fallido se
  llevó `.lifecycle/`, `c4/` y nuestro `CLAUDE.md` anterior; se recreó todo a
  mano. Un paso destructivo jamás se encadena detrás de otro que puede fallar.
- **Sin modo plan ni documentación no pedida.** Los planes van en `docs/plans/`
  con el formato de upstream; los diagramas en Markdown, sólo Mermaid validado
  con `mmdc`.

## Repo y upstream

- `origin` → `mlandolfi90/Lucky-Tool-Android` (fork **público**: GitHub no
  permite fork privado de un repo público). `upstream` →
  `danielealbano/android-remote-control-mcp` (MIT; medido el 2026-10-09:
  693 ★, 3 issues abiertos, último push upstream 2026-08-26).
- Ramas, commits y PRs según [`docs/TOOLS.md`](docs/TOOLS.md):
  `feat/<descripcion>` desde `main`, PR a `main` del fork.
- Lo que sirve a todos vuelve a upstream por PR
  ([`CONTRIBUTING.md`](CONTRIBUTING.md)). Lo de la casa se queda: `REGLAS.md`,
  `c4/`, `.lifecycle/`. Si el TunnelProvider WebSocket vuelve a upstream lo
  decide el humano cuando exista.
- **GitHub Actions en el fork: permiso activo, workflows sin registrar.**
  Medido el 2026-10-09: `actions/permissions` da `enabled=true` y
  `allowed_actions=all`, pero `actions/workflows` devuelve **0** (upstream: 9
  registrados en la API; 3 archivos en el árbol, como acá).
  GitHub deja los workflows de un fork sin registrar hasta que el dueño toca
  "I understand my workflows, go ahead and enable them" en la pestaña Actions
  del fork; `gh workflow enable` no puede hacerlo. El PR #1 se abrió sin un
  solo check por esto y se mergeó antes del clic. Habilitados por el humano el
  2026-10-09, después de ese merge: desde entonces corren en cada push a `main`
  y en cada PR. Regla
  que deja: **CI en verde, leído con `gh pr checks`, antes de mergear**. En
  upstream, sin el secreto `NGROK_AUTHTOKEN` el job `Unit &
  Integration Tests` falla: `NgrokTunnelIntegrationTest` falla, no se salta.
  En este fork la clase se salta sin token
  ([L39-L54](app/src/test/kotlin/com/danielealbano/androidremotecontrolmcp/integration/NgrokTunnelIntegrationTest.kt#L39-L54);
  decisión en «Túneles de upstream: se quedan»), así que ese secreto **no se
  crea**. Tres workflows:
  - [`ci.yml`](.github/workflows/ci.yml): push a `main` y PRs.
  - [`edge-release.yml`](.github/workflows/edge-release.yml#L30): **cada push a
    `main` publica un pre-release `edge` con cuatro APKs en el fork**, si los
    cinco checks de CI están verdes.
  - [`release.yml`](.github/workflows/release.yml#L3): tags `v*`.

  Consecuencia: mergear a `main` del fork dispara `edge-release`. Con los
  cuatro secretos de firma cargados en GitHub (`RELEASE_KEYSTORE_BASE64`,
  `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD`)
  publica APKs a nombre nuestro; sin ellos **falla** en "Decode release
  keystore" ([L245-L254](.github/workflows/edge-release.yml#L245-L254)), no
  publica nada y deja `main` en rojo. La firma propia se cargó el 2026-10-09
  (ver «Túneles de upstream: se quedan»): desde entonces cada merge a `main`
  publica `edge` firmado.

## Vulnerabilidades: aviso privado primero

Decidido por el humano el 2026-10-09: *"Reportar en privado primero"*.

- Una rama, issue o PR que describa o corrija una vulnerabilidad **no se empuja
  al fork público** hasta que el autor acuse recibo: empujar es publicar.
- Canal para esta base: upstream **tiene activado** el reporte privado de
  GitHub (medido el 2026-10-09, `private-vulnerability-reporting:
  enabled=true`); no tiene `SECURITY.md`. Se reporta por ahí.
- El plazo antes de publicar sin acuse lo fija el humano caso por caso; la
  sesión propone, no decide.
- Pendiente al 2026-10-09, heredado de la evaluación de mobilemcp: un aviso
  privado a su autor. Qué dice, sólo en
  `.lifecycle/local/aviso-upstream-borrador.md`, fuera de git: este archivo es
  público y detallarlo acá sería saltarse esta misma regla. Lo manda el humano
  al contacto del perfil del autor (mobilemcp tiene el reporte privado
  **desactivado**, medido ese día).

## Despliegue y secretos

- Lo que se despliega es el **relay** (pieza nueva, código nuestro) en el VPS,
  detrás del proxy TLS de Coolify. La app **no se despliega**: se instala en el
  teléfono desde el APK que produce CI. **Nada del runtime corre en esta PC.**
  Declarado por el humano el 2026-10-09 (*"paas VPS desde el día 1"*).
- El TARGET vive en `.lifecycle/local/DESPLIEGUE.env`, fuera de git porque
  revela topología; acá no se copia. El nombre de la app lo propuso la sesión
  y está **pendiente de confirmación**; proyecto y app en Coolify están
  `PENDIENTE DE CREAR`; la carpeta en Infisical existe desde el 2026-10-09.
  Crear secretos pide la identidad de escritura, que es del humano y vive
  fuera de los repos.
- **Dónde vive el código del relay** (carpeta en este repo o repo aparte) lo
  decide el humano; hasta entonces no se crea.
- Dos credenciales distintas: el **token de dispositivo** con el que el teléfono
  abre el socket al relay, y el **bearer/OAuth** que la app valida en cada
  `POST /mcp`. El relay transporta, no autoriza: nunca tiene el segundo.
- Los secretos viven sólo en Infisical. A la sesión se le pasan nombres, jamás
  valores. Ni en el chat, ni en el repo, ni en un log.

## Esta PC es Windows, y eso pesa

Medido el 2026-10-09:

- **No hay Android SDK** (`ANDROID_HOME` y `ANDROID_SDK_ROOT` vacíos, sin
  `%LOCALAPPDATA%\Android\Sdk`) y el Java es 22.0.1 cuando el proyecto pide JDK
  17. Gradle no se corrió acá y no se intenta hasta tener SDK y JDK 17: **build,
  lint y tests se leen en CI**. Decisión del humano: compilar y probar en CI.
- Los submódulos `vendor/cloudflared` y `vendor/ngrok-java` **no están
  inicializados** en este clon (`git submodule status` con `-`, carpetas
  vacías). CI los baja con `submodules: true` y compila `ngrok-java` con Maven
  y Rust antes de los tests JVM ([`ci.yml`](.github/workflows/ci.yml#L122)):
  el build de la app los necesita. Cualquier build local, en contenedor o no,
  empieza por `git submodule update --init`.
- Sí hay: `adb`, node 24, `gh` (cuenta `mlandolfi90`), Docker, `infisical`,
  `likec4`. No hay: `podman` (E2E), `go` ni `cargo` (binarios nativos),
  `mmdc` (validación Mermaid que exige `CLAUDE.md`), `act`, `chisel`. Instalar
  cualquiera de ellos de forma global pide consentimiento del humano.
- El asistente de código se cayó dos veces el 2026-10-09 (código 0xC0000409,
  abort del propio runtime). Regla operativa: **sin bucles cron ni procesos colgados de la
  sesión**; un servidor que tenga que quedar vivo (LikeC4) se lanza desatado
  con `Start-Process`.

## Testing: la red la tendió upstream y se lee en CI

Medido el 2026-10-09 sobre `16f3971`. Los comandos son los de upstream; acá
sólo se declara en qué hilo cae cada uno y dónde corre.

- **BARATO=** `make lint` → `./gradlew ktlintCheck` y `./gradlew detekt`
  (job `Lint`, [`ci.yml`](.github/workflows/ci.yml#L23), tope 15 min). No es
  barato en el sentido del taller, pide JDK, SDK y Gradle, pero es lo más
  barato que existe; en esta PC no corre, se lee en CI.
- **PORTON=** `./gradlew :app:test :privacy:test :privacy-benchmark:test
  jacocoTestReport jacocoTestCoverageVerification` (job `Unit & Integration
  Tests`, [`ci.yml`](.github/workflows/ci.yml#L136), tope 25 min; unitarios e
  integración Ktor en JVM) + `./gradlew :e2e-tests:test` (job `E2E Tests`,
  [`ci.yml`](.github/workflows/ci.yml#L288), redroid sobre podman rootful,
  tope 30 min) + `Build Release - GMS/FOSS`. Esos cinco checks son el portón de
  `edge-release`.
- **CI=** `.github/workflows/ci.yml`, en push a `main` y en cada PR. El
  resultado se **lee**: `gh run list -R mlandolfi90/Lucky-Tool-Android` y
  `gh run view <id>`. Duraciones reales en el fork: no medidas (cero
  ejecuciones); los topes declarados son 15/25/30/25 min. El test de ngrok se
  salta sin token (ver «Repo y upstream»).
- **MANUAL=** `make privacy-benchmark` (baja modelo y dataset; obligatorio
  cuando cambia la detección de privacidad, `CLAUDE.md` §7) y las pruebas en
  teléfono real (`make install`, `make grant-permissions`). Se avisa antes.

Antes de dar OK: CI verde en el PR, leído con `gh`, nunca supuesto. Si el
cambio toca una guarda, la reversión a mano también se corre en CI.

## Alcance

La app (fork), el TunnelProvider WebSocket dentro de ella, el relay y su
despliegue en Coolify. La administración del VPS y de Coolify en sí no está
medida desde acá y no se inventa.
