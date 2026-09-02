package com.github.danielalejandroamaro.gitlabpipeline.toolWindow

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

internal class PipelineTreeRenderer : ColoredTreeCellRenderer() {

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
                if (data.staleTag) append("  (tag desapuntado)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                append("  #${p.id}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                if (p.tag && !p.ref.isNullOrBlank()) {
                    paintCopyIcon = true
                    toolTipText = if (data.staleTag)
                        "Tag ${p.ref} fue reapuntado a otro commit; este pipeline es histórico."
                    else "Doble click copia la versión (${p.ref}); click-derecho para navegar"
                } else if (!p.ref.isNullOrBlank()) {
                    toolTipText = "Doble click copia la versión (${p.ref}); click-derecho para navegar"
                }
            }
            is JobRow -> {
                icon = iconFor(data.job.status)
                append("${data.job.stage} → ${data.job.name}", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                data.job.duration?.let {
                    append("  (${it.toInt()}s)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                if (data.job.hasArtifacts) {
                    paintDownloadIcon = true
                    val sizeLabel = data.job.artifactsSize?.let { " · ${humanBytesShort(it)}" } ?: ""
                    val nameLabel = data.job.artifactsFilename ?: "artifacts.zip"
                    toolTipText = "Click en el icono ⬇ para descargar artifacts ($nameLabel$sizeLabel)"
                }
            }
            LoadingRow -> {
                icon = AllIcons.Process.Step_1
                append("cargando jobs…", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            EmptyRow -> {
                icon = AllIcons.General.QuestionDialog
                append("(sin jobs)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
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
    }
}
