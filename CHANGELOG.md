<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# gitlab-pipeline Changelog

## v0.0.1 — 2026-05-25

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
