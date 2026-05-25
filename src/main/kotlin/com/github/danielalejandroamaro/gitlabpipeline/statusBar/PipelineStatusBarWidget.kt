package com.github.danielalejandroamaro.gitlabpipeline.statusBar

import com.github.danielalejandroamaro.gitlabpipeline.model.Pipeline
import com.github.danielalejandroamaro.gitlabpipeline.model.PipelineStatus
import com.github.danielalejandroamaro.gitlabpipeline.services.GitLabPipelineService
import com.github.danielalejandroamaro.gitlabpipeline.ui.ColoredDotIcon
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.openapi.wm.impl.status.EditorBasedWidget
import com.intellij.util.Consumer
import com.intellij.util.ui.Animator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    private var current: Pipeline? = null
    private var followingTag: String? = null
    private var currentStage: String? = null
    private var stagesSummary: String = ""

    @Volatile private var spinnerFrame: Int = 0

    /** Platform animator that drives [spinnerFrame] and forces a widget repaint each tick. */
    private val spinnerAnimator: Animator = object : Animator(
        "GitLabPipelineSpinner",
        SPINNER.size,
        800,                   // cycle duration ms → ~100ms per frame
        /* isRepeatable = */ true,
    ) {
        override fun paintNow(frame: Int, totalFrames: Int, cycle: Int) {
            spinnerFrame = frame % SPINNER.size
            myStatusBar?.updateWidget(ID())
        }
    }

    override fun ID(): String = PipelineStatusBarWidgetFactory.WIDGET_ID

    override fun copy(): StatusBarWidget = PipelineStatusBarWidget(project!!)

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    override fun install(statusBar: StatusBar) {
        super<EditorBasedWidget>.install(statusBar)
        subscription = scope.launch {
            project!!.service<GitLabPipelineService>().state.collect { state ->
                val active = state.following ?: state.pipelines.firstOrNull()
                current = active
                followingTag = state.followingTag
                currentStage = state.currentStage
                stagesSummary = if (state.stages.isEmpty()) "" else state.stages.joinToString(
                    separator = " · ",
                ) { s -> "${s.name} ${s.succeededJobs}/${s.totalJobs}" }

                val animating = active != null && !active.status.isTerminal
                if (animating) {
                    if (spinnerAnimator.isDisposed.not() && !spinnerAnimator.isRunning) spinnerAnimator.resume()
                } else {
                    if (spinnerAnimator.isRunning) spinnerAnimator.suspend()
                }
                myStatusBar?.updateWidget(ID())
            }
        }
    }

    override fun getSelectedValue(): String? {
        val p = current ?: return null
        val refHint = (followingTag ?: p.ref)?.let { " · $it" } ?: ""
        val stageHint = currentStage?.let { " · etapa: $it" } ?: ""
        return "Pipeline #${p.id}$refHint — ${labelFor(p.status)}$stageHint"
    }

    override fun getTooltipText(): String? {
        val p = current ?: return null
        val ref = p.ref ?: "?"
        val sha = p.sha?.take(8) ?: "?"
        val stageLine = currentStage?.let { "\netapa actual: $it" } ?: ""
        val breakdown = if (stagesSummary.isNotBlank()) "\nstages: $stagesSummary" else ""
        return "GitLab pipeline #${p.id} — ${p.status.raw}\nref: $ref · sha: $sha$stageLine$breakdown\n(click to open in browser)"
    }

    override fun getIcon(): Icon? {
        val p = current ?: return null
        return iconFor(p.status)
    }

    override fun getClickConsumer(): Consumer<MouseEvent>? = Consumer<MouseEvent> {
        val url = current?.webUrl
        if (!url.isNullOrBlank()) BrowserUtil.browse(url)
    }

    override fun dispose() {
        subscription?.cancel()
        scope.cancel()
        if (spinnerAnimator.isRunning) spinnerAnimator.suspend()
        spinnerAnimator.dispose()
        super<EditorBasedWidget>.dispose()
    }

    private fun iconFor(status: PipelineStatus): Icon = when (status) {
        PipelineStatus.SUCCESS -> ColoredDotIcon.GREEN
        PipelineStatus.FAILED -> ColoredDotIcon.RED
        PipelineStatus.CANCELED, PipelineStatus.SKIPPED -> ColoredDotIcon.GREY
        PipelineStatus.MANUAL, PipelineStatus.SCHEDULED -> ColoredDotIcon.AMBER
        PipelineStatus.UNKNOWN -> ColoredDotIcon.GREY
        // Any non-terminal state -> spinning frame
        else -> SPINNER[spinnerFrame]
    }

    private fun labelFor(status: PipelineStatus): String = when (status) {
        PipelineStatus.SUCCESS -> "terminó OK"
        PipelineStatus.FAILED -> "falló"
        PipelineStatus.CANCELED -> "cancelado"
        PipelineStatus.SKIPPED -> "skipped"
        PipelineStatus.MANUAL -> "manual"
        PipelineStatus.SCHEDULED -> "programado"
        PipelineStatus.RUNNING -> "corriendo"
        PipelineStatus.PENDING -> "pendiente"
        PipelineStatus.PREPARING -> "preparando"
        PipelineStatus.WAITING_FOR_RESOURCE -> "esperando recursos"
        PipelineStatus.CREATED -> "creado"
        PipelineStatus.UNKNOWN -> "?"
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
