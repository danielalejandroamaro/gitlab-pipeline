package com.github.danielalejandroamaro.gitlabpipeline.toolWindow

import com.github.danielalejandroamaro.gitlabpipeline.PipelineBundle
import com.github.danielalejandroamaro.gitlabpipeline.model.Job as PipelineJob
import com.github.danielalejandroamaro.gitlabpipeline.model.Pipeline
import com.github.danielalejandroamaro.gitlabpipeline.model.PipelineStatus
import com.github.danielalejandroamaro.gitlabpipeline.model.StageSummary
import com.github.danielalejandroamaro.gitlabpipeline.ui.ColoredDotIcon
import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import java.awt.Dimension
import java.awt.Graphics
import javax.swing.Icon
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

internal sealed class TreeRow
internal data class PipelineRow(
    val pipeline: Pipeline,
    val staleTag: Boolean = false,
    /** True when the latest stage succeeded but an earlier stage failed — render row as amber. */
    val mixedAmber: Boolean = false,
    /** Other tags whose pipelines ran on this same commit (multi-tag push). */
    val siblingTags: List<String> = emptyList(),
) : TreeRow()
internal data class JobRow(val job: PipelineJob) : TreeRow()
internal object LoadingRow : TreeRow()
internal object EmptyRow : TreeRow()

/**
 * Returns true when the chronologically last stage of [jobs] is SUCCESS but some earlier stage
 * is FAILED — the "partial success" case the user wants painted amber instead of red. Empty or
 * single-stage pipelines never qualify. ponytail: timestamp comparison is string-based on the
 * ISO-8601 strings GitLab returns; cheap and correct since they share zone (Z).
 */
/** Segundos transcurridos desde [startedAt] (ISO-8601 de GitLab), o null si no parsea. */
internal fun elapsedSeconds(startedAt: String?): Long? = startedAt?.let {
    runCatching {
        java.time.Duration.between(java.time.Instant.parse(it), java.time.Instant.now()).seconds
    }.getOrNull()?.coerceAtLeast(0)
}

/** "3m 25s" / "45s" — formato compacto para ETAs. */
internal fun formatSeconds(s: Long): String =
    if (s >= 60) "${s / 60}m ${s % 60}s" else "${s}s"

/**
 * "hace 9 h" — antigüedad de un timestamp ISO-8601 de GitLab, con la misma granularidad que
 * muestra la web (ahora / minutos / horas / días). null si el string falta o no parsea.
 * ponytail: truncamiento, no redondeo — el bucket y el número se eligen con división entera,
 * así nunca sale "hace 60 min" en el borde y el valor solo cambia al cruzar la unidad de verdad.
 * [now] es parámetro para poder testear sin reloj real.
 */
internal fun formatAgo(iso: String?, now: java.time.Instant = java.time.Instant.now()): String? {
    val then = iso?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() } ?: return null
    val s = java.time.Duration.between(then, now).seconds.coerceAtLeast(0)
    return when {
        s < 60 -> PipelineBundle["tree.ago.now"]
        s < 3600 -> PipelineBundle["tree.ago.m", s / 60]
        s < 86_400 -> PipelineBundle["tree.ago.h", s / 3600]
        else -> PipelineBundle["tree.ago.d", s / 86_400]
    }
}

