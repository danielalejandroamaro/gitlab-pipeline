<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# gitlab-pipeline Changelog

## [0.0.8] - 2026-05-25

Copia rápida de tags desde el tree.

- **Ícono de copiar inline en los rows de pipelines-tag** — en cada nodo raíz del árbol cuya `Pipeline.tag == true`, el renderer (`PipelineTreeRenderer`) dibuja un `AllIcons.Actions.Copy` al extremo derecho del cell. El espacio se reserva extendiendo `getPreferredSize().width` (icon width + padding) para que JTree no clippe el ícono, y se pinta en `paintComponent` por encima del area reservada. Click izquierdo en esa zona (hit-test contra `tree.getPathBounds(path).width - iconWidth - 4`) copia `pipeline.ref` al portapapeles vía `CopyPasteManager.getInstance().setContents(StringSelection(...))`. No se desplaza el botón Refresh — sigue donde estaba.
- **Menú contextual (click-derecho) en el tree** — `JPopupMenu` que se arma según el row clickeado:
  - **Pipeline-tag:** "Copiar tag: \<ref\>" (con ícono `Actions.Copy`), separador, "Copiar ID #\<id\>", "Copiar URL", "Abrir en navegador".
  - **Pipeline no-tag:** "Copiar ID", "Copiar ref: \<branch\>", "Copiar URL", "Abrir en navegador".
  - **Job:** "Copiar nombre: \<name\>", "Copiar URL del job", "Abrir job en navegador".
  - `isPopupTrigger` se chequea en `mousePressed` Y `mouseReleased` para que el menú salga tanto en Windows (release) como en Linux (press). El row se selecciona antes de mostrar el menú para que el usuario vea sobre qué está actuando.
- **Notificación de confirmación** — cualquier acción de copia (inline o desde el menú) dispara una notificación informacional en el grupo "GitLab Pipeline Watcher" diciendo "Copiado al portapapeles: \<qué\>". Sin balloons intrusivos, solo el toast estándar del IDE.
- **Tooltip explicativo en rows de tag** — el renderer pone `toolTipText = "Click en el ícono ⧉ para copiar el tag (\<ref\>); click-derecho para más opciones"` para que el ícono no quede sin explicación.

## [0.0.7] - 2026-05-25

Hot loop: auto-refresh, feedback de botón y push detection con runner-aware delay.

- **Auto-refresh cada 3s del listado de pipelines** — `GitLabPipelineService` ahora abre, en su `init`, un loop background (`scope.launch(Dispatchers.IO)`) que ejecuta `refreshNow()` cada 3s mientras haya `.gitlab-ci.yml`. Primer tick es inmediato para que el indicador izquierdo tenga datos sin esperar a abrir el tool window. Resuelve "no se actualiza con la frecuencia que me gustaría" — antes solo se refrescaba en push de tag o click manual.
- **Botón Refresh: feedback + emite siempre** — el `MutableStateFlow` deduplica valores estructuralmente iguales, así que un `refresh()` que devolvía la misma lista quedaba como no-op y el botón "funcionaba a veces". Ahora `State` lleva dos campos nuevos: `isRefreshing: Boolean` y `lastRefreshedAt: Long`. `refresh()` flipa `isRefreshing` antes del fetch y lo revierte + bumpea `lastRefreshedAt` en `finally`, garantizando 2+ emisiones por click incluso si el listado no cambió. En el tool window el botón se desactiva durante el fetch y la status label muestra `· actualizado HH:mm:ss`.
- **Children del treeview se mantienen al día (pipelines NO-followed)** — nuevo loop en `PipelinePanel` (`jobsRefreshLoop`, cada 3s) que recorre los nodos pipeline expandidos por el usuario, excluye el seguido (sus jobs ya vienen via `state.stages`) y los terminales (no cambian), y para los restantes llama `service.fetchJobs(id)`, sustituye los `JobRow` cacheados y re-expande el nodo preservando la posición. Antes los jobs se cacheaban al primer expand y se quedaban congelados.
- **`GitLabApiClient.listPipelines` ahora retorna `List<Pipeline>?`** — fallos transitorios (timeout, 5xx, parse error) devuelven `null` en vez de `emptyList()`. `refreshNow()` lo usa para distinguir "GitLab respondió 0 pipelines" de "no llegamos a GitLab" y, en el segundo caso, no clobera la lista existente (antes la UI flasheaba vacía en cada blip de red, perdiendo el cache de jobs y la expansión).
- **Push detection: delay inicial + baseline pre-refresh** — dos arreglos en `onPushDetected()` / `followTag()`:
  1. `delay(2_000)` **antes** de empezar el polling. El runner tarda 1–3s en materializar la fila del pipeline en GitLab; polear antes era pegarle a un endpoint con la respuesta vieja N veces seguidas.
  2. `baselineLatestTagPipelineId` se snapshotea **antes** de `refreshNow()`. Con el auto-refresh nuevo, en el momento en que llega el push-detection el listado podría ya incluir el pipeline nuevo y el baseline lo capturaría — entonces el polling esperaría a uno *aún* más nuevo y nunca terminaría. Snapshot pre-refresh = baseline correcto, y como `refreshNow()` corre antes del polling, si el pipeline ya está allí lo detectamos en el primer attempt.
  3. Primera fase del ramp sube de 500ms → 1s × 20 (menos hammering durante la ventana en que el pipeline igual no existe). Total del ramp queda en ~6 min, igual que antes.

