package com.github.danielalejandroamaro.gitlabpipeline.statusBar

import com.github.danielalejandroamaro.gitlabpipeline.model.PipelineStatus
import com.github.danielalejandroamaro.gitlabpipeline.services.GitLabPipelineService
import com.github.danielalejandroamaro.gitlabpipeline.ui.ColoredDotIcon
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.Timer as SwingTimer

/**
 * Tiny status-bar indicator that lives in the **left** panel of the status bar (the same one
 * that hosts the NavBar/breadcrumb). Designed to occupy a fixed leftmost position so the user
 * can glance at pipeline status without the indicator drifting when the breadcrumb path grows.
 *
 * Unlike [PipelineStatusBarWidget] this is *not* a `StatusBarWidget`; it's a plain `JComponent`
 * because the public extension point only puts widgets on the right side. Mounted from
 * [com.github.danielalejandroamaro.gitlabpipeline.startup.LeftIndicatorMounter].
 */
class LeftPipelineIndicator(
    private val project: Project,
) : JBLabel(), Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var subscription: Job? = null
    private var currentStatus: PipelineStatus = PipelineStatus.UNKNOWN
    private var currentInfo: String = ""
    private var currentUrl: String? = null
    private var spinnerFrame: Int = 0

    private val spinnerTimer: SwingTimer = SwingTimer(100) {
        spinnerFrame = (spinnerFrame + 1) % SPINNER.size
        icon = currentIcon()
    }.apply { isRepeats = true }

    init {
        border = JBUI.Borders.empty(0, 6, 0, 4)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        icon = ColoredDotIcon.GREY
        toolTipText = "GitLab Pipeline — (sin datos aún)"
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent?) {
                currentUrl?.let { if (it.isNotBlank()) BrowserUtil.browse(it) }
            }
        })

        val service = project.service<GitLabPipelineService>()
        subscription = scope.launch {
            service.state.collect { state ->
                val active = state.following ?: state.pipelines.firstOrNull()
                currentStatus = active?.status ?: PipelineStatus.UNKNOWN
                currentUrl = active?.webUrl
                currentInfo = buildString {
                    append("GitLab pipeline ")
                    if (active == null) {
                        append("(sin datos)")
                    } else {
                        append("#").append(active.id)
                        active.ref?.let { append(" · ").append(it) }
                        append(" — ").append(active.status.raw)
                        state.currentStage?.let { append("\netapa: ").append(it) }
                        if (state.stages.isNotEmpty()) {
                            val summary = state.stages.joinToString(" · ") { "${it.name} ${it.succeededJobs}/${it.totalJobs}" }
                            append("\nstages: ").append(summary)
                        }
                    }
                }
                val animating = active != null && !active.status.isTerminal
                ApplicationManager.getApplication().invokeLater {
                    toolTipText = currentInfo.replace("\n", "<br>").let { "<html>$it</html>" }
                    icon = currentIcon()
                    if (animating) {
                        if (!spinnerTimer.isRunning) spinnerTimer.start()
                    } else {
                        if (spinnerTimer.isRunning) spinnerTimer.stop()
                    }
                    repaint()
                }
            }
        }
    }

    private fun currentIcon(): Icon = when (currentStatus) {
        PipelineStatus.SUCCESS -> ColoredDotIcon.GREEN
        PipelineStatus.FAILED -> ColoredDotIcon.RED
        PipelineStatus.CANCELING, PipelineStatus.CANCELED, PipelineStatus.SKIPPED -> ColoredDotIcon.GREY
        PipelineStatus.MANUAL, PipelineStatus.SCHEDULED -> ColoredDotIcon.AMBER
        PipelineStatus.UNKNOWN -> ColoredDotIcon.GREY
        // Non-terminal → spinner frame
        else -> SPINNER[spinnerFrame]
    }

    override fun dispose() {
        subscription?.cancel()
        scope.cancel()
        if (spinnerTimer.isRunning) spinnerTimer.stop()
    }

    companion object {
        private val SPINNER: List<Icon> = listOf(
            AllIcons.Process.Step_1,
            AllIcons.Process.Step_2,
            AllIcons.Process.Step_3,
            AllIcons.Process.Step_4,
            AllIcons.Process.Step_5,
            AllIcons.Process.Step_6,
            AllIcons.Process.Step_7,
            AllIcons.Process.Step_8,
        )
    }
}
