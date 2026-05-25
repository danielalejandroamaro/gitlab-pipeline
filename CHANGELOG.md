<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# gitlab-pipeline Changelog

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
