package com.github.danielalejandroamaro.gitlabpipeline.services

import com.github.danielalejandroamaro.gitlabpipeline.MyBundle
import com.github.danielalejandroamaro.gitlabpipeline.api.GitLabApiClient
import com.github.danielalejandroamaro.gitlabpipeline.auth.GitLabAuthBridge
import com.github.danielalejandroamaro.gitlabpipeline.model.Pipeline
import com.github.danielalejandroamaro.gitlabpipeline.model.PipelineStatus
import com.github.danielalejandroamaro.gitlabpipeline.model.StageSummary
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
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
        // the user touching the button. One `GET .../pipelines?per_page=20` per tick.
        scope.launch(Dispatchers.IO) {
            // First-time tick is immediate so the left-side indicator (which doesn't trigger any
            // refresh of its own) has data within ~1 RTT of the project opening.
            if (_state.value.ciEnabled) {
                runCatching { refreshNow() }
            }
            while (true) {
                delay(AUTO_REFRESH_INTERVAL_MS)
                if (_state.value.ciEnabled && !_state.value.isRefreshing) {
                    runCatching { refreshNow() }
                }
            }
        }
    }

    /** Cached client + project id after the first successful resolve, so [fetchJobTrace] can hit the API without re-doing the lookup chain on every poll tick. */
    @Volatile private var cachedClient: GitLabApiClient? = null
    @Volatile private var cachedProjectId: Long? = null

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
            _state.value = _state.value.copy(errorMessage = "No GitLab remote detected.", pipelines = emptyList())
            return null
        }
        val account = GitLabAuthBridge.findAccountForRemote(remote.url)
        if (account == null) {
            _state.value = _state.value.copy(
                errorMessage = "No GitLab account configured for ${remote.url}.",
                pipelines = emptyList(),
            )
            return null
        }
        val token = GitLabAuthBridge.tokenFor(account)
        if (token.isNullOrBlank()) {
            _state.value = _state.value.copy(
                errorMessage = "GitLab account ${account.name} has no token stored.",
                pipelines = emptyList(),
            )
            return null
        }
        val client = GitLabApiClient(account.serverUrl, token)
        val projectId = client.resolveProjectId(remote.projectPath)
        if (projectId == null) {
            _state.value = _state.value.copy(
                errorMessage = "Could not resolve project ${remote.projectPath} on ${account.serverUrl}.",
                pipelines = emptyList(),
            )
            return null
        }
        cachedClient = client
        cachedProjectId = projectId
        val pipelines = client.listPipelines(projectId)
        if (pipelines == null) {
            // Transient API failure — keep the previous list so the tool window doesn't flash empty
            // on every blip and the user doesn't lose their tree expansion / cached jobs.
            logger.info("listPipelines transient failure during refresh; keeping previous list")
            return client to projectId
        }
        _state.value = _state.value.copy(
            errorMessage = null,
            pipelines = pipelines,
            lastRefreshedAt = System.currentTimeMillis(),
        )
        return client to projectId
    }

    /**
     * Called by the push listener. Snapshots the latest tag-pipeline id we know
     * about, then polls GitLab for a *new* tag pipeline. Once found, follows it
     * to termination.
     */
    fun onPushDetected() {
        if (!_state.value.ciEnabled) return
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
        /** Cadence of the background auto-refresh of the pipelines list. */
        private const val AUTO_REFRESH_INTERVAL_MS = 3_000L

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
