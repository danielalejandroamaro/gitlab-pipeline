package com.github.danielalejandroamaro.gitlabpipeline.toolWindow

import com.github.danielalejandroamaro.gitlabpipeline.MyBundle
import com.github.danielalejandroamaro.gitlabpipeline.model.Job as PipelineJob
import com.github.danielalejandroamaro.gitlabpipeline.model.Pipeline
import com.github.danielalejandroamaro.gitlabpipeline.model.PipelineStatus
import com.github.danielalejandroamaro.gitlabpipeline.model.StageSummary
import com.github.danielalejandroamaro.gitlabpipeline.services.GitLabCiDetector
import com.github.danielalejandroamaro.gitlabpipeline.services.GitLabPipelineService
import com.github.danielalejandroamaro.gitlabpipeline.ui.ColoredDotIcon
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
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
import javax.swing.JTextArea
import javax.swing.JTree
import javax.swing.SwingConstants
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.Timer as SwingTimer

class PipelineToolWindowFactory : ToolWindowFactory {

    override fun shouldBeAvailable(project: Project): Boolean =
        GitLabCiDetector.hasCiFile(project)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = PipelinePanel(project)
        val content = ContentFactory.getInstance().createContent(panel.root, null, false)
        toolWindow.contentManager.addContent(content)
    }
}

private class PipelinePanel(private val project: Project) {

    private val service = project.service<GitLabPipelineService>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var subscription: Job? = null
    private var jobsRefreshLoop: Job? = null