## [0.0.6] - 2026-05-25

- **Status bar widget: prefer-left, fallback-right-last** — el indicador del panel izquierdo (`LeftPipelineIndicator`, montado vía reflexión en `IdeStatusBarImpl.leftPanel` por `LeftIndicatorMounter`) ahora **remueve el widget derecho** del status bar (`statusBar.removeWidget(WIDGET_ID)`) cuando logra montarse en el panel izquierdo, evitando que aparezcan dos indicadores duplicados. Si la reflexión falla en una versión futura del IDE, el `statusBarWidgetFactory` cae a `order="last"` (antes `before NavBar, first`) → el widget aparece en el extremo derecho del status bar (i.e. "primero" leído derecha-a-izquierda). Resumen del nuevo orden de preferencia: 1) leftmost del panel izquierdo, 2) rightmost del panel derecho.
- **Tool window: tabla → tree view** — la `JBTable` de pipelines (columnas ID/Ref/SHA/Status/Source) se reemplaza por un `com.intellij.ui.treeStructure.Tree` con `DefaultTreeModel`:
  - **Nodos raíz** = pipelines. Título: `ref` cuando `tag == true`, si no `#<id>`. Icono: bolita de status (verde/rojo/gris/ámbar) o `Execute`/`Pause` para estados activos. Sufijo gris = `source` (`push`, `web`, `schedule`, …) por defecto `"push"` cuando GitLab no devuelve `source`. Para pipelines de rama (no-tag) se muestra además `· <ref>` para no perder el nombre de la rama.
  - **Nodos hijo** = jobs del pipeline. Etiqueta `<stage> → <name>` + duración `(Ns)` en gris.
  - **Carga lazy** — al expandir un pipeline distinto del "followed", se llama `service.fetchJobs(pipelineId)` en `Dispatchers.IO` y se sustituye el placeholder `cargando jobs…` por los jobs reales. Se cachea por `pipelineId` para no re-pedir en re-expansiones ni en los rebuilds que produce el `state.collect` (cada 3s mientras se sigue un pipeline).
  - **Followed pipeline auto-feed** — el árbol no espera al expand para el pipeline en curso: `render()` toma `state.stages.flatMap { it.jobs }` y popula la cache, así los jobs aparecen y cambian de bolita en tiempo real con el polling existente.
  - **Estado de expansión persistido** entre rebuilds — `snapshotExpansion()` guarda los `pipelineId` expandidos antes de `treeModel.reload()` y re-aplica `tree.expandPath(...)` después, así el ticking del follow no colapsa lo que el usuario haya abierto.
  - **Doble clic** abre el `web_url` en el navegador, igual para nodos de pipeline y de job (los jobs sí tienen `web_url` propio en la API GitLab).
- **`GitLabPipelineService.fetchJobs(pipelineId)`** — nuevo método público. Reusa el `cachedClient`/`cachedProjectId` que ya se llenaba en `refreshNow()` para `fetchJobTrace`, así el tool window puede pedir jobs de pipelines arbitrarios (no sólo el seguido) sin re-resolver remoto/cuenta/token.