/** "2026-09-17 11:20" en la zona local — para el tooltip, donde sí interesa el reloj exacto. */
internal fun formatLocalClock(iso: String?): String? = iso?.let {
    runCatching {
        java.time.Instant.parse(it)
            .atZone(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
    }.getOrNull()
}

/**
 * pipeline id → the OTHER tags that ran pipelines on the same sha (one push, several tags).
 * ponytail: only sees the loaded window, same ceiling as the stale-tag detection.
 */
internal fun computeSiblingTags(pipelines: List<Pipeline>): Map<Long, List<String>> {
    val tagsBySha = pipelines.filter { it.tag && !it.sha.isNullOrBlank() && !it.ref.isNullOrBlank() }
        .groupBy({ it.sha!! }, { it.ref!! })
    return pipelines.filter { it.tag }.associate { p ->
        p.id to (tagsBySha[p.sha].orEmpty().distinct() - p.ref.orEmpty())
    }.filterValues { it.isNotEmpty() }
}

internal fun computeMixedAmber(jobs: List<PipelineJob>): Boolean {
    if (jobs.isEmpty()) return false
    val stages = jobs.groupBy { it.stage }
        .map { (name, js) -> StageSummary.fromJobs(name, js) }
    if (stages.size < 2) return false
    val last = stages.maxByOrNull { st ->
        st.jobs.mapNotNull { it.finishedAt ?: it.startedAt }.maxOrNull() ?: ""
    } ?: return false
    if (last.status != PipelineStatus.SUCCESS) return false
    return stages.any { it !== last && it.status == PipelineStatus.FAILED }
}

internal class PipelineTreeRenderer(
    /** Duración de la última corrida terminada del job con ese nombre (s), o null sin historia. */
    private val jobEstimate: (String) -> Double? = { null },
) : ColoredTreeCellRenderer() {

    /** Set to true on tag-pipeline rows so paintComponent draws the inline copy icon. */
    private var paintCopyIcon: Boolean = false
    /** Set to true on JobRow with artifacts so paintComponent draws the inline download icon. */
    private var paintDownloadIcon: Boolean = false

    override fun customizeCellRenderer(
        tree: JTree, value: Any?, selected: Boolean, expanded: Boolean,
        leaf: Boolean, row: Int, hasFocus: Boolean,
    ) {
        paintCopyIcon = false
        paintDownloadIcon = false
        toolTipText = null
        val node = value as? DefaultMutableTreeNode ?: return
        when (val data = node.userObject) {
            is PipelineRow -> {
                val p = data.pipeline
                icon = if (data.mixedAmber) ColoredDotIcon.AMBER else iconFor(p.status)
                // Format: "action/version  #id" — the version is the ref/tag/branch, so a double
                // click can copy it directly without the user having to scan past the id first.
                val action = p.source ?: "push"
                val version = p.ref?.takeIf { it.isNotBlank() } ?: p.sha?.take(8) ?: "?"
                val versionAttrs = if (data.staleTag) {
                    SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, null)
                } else SimpleTextAttributes.REGULAR_ATTRIBUTES
                append("$action/$version", versionAttrs)
                // Multi-tag commit: only the count inline (accent colour); the tag names go in the tooltip.
                if (data.siblingTags.isNotEmpty()) append("  +${data.siblingTags.size}", SIBLING_TAGS_ATTRS)
                if (data.staleTag) append("  (${PipelineBundle["tree.staleTagSuffix"]})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                // Cuándo corrió: relativo inline (como la web) y reloj exacto en el tooltip.
                // Se recalcula en cada paint, así que el poll de refresh lo mantiene al día.
                formatAgo(p.createdAt)?.let { append("  · $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                if (p.tag && !p.ref.isNullOrBlank()) {
                    paintCopyIcon = true
                    toolTipText = if (data.staleTag)
                        PipelineBundle["tree.tooltip.staleTag", p.ref]
                    else PipelineBundle["tree.tooltip.copyVersion", p.ref]
                } else if (!p.ref.isNullOrBlank()) {
                    toolTipText = PipelineBundle["tree.tooltip.copyVersion", p.ref]
                }
                if (data.siblingTags.isNotEmpty()) {
                    val tags = PipelineBundle["tree.tooltip.sameCommitTags", data.siblingTags.joinToString(", ")]
                    toolTipText = toolTipText?.let { "$it · $tags" } ?: tags
                }
                formatLocalClock(p.createdAt)?.let { clock ->
                    toolTipText = toolTipText?.let { "$it · $clock" } ?: clock
                }
            }
            is JobRow -> {
                icon = iconFor(data.job.status)
                append("${data.job.stage} → ${data.job.name}", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                if (data.job.status == PipelineStatus.RUNNING) {
                    // Progreso contra la corrida anterior del MISMO job: "(43s / ~211s · 20%)".
                    // Sin baseline (primer run que vemos): solo el transcurrido.
                    val elapsed = data.job.duration?.toLong() ?: elapsedSeconds(data.job.startedAt)
                    val est = jobEstimate(data.job.name)?.toLong()
                    when {
                        elapsed != null && est != null && est > 0 -> {
                            val pct = ((elapsed * 100) / est).coerceAtMost(99)
                            append(
                                "  (${formatSeconds(elapsed)} / ~${formatSeconds(est)} · $pct%)",
                                SimpleTextAttributes.GRAYED_ATTRIBUTES,
                            )
                        }
                        elapsed != null -> append("  (${formatSeconds(elapsed)})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                } else data.job.duration?.let {
                    append("  (${it.toInt()}s)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                if (data.job.hasArtifacts) {
                    paintDownloadIcon = true
                    val sizeLabel = data.job.artifactsSize?.let { " · ${humanBytesShort(it)}" } ?: ""
                    val nameLabel = data.job.artifactsFilename ?: "artifacts.zip"
                    toolTipText = PipelineBundle["tree.tooltip.downloadArtifacts", nameLabel, sizeLabel]
                }
            }
            LoadingRow -> {
                icon = AllIcons.Process.Step_1
                append(PipelineBundle["tree.loadingJobs"], SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            EmptyRow -> {
                icon = AllIcons.General.QuestionDialog
                append(PipelineBundle["tree.noJobs"], SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }

    /**
     * Reserve trailing space for the copy/download icon so it doesn't get clipped by the cell's
     * preferred width. JTree sizes the cell to this preferredSize before painting.
     */
    override fun getPreferredSize(): Dimension {
        val base = super.getPreferredSize()
        if (paintCopyIcon) {
            base.width += AllIcons.Actions.Copy.iconWidth + COPY_ICON_TOTAL_PADDING
        }
        if (paintDownloadIcon) {
            base.width += AllIcons.Actions.Download.iconWidth + COPY_ICON_TOTAL_PADDING
        }
        return base
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        if (paintCopyIcon) {
            val copy = AllIcons.Actions.Copy
            val iconX = width - copy.iconWidth - COPY_ICON_RIGHT_PAD_PX
            val iconY = (height - copy.iconHeight) / 2
            copy.paintIcon(this, g, iconX, iconY)
        }
        if (paintDownloadIcon) {
            val dl = AllIcons.Actions.Download
            val iconX = width - dl.iconWidth - COPY_ICON_RIGHT_PAD_PX
            val iconY = (height - dl.iconHeight) / 2
            dl.paintIcon(this, g, iconX, iconY)
        }
    }

    private fun humanBytesShort(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes.toDouble() / 1024
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return String.format("%.1f %s", v, units[i])
    }

    private fun iconFor(status: PipelineStatus): Icon = when (status) {
        PipelineStatus.SUCCESS -> ColoredDotIcon.GREEN
        PipelineStatus.FAILED -> ColoredDotIcon.RED
        PipelineStatus.CANCELING, PipelineStatus.CANCELED, PipelineStatus.SKIPPED -> ColoredDotIcon.GREY
        PipelineStatus.MANUAL, PipelineStatus.SCHEDULED -> ColoredDotIcon.AMBER
        PipelineStatus.RUNNING -> AllIcons.Actions.Execute
        PipelineStatus.PENDING, PipelineStatus.WAITING_FOR_RESOURCE,
        PipelineStatus.PREPARING, PipelineStatus.CREATED -> AllIcons.Actions.Pause
        PipelineStatus.UNKNOWN -> AllIcons.General.QuestionDialog
    }

    private companion object {
        // Right padding bumped to 12 so the inline icon sits visually inside the row's hover/
        // selection highlight instead of hugging the cell's right edge (where it looked clipped
        // outside the highlight on dark themes).
        private const val COPY_ICON_RIGHT_PAD_PX = 12
        private const val COPY_ICON_LEFT_PAD_PX = 8
        private const val COPY_ICON_TOTAL_PADDING = COPY_ICON_LEFT_PAD_PX + COPY_ICON_RIGHT_PAD_PX
        private val SIBLING_TAGS_ATTRS = SimpleTextAttributes(
            SimpleTextAttributes.STYLE_BOLD or SimpleTextAttributes.STYLE_SMALLER,
            com.intellij.ui.JBColor.namedColor("Link.activeForeground", com.intellij.ui.JBColor.BLUE),
        )
    }
}