    private val rootNode = DefaultMutableTreeNode("root")
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = PipelineTreeRenderer()
        addMouseListener(object : MouseAdapter() {
            // Right-click → context menu. `isPopupTrigger` is checked on both press AND release
            // because Windows fires it on release and Linux on press.
            override fun mousePressed(e: MouseEvent) { if (e.isPopupTrigger) showContextMenu(e) }
            override fun mouseReleased(e: MouseEvent) { if (e.isPopupTrigger) showContextMenu(e) }

            override fun mouseClicked(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1 || e.isPopupTrigger) return
                val path = getPathForLocation(e.x, e.y) ?: return
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val data = node.userObject

                // Inline copy icon hit-test on tag pipeline rows (rightmost slice of the cell).
                if (data is PipelineRow && data.pipeline.tag && !data.pipeline.ref.isNullOrBlank()) {
                    val bounds = getPathBounds(path) ?: return
                    val copyIcon = AllIcons.Actions.Copy
                    val iconLeft = bounds.x + bounds.width - copyIcon.iconWidth - COPY_ICON_RIGHT_PADDING
                    val iconRight = bounds.x + bounds.width
                    if (e.x in iconLeft..iconRight) {
                        copyTagToClipboard(data.pipeline.ref!!)
                        return
                    }
                }

                if (e.clickCount == 2) {
                    val url = when (data) {
                        is PipelineRow -> data.pipeline.webUrl
                        is JobRow -> data.job.webUrl
                        else -> null
                    }
                    if (!url.isNullOrBlank()) BrowserUtil.browse(url)
                }
            }
        })
        addTreeWillExpandListener(object : TreeWillExpandListener {
            override fun treeWillExpand(event: TreeExpansionEvent) {
                val node = event.path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val row = node.userObject as? PipelineRow ?: return
                maybeLoadJobs(node, row.pipeline.id)
            }
            override fun treeWillCollapse(event: TreeExpansionEvent) {}
        })
    }

    /** Cached jobs by pipeline id so re-expanding (or a state-driven rebuild) doesn't re-fetch. */
    private val jobsCache = mutableMapOf<Long, List<PipelineJob>>()
    /** Pipeline ids with a fetch currently in flight — guards against repeated expand clicks. */
    private val loadingPipelineIds = mutableSetOf<Long>()
    /** Pipeline ids currently expanded — preserved across model rebuilds driven by state ticks. */
    private val expandedPipelineIds = mutableSetOf<Long>()

    private val statusLabel = JBLabel("", SwingConstants.LEFT).apply {
        border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
    }

    private val refreshButton = JButton(MyBundle["toolWindow.refresh"]).apply {
        addActionListener { service.refresh() }
    }

    private val stagesPanel = StagesStripPanel()
    private val logsPanel = LiveLogsPanel(project, service, scope)

    /** SOUTH-vertical: stages strip on top, logs panel below (logs only visible while running). */
    private val southStack: JPanel = JPanel().apply {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
        add(stagesPanel)
        add(logsPanel.root)
    }

    val root: JComponent = JBPanel<JBPanel<*>>(BorderLayout()).apply {
        val top = JPanel(BorderLayout()).apply {
            add(statusLabel, BorderLayout.CENTER)
            add(refreshButton, BorderLayout.EAST)
        }
        add(top, BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)
        add(southStack, BorderLayout.SOUTH)
    }

    init {
        subscription = scope.launch {
            service.state.collect { state -> render(state) }
        }
        // Refresh the jobs of any expanded non-followed non-terminal pipeline every few seconds
        // — otherwise their children stay frozen at whatever they showed on first expand. The
        // followed pipeline doesn't need this loop: its jobs come through state.stages and are
        // refreshed by `render()` on every tick of the service follow loop.
        jobsRefreshLoop = scope.launch {
            while (true) {
                delay(JOBS_REFRESH_INTERVAL_MS)
                refreshExpandedNonFollowedJobs()
            }
        }
        service.refresh()
    }

    private fun render(state: GitLabPipelineService.State) {
        ApplicationManager.getApplication().invokeLater {
            refreshButton.isEnabled = !state.isRefreshing
            val timeHint = if (state.lastRefreshedAt > 0L) {
                " · actualizado ${formatHms(state.lastRefreshedAt)}"
            } else ""
            when {
                !state.ciEnabled -> statusLabel.text = MyBundle["toolWindow.noCi"]
                state.errorMessage != null -> statusLabel.text = state.errorMessage + timeHint
                state.followingTag != null -> {
                    val stageHint = state.currentStage?.let { " — etapa: $it" } ?: ""
                    statusLabel.text = MyBundle[
                        "toolWindow.followingTag",
                        state.followingTag,
                        (state.following?.status?.raw ?: "?") + stageHint,
                    ] + timeHint
                }
                state.pipelines.isEmpty() -> statusLabel.text = MyBundle["toolWindow.empty"] + timeHint
                else -> statusLabel.text = "${state.pipelines.size} pipelines$timeHint"
            }
            // The followed pipeline streams its jobs through state.stages — feed that into the
            // tree cache so the user sees live job statuses without a manual re-expand.
            val following = state.following
            if (following != null && state.stages.isNotEmpty()) {
                jobsCache[following.id] = state.stages.flatMap { it.jobs }
            }
            rebuildTree(state.pipelines)
            stagesPanel.update(state.stages, state.currentStage)
        }
    }

    /**
     * For every pipeline node the user currently has expanded (other than the followed one and
     * any already-terminal ones), hit `service.fetchJobs(id)` and re-attach the children with
     * the fresh job status. This is what keeps the tree children alive when the user is looking
     * at a non-followed, in-progress pipeline.
     */
    private suspend fun refreshExpandedNonFollowedJobs() {
        val followingId = service.state.value.following?.id
        val candidates = mutableListOf<Pair<DefaultMutableTreeNode, Long>>()
        // Walking the tree must happen on EDT.
        withContext(Dispatchers.Main) {
            for (i in 0 until rootNode.childCount) {
                val child = rootNode.getChildAt(i) as DefaultMutableTreeNode
                val row = child.userObject as? PipelineRow ?: continue
                val id = row.pipeline.id
                if (id == followingId) continue
                if (row.pipeline.status.isTerminal) continue
                if (!tree.isExpanded(TreePath(child.path))) continue
                candidates += child to id
            }
        }
        for ((node, id) in candidates) {
            val jobs = withContext(Dispatchers.IO) { service.fetchJobs(id) } ?: continue
            withContext(Dispatchers.Main) {
                // Bail if the user collapsed it mid-flight — no point thrashing the tree.
                if (!tree.isExpanded(TreePath(node.path))) {
                    jobsCache[id] = jobs
                    return@withContext
                }
                jobsCache[id] = jobs
                attachJobs(node, jobs)
                treeModel.reload(node)
                tree.expandPath(TreePath(node.path))
            }
        }
    }

    private fun formatHms(epochMs: Long): String =
        java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(epochMs))

    /**
     * Right-click context menu. Entries vary depending on what the user right-clicked:
     * tag pipeline → "Copiar tag" first, then ID/URL; non-tag pipeline → ID/URL; job → name/URL.
     * The triggering row is selected first so the user sees what they're acting on.
     */
    private fun showContextMenu(e: MouseEvent) {
        val path = tree.getPathForLocation(e.x, e.y) ?: return
        tree.selectionPath = path
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        val data = node.userObject ?: return
        val menu = JPopupMenu()
        when (data) {
            is PipelineRow -> {
                val p = data.pipeline
                if (p.tag && !p.ref.isNullOrBlank()) {
                    menu.add(JMenuItem("Copiar tag: ${p.ref}", AllIcons.Actions.Copy).apply {
                        addActionListener { copyTagToClipboard(p.ref!!) }
                    })
                    menu.addSeparator()
                }
                menu.add(JMenuItem("Copiar ID: #${p.id}").apply {
                    addActionListener { copyToClipboard("${p.id}", "ID #${p.id}") }
                })
                if (!p.ref.isNullOrBlank() && !p.tag) {
                    menu.add(JMenuItem("Copiar ref: ${p.ref}").apply {
                        addActionListener { copyToClipboard(p.ref, "ref ${p.ref}") }
                    })
                }
                if (!p.webUrl.isNullOrBlank()) {
                    menu.add(JMenuItem("Copiar URL").apply {
                        addActionListener { copyToClipboard(p.webUrl, "URL") }
                    })
                    menu.addSeparator()
                    menu.add(JMenuItem("Abrir en navegador").apply {
                        addActionListener { BrowserUtil.browse(p.webUrl) }
                    })
                }
            }
            is JobRow -> {
                val j = data.job
                menu.add(JMenuItem("Copiar nombre: ${j.name}").apply {
                    addActionListener { copyToClipboard(j.name, "nombre del job") }
                })
                if (!j.webUrl.isNullOrBlank()) {
                    menu.add(JMenuItem("Copiar URL del job").apply {
                        addActionListener { copyToClipboard(j.webUrl, "URL del job") }
                    })
                    menu.add(JMenuItem("Abrir job en navegador").apply {
                        addActionListener { BrowserUtil.browse(j.webUrl) }
                    })
                }
            }
        }
        if (menu.componentCount > 0) menu.show(tree, e.x, e.y)
    }

    private fun copyTagToClipboard(tag: String) = copyToClipboard(tag, "tag $tag")

    private fun copyToClipboard(text: String, label: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        com.intellij.notification.NotificationGroupManager.getInstance()
            .getNotificationGroup("GitLab Pipeline Watcher")
            .createNotification("Copiado al portapapeles: $label", com.intellij.notification.NotificationType.INFORMATION)
            .notify(project)
    }

    private fun rebuildTree(pipelines: List<Pipeline>) {
        snapshotExpansion()
        rootNode.removeAllChildren()
        for (p in pipelines) {
            val pipelineNode = DefaultMutableTreeNode(PipelineRow(p))
            val cachedJobs = jobsCache[p.id]
            if (cachedJobs != null) {
                attachJobs(pipelineNode, cachedJobs)
            } else {
                pipelineNode.add(DefaultMutableTreeNode(LoadingRow))
            }
            rootNode.add(pipelineNode)
        }
        treeModel.reload()
        for (i in 0 until rootNode.childCount) {
            val child = rootNode.getChildAt(i) as DefaultMutableTreeNode
            val row = child.userObject as? PipelineRow ?: continue
            if (expandedPipelineIds.contains(row.pipeline.id)) {
                tree.expandPath(TreePath(child.path))
            }
        }
    }

    private fun snapshotExpansion() {
        expandedPipelineIds.clear()
        for (i in 0 until rootNode.childCount) {
            val child = rootNode.getChildAt(i) as DefaultMutableTreeNode
            val row = child.userObject as? PipelineRow ?: continue
            if (tree.isExpanded(TreePath(child.path))) expandedPipelineIds += row.pipeline.id
        }
    }

    private fun attachJobs(pipelineNode: DefaultMutableTreeNode, jobs: List<PipelineJob>) {
        pipelineNode.removeAllChildren()
        if (jobs.isEmpty()) {
            pipelineNode.add(DefaultMutableTreeNode(EmptyRow))
            return
        }
        for (job in jobs) {
            pipelineNode.add(DefaultMutableTreeNode(JobRow(job)))
        }
    }

    private fun maybeLoadJobs(pipelineNode: DefaultMutableTreeNode, pipelineId: Long) {
        val first = pipelineNode.getChildAt(0) as? DefaultMutableTreeNode
        if (first != null && first.userObject !is LoadingRow) return
        if (!loadingPipelineIds.add(pipelineId)) return
        scope.launch(Dispatchers.IO) {
            val jobs = service.fetchJobs(pipelineId).orEmpty()
            ApplicationManager.getApplication().invokeLater {
                jobsCache[pipelineId] = jobs
                attachJobs(pipelineNode, jobs)
                treeModel.reload(pipelineNode)
                tree.expandPath(TreePath(pipelineNode.path))
                loadingPipelineIds -= pipelineId
            }
        }
    }

    fun dispose() {
        subscription?.cancel()
        jobsRefreshLoop?.cancel()
        logsPanel.dispose()
        scope.cancel()
    }

    private companion object {
        /** How often we re-fetch jobs for expanded non-followed non-terminal pipelines. */
        private const val JOBS_REFRESH_INTERVAL_MS = 3_000L

        /** Right-edge padding (in pixels) around the inline copy icon on tag pipeline rows. */
        private const val COPY_ICON_RIGHT_PADDING = 4
    }
}

