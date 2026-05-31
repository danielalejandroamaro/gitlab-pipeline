package com.github.danielalejandroamaro.gitlabpipeline.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date

/**
 * In-memory ring buffer of plugin-internal events (resolve-chain failures, recoveries, pipeline
 * lifecycle transitions, diagnostic dumps). Surfaced in the "Logs" tab of the GitLab Pipelines
 * tool window so the user has a single place to look when the plugin "just isn't connecting" —
 * the balloons via [com.intellij.notification.NotificationGroupManager] already land in the IDE's
 * Event Log, but those are global and easy to miss; this tab is plugin-scoped and persistent for
 * the session.
 */
@Service(Service.Level.PROJECT)
class PipelineEventLog(@Suppress("unused") private val project: Project) {

    enum class Level { INFO, WARN, ERROR }

    data class Entry(val ts: Long, val level: Level, val message: String) {
        fun format(): String = "${formatTime(ts)} [${level.name.padEnd(5)}] $message"

        companion object {
            private val FMT = SimpleDateFormat("HH:mm:ss")
            private fun formatTime(t: Long): String = synchronized(FMT) { FMT.format(Date(t)) }
        }
    }

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    fun info(message: String) = append(Level.INFO, message)
    fun warn(message: String) = append(Level.WARN, message)
    fun error(message: String) = append(Level.ERROR, message)

    fun clear() {
        _entries.value = emptyList()
    }

    private fun append(level: Level, message: String) {
        val entry = Entry(System.currentTimeMillis(), level, message)
        val current = _entries.value
        // Drop oldest when the ring is full so the panel doesn't grow unbounded over a long
        // session — the user cares about recent events.
        val next = if (current.size >= MAX_ENTRIES) current.drop(1) + entry else current + entry
        _entries.value = next
    }

    companion object {
        private const val MAX_ENTRIES = 500
    }
}
