package com.github.danielalejandroamaro.gitlabpipeline.toolWindow

import com.github.danielalejandroamaro.gitlabpipeline.MyBundle
import com.github.danielalejandroamaro.gitlabpipeline.model.Pipeline
import com.github.danielalejandroamaro.gitlabpipeline.model.PipelineStatus
import com.github.danielalejandroamaro.gitlabpipeline.model.StageSummary
import com.github.danielalejandroamaro.gitlabpipeline.services.GitLabCiDetector
import com.github.danielalejandroamaro.gitlabpipeline.services.GitLabPipelineService
import com.github.danielalejandroamaro.gitlabpipeline.ui.ColoredDotIcon
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

class PipelineToolWindowFactory : ToolWindowFactory {

    override fun shouldBeAvailable(project: Project): Boolean =
        GitLabCiDetector.hasCiFile(project)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = PipelinePanel(project)
        val content = ContentFactory.getInstance().createContent(panel.root, null, false)
        toolWindow.contentManager.addContent(content)
    }
}

private class PipelinePanel(private val project: Project) {

    private val service = project.service<GitLabPipelineService>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var subscription: Job? = null

    private val tableModel = PipelineTableModel()
    private val table = JBTable(tableModel).apply {
        setShowGrid(false)
        rowHeight = 24
        columnModel.getColumn(0).maxWidth = 28
        columnModel.getColumn(0).minWidth = 28
        getColumnModel().getColumn(0).cellRenderer = StatusIconRenderer()
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val row = rowAtPoint(e.point)
                    if (row >= 0) {
                        val url = tableModel.urlAt(row)
                        if (!url.isNullOrBlank()) BrowserUtil.browse(url)
                    }
                }
            }
        })
    }

    private val statusLabel = JBLabel("", SwingConstants.LEFT).apply {
        border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
    }

    private val refreshButton = JButton(MyBundle["toolWindow.refresh"]).apply {
        addActionListener { service.refresh() }
    }

    private val stagesPanel = StagesStripPanel()

    val root: JComponent = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        val top = JPanel(BorderLayout()).apply {
            add(statusLabel, BorderLayout.CENTER)
            add(refreshButton, BorderLayout.EAST)
        }
        add(top, BorderLayout.NORTH)
        add(JBScrollPane(table), BorderLayout.CENTER)
        add(stagesPanel, BorderLayout.SOUTH)
    }

    init {
        subscription = scope.launch {
            service.state.collect { state -> render(state) }
        }
        service.refresh()
    }

    private fun render(state: GitLabPipelineService.State) {
        ApplicationManager.getApplication().invokeLater {
            when {
                !state.ciEnabled -> statusLabel.text = MyBundle["toolWindow.noCi"]
                state.errorMessage != null -> statusLabel.text = state.errorMessage
                state.followingTag != null -> {
                    val stageHint = state.currentStage?.let { " — etapa: $it" } ?: ""
                    statusLabel.text = MyBundle[
                        "toolWindow.followingTag",
                        state.followingTag,
                        (state.following?.status?.raw ?: "?") + stageHint,
                    ]
                }
                state.pipelines.isEmpty() -> statusLabel.text = MyBundle["toolWindow.empty"]
                else -> statusLabel.text = "${state.pipelines.size} pipelines"
            }
            tableModel.update(state.pipelines)
            stagesPanel.update(state.stages, state.currentStage)
        }
    }

    fun dispose() {
        subscription?.cancel()
        scope.cancel()
    }
}

private class PipelineTableModel : AbstractTableModel() {
    private var rows: List<Pipeline> = emptyList()
    private val columns = arrayOf("", "ID", "Ref", "SHA", "Status", "Source")

    fun update(rows: List<Pipeline>) {
        this.rows = rows
        fireTableDataChanged()
    }

    fun urlAt(row: Int): String? = rows.getOrNull(row)?.webUrl
    fun statusAt(row: Int): PipelineStatus = rows.getOrNull(row)?.status ?: PipelineStatus.UNKNOWN

    override fun getRowCount() = rows.size
    override fun getColumnCount() = columns.size
    override fun getColumnName(column: Int) = columns[column]

    override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
        val p = rows[rowIndex]
        return when (columnIndex) {
            0 -> "" // icon rendered separately
            1 -> p.id
            2 -> (if (p.tag) "tag:" else "") + (p.ref ?: "")
            3 -> p.sha?.take(8) ?: ""
            4 -> p.status.raw
            5 -> p.source ?: ""
            else -> ""
        }
    }
}

private class StatusIconRenderer : DefaultTableCellRenderer() {
    override fun getTableCellRendererComponent(
        table: javax.swing.JTable, value: Any?, isSelected: Boolean, hasFocus: Boolean,
        row: Int, column: Int,
    ): java.awt.Component {
        super.getTableCellRendererComponent(table, "", isSelected, hasFocus, row, column)
        val model = table.model as PipelineTableModel
        icon = iconFor(model.statusAt(row))
        text = ""
        horizontalAlignment = SwingConstants.CENTER
        return this
    }

    private fun iconFor(status: PipelineStatus): Icon = when (status) {
        PipelineStatus.SUCCESS -> ColoredDotIcon.GREEN
        PipelineStatus.FAILED -> ColoredDotIcon.RED
        PipelineStatus.CANCELED, PipelineStatus.SKIPPED -> ColoredDotIcon.GREY
        PipelineStatus.MANUAL, PipelineStatus.SCHEDULED -> ColoredDotIcon.AMBER
        PipelineStatus.RUNNING -> AllIcons.Actions.Execute
        PipelineStatus.PENDING, PipelineStatus.WAITING_FOR_RESOURCE,
        PipelineStatus.PREPARING, PipelineStatus.CREATED -> AllIcons.Actions.Pause
        PipelineStatus.UNKNOWN -> AllIcons.General.QuestionDialog
    }
}

/**
 * Horizontal strip of "stage chips" — one per stage of the currently-followed
 * pipeline. The chip for the active stage is highlighted.
 */
private class StagesStripPanel : JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)) {

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border()),
            JBUI.Borders.empty(4, 8),
        )
        isVisible = false
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
        PipelineStatus.CANCELED, PipelineStatus.SKIPPED -> ColoredDotIcon.GREY
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
