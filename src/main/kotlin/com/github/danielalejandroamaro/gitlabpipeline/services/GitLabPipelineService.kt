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
    )

    private val _state = MutableStateFlow(State(ciEnabled = GitLabCiDetector.hasCiFile(project)))
    val state: StateFlow<State> = _state.asStateFlow()

    private var followJob: Job? = null

    /** Re-check `.gitlab-ci.yml` presence (called from VFS listener / refresh). */
    fun recheckCi() {
        _state.value = _state.value.copy(ciEnabled = GitLabCiDetector.hasCiFile(project))
    }

    /** Manual refresh from the tool window. */
    fun refresh() {
        if (!_state.value.ciEnabled) return
        scope.launch(Dispatchers.IO) { refreshNow() }
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
        val pipelines = client.listPipelines(projectId)
        _state.value = _state.value.copy(
            errorMessage = null,
            pipelines = pipelines,
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
            val ctx = refreshNow() ?: return@launch
            val (client, projectId) = ctx
            val baselineLatestTagPipelineId = _state.value.pipelines
                .filter { it.tag }
                .maxOfOrNull { it.id } ?: 0L

            var newPipeline: Pipeline? = null
            repeat(60) { attempt ->
                val candidates = client.listPipelines(projectId, perPage = 10)
                newPipeline = candidates.firstOrNull { it.tag && it.id > baselineLatestTagPipelineId }
                if (newPipeline != null) return@repeat
                delay(if (attempt < 5) 2_000L else 10_000L)
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

            // Poll up to ~10 minutes for the pipeline to appear.
            var pipeline: Pipeline? = null
            repeat(60) { attempt ->
                pipeline = client.findPipelineForTag(projectId, tagName)
                if (pipeline != null) return@repeat
                delay(if (attempt < 5) 2_000L else 10_000L)
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
                val durationLabel = updated.duration?.let { "${it}s" } ?: "?"
                val breakdown = formatStageBreakdown(stages)
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
            delay(8_000L)
        }
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
}
