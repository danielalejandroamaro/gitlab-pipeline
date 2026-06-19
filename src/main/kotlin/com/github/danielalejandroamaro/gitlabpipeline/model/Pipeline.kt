package com.github.danielalejandroamaro.gitlabpipeline.model

enum class PipelineStatus(val raw: String) {
    CREATED("created"),
    WAITING_FOR_RESOURCE("waiting_for_resource"),
    PREPARING("preparing"),
    PENDING("pending"),
    RUNNING("running"),
    SUCCESS("success"),
    FAILED("failed"),
    CANCELING("canceling"),
    CANCELED("canceled"),
    SKIPPED("skipped"),
    MANUAL("manual"),
    SCHEDULED("scheduled"),
    UNKNOWN("unknown");

    val isTerminal: Boolean
        get() = this == SUCCESS || this == FAILED || this == CANCELED || this == SKIPPED

    companion object {
        fun fromRaw(raw: String?): PipelineStatus =
            entries.firstOrNull { it.raw.equals(raw, ignoreCase = true) } ?: UNKNOWN
    }
}

data class Pipeline(
    val id: Long,
    val iid: Long?,
    val projectId: Long,
    val status: PipelineStatus,
    val ref: String?,
    val sha: String?,
    val tag: Boolean,
    val webUrl: String?,
    val createdAt: String?,
    val updatedAt: String?,
    val duration: Long?, // seconds
    val source: String?,
)

/**
 * A GitLab CI job. We keep only the fields the UI cares about.
 */
data class Job(
    val id: Long,
    val name: String,
    val stage: String,
    val status: PipelineStatus,
    val allowFailure: Boolean,
    val webUrl: String?,
    val startedAt: String?,
    val finishedAt: String?,
    val duration: Double?, // seconds (GitLab returns Double)
    /** Filename of the job's artifacts archive (e.g. `artifacts.zip`), or null when the job has no artifacts. */
    val artifactsFilename: String? = null,
    /** Size in bytes of the artifacts archive. Used for tooltip; null when unknown / no artifacts. */
    val artifactsSize: Long? = null,
) {
    val hasArtifacts: Boolean get() = artifactsFilename != null
}

/**
 * Aggregated view of a pipeline stage — used to render "etapa actual" + breakdown.
 *
 * Stage status is derived from the worst job status (failed > running > pending > success > skipped).
 */
data class StageSummary(
    val name: String,
    val status: PipelineStatus,
    val jobs: List<Job>,
) {
    val succeededJobs: Int get() = jobs.count { it.status == PipelineStatus.SUCCESS }
    val failedJobs: Int get() = jobs.count { it.status == PipelineStatus.FAILED && !it.allowFailure }
    val totalJobs: Int get() = jobs.size

    companion object {
        /** Roll a list of jobs into one stage summary; stage status = worst job status. */
        fun fromJobs(name: String, jobs: List<Job>): StageSummary {
            val effective = jobs.filterNot { it.allowFailure && it.status == PipelineStatus.FAILED }
            val status = when {
                jobs.any { it.status == PipelineStatus.RUNNING } -> PipelineStatus.RUNNING
                jobs.any { it.status == PipelineStatus.CANCELING } -> PipelineStatus.CANCELING
                effective.any { it.status == PipelineStatus.FAILED } -> PipelineStatus.FAILED
                jobs.any { it.status == PipelineStatus.PENDING || it.status == PipelineStatus.PREPARING ||
                           it.status == PipelineStatus.WAITING_FOR_RESOURCE || it.status == PipelineStatus.CREATED }
                    -> PipelineStatus.PENDING
                jobs.any { it.status == PipelineStatus.MANUAL } -> PipelineStatus.MANUAL
                jobs.any { it.status == PipelineStatus.SCHEDULED } -> PipelineStatus.SCHEDULED
                jobs.all { it.status == PipelineStatus.SKIPPED } -> PipelineStatus.SKIPPED
                jobs.all { it.status == PipelineStatus.CANCELED || it.status == PipelineStatus.SKIPPED }
                    -> PipelineStatus.CANCELED
                jobs.all { it.status == PipelineStatus.SUCCESS || it.status == PipelineStatus.SKIPPED }
                    -> PipelineStatus.SUCCESS
                else -> PipelineStatus.UNKNOWN
            }
            return StageSummary(name = name, status = status, jobs = jobs)
        }
    }
}
