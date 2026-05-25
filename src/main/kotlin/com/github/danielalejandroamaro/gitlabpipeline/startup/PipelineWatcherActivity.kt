package com.github.danielalejandroamaro.gitlabpipeline.startup

import com.github.danielalejandroamaro.gitlabpipeline.services.GitLabCiDetector
import com.github.danielalejandroamaro.gitlabpipeline.services.GitLabPipelineService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.AsyncFileListener
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindowManager

/**
 * On project open: prime the pipeline service and start watching for
 * `.gitlab-ci.yml` create/delete events so the tool window can be shown/hidden
 * without an IDE restart.
 */
class PipelineWatcherActivity : ProjectActivity {

    override suspend fun execute(project: Project) {
        val service = project.service<GitLabPipelineService>()
        service.recheckCi()
        if (service.state.value.ciEnabled) {
            service.refresh()
        }

        VirtualFileManager.getInstance().addAsyncFileListener(CiFileListener(project), project)
    }
}

private class CiFileListener(private val project: Project) : AsyncFileListener {
    override fun prepareChange(events: MutableList<out VFileEvent>): AsyncFileListener.ChangeApplier? {
        val touchesCiFile = events.any { ev ->
            ev.path.endsWith("/.gitlab-ci.yml") || ev.path.endsWith("\\.gitlab-ci.yml")
        }
        if (!touchesCiFile) return null
        return object : AsyncFileListener.ChangeApplier {
            override fun afterVfsChange() {
                val wasEnabled = project.service<GitLabPipelineService>().state.value.ciEnabled
                project.service<GitLabPipelineService>().recheckCi()
                val isEnabled = GitLabCiDetector.hasCiFile(project)
                if (wasEnabled != isEnabled) {
                    ApplicationManager.getApplication().invokeLater {
                        val twm = ToolWindowManager.getInstance(project)
                        twm.getToolWindow("GitLab Pipelines")?.setAvailable(isEnabled, null)
                    }
                }
            }
        }
    }
}
