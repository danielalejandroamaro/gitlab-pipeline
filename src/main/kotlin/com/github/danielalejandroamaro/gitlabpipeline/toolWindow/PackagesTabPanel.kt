package com.github.danielalejandroamaro.gitlabpipeline.toolWindow

import com.github.danielalejandroamaro.gitlabpipeline.model.GitLabPackage
import com.github.danielalejandroamaro.gitlabpipeline.services.GitLabPipelineService
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTree
import javax.swing.SwingConstants
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Fourth content of the GitLab Pipelines tool window: the project's Package Registry, one row
 * per published package version (mirrors GitLab's own flat listing). Driven off
 * [GitLabPipelineService.State] like the Releases tab — same refresh tick, no per-tab polling.
 *
 * Context menu on npm packages copies a ready-to-paste `pnpm install <name>` to the clipboard.
 */
class PackagesTabPanel(private val project: Project) {

    private val service = project.service<GitLabPipelineService>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var subscription: Job? = null

    private val rootNode = DefaultMutableTreeNode("root")
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = false
        toggleClickCount = 0
        cellRenderer = PackagesTreeRenderer()
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) { if (e.isPopupTrigger) showContextMenu(e) }
            override fun mouseReleased(e: MouseEvent) { if (e.isPopupTrigger) showContextMenu(e) }
            override fun mouseClicked(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1 || e.isPopupTrigger || e.clickCount != 2) return
                val pkg = packageAt(e) ?: return
                pkg.webUrl?.let { BrowserUtil.browse(it) }
            }
        })
    }

    private val statusLabel = JBLabel("", SwingConstants.LEFT).apply {
        border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
    }

    private val refreshButton = JButton("Refresh").apply {
        addActionListener { service.refresh() }
    }

    val root: JComponent = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        val top = JPanel(BorderLayout()).apply {
            add(statusLabel, BorderLayout.CENTER)
            add(refreshButton, BorderLayout.EAST)
        }
        add(top, BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)
    }

    init {
        subscription = scope.launch {
            service.state.collect { state ->
                ApplicationManager.getApplication().invokeLater { render(state) }
            }
        }
    }

    private fun render(state: GitLabPipelineService.State) {
        refreshButton.isEnabled = !state.isRefreshing
        when {
            !state.ciEnabled -> {
                statusLabel.text = "Sin .gitlab-ci.yml"
                rebuild(emptyList())
            }
            state.errorMessage != null -> statusLabel.text = state.errorMessage
            state.packages.isEmpty() -> {
                statusLabel.text = if (state.lastRefreshedAt == 0L) "Cargando packages…" else "(sin packages)"
                rebuild(emptyList())
            }
            else -> {
                statusLabel.text = "${state.packages.size} packages"
                rebuild(state.packages)
            }
        }
    }

    private fun rebuild(packages: List<GitLabPackage>) {
        rootNode.removeAllChildren()
        for (p in packages) rootNode.add(DefaultMutableTreeNode(p))
        treeModel.reload()
    }

    private fun packageAt(e: MouseEvent): GitLabPackage? {
        val path = tree.getPathForLocation(e.x, e.y) ?: return null
        return (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? GitLabPackage
    }

    private fun showContextMenu(e: MouseEvent) {
        val path = tree.getPathForLocation(e.x, e.y) ?: return
        tree.selectionPath = path
        val pkg = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? GitLabPackage ?: return
        val menu = JPopupMenu()
        if (pkg.packageType == "npm") {
            val cmd = "pnpm install ${pkg.name}"
            menu.add(JMenuItem("Copiar: $cmd", AllIcons.Actions.Copy).apply {
                addActionListener {
                    CopyPasteManager.getInstance().setContents(StringSelection(cmd))
                    notifyCopied(cmd)
                }
            })
            pkg.version?.let { v ->
                val cmdVersioned = "pnpm install ${pkg.name}@$v"
                menu.add(JMenuItem("Copiar: $cmdVersioned", AllIcons.Actions.Copy).apply {
                    addActionListener {
                        CopyPasteManager.getInstance().setContents(StringSelection(cmdVersioned))
                        notifyCopied(cmdVersioned)
                    }
                })
            }
        }
        pkg.webUrl?.let { url ->
            menu.add(JMenuItem("Abrir en navegador").apply {
                addActionListener { BrowserUtil.browse(url) }
            })
        }
        menu.addSeparator()
        menu.add(JMenuItem("Borrar package ${labelOf(pkg)}", AllIcons.Actions.GC).apply {
            addActionListener { confirmAndDeletePackage(pkg) }
        })
        menu.show(tree, e.x, e.y)
    }

    /**
     * Confirm, then delete the package version on a background thread. Refresh on success so the
     * row disappears. Deleting a package removes ALL its files from the registry — irreversible,
     * hence the warning dialog.
     */
    private fun confirmAndDeletePackage(pkg: GitLabPackage) {
        val label = labelOf(pkg)
        val ok = com.intellij.openapi.ui.Messages.showYesNoDialog(
            project,
            "Vas a borrar el package $label del Package Registry en GitLab.\n" +
                "Se borran todos sus archivos; la acción no tiene deshacer.\n\n¿Continuar?",
            "Borrar package",
            com.intellij.openapi.ui.Messages.getWarningIcon(),
        )
        if (ok != com.intellij.openapi.ui.Messages.YES) return
        scope.launch(Dispatchers.IO) {
            val deleted = service.deletePackage(pkg.id)
            ApplicationManager.getApplication().invokeLater {
                val type = if (deleted) NotificationType.INFORMATION else NotificationType.ERROR
                val msg = if (deleted) "Package $label borrado" else "No se pudo borrar el package $label"
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("GitLab Pipeline Watcher")
                    .createNotification(msg, type)
                    .notify(project)
                if (deleted) service.refresh()
            }
        }
    }

    private fun labelOf(pkg: GitLabPackage): String = "${pkg.name} ${pkg.version ?: ""}".trim()

    private fun notifyCopied(text: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("GitLab Pipeline Watcher")
            .createNotification("Copiado al portapapeles: $text", NotificationType.INFORMATION)
            .notify(project)
    }

    fun dispose() {
        subscription?.cancel()
        scope.cancel()
    }
}

private class PackagesTreeRenderer : ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree, value: Any?, selected: Boolean, expanded: Boolean,
        leaf: Boolean, row: Int, hasFocus: Boolean,
    ) {
        val pkg = (value as? DefaultMutableTreeNode)?.userObject as? GitLabPackage ?: return
        icon = AllIcons.Nodes.PpJar
        append(pkg.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
        pkg.version?.let { append("  $it", SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES) }
        append("  · ${pkg.packageType}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        toolTipText = "Doble click abre el package en navegador; click derecho para copiar pnpm install"
    }
}
