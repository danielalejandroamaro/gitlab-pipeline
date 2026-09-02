package com.github.danielalejandroamaro.gitlabpipeline.toolWindow

import com.github.danielalejandroamaro.gitlabpipeline.model.Job as PipelineJob
import com.github.danielalejandroamaro.gitlabpipeline.model.PipelineStatus
import com.github.danielalejandroamaro.gitlabpipeline.services.GitLabPipelineService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.Timer as SwingTimer

/**
 * Live tail of the currently-running job. Visible only while at least one job in the
 * followed pipeline is RUNNING; collapses to zero-height otherwise so the pipelines
 * tree gets all the room when there's nothing live to see.
 */
internal class LiveLogsPanel(
    private val project: Project,
    private val service: GitLabPipelineService,
    private val parentScope: CoroutineScope,
) {
    private val title = JBLabel("(no hay job corriendo)").apply {
        border = BorderFactory.createEmptyBorder(4, 8, 2, 8)
    }
    private val area = JTextArea("").apply {
        isEditable = false
        lineWrap = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        background = JBColor(Color(0xF7F7F7), Color(0x2B2B2B))
    }
    private val scrollPane = JBScrollPane(area).apply {
        preferredSize = Dimension(0, JBUI.scale(180))
    }

    val root: JComponent = JPanel(BorderLayout()).apply {
        border = BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border())
        add(title, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        isVisible = false
    }

    @Volatile private var currentJobId: Long? = null
    private var pollTimer: SwingTimer? = null
    private var stateSubscription: Job? = null

    init {
        stateSubscription = parentScope.launch {
            service.state.collect { state ->
                val running = state.stages.firstOrNull { !it.status.isTerminal }
                    ?.jobs?.firstOrNull { it.status == PipelineStatus.RUNNING }
                ApplicationManager.getApplication().invokeLater { onRunningJobChanged(running) }
            }
        }
    }

    private fun onRunningJobChanged(running: PipelineJob?) {
        if (running == null) {
            stopPolling()
            root.isVisible = false
            root.revalidate()
            return
        }
        root.isVisible = true
        if (running.id != currentJobId) {
            currentJobId = running.id
            title.text = "Runner log — ${running.stage} → ${running.name} (#${running.id})"
            area.text = "(cargando log…)"
            startPolling()
            fetchTraceAsync()
        }
    }

    private fun startPolling() {
        stopPolling()
        pollTimer = SwingTimer(3_000) { fetchTraceAsync() }.apply {
            isRepeats = true
            start()
        }
    }

    private fun stopPolling() {
        pollTimer?.stop()
        pollTimer = null
    }

    private fun fetchTraceAsync() {
        val jobId = currentJobId ?: return
        parentScope.launch(Dispatchers.IO) {
            val trace = service.fetchJobTrace(jobId)
            if (trace != null) {
                ApplicationManager.getApplication().invokeLater {
                    val verticalBar = scrollPane.verticalScrollBar
                    val wasAtBottom = verticalBar.value + verticalBar.visibleAmount >= verticalBar.maximum - 4
                    if (area.text != trace) area.text = trace
                    if (wasAtBottom) {
                        ApplicationManager.getApplication().invokeLater {
                            verticalBar.value = verticalBar.maximum
                        }
                    }
                }
            }
        }
    }

    fun dispose() {
        stateSubscription?.cancel()
        stopPolling()
    }
}
