# GitLab Pipeline Watcher

Plugin para IDEs de JetBrains (IntelliJ IDEA, WebStorm, PyCharm, GoLand, Rider, …) que sigue pipelines de GitLab CI desde dentro del IDE reutilizando la autenticación del plugin oficial de JetBrains [GitLab](https://plugins.jetbrains.com/plugin/22857-gitlab). Funciona con instancias self-hosted sin configurar nada propio: si ya tienes una cuenta en `Settings → Version Control → GitLab`, este plugin la reusa.

![Build](https://github.com/danielalejandroamaro/gitlab-pipeline/workflows/Build/badge.svg)

## Qué hace

- **Tool window "GitLab Pipelines"** (abajo del IDE) con tabla iconada de los pipelines recientes: status, ref, sha corto, source. Doble clic abre el pipeline en el navegador.
- **Status bar widget animado** mientras hay un pipeline en `running`: spinner de 8 frames usando los iconos nativos de IntelliJ; al pasar a estado terminal el icono queda fijo (tick verde, cruz roja, cancel, skipped…). Tooltip con ID, status, ref y sha. Click → abre el pipeline.
- **Detección de push de tag** vía `git4idea.push.GitPushListener`: cualquier push exitoso desde el IDE arranca un seguimiento que polea GitLab hasta encontrar el pipeline disparado por el tag y lo sigue hasta el final. Al iniciar el seguimiento el tool window se abre solo y aparece una notificación; al terminar, otra notificación con duración y resultado.
- **Auto-desactivación si no hay `.gitlab-ci.yml`**: si el proyecto no tiene el archivo, el tool window y el widget no aparecen. Si lo creas o lo borras en caliente, el plugin reacciona vía `AsyncFileListener` sin reiniciar el IDE.

## Requisitos

- IDE basado en IntelliJ Platform ≥ build `252` (IDEA / WebStorm / PyCharm / etc. 2025.2 o superior).
- **Plugin oficial de GitLab** ([Marketplace 22857](https://plugins.jetbrains.com/plugin/22857-gitlab)) instalado y con al menos una cuenta configurada (`Settings → Version Control → GitLab → Add account…`). En IDEA Ultimate viene bundleado; en WebStorm/PyCharm/GoLand/Rider hay que instalarlo a mano desde Marketplace.
- Plugin de Git activado (viene bundleado y habilitado por defecto en todos los IDEs de JetBrains).
- Personal Access Token de GitLab con scopes `api` + `read_repository`.

## Instalación (manual, mientras no está publicado en Marketplace)

1. Descarga el `gitlab-pipeline-watcher-X.Y.Z.zip` desde [Releases](https://github.com/danielalejandroamaro/gitlab-pipeline/releases).
2. En el IDE: `Settings (Ctrl+Alt+S)` → `Plugins` → engranaje arriba a la derecha → `Install plugin from disk…` → selecciona el zip.
3. Reinicia cuando lo pida.

## Configuración

Cero configuración propia. El plugin lee:

- Cuentas + tokens del plugin oficial GitLab (`GitLabAccountManager.accountsState` + `findCredentials(account)`).
- El primer remoto git del proyecto cuyo host coincida con alguna de esas cuentas (fallback a `origin`).

Si el plugin no detecta el proyecto en GitLab, el tool window lo dice explícitamente ("No GitLab account configured for X" / "Could not resolve project X on Y").

## Cómo funciona internamente

| Componente | Archivo | Rol |
| --- | --- | --- |
| `GitLabAuthBridge` | `auth/GitLabAuthBridge.kt` | Lee cuentas y tokens del plugin oficial; matchea remoto ↔ cuenta por host. |
| `GitLabApiClient` | `api/GitLabApiClient.kt` | Cliente HTTP v4 sobre `HttpRequests` con header `PRIVATE-TOKEN`. |
| `GitLabPipelineService` | `services/GitLabPipelineService.kt` | Project-level service con `StateFlow<State>`; `refresh()`, `onPushDetected()`, `followTag()`. |
| `PipelinePushListener` | `git/PipelinePushListener.kt` | Suscrito al topic `git4idea.push.GitPushListener`. |
| `PipelineToolWindowFactory` | `toolWindow/PipelineToolWindowFactory.kt` | Tabla de pipelines. |
| `PipelineStatusBarWidget` | `statusBar/PipelineStatusBarWidget.kt` | Widget animado en la status bar. |
| `GitLabCiDetector` + `PipelineWatcherActivity` | `services/`, `startup/` | Activación/desactivación condicional por `.gitlab-ci.yml`. |

El polling tras push hace snapshot del id máximo de tag-pipeline conocido y busca uno con `id` mayor durante ~10 min (cada 2 s los primeros 5 intentos, después cada 10 s). Una vez encontrado, lee el pipeline cada 8 s hasta estado terminal.

## Limitaciones conocidas

- **Push desde la CLI** (fuera del IDE) no dispara el `GitPushListener`, así que el follow automático no se activa. Workaround: pulsar Refresh en el tool window. Iteración futura: poll periódico con `Alarm` cuando el tool window esté visible.
- **Identificación del tag pushed**: `GitPushRepoResult` solo expone branch-level info de forma fiable, así que en vez de leer el tag del result, snapshoteamos el id máximo de tag-pipeline y buscamos uno nuevo en GitLab. Funciona pero asume que el push y el pipeline-trigger en GitLab ocurren dentro de la ventana de polling.

## Desarrollo

### 📦 Dónde queda el `.zip`

```
build\distributions\gitlab-pipeline-watcher-<version>.zip
```

Es **siempre** ahí. Ruta absoluta típica en esta máquina: `C:\work\gitlab-pipeline\build\distributions\gitlab-pipeline-watcher-<version>.zip`. Para abrirla en Explorer sin pensarlo: `make open-dist` (o `make install`, que builda y abre la carpeta de una).

### Comandos

```bash
make                # build → build\distributions\gitlab-pipeline-watcher-<version>.zip
make dev            # Sandbox IDE con el plugin precargado (no instala nada)
make install        # Build + abre build\distributions\ en Explorer
make rebuild        # Clean + build (zip fresco)
make zip            # Imprime la ruta absoluta del .zip generado
make open-dist      # Abre build\distributions\ en Explorer
make help           # Lista todos los targets
```

Si prefieres gradle directo (con `JAVA_HOME` configurado):

```bash
./gradlew buildPlugin   # Output: build/distributions/gitlab-pipeline-watcher-<version>.zip
./gradlew runIde        # Sandbox IDE con plugin cargado
./gradlew test          # Tests
```

Stack: Kotlin 2.1, IntelliJ Platform Gradle Plugin 2.16, JDK 21 (probado con Microsoft OpenJDK 21).

Notas de build:

- `instrumentCode = false` en `build.gradle.kts` — no hay `.form` ni `@NotNull` que instrumentar, y además sortea un bug de IPP 2.16 que en JDKs no-JBR busca `$JAVA_HOME\Packages` y peta.
- Dependencias declaradas con la sintaxis moderna `<dependencies><plugin id="..."/></dependencies>` en lugar de la legacy `<depends>` — evita un warning stale del Plugin Manager UI en 2026.1.

## Licencia

Este plugin **no está afiliado, respaldado, patrocinado ni aprobado por GitLab Inc.** GitLab es marca registrada de GitLab Inc.

## Créditos

Plugin construido sobre el [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template) de JetBrains.
