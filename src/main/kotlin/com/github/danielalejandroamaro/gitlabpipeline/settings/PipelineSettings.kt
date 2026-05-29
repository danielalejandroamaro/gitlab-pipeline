package com.github.danielalejandroamaro.gitlabpipeline.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Application-level persisted settings for the pipeline watcher. Exposed as a [PersistentStateComponent]
 * so IntelliJ handles load/save automatically into `pipelineWatcher.xml` under the IDE config dir.
 */
@Service(Service.Level.APP)
@State(
    name = "GitLabPipelineWatcherSettings",
    storages = [Storage("pipelineWatcher.xml")],
)
class PipelineSettings : PersistentStateComponent<PipelineSettings.State> {

    data class State(
        /** Auto-refresh cadence in seconds. Clamped at read time to [MIN_INTERVAL_SECONDS]..[MAX_INTERVAL_SECONDS]. */
        var refreshIntervalSeconds: Int = DEFAULT_INTERVAL_SECONDS,
        /**
         * When false, the background poll stops ticking once every known pipeline is terminal. The
         * service still does the initial load at project open and keeps polling while any pipeline
         * is in progress (it only shuts off when work settles).
         */
        var idlePollingEnabled: Boolean = true,
    )

    private var state = State()

    override fun getState(): State = state
    override fun loadState(loaded: State) {
        XmlSerializerUtil.copyBean(loaded, state)
    }

    val refreshIntervalMs: Long
        get() = state.refreshIntervalSeconds
            .coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS)
            .toLong() * 1_000L

    val idlePollingEnabled: Boolean get() = state.idlePollingEnabled

    fun update(intervalSeconds: Int, idlePollingEnabled: Boolean) {
        state.refreshIntervalSeconds = intervalSeconds.coerceIn(MIN_INTERVAL_SECONDS, MAX_INTERVAL_SECONDS)
        state.idlePollingEnabled = idlePollingEnabled
    }

    companion object {
        const val DEFAULT_INTERVAL_SECONDS = 3
        const val MIN_INTERVAL_SECONDS = 1
        const val MAX_INTERVAL_SECONDS = 300

        fun getInstance(): PipelineSettings =
            ApplicationManager.getApplication().getService(PipelineSettings::class.java)
    }
}
