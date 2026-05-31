package com.github.danielalejandroamaro.gitlabpipeline.toolWindow

import com.github.danielalejandroamaro.gitlabpipeline.services.GitLabPipelineService
import com.github.danielalejandroamaro.gitlabpipeline.services.PipelineEventLog
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea

/**
 * Second content of the GitLab Pipelines tool window: a plain-text dump of internal events
 * (resolve-chain failures, connection recoveries, pipeline lifecycle transitions, diagnostics).
 * The user opens this tab when "no se conecta a gitlab" — the resolve chain stops silently from
 * outside the tool window otherwise, and balloons get lost in the global Event Log.
 *
 * Top bar:
 *   - "Diagnosticar ahora" — runs [GitLabPipelineService.runDiagnostics] which writes every step
 *     of the resolve chain to the log so we can pinpoint where the connection breaks.
 *   - "Copiar todo" — dumps the visible buffer to the clipboard for pasting in a bug report.
 *   - "Limpiar" — empties the buffer.
 */
class EventLogTabPanel(private val project: Project) {

    private val eventLog = project.service<PipelineEventLog>()
    private val service = project.service<GitLabPipelineService>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var subscription: Job? = null

    private val area = JTextArea("").apply {
        isEditable = false
        lineWrap = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        background = JBColor(Color(0xF7F7F7), Color(0x2B2B2B))
    }

    private val scrollPane = JBScrollPane(area)

    private val diagnoseButton = JButton("Diagnosticar ahora").apply {
        addActionListener {
            // Run off the EDT — the diagnostic chain hits the network (resolveProjectId,
            // listPipelines). The log writes themselves are thread-safe (StateFlow).
            scope.launch(Dispatchers.IO) { service.runDiagnostics() }
        }
    }

    private val copyButton = JButton("Copiar todo").apply {
        addActionListener {
            CopyPasteManager.getInstance().setContents(StringSelection(area.text))
        }
    }

    private val clearButton = JButton("Limpiar").apply {
        addActionListener { eventLog.clear() }
    }

    private val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
        add(diagnoseButton)
        add(copyButton)
        add(clearButton)
    }

    val root: JComponent = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        border = BorderFactory.createEmptyBorder()
        add(toolbar, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
    }

    init {
        subscription = scope.launch {
            eventLog.entries.collect { entries -> renderEntries(entries) }
        }
    }

    private fun renderEntries(entries: List<PipelineEventLog.Entry>) {
        ApplicationManager.getApplication().invokeLater {
            val text = entries.joinToString("\n") { it.format() }
            // Preserve auto-scroll-to-bottom while the user is reading the tail; if they've
            // scrolled up to inspect something, don't yank them back down.
            val verticalBar = scrollPane.verticalScrollBar
            val wasAtBottom = verticalBar.value + verticalBar.visibleAmount >= verticalBar.maximum - 4
            if (area.text != text) area.text = text
            if (wasAtBottom) {
                ApplicationManager.getApplication().invokeLater {
                    verticalBar.value = verticalBar.maximum
                }
            }
        }
    }

    fun dispose() {
        subscription?.cancel()
        scope.cancel()
    }
}