## [0.0.5] - 2026-05-25

- **Indicador pegado al inicio del status bar (antes del NavBar/breadcrumb)** — el `<statusBarWidgetFactory>` no llega al panel izquierdo del status bar; añado un `LeftPipelineIndicator` (icono-only `JBLabel` con tooltip + click) que se inyecta vía reflexión en `IdeStatusBarImpl.leftPanel` en posición 0 (antes del NavBar) desde `LeftIndicatorMounter` (`ProjectActivity`). Si el campo `leftPanel` no se encuentra (versión nueva del IDE que lo renombre), cae al método público deprecado `StatusBar.addCustomIndicationComponent` que añade al panel izquierdo pero al final. El indicador comparte estado y bolitas/spinner con el widget de la derecha — son dos vistas del mismo `GitLabPipelineService.state`. Motivación: cuando el breadcrumb crece (paths largos), el widget de la derecha se desplaza y deja de ser glanceable; el izquierdo mantiene posición fija.

## [0.0.4] - 2026-05-25

- **Widget intenta posicionarse antes del NavBar/breadcrumb** — `order="before NavBar, first"` en `plugin.xml`. Si el platform respeta el orden cross-zona (izquierda/derecha), el widget aparecerá adyacente al breadcrumb por su lado izquierdo; si no, queda como `first` en la zona derecha (sin regresión visual respecto a 0.0.3). El widget id del NavBar oficial es `"NavBar"` (`NavBarStatusBarWidgetFactory`).

## [0.0.3] - 2026-05-25

Reactividad y observabilidad.

### Cambios

- **Status bar widget pegado a la izquierda** — `order="last"` → `order="first"` en `plugin.xml`. Se ve antes que el resto de widgets del platform.
- **Detección de pipeline tras push acotada** — el polling de `onPushDetected` / `followTag` arranca a **500 ms × 20 intentos** (≈10 s de detección sub-segundo), después 2 s × 20, después 5 s. Antes era 2 s × 5 + 10 s. Reacción típica baja de "≥10 s" a "<1 s en caso bueno".
- **Follow loop más vivo** — el `getPipeline` + `listJobs` de `followUntilTerminal` pasa de 8 s a **3 s** por tick (UI se siente actualizada).
- **Animator → Swing Timer** — `PipelineStatusBarWidget` ya no usa `com.intellij.util.ui.Animator`: en 2026.1 logueaba `java.lang.Throwable: Do not use repeatable animators without an explicit lifetime scope` desde el constructor. Reemplazado por `javax.swing.Timer(100ms)` que itera `spinnerFrame` y llama `myStatusBar?.updateWidget(ID())`. Sin dependencias de IntelliJ internals que se mueven entre versiones.
- **Panel "Runner log" en el tool window** — `LiveLogsPanel` debajo de la franja de stages. Muestra título "Runner log — <stage> → <job> (#<id>)" y un `JTextArea` monospace con el contenido del job en curso. Se hace visible solo cuando hay un job RUNNING; oculto si no. Polling cada **3 s** vía `javax.swing.Timer` mientras el job esté corriendo; congela al terminar. Auto-scroll al fondo solo si el usuario ya estaba en el fondo (no roba la posición si el usuario está leyendo arriba). Endpoint: `GET /api/v4/projects/:id/jobs/:job_id/trace` (texto plano).
- **`GitLabApiClient.jobTrace(projectId, jobId)`** — nuevo método. Cliente cacheado en `GitLabPipelineService.cachedClient/cachedProjectId` tras el primer `refreshNow` exitoso, así el panel puede llamar `service.fetchJobTrace(jobId)` sin re-resolver remoto/cuenta/token cada 3 s.

## [0.0.2] - 2026-05-25

Feedback visual y soporte de pipelines multi-etapa. La status bar ahora distingue de un vistazo verde/rojo y gira mientras corre; el tool window enseña la franja de stages y la notificación final desglosa qué etapa pasó y cuál falló.

### Status bar widget — bolitas + spinner nativo

