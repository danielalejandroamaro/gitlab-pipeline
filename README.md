# GitLab Pipeline Watcher

JetBrains IDE plugin (IntelliJ IDEA, WebStorm, PyCharm, GoLand, Rider, …) that follows GitLab CI pipelines from inside the IDE, reusing the authentication of the official JetBrains [GitLab plugin](https://plugins.jetbrains.com/plugin/22857-gitlab). Works with self-hosted GitLab instances without any plugin-specific configuration: if you already have an account set up under `Settings → Version Control → GitLab`, this plugin reuses it.

![Build](https://github.com/danielalejandroamaro/gitlab-pipeline/workflows/Build/badge.svg)

## What it does

- **"GitLab Pipelines" tool window** (bottom of the IDE) with an icon-tagged tree of recent pipelines: status, ref, short sha, source. Double-click opens the pipeline in your browser.
- **Animated status bar widget** while a pipeline is `running`: an 8-frame spinner using IntelliJ's native icons; when the pipeline reaches a terminal state the icon freezes (green tick, red cross, cancel, skipped…). Tooltip with ID, status, ref and sha. Click → opens the pipeline.
- **Tag-push detection** via `git4idea.push.GitPushListener`: any successful push from inside the IDE starts a follow loop that polls GitLab until it finds the pipeline triggered by the tag, then follows it through to terminal. The tool window auto-opens at the start of a follow and you get a notification; on terminal another notification shows duration and result.
- **Auto-disabled when there is no `.gitlab-ci.yml`**: if the project doesn't have the file, the tool window and the widget stay hidden. If you create or delete the file at runtime, the plugin reacts via `AsyncFileListener` without restarting the IDE.

## Requirements

- IntelliJ Platform-based IDE, build `252` or later (IDEA / WebStorm / PyCharm / etc., version 2025.2+).
- **Official GitLab plugin** ([Marketplace 22857](https://plugins.jetbrains.com/plugin/22857-gitlab)) installed and with at least one account configured (`Settings → Version Control → GitLab → Add account…`). On IDEA Ultimate it's bundled; on WebStorm/PyCharm/GoLand/Rider you install it manually from the Marketplace.
- Git plugin enabled (bundled and enabled by default on every JetBrains IDE).
- A GitLab Personal Access Token with `api` + `read_repository` scopes.

## Installation (manual, while not yet published on the Marketplace)

1. Download `gitlab-pipeline-watcher-X.Y.Z.zip` from [Releases](https://github.com/danielalejandroamaro/gitlab-pipeline/releases).
2. In the IDE: `Settings (Ctrl+Alt+S)` → `Plugins` → cog icon top-right → `Install plugin from disk…` → pick the zip.
3. Restart when prompted.

## Configuration

Zero plugin-specific configuration. The plugin reads:

- Accounts + tokens from the official GitLab plugin (`GitLabAccountManager.accountsState` + `findCredentials(account)`).
- The first git remote in the project whose host matches one of those accounts (fallback: `origin`).

If the plugin can't locate the project on GitLab, the tool window says so explicitly ("No GitLab account configured for X" / "Could not resolve project X on Y").

## How it works internally

| Component | File | Role |
| --- | --- | --- |
| `GitLabAuthBridge` | `auth/GitLabAuthBridge.kt` | Reads accounts and tokens from the official plugin; matches remote ↔ account by host. |
| `GitLabApiClient` | `api/GitLabApiClient.kt` | HTTP v4 client on top of `HttpRequests` with `PRIVATE-TOKEN` header. |
| `GitLabPipelineService` | `services/GitLabPipelineService.kt` | Project-level service with `StateFlow<State>`; `refresh()`, `onPushDetected()`, `followTag()`. |
| `PipelinePushListener` | `git/PipelinePushListener.kt` | Subscribed to topic `git4idea.push.GitPushListener`. |
| `PipelineToolWindowFactory` | `toolWindow/PipelineToolWindowFactory.kt` | Pipelines tree view. |
| `PipelineStatusBarWidget` | `statusBar/PipelineStatusBarWidget.kt` | Animated status bar widget. |
| `GitLabCiDetector` + `PipelineWatcherActivity` | `services/`, `startup/` | Conditional activation/deactivation based on `.gitlab-ci.yml`. |

After a push, polling snapshots the maximum tag-pipeline id we know about and looks for one with a larger `id` for roughly 10 minutes (every 2 s for the first 5 attempts, then every 10 s). Once found, it reads the pipeline every 8 s until terminal.

## Known limitations

- **CLI pushes** (outside the IDE) don't fire `GitPushListener`, so the automatic follow doesn't kick in. Workaround: hit Refresh in the tool window. Possible future iteration: periodic polling with `Alarm` while the tool window is visible.
- **Identifying the pushed tag**: `GitPushRepoResult` only exposes branch-level info reliably, so instead of reading the tag off the result we snapshot the max tag-pipeline id and look for a newer one on GitLab. Works fine, but it does assume the push and the GitLab pipeline trigger happen within the polling window.

## Development

### 📦 Where the `.zip` ends up

```
build\distributions\gitlab-pipeline-watcher-<version>.zip
```

**Always** there. Typical absolute path on this machine: `C:\work\gitlab-pipeline\build\distributions\gitlab-pipeline-watcher-<version>.zip`. To open it in Explorer without thinking: `make open-dist` (or `make install`, which builds and opens the folder in one go).

### Commands

```bash
make                # build → build\distributions\gitlab-pipeline-watcher-<version>.zip
make dev            # Sandbox IDE with the plugin pre-loaded (does not install)
make install        # Build + open build\distributions\ in Explorer
make rebuild        # Clean + build (fresh zip)
make zip            # Print the absolute path of the generated .zip
make open-dist      # Open build\distributions\ in Explorer
make help           # List every target
```

If you'd rather go straight to gradle (with `JAVA_HOME` configured):

```bash
./gradlew buildPlugin   # Output: build/distributions/gitlab-pipeline-watcher-<version>.zip
./gradlew runIde        # Sandbox IDE with the plugin loaded
./gradlew test          # Tests
```

Stack: Kotlin 2.1, IntelliJ Platform Gradle Plugin 2.16, JDK 21 (tested with Microsoft OpenJDK 21).

Build notes:

- `instrumentCode = false` in `build.gradle.kts` — there are no `.form` files or `@NotNull` to instrument, and it also sidesteps an IPP 2.16 bug that on non-JBR JDKs looks for `$JAVA_HOME\Packages` and crashes.
- Dependencies declared with the modern `<dependencies><plugin id="..."/></dependencies>` syntax instead of legacy `<depends>` — avoids a stale Plugin Manager UI warning on 2026.1.

## License

Released under the **GNU AGPL v3** (full text in [`LICENSE`](LICENSE)).

Practical summary of what AGPL v3 lets you do:

- **Personal or internal team use**: free. Install, modify, share with your team without asking.
- **Public fork**: free, as long as your fork is also published under AGPL v3 and keeps the copyright notice.
- **Distributing a derivative work** (modified or not): you must publish the source code of the derivative under AGPL v3.
- **Serving as SaaS / remote IDE / Code With Me**: this also counts as "distribution" under AGPL — if you expose this plugin's functionality to external users, you must publish the source.

### Commercial license (dual licensing)

If you want to use this plugin **embedded in a closed-source product**, **resell it**, **bundle it into a commercial offering without opening your own code**, or otherwise do something AGPL v3 forbids — contact **danielalejandro.amaroramos@gmail.com** to negotiate a separate commercial license.

This plugin is **not affiliated with, endorsed by, sponsored by, or approved by GitLab Inc.** GitLab is a registered trademark of GitLab Inc.

## Credits

Built on top of JetBrains' [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template).
