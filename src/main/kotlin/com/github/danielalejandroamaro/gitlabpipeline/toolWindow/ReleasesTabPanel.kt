package com.github.danielalejandroamaro.gitlabpipeline.toolWindow

import com.github.danielalejandroamaro.gitlabpipeline.model.Release
import com.github.danielalejandroamaro.gitlabpipeline.model.ReleaseAsset
import com.github.danielalejandroamaro.gitlabpipeline.model.ReleaseAssetKind
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
import java.awt.Dimension
import java.awt.Graphics
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTree
import javax.swing.SwingConstants
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Third content of the GitLab Pipelines tool window: project releases with their assets grouped
 * by kind (Packages / Sources / Evidence / Other). Packages auto-expand because that's what the
 * user normally hits; the other groups stay collapsed to keep the tree compact.
 *
 * Driven off [GitLabPipelineService.State] — same refresh loop and balloon system as the
 * Pipelines tab, so a new release shows up automatically without the user touching Refresh.
 */
class ReleasesTabPanel(private val project: Project) {

    private val service = project.service<GitLabPipelineService>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var subscription: Job? = null

    private val rootNode = DefaultMutableTreeNode("root")
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        toggleClickCount = 0
        cellRenderer = ReleasesTreeRenderer()
        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) { if (e.isPopupTrigger) showContextMenu(e) }
            override fun mouseReleased(e: MouseEvent) { if (e.isPopupTrigger) showContextMenu(e) }
            override fun mouseClicked(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1 || e.isPopupTrigger) return
                val path = getPathForLocation(e.x, e.y) ?: return
                val data = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject ?: return

                // Inline download icon hit-test on package rows — mirrors the pattern in
                // PipelinePanel.JobRow for artifacts.
                if (data is AssetRow && data.asset.kind == ReleaseAssetKind.PACKAGE
                    && data.asset.downloadUrl.isNotBlank()) {
                    val bounds = getPathBounds(path) ?: return
                    val dl = AllIcons.Actions.Download
                    val iconLeft = bounds.x + bounds.width - dl.iconWidth - ICON_RIGHT_PAD
                    val iconRight = bounds.x + bounds.width
                    if (e.x in iconLeft..iconRight) {
                        downloadAsset(data.asset)
                        return
                    }
                }

                if (e.clickCount == 2) {
                    when (data) {
                        is AssetRow -> if (data.asset.url.isNotBlank()) BrowserUtil.browse(data.asset.url)
                        is ReleaseHeaderRow -> BrowserUtil.browse(releaseWebUrl(data.release) ?: return)
                        is AssetGroupRow -> {} // group nodes only expand/collapse via the chevron
                    }
                }
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
        // Same model as PipelinePanel: subscribe to the service state flow. The auto-refresh
        // loop in the service ticks releases alongside pipelines, so we get free updates and
        // delta balloons without any per-tab polling.
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
            state.releases.isEmpty() -> {
                statusLabel.text = if (state.lastRefreshedAt == 0L) "Cargando releases…" else "(sin releases)"
                rebuild(emptyList())
            }
            else -> {
                statusLabel.text = "${state.releases.size} releases"
                rebuild(state.releases)
            }
        }
    }

    /**
     * Tracks which group nodes the user has manually collapsed so a state-driven rebuild doesn't
     * forcibly re-expand them every refresh tick. Keyed by "<releaseTag>|<groupKind>".
     */
    private val collapsedGroupKeys = mutableSetOf<String>()
    /** Releases the user has collapsed at the root level. Same idea, keyed by tagName. */
    private val collapsedReleaseTags = mutableSetOf<String>()

    private fun rebuild(releases: List<Release>) {
        snapshotCollapsedState()
        rootNode.removeAllChildren()
        for (r in releases) {
            val rn = DefaultMutableTreeNode(ReleaseHeaderRow(r))
            // Display order: Packages (the interesting one) first, then Sources, Evidence, Other.
            val groups = r.assets.groupBy { it.kind }
            for (kind in DISPLAY_ORDER) {
                val items = groups[kind] ?: continue
                val gn = DefaultMutableTreeNode(AssetGroupRow(r, kind, items.size))
                for (a in items) gn.add(DefaultMutableTreeNode(AssetRow(a)))
                rn.add(gn)
            }
            rootNode.add(rn)
        }
        treeModel.reload()
        applyExpansionPolicy(releases)
    }

    private fun snapshotCollapsedState() {
        for (i in 0 until rootNode.childCount) {
            val rn = rootNode.getChildAt(i) as DefaultMutableTreeNode
            val rh = rn.userObject as? ReleaseHeaderRow ?: continue
            if (!tree.isExpanded(TreePath(rn.path))) collapsedReleaseTags += rh.release.tagName
            else collapsedReleaseTags -= rh.release.tagName
            for (j in 0 until rn.childCount) {
                val gn = rn.getChildAt(j) as DefaultMutableTreeNode
                val g = gn.userObject as? AssetGroupRow ?: continue
                val key = "${rh.release.tagName}|${g.kind}"
                if (!tree.isExpanded(TreePath(gn.path))) collapsedGroupKeys += key
                else collapsedGroupKeys -= key
            }
        }
    }

    /**
     * Default expansion: each release expanded; within each release only Packages expanded.
     * Respects whatever the user manually collapsed since last render.
     */
    private fun applyExpansionPolicy(releases: List<Release>) {
        for (i in 0 until rootNode.childCount) {
            val rn = rootNode.getChildAt(i) as DefaultMutableTreeNode
            val rh = rn.userObject as? ReleaseHeaderRow ?: continue
            if (rh.release.tagName !in collapsedReleaseTags) {
                tree.expandPath(TreePath(rn.path))
            }
            for (j in 0 until rn.childCount) {
                val gn = rn.getChildAt(j) as DefaultMutableTreeNode
                val g = gn.userObject as? AssetGroupRow ?: continue
                val key = "${rh.release.tagName}|${g.kind}"
                val expandByDefault = g.kind == ReleaseAssetKind.PACKAGE
                if (expandByDefault && key !in collapsedGroupKeys) {
                    tree.expandPath(TreePath(gn.path))
                }
            }
        }
    }

    private fun showContextMenu(e: MouseEvent) {
        val path = tree.getPathForLocation(e.x, e.y) ?: return
        tree.selectionPath = path
        val data = (path.lastPathComponent as? DefaultMutableTreeNode)?.userObject ?: return
        val menu = JPopupMenu()
        when (data) {
            is AssetRow -> {
                val a = data.asset
                if (a.kind == ReleaseAssetKind.PACKAGE && a.downloadUrl.isNotBlank()) {
                    menu.add(JMenuItem("Descargar ${a.name}", AllIcons.Actions.Download).apply {
                        addActionListener { downloadAsset(a) }
                    })
                    menu.addSeparator()
                }
                if (a.url.isNotBlank()) {
                    menu.add(JMenuItem("Abrir en navegador").apply {
                        addActionListener { BrowserUtil.browse(a.url) }
                    })
                    menu.add(JMenuItem("Copiar URL").apply {
                        addActionListener {
                            CopyPasteManager.getInstance().setContents(StringSelection(a.url))
                        }
                    })
                }
            }
            is ReleaseHeaderRow -> {
                releaseWebUrl(data.release)?.let { url ->
                    menu.add(JMenuItem("Abrir release en navegador").apply {
                        addActionListener { BrowserUtil.browse(url) }
                    })
                }
                menu.add(JMenuItem("Copiar tag: ${data.release.tagName}").apply {
                    addActionListener {
                        CopyPasteManager.getInstance().setContents(StringSelection(data.release.tagName))
                    }
                })
            }
            is AssetGroupRow -> {} // group nodes have no contextual actions
        }
        if (menu.componentCount > 0) menu.show(tree, e.x, e.y)
    }

    private fun downloadAsset(asset: ReleaseAsset) {
        val suggestedName = asset.downloadUrl.substringAfterLast('/').ifBlank { "${asset.name}.bin" }
        val descriptor = com.intellij.openapi.fileChooser.FileSaverDescriptor(
            "Descargar package", "Selecciona dónde guardar el archivo", "",
        )
        val saver = com.intellij.openapi.fileChooser.FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
        val chosen = saver.save(null as java.nio.file.Path?, suggestedName) ?: return
        val dest = chosen.file
        scope.launch(Dispatchers.IO) {
            val ok = service.downloadFromUrl(asset.downloadUrl, dest)
            ApplicationManager.getApplication().invokeLater {
                val type = if (ok) NotificationType.INFORMATION else NotificationType.ERROR
                val msg = if (ok) "Descargado en ${dest.absolutePath}"
                          else "No se pudo descargar ${asset.name}"
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("GitLab Pipeline Watcher")
                    .createNotification(msg, type)
                    .notify(project)
            }
        }
    }

    /**
     * GitLab serves releases at /-/releases/<tag>. We don't always have the project web URL,
     * so we infer it from the first asset's URL by stripping after `/-/`. ponytail: cheap, works
     * because every release asset lives under the same project base; upgrade when a release with
     * zero assets needs deep-linking.
     */
    private fun releaseWebUrl(release: Release): String? {
        val anyAssetUrl = release.assets.firstOrNull { it.url.isNotBlank() }?.url ?: return null
        val base = anyAssetUrl.substringBefore("/-/").ifBlank { return null }
        return "$base/-/releases/${release.tagName}"
    }

    fun dispose() {
        subscription?.cancel()
        scope.cancel()
    }

    private companion object {
        /** Render order for asset groups. Packages first because that's what the user normally wants. */
        private val DISPLAY_ORDER = listOf(
            ReleaseAssetKind.PACKAGE,
            ReleaseAssetKind.SOURCE,
            ReleaseAssetKind.EVIDENCE,
            ReleaseAssetKind.OTHER,
        )
        /** Right-edge padding (px) around the inline download icon on package rows. */
        const val ICON_RIGHT_PAD = 4
    }
}