- **Iconos como bolitas de color** — nuevo `ui/ColoredDotIcon.kt` que pinta un círculo relleno con `JBColor` (light/dark aware) escalado con `JBUIScale`. Constantes `GREEN` (`#4CAF50 / #5FB85F`), `RED` (`#E53935 / #E57373`), `GREY` y `AMBER`. El widget muestra: verde → último pipeline OK, rojo → falló, gris → cancelled/skipped/unknown, ámbar → manual/scheduled.
- **Spinner animado durante TODO estado no-terminal** — sustituida la coroutine custom de animación por el `com.intellij.util.ui.Animator` del platform (cycle de 800 ms · 8 frames `AllIcons.Process.Step_1..8`). Antes solo animaba en `RUNNING`; ahora gira también en `CREATED`/`PENDING`/`PREPARING`/`WAITING_FOR_RESOURCE`. Reemplazo motivado por: el `updateWidget(ID())` manual desde la coroutine no siempre repintaba; `Animator` engancha directo con el repaint loop del platform y se ve fluido.
- **Label castellanizado + etapa actual** — `getSelectedValue()` ahora devuelve `Pipeline #1234 · v1.2.3 — corriendo · etapa: build` (antes era `#1234 running`). Tooltip extendido con `etapa actual: X` y resumen `stages: build 3/3 · test 2/4 · deploy 0/1`. Status strings traducidas en `labelFor()` (terminó OK / falló / cancelado / pendiente / preparando / esperando recursos / etc.).

### Soporte multi-etapa (model + API + service)

- **Modelo nuevo `Job` + `StageSummary`** — `model/Pipeline.kt` añade `Job(id, name, stage, status, allowFailure, webUrl, startedAt, finishedAt, duration)` y `StageSummary(name, status, jobs)`. El `status` del stage se deriva por prioridad: `RUNNING` > `FAILED` (ignorando jobs con `allow_failure: true`) > `PENDING/PREPARING/WAITING/CREATED` > `MANUAL` > `SCHEDULED` > `SKIPPED` > `SUCCESS`. Accesores `succeededJobs`, `failedJobs`, `totalJobs`.
- **`GitLabApiClient.listJobs(projectId, pipelineId)`** — paginado hasta 5 páginas de 100, `include_retried=false`. Cliente sigue siendo `HttpRequests` + `PRIVATE-TOKEN`, sin nuevas deps.
- **`GitLabPipelineService.State` extendido** — añade `stages: List<StageSummary>` y `currentStage: String?` (primer stage no-terminal, en orden de declaración). `followUntilTerminal` ahora hace `client.listJobs()` en cada tick y construye los stages preservando el orden del `.gitlab-ci.yml` (`LinkedHashMap`). Al alcanzar terminal, `currentStage` vuelve a null.
- **Notificación final con breakdown por etapa** — `formatStageBreakdown()` genera bloque texto multi-línea estilo:
  ```
  Pipeline #1234 success (87s)
    [OK]   build (3/3)
    [FAIL] test (1/4)
    [SKIP] deploy (0/1)
  ```
  Sin emojis (por convención). Marcadores cortos `OK/FAIL/CANCEL/SKIP/RUN/WAIT/MANUAL/SCHED`.

### Tool window — franja de stages

- **`StagesStripPanel`** añadido al `SOUTH` del tool window (`toolWindow/PipelineToolWindowFactory.kt`). Visible sólo mientras hay un pipeline siguiéndose. Renderiza un `FlowLayout` horizontal de "stage chips" separados por flechas `→`. Cada chip = bolita de status + nombre + contador `(succeeded/total)`. El chip de la etapa en curso lleva fondo destacado (`JBColor(#DCE6FF, #3A4D70)`) + borde azul y sufijo `(en curso)`. Bordes verde/rojo para stages success/failed.
- **Tabla de pipelines unificada con las bolitas** — antes el renderer de la tabla usaba `AllIcons.RunConfigurations.TestPassed/TestFailed`; ahora reutiliza las mismas bolitas verde/rojo/gris/ámbar para coherencia con el widget.
- **Label superior** del tool window añade ` — etapa: <stage>` cuando hay follow activo.

### Manifest

- **Icono del tool window cambiado** — de `AllIcons.Vcs.Branch` (que sugería branching) a `AllIcons.Toolwindows.ToolWindowBuild` (martillo + engranaje), más coherente con un dashboard de CI/build.

### Bugs y limpieza

