package com.github.danielalejandroamaro.gitlabpipeline.services

import com.github.danielalejandroamaro.gitlabpipeline.MyBundle
import com.github.danielalejandroamaro.gitlabpipeline.api.GitLabApiClient
import com.github.danielalejandroamaro.gitlabpipeline.auth.GitLabAuthBridge
import com.github.danielalejandroamaro.gitlabpipeline.model.Pipeline
import com.github.danielalejandroamaro.gitlabpipeline.model.PipelineStatus
import com.github.danielalejandroamaro.gitlabpipeline.model.StageSummary
import com.github.danielalejandroamaro.gitlabpipeline.settings.PipelineSettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Project-level service that holds the latest known pipelines and follows a
 * pipeline triggered by a freshly-pushed tag until it reaches a terminal state.
 */
@Service(Service.Level.PROJECT)
class GitLabPipelineService(
    private val project: Project,
    private val scope: CoroutineScope,
) {

    private val logger = thisLogger()

    data class State(
        val ciEnabled: Boolean,
        val errorMessage: String? = null,
        val pipelines: List<Pipeline> = emptyList(),
        val following: Pipeline? = null,
        val followingTag: String? = null,
        /** Stages of the currently-followed pipeline, in declaration order. */
        val stages: List<StageSummary> = emptyList(),
        /** Name of the stage that's currently running (first non-terminal one). null when no follow active. */
        val currentStage: String? = null,
        /** True from the moment refresh() is invoked until refreshNow() returns. UI uses this to disable the Refresh button and show feedback. */
        val isRefreshing: Boolean = false,
        /** Epoch millis of the last completed refresh (manual or auto). 0 = never. */
        val lastRefreshedAt: Long = 0L,
    )

    private val _state = MutableStateFlow(State(ciEnabled = GitLabCiDetector.hasCiFile(project)))
    val state: StateFlow<State> = _state.asStateFlow()

    private var followJob: Job? = null

    init {
        // Background auto-refresh so the tree picks up new pipelines / status transitions without
        // the user touching the button. One `GET .../pipelines?per_page=20` per tick. When idle
        // polling is OFF in settings, we still do the initial load and keep ticking while at least
        // one known pipeline is non-terminal; the loop falls silent once everything settles.
        scope.launch(Dispatchers.IO) {
            if (_state.value.ciEnabled) {
                runCatching { refreshNow() }
            }
            while (true) {
                val settings = PipelineSettings.getInstance()
                delay(settings.refreshIntervalMs)
                if (!_state.value.ciEnabled || _state.value.isRefreshing) continue
                val hasActive = _state.value.pipelines.any { !it.status.isTerminal } ||
                    _state.value.following != null
                if (settings.idlePollingEnabled || hasActive) {
                    runCatching { refreshNow() }
                }
            }
        }
    }

    /** Cached client + project id after the first successful resolve, so [fetchJobTrace] can hit the API without re-doing the lookup chain on every poll tick. */
    @Volatile private var cachedClient: GitLabApiClient? = null
    @Volatile private var cachedProjectId: Long? = null

    /**
     * Tracks pipeline ids we've already announced (started/detected) so the auto-refresh loop
     * doesn't re-fire a balloon every tick. Same for terminal — once we've notified that #X is
     * SUCCESS/FAILED we don't notify again.
     */
    private val notifiedStartedIds = mutableSetOf<Long>()
    private val notifiedTerminalIds = mutableSetOf<Long>()
    /** False until the first successful list fetch lands. Suppresses notifications for the historic backlog at project open. */
    @Volatile private var firstFetchSeeded = false

    /**
     * Last error category we balloon'd (e.g. `NO_REMOTE`, `NO_ACCOUNT`, `NO_TOKEN`, `NO_PROJECT`).
     * Used to dedupe error balloons when the same failure recurs every tick of the auto-refresh
     * loop — the user only sees one toast per error type until either the error clears (success)
     * or the category changes (e.g. account now configured but token missing).
     */
    @Volatile private var lastErrorKey: String? = null

    private val eventLog get() = project.service<PipelineEventLog>()

    /** Trace text for a single job, or null if the API call fails / the service isn't initialised yet. */
    fun fetchJobTrace(jobId: Long): String? {
        val client = cachedClient ?: return null
        val pid = cachedProjectId ?: return null
        return client.jobTrace(pid, jobId)
    }

    /**
     * Jobs for an arbitrary pipeline (not just the followed one). Used by the tool window tree
     * to lazy-load children when the user expands a pipeline node. Returns null if the service
     * hasn't done its first successful refresh yet (no cached client).
     */
    fun fetchJobs(pipelineId: Long): List<com.github.danielalejandroamaro.gitlabpipeline.model.Job>? {
        val client = cachedClient ?: return null
        val pid = cachedProjectId ?: return null
        return client.listJobs(pid, pipelineId)
    }

    /** Re-check `.gitlab-ci.yml` presence (called from VFS listener / refresh). */
    fun recheckCi() {
        _state.value = _state.value.copy(ciEnabled = GitLabCiDetector.hasCiFile(project))
    }

    /**
     * Manual refresh from the tool window. Always flips `isRefreshing` true→false so the
     * StateFlow emits even when the fetched pipelines list is structurally identical to the
     * previous one (without this, `MutableStateFlow` dedups equal values and the Refresh
     * button looks broken — "funciona a veces").
     */
    fun refresh() {
        if (!_state.value.ciEnabled) return
        if (_state.value.isRefreshing) return
        _state.value = _state.value.copy(isRefreshing = true)
        scope.launch(Dispatchers.IO) {
            try {
                refreshNow()
            } finally {
                _state.value = _state.value.copy(
                    isRefreshing = false,
                    lastRefreshedAt = System.currentTimeMillis(),
                )
            }
        }
    }

    private fun refreshNow(): Pair<GitLabApiClient, Long>? {
        val remote = GitRemoteResolver.resolve(project)
        if (remote == null) {
            val msg = "No GitLab remote detected."
            _state.value = _state.value.copy(errorMessage = msg, pipelines = emptyList())
            notifyError("NO_REMOTE", msg)
            return null
        }
        val account = GitLabAuthBridge.findAccountForRemote(remote.url)
        if (account == null) {
            val msg = "No GitLab account configured for ${remote.url}."
            _state.value = _state.value.copy(errorMessage = msg, pipelines = emptyList())
            notifyError("NO_ACCOUNT", msg)
            return null
        }
        val token = GitLabAuthBridge.tokenFor(account)
        if (token.isNullOrBlank()) {
            val msg = "GitLab account ${account.name} has no token stored."
            _state.value = _state.value.copy(errorMessage = msg, pipelines = emptyList())
            notifyError("NO_TOKEN", msg)
            return null
        }
        val client = GitLabApiClient(account.serverUrl, token)
        val projectId = client.resolveProjectId(remote.projectPath)
        if (projectId == null) {
            val msg = "Could not resolve project ${remote.projectPath} on ${account.serverUrl}."
            _state.value = _state.value.copy(errorMessage = msg, pipelines = emptyList())
            notifyError("NO_PROJECT", msg)
            return null
        }
        cachedClient = client
        cachedProjectId = projectId
        val pipelines = client.listPipelines(projectId)
        if (pipelines == null) {
            // Transient API failure — keep the previous list so the tool window doesn't flash empty
            // on every blip and the user doesn't lose their tree expansion / cached jobs. No
            // balloon: a single failed tick on an otherwise-healthy connection isn't worth alerting.
            logger.info("listPipelines transient failure during refresh; keeping previous list")
            eventLog.warn("listPipelines transient failure (kept previous list)")
            return client to projectId
        }
        clearErrorDedupe()
        val previousById = _state.value.pipelines.associateBy { it.id }
        _state.value = _state.value.copy(
            errorMessage = null,
            pipelines = pipelines,
            lastRefreshedAt = System.currentTimeMillis(),
        )
        emitListDeltaNotifications(pipelines, previousById)
        return client to projectId
    }

    /**
     * Balloon dedupe so the resolve chain (which retries every refresh tick) doesn't spam the
     * notification center with the same red toast. Re-fires when [key] changes (resolve chain
     * stepped past the previous gate) or after [clearErrorDedupe] runs on a successful refresh.
     * Always writes to the in-memory event log, regardless of dedupe state.
     */
    private fun notifyError(key: String, message: String) {
        eventLog.error(message)
        if (lastErrorKey == key) return
        lastErrorKey = key
        notify(message, NotificationType.ERROR)
    }

    private fun clearErrorDedupe() {
        if (lastErrorKey != null) {
            eventLog.info("Connection to GitLab recovered")
            lastErrorKey = null
        }
    }

    /**
     * One-shot diagnostic: dumps every step of the resolve chain into the event log so the user
     * can see exactly where the connection breaks without grepping `idea.log`. Wired to the
     * "Diagnosticar ahora" button in the Logs tab.
     */
    fun runDiagnostics() {
        eventLog.info("=== Diagnóstico ===")
        eventLog.info("ciEnabled = ${_state.value.ciEnabled}")
        val remote = GitRemoteResolver.resolve(project)
        if (remote == null) {
            eventLog.error("No GitLab remote detected for this project")
            return
        }
        eventLog.info("Remote URL: ${remote.url}")
        eventLog.info("Project path: ${remote.projectPath}")
        val accounts = GitLabAuthBridge.accounts()
        eventLog.info("GitLab accounts available: ${accounts.size}")
        accounts.forEach { acc ->
            eventLog.info("  - ${acc.name} @ ${acc.serverUrl}")
        }
        val account = GitLabAuthBridge.findAccountForRemote(remote.url)
        if (account == null) {
            eventLog.error("No GitLab account matches host of ${remote.url}")
            return
        }
        eventLog.info("Matched account: ${account.name} (${account.serverUrl})")
        val token = GitLabAuthBridge.tokenFor(account)
        if (token.isNullOrBlank()) {
            eventLog.error("Token missing or blank for account ${account.name}")
            return
        }
        eventLog.info("Token present (length ${token.length})")
        val client = GitLabApiClient(account.serverUrl, token)
        val projectId = client.resolveProjectId(remote.projectPath)
        if (projectId == null) {
            eventLog.error("resolveProjectId returned null for ${remote.projectPath}")
            return
        }
        eventLog.info("Resolved project id: $projectId")
        val pipelines = client.listPipelines(projectId)
        if (pipelines == null) {
            eventLog.error("listPipelines returned null (network / 5xx / parse error)")
            return
        }
        eventLog.info("listPipelines OK: ${pipelines.size} pipelines")
        eventLog.info("=== Fin del diagnóstico ===")
    }

    /**
     * Compare the freshly fetched pipeline list against the previous snapshot and surface balloon
     * notifications for: (a) pipeline ids we've never seen → "started", (b) ids we'd previously
     * seen non-terminal that now report a terminal status → "finished". Both paths short-circuit
     * the first time around: at project open we seed [notifiedStartedIds] from the historic list
     * so the user doesn't get a wall of balloons announcing yesterday's pipelines.
     *
     * The `followUntilTerminal` path emits its own richer breakdown for the currently-followed
     * pipeline, so we suppress the terminal balloon here when this id matches the followed one to
     * avoid double notifying.
     */
    private fun emitListDeltaNotifications(pipelines: List<Pipeline>, previousById: Map<Long, Pipeline>) {
        if (!firstFetchSeeded) {
            for (p in pipelines) {
                notifiedStartedIds += p.id
                if (p.status.isTerminal) notifiedTerminalIds += p.id
            }
            firstFetchSeeded = true
            return
        }
        val followingId = _state.value.following?.id
        for (p in pipelines) {
            if (notifiedStartedIds.add(p.id)) {
                val versionLabel = p.ref ?: p.sha?.take(8) ?: "?"
                val source = p.source ?: "push"
                notify(
                    MyBundle["notification.pipelineDetected", p.id.toString(), source, versionLabel],
                    NotificationType.INFORMATION,
                )
                eventLog.info("Pipeline #${p.id} detected ($source / $versionLabel, status=${p.status.raw})")
            }
            if (p.status.isTerminal && p.id != followingId && notifiedTerminalIds.add(p.id)) {
                val previousStatus = previousById[p.id]?.status
                val wasAlreadyTerminal = previousStatus != null && previousStatus.isTerminal
                if (wasAlreadyTerminal) continue
                val durationLabel = p.duration?.let { "${it}s" } ?: "?"
                notify(
                    MyBundle["notification.pipelineTerminal", p.id.toString(), p.status.raw, durationLabel],
                    if (p.status == PipelineStatus.SUCCESS) NotificationType.INFORMATION
                    else NotificationType.ERROR,
                )
                eventLog.info("Pipeline #${p.id} terminal: ${p.status.raw} (${durationLabel})")
            }
        }
    }

    /**
     * Called by the push listener. Snapshots the latest tag-pipeline id we know
     * about, then polls GitLab for a *new* tag pipeline. Once found, follows it
     * to termination.
     */
    fun onPushDetected() {
        if (!_state.value.ciEnabled) return
        eventLog.info("Push detected — looking for newly-created tag pipeline")
        followJob?.cancel()
        followJob = scope.launch(Dispatchers.IO) {
            // Capture the baseline BEFORE refreshing. If a recent auto-refresh already pulled in
            // the just-pushed pipeline, the refreshed list would otherwise raise our baseline to
            // include it and we'd wait forever for an even-newer one.
            val baselineLatestTagPipelineId = _state.value.pipelines
                .filter { it.tag }
                .maxOfOrNull { it.id } ?: 0L
            val ctx = refreshNow() ?: return@launch
            val (client, projectId) = ctx

            // GitLab's pipeline-creation isn't instantaneous after a push lands — the runner
            // takes a few seconds to materialize the pipeline row. Pause before polling so we
            // don't fire a burst of `listPipelines` calls against a pipeline that doesn't yet
            // exist.
            delay(PUSH_INITIAL_DELAY_MS)

            var newPipeline: Pipeline? = null
            // Ramp: 1s × 20 (≈20s after the initial delay) → 2s × 20 (≈40s) → 5s × 60 (≈5min).
            repeat(100) { attempt ->
                val candidates = client.listPipelines(projectId, perPage = 10).orEmpty()
                newPipeline = candidates.firstOrNull { it.tag && it.id > baselineLatestTagPipelineId }
                if (newPipeline != null) return@repeat
                val sleep = when {
                    attempt < 20 -> 1_000L
                    attempt < 40 -> 2_000L
                    else -> 5_000L
                }
                delay(sleep)
            }
            val started = newPipeline ?: return@launch
            val tagName = started.ref ?: "(unknown)"
            _state.value = _state.value.copy(following = started, followingTag = tagName)
            notify(
                MyBundle["notification.pipelineStarted", started.id.toString(), tagName],
                NotificationType.INFORMATION,
            )
            popToolWindow()
            followUntilTerminal(client, projectId, started.id)
        }
    }

    private fun popToolWindow() {
        ApplicationManager.getApplication().invokeLater {
            ToolWindowManager.getInstance(project)
                .getToolWindow("GitLab Pipelines")
                ?.apply {
                    setAvailable(true, null)
                    activate(null, true)
                }
        }
    }

    /**
     * Same as [onPushDetected] but tied to a known tag name. Useful when the
     * caller can already name the tag (e.g. wired up from a future Git push API
     * that surfaces pushed tags).
     */
    fun followTag(tagName: String) {
        if (!_state.value.ciEnabled) return
        followJob?.cancel()
        followJob = scope.launch(Dispatchers.IO) {
            logger.info("Following pipeline for tag $tagName")
            val ctx = refreshNow() ?: return@launch
            val (client, projectId) = ctx

            // Same "give the runner a couple of seconds" preamble as onPushDetected.
            delay(PUSH_INITIAL_DELAY_MS)

            // Same fast-then-slow ramp as onPushDetected.
            var pipeline: Pipeline? = null
            repeat(100) { attempt ->
                pipeline = client.findPipelineForTag(projectId, tagName)
                if (pipeline != null) return@repeat
                val sleep = when {
                    attempt < 20 -> 1_000L
                    attempt < 40 -> 2_000L
                    else -> 5_000L
                }
                delay(sleep)
            }
            if (pipeline == null) {
                notify(MyBundle["notification.pipelineNotFound", tagName], NotificationType.WARNING)
                return@launch
            }

            _state.value = _state.value.copy(following = pipeline, followingTag = tagName)
            notify(
                MyBundle["notification.pipelineStarted", pipeline!!.id.toString(), tagName],
                NotificationType.INFORMATION,
            )
            popToolWindow()
            followUntilTerminal(client, projectId, pipeline!!.id)
        }
    }

    private suspend fun followUntilTerminal(client: GitLabApiClient, projectId: Long, pipelineId: Long) {
        while (true) {
            val updated = client.getPipeline(projectId, pipelineId) ?: break
            val jobs = client.listJobs(projectId, pipelineId)
            val stages = buildStages(jobs)
            val currentStage = stages.firstOrNull { !it.status.isTerminal }?.name
            _state.value = _state.value.copy(
                following = updated,
                pipelines = listOf(updated) +
                    _state.value.pipelines.filter { it.id != updated.id },
                stages = stages,
                currentStage = currentStage,
            )
            if (updated.status.isTerminal) {
                // GitLab is eventually consistent — pipeline.status can flip to SUCCESS/FAILED a
                // few seconds before /jobs settles every job into its final state. Keep refreshing
                // jobs (with the pipeline still treated as "followed" so the panel keeps mirroring
                // state.stages into its cache) until every job is terminal too (or MANUAL, which
                // legitimately stays non-terminal until a human triggers it), or we hit the
                // convergence budget. Without this, the tree freezes with stale "running" job
                // icons while the parent pipeline shows as finished.
                var finalStages = stages
                var attempts = 0
                while (!jobsConverged(finalStages) && attempts < POST_TERMINAL_CONVERGENCE_ATTEMPTS) {
                    delay(POST_TERMINAL_CONVERGENCE_INTERVAL_MS)
                    val refreshedJobs = client.listJobs(projectId, pipelineId)
                    finalStages = buildStages(refreshedJobs)
                    _state.value = _state.value.copy(stages = finalStages)
                    attempts++
                }

                val durationLabel = updated.duration?.let { "${it}s" } ?: "?"
                val breakdown = formatStageBreakdown(finalStages)
                val baseMsg = MyBundle["notification.pipelineFinished", updated.id.toString(), updated.status.raw, durationLabel]
                val fullMsg = if (breakdown.isBlank()) baseMsg else "$baseMsg\n$breakdown"
                notify(
                    fullMsg,
                    if (updated.status == PipelineStatus.SUCCESS) NotificationType.INFORMATION
                    else NotificationType.ERROR,
                )
                // Suppress the upcoming auto-refresh "terminal" balloon for this id — we just sent
                // the richer one above.
                notifiedTerminalIds += updated.id
                _state.value = _state.value.copy(following = null, followingTag = null, currentStage = null)
                break
            }
            delay(3_000L)
        }
    }

    /**
     * A pipeline's jobs are "converged" when every one of them is in a terminal status or in
     * MANUAL (the latter never auto-resolves — it's terminal-for-our-purposes). Used to decide
     * when to stop the post-terminal convergence loop above.
     */
    private fun jobsConverged(stages: List<StageSummary>): Boolean =
        stages.all { stage ->
            stage.jobs.all { it.status.isTerminal || it.status == PipelineStatus.MANUAL }
        }

    /**
     * Preserves the order in which stages were first introduced by the GitLab jobs response
     * (which mirrors `.gitlab-ci.yml` declaration order).
     */
    private fun buildStages(jobs: List<com.github.danielalejandroamaro.gitlabpipeline.model.Job>): List<StageSummary> {
        val byStage = linkedMapOf<String, MutableList<com.github.danielalejandroamaro.gitlabpipeline.model.Job>>()
        for (job in jobs) {
            byStage.getOrPut(job.stage) { mutableListOf() } += job
        }
        return byStage.map { (name, jobsInStage) -> StageSummary.fromJobs(name, jobsInStage) }
    }

    /** Multi-line summary like "✓ build (3/3) · ✗ test (1/4) · — deploy (skipped)" — without emojis. */
    private fun formatStageBreakdown(stages: List<StageSummary>): String {
        if (stages.isEmpty()) return ""
        return stages.joinToString(separator = "\n") { s ->
            val marker = when (s.status) {
                PipelineStatus.SUCCESS -> "OK"
                PipelineStatus.FAILED -> "FAIL"
                PipelineStatus.CANCELED -> "CANCEL"
                PipelineStatus.SKIPPED -> "SKIP"
                PipelineStatus.RUNNING -> "RUN"
                PipelineStatus.PENDING, PipelineStatus.PREPARING,
                PipelineStatus.WAITING_FOR_RESOURCE, PipelineStatus.CREATED -> "WAIT"
                PipelineStatus.MANUAL -> "MANUAL"
                PipelineStatus.SCHEDULED -> "SCHED"
                PipelineStatus.UNKNOWN -> "?"
            }
            "  [$marker] ${s.name} (${s.succeededJobs}/${s.totalJobs})"
        }
    }

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("GitLab Pipeline Watcher")
            .createNotification(message, type)
            .notify(project)
    }

    fun dispose() {
        scope.cancel()
    }

    companion object {
        /**
         * Pause between detecting a push (or being told to `followTag`) and the first poll
         * against GitLab. Runners aren't instantaneous — the pipeline row typically appears
         * 1–3s after the push lands. Polling before that is just wasted requests.
         */
        private const val PUSH_INITIAL_DELAY_MS = 2_000L

        /** How many extra job polls we do after the parent pipeline goes terminal, to absorb GitLab's eventual-consistency lag between /pipeline status and /jobs status. */
        private const val POST_TERMINAL_CONVERGENCE_ATTEMPTS = 5

        /** Spacing between those post-terminal job polls. */
        private const val POST_TERMINAL_CONVERGENCE_INTERVAL_MS = 1_500L
    }
}
