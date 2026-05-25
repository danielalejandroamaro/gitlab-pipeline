package com.github.danielalejandroamaro.gitlabpipeline.statusBar

import com.github.danielalejandroamaro.gitlabpipeline.model.Pipeline
import com.github.danielalejandroamaro.gitlabpipeline.model.PipelineStatus
import com.github.danielalejandroamaro.gitlabpipeline.services.GitLabPipelineService
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.impl.status.EditorBasedWidget
import com.intellij.util.Consumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.awt.event.MouseEvent
import javax.swing.Icon

class PipelineStatusBarWidgetFactory : StatusBarWidgetFactory {
    override fun getId(): String = WIDGET_ID
    override fun getDisplayName(): String = "GitLab Pipeline"
    override fun isAvailable(project: Project): Boolean = true
    override fun createWidget(project: Project): StatusBarWidget = PipelineStatusBarWidget(project)
    override fun canBeEnabledOn(statusBar: StatusBar): Boolean = true

    companion object {
        const val WIDGET_ID = "GitLabPipelineStatusBarWidget"
    }
}

class PipelineStatusBarWidget(
    project: Project,
) : EditorBasedWidget(project), StatusBarWidget.MultipleTextValuesPresentation, StatusBarWidget.Multiframe {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var subscription: Job? = null
    private var animator: Job? = null
    private var current: Pipeline? = null
    private var followingTag: String? = null
    private var spinnerFrame: Int = 0

    override fun ID(): String = PipelineStatusBarWidgetFactory.WIDGET_ID

    override fun copy(): StatusBarWidget = PipelineStatusBarWidget(project!!)

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        super<EditorBasedWidget>.install(statusBar)
        subscription = scope.launch {
            project!!.service<GitLabPipelineService>().state.collect { state ->
                val previous = current
                current = state.following ?: state.pipelines.firstOrNull { it.tag }
                followingTag = state.followingTag
                if (state.following != null && state.following.status == PipelineStatus.RUNNING) {
                    if (animator == null || animator?.isActive != true) startSpinner()
                } else {
                    stopSpinner()
                }
                if (previous?.id != current?.id || previous?.status != current?.status) {
                    repaint()
                }
            }
        }
    }

    private fun startSpinner() {
        animator?.cancel()
        animator = scope.launch {
            while (true) {
                spinnerFrame = (spinnerFrame + 1) % SPINNER.size
                repaint()
                delay(120L)
            }
        }
    }

    private fun stopSpinner() {
        animator?.cancel()
        animator = null
        spinnerFrame = 0
    }

    private fun repaint() {
        ApplicationManager.getApplication().invokeLater { myStatusBar?.updateWidget(ID()) }
    }

    override fun getSelectedValue(): String? {
        val p = current ?: return null
        val tagHint = followingTag?.let { " · $it" } ?: ""
        return "#${p.id} ${p.status.raw}$tagHint"
    }

    override fun getTooltipText(): String? {
        val p = current ?: return null
        val ref = p.ref ?: "?"
        val sha = p.sha?.take(8) ?: "?"
        return "GitLab pipeline #${p.id} — ${p.status.raw}\nref: $ref · sha: $sha\n(click to open in browser)"
    }

    override fun getIcon(): Icon? {
        val p = current ?: return null
        if (p.status == PipelineStatus.RUNNING) return SPINNER[spinnerFrame]
        return iconFor(p.status)
    }

    override fun getClickConsumer(): Consumer<MouseEvent>? = Consumer<MouseEvent> {
        val url = current?.webUrl
        if (!url.isNullOrBlank()) BrowserUtil.browse(url)
    }

    override fun dispose() {
        subscription?.cancel()
        animator?.cancel()
        scope.cancel()
        super<EditorBasedWidget>.dispose()
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

        fun iconFor(status: PipelineStatus): Icon = when (status) {
            PipelineStatus.SUCCESS -> AllIcons.RunConfigurations.TestPassed
            PipelineStatus.FAILED -> AllIcons.RunConfigurations.TestFailed
            PipelineStatus.RUNNING -> AllIcons.Actions.Execute
            PipelineStatus.PENDING, PipelineStatus.WAITING_FOR_RESOURCE,
            PipelineStatus.PREPARING, PipelineStatus.CREATED -> AllIcons.Actions.Pause
            PipelineStatus.CANCELED -> AllIcons.Actions.Cancel
            PipelineStatus.SKIPPED -> AllIcons.RunConfigurations.TestIgnored
            PipelineStatus.MANUAL -> AllIcons.Actions.RunAll
            PipelineStatus.SCHEDULED -> AllIcons.Vcs.History
            PipelineStatus.UNKNOWN -> AllIcons.General.QuestionDialog
        }
    }
}
