package com.github.danielalejandroamaro.gitlabpipeline.model

enum class PipelineStatus(val raw: String) {
    CREATED("created"),
    WAITING_FOR_RESOURCE("waiting_for_resource"),
    PREPARING("preparing"),
    PENDING("pending"),
    RUNNING("running"),
    SUCCESS("success"),
    FAILED("failed"),
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
