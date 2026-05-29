package com.github.danielalejandroamaro.gitlabpipeline.settings

import com.github.danielalejandroamaro.gitlabpipeline.MyBundle
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * Settings page exposed under Settings ▸ Tools ▸ GitLab Pipeline Watcher. Lets the user
 * tweak the auto-refresh cadence and toggle the "idle polling" behavior.
 */
class PipelineSettingsConfigurable : Configurable {

    private val settings get() = PipelineSettings.getInstance()

    private val intervalSpinner = JSpinner(
        SpinnerNumberModel(
            PipelineSettings.DEFAULT_INTERVAL_SECONDS,
            PipelineSettings.MIN_INTERVAL_SECONDS,
            PipelineSettings.MAX_INTERVAL_SECONDS,
            1,
        ),
    )

    private val idlePollingCheckbox = JBCheckBox(MyBundle["settings.idlePollingEnabled"])

    private var panel: JPanel? = null

    override fun getDisplayName(): String = MyBundle["settings.displayName"]

    override fun createComponent(): JComponent {
        val description = JBLabel("<html>${MyBundle["settings.idlePollingHint"]}</html>").apply {
            border = JBUI.Borders.emptyTop(4)
        }
        val builder = FormBuilder.createFormBuilder()
            .addLabeledComponent(MyBundle["settings.refreshInterval"], intervalSpinner, 1, false)
            .addComponent(idlePollingCheckbox, 1)
            .addComponent(description)
            .addComponentFillVertically(JPanel(), 0)
        val built = builder.panel
        panel = built
        reset()
        return built
    }

    override fun isModified(): Boolean {
        val s = settings.state
        return intervalSpinner.value != s.refreshIntervalSeconds ||
            idlePollingCheckbox.isSelected != s.idlePollingEnabled
    }

    override fun apply() {
        settings.update(
            intervalSeconds = (intervalSpinner.value as Number).toInt(),
            idlePollingEnabled = idlePollingCheckbox.isSelected,
        )
    }

    override fun reset() {
        val s = settings.state
        intervalSpinner.value = s.refreshIntervalSeconds
        idlePollingCheckbox.isSelected = s.idlePollingEnabled
    }

    override fun disposeUIResources() {
        panel = null
    }
}