private sealed class ReleaseTreeRow
private data class ReleaseHeaderRow(val release: Release) : ReleaseTreeRow()
private data class AssetGroupRow(val release: Release, val kind: ReleaseAssetKind, val count: Int) : ReleaseTreeRow()
private data class AssetRow(val asset: ReleaseAsset) : ReleaseTreeRow()

private class ReleasesTreeRenderer : ColoredTreeCellRenderer() {

    /** Set true on package rows so paintComponent draws the inline download icon. */
    private var paintDownloadIcon: Boolean = false

    override fun customizeCellRenderer(
        tree: JTree, value: Any?, selected: Boolean, expanded: Boolean,
        leaf: Boolean, row: Int, hasFocus: Boolean,
    ) {
        paintDownloadIcon = false
        val node = value as? DefaultMutableTreeNode ?: return
        when (val data = node.userObject) {
            is ReleaseHeaderRow -> {
                icon = AllIcons.Nodes.Tag
                append(data.release.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                data.release.releasedAt?.let {
                    append("  ${it.take(10)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                toolTipText = "Doble click abre el release en navegador"
            }
            is AssetGroupRow -> {
                icon = groupIcon(data.kind)
                append(groupLabel(data.kind), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                append("  (${data.count})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            is AssetRow -> {
                icon = iconFor(data.asset.kind)
                append(data.asset.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                if (data.asset.kind == ReleaseAssetKind.PACKAGE && data.asset.downloadUrl.isNotBlank()) {
                    paintDownloadIcon = true
                    toolTipText = "Click en el icono ⬇ para descargar; doble click abre en navegador"
                } else {
                    toolTipText = data.asset.url.ifBlank { null }
                }
            }
        }
    }

    override fun getPreferredSize(): Dimension {
        val base = super.getPreferredSize()
        if (paintDownloadIcon) {
            base.width += AllIcons.Actions.Download.iconWidth + ICON_TOTAL_PADDING
        }
        return base
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        if (paintDownloadIcon) {
            val dl = AllIcons.Actions.Download
            val iconX = width - dl.iconWidth - ICON_RIGHT_PAD_PX
            val iconY = (height - dl.iconHeight) / 2
            dl.paintIcon(this, g, iconX, iconY)
        }
    }

    private fun iconFor(kind: ReleaseAssetKind): Icon = when (kind) {
        ReleaseAssetKind.PACKAGE -> AllIcons.Nodes.PpJar
        ReleaseAssetKind.SOURCE -> AllIcons.FileTypes.Archive
        ReleaseAssetKind.EVIDENCE -> AllIcons.FileTypes.Json
        ReleaseAssetKind.OTHER -> AllIcons.General.Web
    }

    private fun groupIcon(kind: ReleaseAssetKind): Icon = when (kind) {
        ReleaseAssetKind.PACKAGE -> AllIcons.Nodes.PpLibFolder
        ReleaseAssetKind.SOURCE -> AllIcons.Nodes.Folder
        ReleaseAssetKind.EVIDENCE -> AllIcons.Nodes.Folder
        ReleaseAssetKind.OTHER -> AllIcons.Nodes.Folder
    }

    private fun groupLabel(kind: ReleaseAssetKind): String = when (kind) {
        ReleaseAssetKind.PACKAGE -> "Packages"
        ReleaseAssetKind.SOURCE -> "Source archives"
        ReleaseAssetKind.EVIDENCE -> "Evidence"
        ReleaseAssetKind.OTHER -> "Other"
    }

    private companion object {
        private const val ICON_RIGHT_PAD_PX = 12
        private const val ICON_LEFT_PAD_PX = 8
        private const val ICON_TOTAL_PADDING = ICON_LEFT_PAD_PX + ICON_RIGHT_PAD_PX
    }
}