- **CHANGELOG header arreglado** — `## v0.0.1 — 2026-05-25` no parseaba con el plugin gradle-changelog (header parser regex pide SemVer puro). Cambiado a formato keepachangelog `## [0.0.1] - 2026-05-25`. La entrada de esta release sigue el mismo formato.
- **README rebuild** — `README.md` reescrito de cero: fuera el scaffold con `MARKETPLACE_ID` placeholders, ahora describe qué hace el plugin, requisitos (link al 22857 + nota sobre IDE Ultimate vs no-Ultimate), instalación manual desde zip, tabla de componentes internos, limitaciones conocidas (CLI push, heurística de detección de tag) y comandos de desarrollo.

### Detalles de implementación

- **`Animator.dispose()`** se llama directo, no vía `Disposer.dispose()` — `Animator` no implementa `Disposable` en esta versión del platform (se descubrió en build, ver compile error en `PipelineStatusBarWidget.kt:125`).
- **Coroutine subscription del widget sigue cancelándose** en `dispose()` + el animator se `suspend()` antes de su `dispose()` para que no quede repintando huérfano.
- **Multi-line tooltips** en el widget vía `\n` en `getTooltipText()` — Swing los renderiza con line break sin necesidad de `<html>`.

## [0.0.1] - 2026-05-25

Primera release publicable. El proyecto pasa de scaffold de [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template) a un plugin funcional que sigue pipelines de GitLab CI dentro del IDE, reutilizando la autenticación del plugin oficial de JetBrains (incluye self-hosted).

### Plugin core

- **Integración con el plugin oficial de GitLab (`org.jetbrains.plugins.gitlab`, Marketplace 22857)** — `auth/GitLabAuthBridge.kt` lee cuentas y tokens directamente de `GitLabAccountManager.accountsState` y `findCredentials(...)` (suspend, envuelto en `runBlocking` en background). Empareja cuenta ↔ remoto por host del URL git (`extractHost` soporta tanto `https://...` como `git@host:path.git`). Cero credenciales propias: si el usuario ya configuró la cuenta self-hosted en `Settings → Version Control → GitLab`, este plugin la reusa.
- **Modelo de pipeline + cliente HTTP v4** — `model/Pipeline.kt` + enum `PipelineStatus` (con `isTerminal`). `api/GitLabApiClient.kt` usa `HttpRequests` del platform y header `PRIVATE-TOKEN`. Endpoints: `resolveProjectId(path)`, `listPipelines(projectId, perPage)`, `findPipelineForTag(projectId, tag)`, `getPipeline(projectId, pipelineId)`.
- **Detección de `.gitlab-ci.yml`** — `services/GitLabCiDetector.kt` chequea `basePath/.gitlab-ci.yml`. Si el archivo no existe, el tool window se oculta vía `ToolWindowFactory.shouldBeAvailable`. `startup/PipelineWatcherActivity.kt` registra un `AsyncFileListener` para crear/borrar del archivo y activa/desactiva el tool window en caliente sin reiniciar el IDE.
- **Resolución del remoto GitLab** — `services/GitRemoteResolver.kt` itera por `GitRepositoryManager.getInstance(project).repositories`, prefiere remotos que matchen una cuenta configurada, cae a `origin` y como último recurso al primer remoto que parezca GitLab.
- **Servicio principal `GitLabPipelineService` (project-level)** — mantiene un `StateFlow<State>` con `ciEnabled`, `pipelines`, `following`, `followingTag` y `errorMessage`. Métodos: `refresh()` (manual), `onPushDetected()` (snapshot del último tag-pipeline + polling 60×) y `followTag(tagName)`. Notifica vía canal `GitLab Pipeline Watcher` cuando arranca un seguimiento y cuando termina (success → INFORMATION, fail → ERROR, con duración). El service auto-abre el tool window con `popToolWindow()` cuando empieza a seguir un pipeline.

### Detección de push de tag

- **`git/PipelinePushListener.kt`** — registrado como `<projectListeners>` contra el topic `git4idea.push.GitPushListener`. Cualquier push exitoso (no `ERROR`) llama a `onPushDetected()`, que toma snapshot del id máximo de tag-pipeline conocido y polea hasta detectar uno nuevo con `id` mayor (hasta ~10 min). Una vez encontrado, lo sigue cada 8 s hasta estado terminal.
- **Caveat conocido** — pushes hechos desde la CLI fuera del IDE no disparan el listener; el usuario tiene que pulsar Refresh manualmente. Documentado para iteración futura (poll periódico con `Alarm` cuando el tool window esté visible).

