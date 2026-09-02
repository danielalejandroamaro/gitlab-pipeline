package com.github.danielalejandroamaro.gitlabpipeline.toolWindow

import com.github.danielalejandroamaro.gitlabpipeline.model.PipelineStatus
import com.github.danielalejandroamaro.gitlabpipeline.model.StageSummary
import com.github.danielalejandroamaro.gitlabpipeline.ui.ColoredDotIcon
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JPanel

/**
 * Horizontal strip of "stage chips" — one per stage of the currently-followed
 * pipeline. The chip for the active stage is highlighted.
 */
internal class StagesStripPanel : JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)) {

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border()),
            JBUI.Borders.empty(4, 8),
        )
        isVisible = false
        // Width changed → the wrapped row count may have changed, so the preferred height is
        // stale until we ask the parent to re-layout.
        addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent?) = revalidate()
        })
    }

    /**
     * `FlowLayout` *lays out* wrapped rows fine, but reports a single-row preferred height, so
     * everything past the first row gets clipped when the tool window is narrow. Recompute the
     * height for the width we actually have.
     */
    override fun getPreferredSize(): Dimension {
        val base = super.getPreferredSize()
        val fl = layout as? FlowLayout ?: return base
        val avail = width - insets.left - insets.right
        if (avail <= 0) return base
        var rowWidth = 0
        var rowHeight = 0
        var total = 0
        for (c in components) {
            if (!c.isVisible) continue
            val d = c.preferredSize
            if (rowWidth > 0 && rowWidth + fl.hgap + d.width > avail) {
                total += rowHeight + fl.vgap
                rowWidth = 0
                rowHeight = 0
            }
            rowWidth += (if (rowWidth > 0) fl.hgap else 0) + d.width
            rowHeight = maxOf(rowHeight, d.height)
        }
        total += rowHeight + 2 * fl.vgap + insets.top + insets.bottom
        return Dimension(minOf(base.width, width), total)
    }

    fun update(stages: List<StageSummary>, currentStage: String?) {
        removeAll()
        if (stages.isEmpty()) {
            isVisible = false
            revalidate()
            repaint()
            return
        }
        isVisible = true
        for ((index, stage) in stages.withIndex()) {
            add(StageChip(stage, isCurrent = stage.name == currentStage))
            if (index < stages.size - 1) {
                add(JBLabel("→").apply { border = JBUI.Borders.emptyLeft(2) })
            }
        }
        revalidate()
        repaint()
    }
}

private class StageChip(stage: StageSummary, isCurrent: Boolean) : JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)) {
    init {
        isOpaque = isCurrent
        if (isCurrent) {
            background = JBColor(Color(0xDCE6FF), Color(0x3A4D70))
        }
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(borderColor(stage.status, isCurrent), 1, true),
            JBUI.Borders.empty(2, 6),
        )
        add(JBLabel(iconFor(stage.status)))
        val countLabel = "${stage.succeededJobs}/${stage.totalJobs}"
        val nameSuffix = if (isCurrent) " (en curso)" else ""
        add(JBLabel("${stage.name} ($countLabel)$nameSuffix"))
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

    private fun borderColor(status: PipelineStatus, isCurrent: Boolean): Color = when {
        isCurrent -> JBColor(Color(0x3367D6), Color(0x6BAAFF))
        status == PipelineStatus.FAILED -> JBColor(Color(0xE53935), Color(0xE57373))
        status == PipelineStatus.SUCCESS -> JBColor(Color(0x4CAF50), Color(0x5FB85F))
        else -> JBColor.border()
    }
}
