package com.github.danielalejandroamaro.gitlabpipeline.settings

import com.github.danielalejandroamaro.gitlabpipeline.MyBundle
import com.github.danielalejandroamaro.gitlabpipeline.auth.GitLabAuthBridge
import com.github.danielalejandroamaro.gitlabpipeline.services.GitLabPipelineService
import com.github.danielalejandroamaro.gitlabpipeline.services.GitRemoteResolver
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.JTextArea
import javax.swing.SpinnerNumberModel

/**
 * Settings page exposed under Settings ▸ Tools ▸ GitLab Pipeline Watcher. Lets the user
 * tweak the auto-refresh cadence, toggle the "idle polling" behavior, pick which git remote
 * to watch (repos with several remotes), and inspect / re-poll the GitLab account-manager
 * binding from the official JetBrains GitLab plugin.
 */
class PipelineSettingsConfigurable(private val project: Project) : Configurable {

    private val settings get() = PipelineSettings.getInstance()
    private val projectSettings get() = PipelineProjectSettings.getInstance(project)

    /** First item = auto-detect; the rest are the project's GitLab-shaped remote URLs. */
    private val remoteCombo = ComboBox<String>()

    private val intervalSpinner = JSpinner(
        SpinnerNumberModel(
            PipelineSettings.DEFAULT_INTERVAL_SECONDS,
            PipelineSettings.MIN_INTERVAL_SECONDS,
            PipelineSettings.MAX_INTERVAL_SECONDS,
            1,
        ),
    )

    private val idlePollingCheckbox = JBCheckBox(MyBundle["settings.idlePollingEnabled"])

    private val accountsDiagnosticArea = JTextArea("").apply {
        isEditable = false
        lineWrap = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        rows = 7
    }

    private val accountsScrollPane = JBScrollPane(accountsDiagnosticArea).apply {
        preferredSize = Dimension(0, JBUI.scale(120))
    }

    private val refreshAccountsButton = JButton(MyBundle["settings.refreshAccounts"]).apply {
        addActionListener { reloadAccountsDiagnostic() }
    }

    private var panel: JPanel? = null

    override fun getDisplayName(): String = MyBundle["settings.displayName"]

    override fun createComponent(): JComponent {
        val description = JBLabel("<html>${MyBundle["settings.idlePollingHint"]}</html>").apply {
            border = JBUI.Borders.emptyTop(4)
        }
        val accountsHeader = JBLabel("<html><b>${MyBundle["settings.accountsHeader"]}</b></html>").apply {
            border = JBUI.Borders.emptyTop(12)
        }
        val accountsHint = JBLabel("<html>${MyBundle["settings.accountsHint"]}</html>").apply {
            border = JBUI.Borders.emptyTop(4)
        }
        val remoteHint = JBLabel("<html>${MyBundle["settings.remoteHint"]}</html>").apply {
            border = JBUI.Borders.emptyTop(4)
        }
        val builder = FormBuilder.createFormBuilder()
            .addLabeledComponent(MyBundle["settings.refreshInterval"], intervalSpinner, 1, false)
            .addComponent(idlePollingCheckbox, 1)
            .addComponent(description)
            .addLabeledComponent(MyBundle["settings.remoteLabel"], remoteCombo, 12, false)
            .addComponent(remoteHint)
            .addComponent(accountsHeader)
            .addComponent(refreshAccountsButton)
            .addComponent(accountsScrollPane)
            .addComponent(accountsHint)
            .addComponentFillVertically(JPanel(), 0)
        val built = builder.panel
        panel = built
        reset()
        reloadAccountsDiagnostic()
        return built
    }

    /** Selected remote URL, or null when "auto" is chosen. */
    private fun selectedRemoteUrl(): String? =
        (remoteCombo.selectedItem as? String)?.takeIf { it != MyBundle["settings.remoteAuto"] }

    override fun isModified(): Boolean {
        val s = settings.state
        return intervalSpinner.value != s.refreshIntervalSeconds ||
            idlePollingCheckbox.isSelected != s.idlePollingEnabled ||
            selectedRemoteUrl() != projectSettings.preferredRemoteUrl
    }

    override fun apply() {
        settings.update(
            intervalSeconds = (intervalSpinner.value as Number).toInt(),
            idlePollingEnabled = idlePollingCheckbox.isSelected,
        )
        val remoteChanged = selectedRemoteUrl() != projectSettings.preferredRemoteUrl
        projectSettings.preferredRemoteUrl = selectedRemoteUrl()
        if (remoteChanged) {
            // re-resolve ya: el cache del service se invalida solo (su key lleva el projectPath)
            project.service<GitLabPipelineService>().refresh()
        }
    }

    override fun reset() {
        val s = settings.state
        intervalSpinner.value = s.refreshIntervalSeconds
        idlePollingCheckbox.isSelected = s.idlePollingEnabled
        reloadRemoteCombo()
    }

    /** Repuebla el combo con auto + los remotes GitLab del proyecto y selecciona el vigente. */
    private fun reloadRemoteCombo() {
        val auto = MyBundle["settings.remoteAuto"]
        remoteCombo.removeAllItems()
        remoteCombo.addItem(auto)
        GitRemoteResolver.candidates(project).forEach { remoteCombo.addItem(it.url) }
        val preferred = projectSettings.preferredRemoteUrl
        remoteCombo.selectedItem =
            if (preferred != null && (0 until remoteCombo.itemCount).any { remoteCombo.getItemAt(it) == preferred }) {
                preferred
            } else auto
    }

    override fun disposeUIResources() {
        panel = null
    }

    /**
     * Re-resolves the account-manager binding by calling [GitLabAuthBridge.accounts] (which
     * triggers the [Class.forName] lookup chain in the bridge property getter) and dumps the
     * result into the diagnostic area: which impl class the platform returned, last resolution
     * error if any, and the full list of accounts the bridge can see (name + server URL).
     */
    private fun reloadAccountsDiagnostic() {
        val accounts = GitLabAuthBridge.accounts()
        val sb = StringBuilder()
        sb.append("Service class resuelto: ")
            .append(GitLabAuthBridge.lastResolvedManagerClass ?: "(no resuelto)")
            .append('\n')
        GitLabAuthBridge.lastResolutionError?.let {
            sb.append("Último error: ").append(it).append('\n')
        }
        sb.append("Cuentas detectadas: ").append(accounts.size).append('\n')
        accounts.forEach { acc ->
            sb.append("  - ").append(acc.name).append(" @ ").append(acc.serverUrl).append('\n')
        }
        accountsDiagnosticArea.text = sb.toString().trimEnd()
        accountsDiagnosticArea.caretPosition = 0
    }
}