private sealed class TreeRow
private data class PipelineRow(val pipeline: Pipeline) : TreeRow()
private data class JobRow(val job: PipelineJob) : TreeRow()
private object LoadingRow : TreeRow()
private object EmptyRow : TreeRow()

private class PipelineTreeRenderer : ColoredTreeCellRenderer() {

    /** Set to true on tag-pipeline rows so paintComponent draws the inline copy icon. */
    private var paintCopyIcon: Boolean = false

    override fun customizeCellRenderer(
        tree: JTree, value: Any?, selected: Boolean, expanded: Boolean,
        leaf: Boolean, row: Int, hasFocus: Boolean,
    ) {
        paintCopyIcon = false
        val node = value as? DefaultMutableTreeNode ?: return
        when (val data = node.userObject) {
            is PipelineRow -> {
                val p = data.pipeline
                icon = iconFor(p.status)
                val title = if (p.tag && !p.ref.isNullOrBlank()) p.ref!! else "#${p.id}"
                append(title, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                val action = p.source ?: "push"
                append("  $action", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                if (!p.tag && !p.ref.isNullOrBlank()) {
                    append("  · ${p.ref}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                if (p.tag && !p.ref.isNullOrBlank()) {
                    paintCopyIcon = true
                    toolTipText = "Click en el ícono ⧉ para copiar el tag (${p.ref}); click-derecho para más opciones"
                }
            }
            is JobRow -> {
                icon = iconFor(data.job.status)
                append("${data.job.stage} → ${data.job.name}", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                data.job.duration?.let {
                    append("  (${it.toInt()}s)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
            LoadingRow -> {
                icon = AllIcons.Process.Step_1
                append("cargando jobs…", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
            EmptyRow -> {
                icon = AllIcons.General.QuestionDialog
                append("(sin jobs)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
    }

    /**
     * Reserve trailing space for the copy icon so it doesn't get clipped by the cell's
     * preferred width. JTree sizes the cell to this preferredSize before painting.
     */
    override fun getPreferredSize(): Dimension {
        val base = super.getPreferredSize()
        if (paintCopyIcon) {
            base.width += AllIcons.Actions.Copy.iconWidth + COPY_ICON_TOTAL_PADDING
        }
        return base
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        if (paintCopyIcon) {
            val copy = AllIcons.Actions.Copy
            val iconX = width - copy.iconWidth - COPY_ICON_RIGHT_PAD_PX
            val iconY = (height - copy.iconHeight) / 2
            copy.paintIcon(this, g, iconX, iconY)
        }
    }

    private fun iconFor(status: PipelineStatus): Icon = when (status) {
        PipelineStatus.SUCCESS -> ColoredDotIcon.GREEN
        PipelineStatus.FAILED -> ColoredDotIcon.RED
        PipelineStatus.CANCELED, PipelineStatus.SKIPPED -> ColoredDotIcon.GREY
        PipelineStatus.MANUAL, PipelineStatus.SCHEDULED -> ColoredDotIcon.AMBER
        PipelineStatus.RUNNING -> AllIcons.Actions.Execute
        PipelineStatus.PENDING, PipelineStatus.WAITING_FOR_RESOURCE,
        PipelineStatus.PREPARING, PipelineStatus.CREATED -> AllIcons.Actions.Pause
        PipelineStatus.UNKNOWN -> AllIcons.General.QuestionDialog
    }

    private companion object {
        private const val COPY_ICON_RIGHT_PAD_PX = 4
        private const val COPY_ICON_LEFT_PAD_PX = 8
        private const val COPY_ICON_TOTAL_PADDING = COPY_ICON_LEFT_PAD_PX + COPY_ICON_RIGHT_PAD_PX
    }
}

/**
 * Live tail of the currently-running job. Visible only while at least one job in the
 * followed pipeline is RUNNING; collapses to zero-height otherwise so the pipelines
 * tree gets all the room when there's nothing live to see.
 */
private class LiveLogsPanel(
    private val project: Project,
    private val service: GitLabPipelineService,
    private val parentScope: CoroutineScope,
) {
    private val title = JBLabel("(no hay job corriendo)").apply {
        border = BorderFactory.createEmptyBorder(4, 8, 2, 8)
    }
    private val area = JTextArea("").apply {
        isEditable = false
        lineWrap = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 11)
        background = JBColor(Color(0xF7F7F7), Color(0x2B2B2B))
    }
    private val scrollPane = JBScrollPane(area).apply {
        preferredSize = Dimension(0, JBUI.scale(180))
    }

    val root: JComponent = JPanel(BorderLayout()).apply {
        border = BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border())
        add(title, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        isVisible = false
    }

    @Volatile private var currentJobId: Long? = null
    private var pollTimer: SwingTimer? = null
    private var stateSubscription: Job? = null

    init {
        stateSubscription = parentScope.launch {
            service.state.collect { state ->
                val running = state.stages.firstOrNull { !it.status.isTerminal }
                    ?.jobs?.firstOrNull { it.status == PipelineStatus.RUNNING }
                ApplicationManager.getApplication().invokeLater { onRunningJobChanged(running) }
            }
        }
    }

    private fun onRunningJobChanged(running: PipelineJob?) {
        if (running == null) {
            stopPolling()
            root.isVisible = false
            root.revalidate()
            return
        }
        root.isVisible = true
        if (running.id != currentJobId) {
            currentJobId = running.id
            title.text = "Runner log — ${running.stage} → ${running.name} (#${running.id})"
            area.text = "(cargando log…)"
            startPolling()
            fetchTraceAsync()
        }
    }

    private fun startPolling() {
        stopPolling()
        pollTimer = SwingTimer(3_000) { fetchTraceAsync() }.apply {
            isRepeats = true
            start()
        }
    }

    private fun stopPolling() {
        pollTimer?.stop()
        pollTimer = null
    }

    private fun fetchTraceAsync() {
        val jobId = currentJobId ?: return
        parentScope.launch(Dispatchers.IO) {
            val trace = service.fetchJobTrace(jobId)
            if (trace != null) {
                ApplicationManager.getApplication().invokeLater {
                    val verticalBar = scrollPane.verticalScrollBar
                    val wasAtBottom = verticalBar.value + verticalBar.visibleAmount >= verticalBar.maximum - 4
                    if (area.text != trace) area.text = trace
                    if (wasAtBottom) {
                        ApplicationManager.getApplication().invokeLater {
                            verticalBar.value = verticalBar.maximum
                        }
                    }
                }
            }
        }
    }

    fun dispose() {
        stateSubscription?.cancel()
        stopPolling()
    }
}

/**
 * Horizontal strip of "stage chips" — one per stage of the currently-followed
 * pipeline. The chip for the active stage is highlighted.
 */
private class StagesStripPanel : JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)) {

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border()),
            JBUI.Borders.empty(4, 8),
        )
        isVisible = false
    }

    fun update(stages: List<StageSummary>, currentStage: String?) {
        removeAll()
        if (stages.isEmpty()) {
            isVisible = false
            revalidate()
            repaint()
            return
        }
        isVisible = true
        for ((index, stage) in stages.withIndex()) {
            add(StageChip(stage, isCurrent = stage.name == currentStage))
            if (index < stages.size - 1) {
                add(JBLabel("→").apply { border = JBUI.Borders.emptyLeft(2) })
            }
        }
        revalidate()
        repaint()
    }
}

private class StageChip(stage: StageSummary, isCurrent: Boolean) : JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)) {
    init {
        isOpaque = isCurrent
        if (isCurrent) {
            background = JBColor(Color(0xDCE6FF), Color(0x3A4D70))
        }
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(borderColor(stage.status, isCurrent), 1, true),
            JBUI.Borders.empty(2, 6),
        )
        add(JBLabel(iconFor(stage.status)))
        val countLabel = "${stage.succeededJobs}/${stage.totalJobs}"
        val nameSuffix = if (isCurrent) " (en curso)" else ""
        add(JBLabel("${stage.name} ($countLabel)$nameSuffix"))
    }

    private fun iconFor(status: PipelineStatus): Icon = when (status) {
        PipelineStatus.SUCCESS -> ColoredDotIcon.GREEN
        PipelineStatus.FAILED -> ColoredDotIcon.RED
        PipelineStatus.CANCELED, PipelineStatus.SKIPPED -> ColoredDotIcon.GREY
        PipelineStatus.MANUAL, PipelineStatus.SCHEDULED -> ColoredDotIcon.AMBER
        PipelineStatus.RUNNING -> AllIcons.Actions.Execute
        PipelineStatus.PENDING, PipelineStatus.WAITING_FOR_RESOURCE,
        PipelineStatus.PREPARING, PipelineStatus.CREATED -> AllIcons.Actions.Pause
        PipelineStatus.UNKNOWN -> AllIcons.General.QuestionDialog
    }

    private fun borderColor(status: PipelineStatus, isCurrent: Boolean): Color = when {
        isCurrent -> JBColor(Color(0x3367D6), Color(0x6BAAFF))
        status == PipelineStatus.FAILED -> JBColor(Color(0xE53935), Color(0xE57373))
        status == PipelineStatus.SUCCESS -> JBColor(Color(0x4CAF50), Color(0x5FB85F))
        else -> JBColor.border()
    }
}
