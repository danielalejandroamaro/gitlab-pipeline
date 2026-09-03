package com.github.danielalejandroamaro.gitlabpipeline.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Project-level persisted settings. Lives in the workspace file (not versioned) because the
 * remote choice is a local preference: two devs of the same repo can watch different remotes.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "GitLabPipelineWatcherProject",
    storages = [Storage(StoragePathMacros.WORKSPACE_FILE)],
)
class PipelineProjectSettings : PersistentStateComponent<PipelineProjectSettings.State> {

    data class State(
        /** Git remote URL to watch. Null/blank = auto-detect (first remote with a matching account). */
        var preferredRemoteUrl: String? = null,
    )

    private var state = State()

    override fun getState(): State = state
    override fun loadState(loaded: State) {
        XmlSerializerUtil.copyBean(loaded, state)
    }

    var preferredRemoteUrl: String?
        get() = state.preferredRemoteUrl?.takeIf { it.isNotBlank() }
        set(value) {
            state.preferredRemoteUrl = value?.takeIf { it.isNotBlank() }
        }

    companion object {
        fun getInstance(project: Project): PipelineProjectSettings =
            project.getService(PipelineProjectSettings::class.java)
    }
}