### Visualización

- **Tool window "GitLab Pipelines" en el anchor `bottom`** — `toolWindow/PipelineToolWindowFactory.kt`. Tabla con icono de status, ID, ref (prefijo `tag:` si aplica), sha corto, status raw y source. Doble clic abre el `web_url` del pipeline en el navegador. Botón Refresh y label superior que cambia entre "no CI", "no account configured", "following tag X", contador de pipelines o errores específicos.
- **Status bar widget `GitLabPipelineStatusBarWidget`** — `statusBar/PipelineStatusBarWidget.kt`. Aparece a la derecha del status bar mientras hay un pipeline activo. Mientras está `RUNNING`, anima usando los 8 frames `AllIcons.Process.Step_1..8` con un coroutine de 120 ms por frame. Al pasar a terminal el icono queda fijo (tick verde / cruz roja / cancel / skipped / etc). Tooltip con ID, status, ref y sha. Click → abre el pipeline en navegador. Implementa `StatusBarWidget.MultipleTextValuesPresentation` + `Multiframe`, con `super<EditorBasedWidget>` qualifier para resolver ambigüedad de la jerarquía.
- **Auto-pop del tool window** — al detectar el inicio del pipeline, `popToolWindow()` invoca `ToolWindowManager → setAvailable(true) + activate(null, true)` en EDT.
- **Notificaciones balloon** — group `GitLab Pipeline Watcher` con strings en `messages/MyBundle.properties` (start / finish con duración / not-found tras polling).

### Manifest del plugin

- **`<dependencies>` con sintaxis moderna** — en lugar del legacy `<depends>X</depends>` (que dejaba pegado el warning "depends on unknown plugins" en el plugin manager UI de 2026.1 aunque el plugin cargara bien), ahora se usa el bloque nuevo:
  ```xml
  <depends>com.intellij.modules.platform</depends>
  <dependencies>
      <plugin id="Git4Idea"/>
      <plugin id="org.jetbrains.plugins.gitlab"/>
  </dependencies>
  ```
- **Registros en `plugin.xml`** — `toolWindow`, `postStartupActivity`, `notificationGroup`, `statusBarWidgetFactory` con `order="last"`, y el `projectListener` apuntando a `git4idea.push.GitPushListener`.

### Build / Gradle

- **`build.gradle.kts`** — añadidas `bundledPlugin("Git4Idea")` y `bundledPlugin("org.jetbrains.plugins.gitlab")` como deps del platform. Desactivado `instrumentCode = false` (no hay `.form` ni `@NotNull` que instrumentar, y además evita un bug de la versión 2.16 del IntelliJ Platform Gradle Plugin que en JDKs no-JBR busca `$JAVA_HOME\Packages` y peta).
- **`settings.gradle.kts`** — `rootProject.name` renombrado a `gitlab-pipeline-watcher` para que el zip distribuible salga con nombre limpio (antes era `IntelliJ Platform Plugin Template-0.0.1.zip`).

### Limpieza

- **Borrado del scaffold del template** — `MyProjectService.kt`, `MyProjectActivity.kt`, `MyToolWindowFactory.kt` y `src/test/testData/rename/*`. `MyBundle.kt` (helper de resource bundle) se mantiene; las claves de `MyBundle.properties` se reescriben para el dominio nuevo (status, toolWindow, notification).
- **Tests** — `MyPluginTest.kt` reemplazado: ahora cubre `GitLabAuthBridge.extractProjectPath` (https + ssh) y los terminal-states de `PipelineStatus`.

### Distribución

- **Sideload local** — zip empaquetable con `./gradlew buildPlugin` en `build/distributions/gitlab-pipeline-watcher-0.0.1.zip` (~84 KB). Probado contra IntelliJ IDEA 2026.1 Ultimate, PyCharm 2026.1 y WebStorm 2026.1 — el plugin carga sin restart (`Plugin com.github.danielalejandroamaro.gitlabpipeline loaded without restart in 16 ms` en `idea.log`).
