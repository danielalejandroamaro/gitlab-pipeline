package com.github.danielalejandroamaro.gitlabpipeline.toolWindow

import com.github.danielalejandroamaro.gitlabpipeline.MyBundle
import com.github.danielalejandroamaro.gitlabpipeline.model.Job as PipelineJob
import com.github.danielalejandroamaro.gitlabpipeline.model.Pipeline
import com.github.danielalejandroamaro.gitlabpipeline.model.PipelineStatus
import com.github.danielalejandroamaro.gitlabpipeline.services.GitLabCiDetector
import com.github.danielalejandroamaro.gitlabpipeline.services.GitLabPipelineService
import com.github.danielalejandroamaro.gitlabpipeline.services.PipelineEventLog
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.treeStructure.Tree
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
import javax.swing.event.TreeExpansionEvent
import javax.swing.event.TreeWillExpandListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

class PipelineToolWindowFactory : ToolWindowFactory {

    override fun shouldBeAvailable(project: Project): Boolean =
        GitLabCiDetector.hasCiFile(project)

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val pipelinesPanel = PipelinePanel(project)
        val releasesPanel = ReleasesTabPanel(project)
        val packagesPanel = PackagesTabPanel(project)
        val eventLogPanel = EventLogTabPanel(project)
        val cf = ContentFactory.getInstance()
        toolWindow.contentManager.addContent(cf.createContent(pipelinesPanel.root, "Pipelines", false))
        toolWindow.contentManager.addContent(cf.createContent(releasesPanel.root, "Releases", false))
        toolWindow.contentManager.addContent(cf.createContent(packagesPanel.root, "Packages", false))
        toolWindow.contentManager.addContent(cf.createContent(eventLogPanel.root, "Logs", false))
    }
}

private class PipelinePanel(private val project: Project) {

    private val service = project.service<GitLabPipelineService>()
    private val eventLog = project.service<PipelineEventLog>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var subscription: Job? = null
    private var jobsRefreshLoop: Job? = null

    private val rootNode = DefaultMutableTreeNode("root")
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel).apply {
        isRootVisible = false
        showsRootHandles = true
        // Disable JTree's built-in "double-click toggles expansion" so our doble-click handler
        // owns the gesture (copy version) without colliding with the default expand/collapse.
        // Expansion still works via the disclosure chevron on the left of the row.
        toggleClickCount = 0
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

                // Inline download icon hit-test on job rows with artifacts (rightmost slice).
                if (data is JobRow && data.job.hasArtifacts) {
                    val bounds = getPathBounds(path) ?: return
                    val dlIcon = AllIcons.Actions.Download
                    val iconLeft = bounds.x + bounds.width - dlIcon.iconWidth - COPY_ICON_RIGHT_PADDING
                    val iconRight = bounds.x + bounds.width
                    if (e.x in iconLeft..iconRight) {
                        downloadArtifactsFor(data.job)
                        return
                    }
                }

                if (e.clickCount == 2) {
                    when (data) {
                        // Default double-click on a pipeline copies the version (= ref/tag/branch)
                        // and surfaces a balloon. Navigation lives in the right-click menu.
                        is PipelineRow -> {
                            val version = data.pipeline.ref
                            if (!version.isNullOrBlank()) {
                                copyToClipboard(version, "version $version")
                            }
                        }
                        is JobRow -> {
                            val url = data.job.webUrl
                            if (!url.isNullOrBlank()) BrowserUtil.browse(url)
                        }
                    }
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
        // Track expansion by pipeline id via events instead of re-snapshotting the tree on each
        // rebuild: `treeModel.reload()` collapses everything WITHOUT firing treeCollapsed, so a
        // rebuild that lands mid-transient (Refresh chains cache-clear + several state emissions)
        // could snapshot a collapsed/detached node and wipe the expansion memory. With events,
        // only a real user collapse removes an id — rebuilds always re-expand what the user had.
        addTreeExpansionListener(object : javax.swing.event.TreeExpansionListener {
            override fun treeExpanded(event: TreeExpansionEvent) {
                pipelineIdAt(event)?.let {
                    expandedPipelineIds += it
                    eventLog.info("tree: EXPAND event pid=$it")
                }
            }
            override fun treeCollapsed(event: TreeExpansionEvent) {
                pipelineIdAt(event)?.let {
                    expandedPipelineIds -= it
                    eventLog.info("tree: COLLAPSE event pid=$it")
                }
            }
            private fun pipelineIdAt(event: TreeExpansionEvent): Long? {
                val node = event.path.lastPathComponent as? DefaultMutableTreeNode ?: return null
                return (node.userObject as? PipelineRow)?.pipeline?.id
            }
        })
    }

    /** Cached jobs by pipeline id so re-expanding (or a state-driven rebuild) doesn't re-fetch. */
    private val jobsCache = mutableMapOf<Long, List<PipelineJob>>()
    /** Pipeline ids with a fetch currently in flight — guards against repeated expand clicks. */
    private val loadingPipelineIds = mutableSetOf<Long>()
    /** Pipeline ids the user has expanded — kept by the TreeExpansionListener (only a real user collapse removes one), re-applied after every rebuild. */
    private val expandedPipelineIds = mutableSetOf<Long>()

    private val statusLabel = JBLabel("", SwingConstants.LEFT).apply {
        border = BorderFactory.createEmptyBorder(4, 8, 4, 8)
    }

    private val refreshButton = JButton(MyBundle["toolWindow.refresh"]).apply {
        toolTipText = "Refresh completo: re-fetch de pipelines + jobs + artifacts " +
            "(incluyendo pipelines terminales). Útil cuando algo cambia o se borra en GitLab y " +
            "el polling ligero (cada 3s) no lo refleja."
        addActionListener {
            // DEEP refresh — distinto del auto-loop ligero cada 3s:
            //  1. suelta el jobsCache de los pipelines NO expandidos (re-fetch al expandir);
            //     los expandidos conservan sus jobs visibles — el paso 3 los reemplaza in
            //     situ sin pasar por LoadingRow, que era lo que colapsaba el nodo.
            //  2. dispara service.refresh() para re-traer la lista de pipelines.
            //  3. re-fetch jobs de TODOS los pipelines expandidos sin filtros (incluye
            //     followed y terminales) — así reflejamos artifacts nuevos/eliminados y
            //     cambios en jobs ya terminados.
            jobsCache.keys.retainAll(expandedPipelineIds)
            service.refresh()
            scope.launch { refreshExpandedJobsDeep() }
        }
    }

    private val settingsButton = JButton(AllIcons.General.Settings).apply {
        toolTipText = "Configuración del watcher: remote a vigilar, intervalo de refresh, cuentas"
        addActionListener {
            com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, MyBundle["settings.displayName"])
        }
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
            add(
                JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0)).apply {
                    add(refreshButton)
                    add(settingsButton)
                },
                BorderLayout.EAST,
            )
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
                if (!tree.isExpanded(TreePath(child.path))) continue
                // For terminal pipelines: only skip if their cached jobs are ALSO all in a
                // terminal state. GitLab is eventually consistent — the parent pipeline can flip
                // to SUCCESS/FAILED before the /jobs endpoint settles every job — and previously
                // we skipped terminal pipelines unconditionally, which left jobs frozen in
                // "running"/"build" forever. MANUAL counts as "settled" since it never auto-resolves.
                if (row.pipeline.status.isTerminal) {
                    val cached = jobsCache[id]
                    if (cached != null && cached.all { it.status.isTerminal || it.status == PipelineStatus.MANUAL }) {
                        continue
                    }
                }
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
                val newRows: List<Any> = if (jobs.isEmpty()) listOf(EmptyRow) else jobs.map { JobRow(it) }
                swapChildren(node, newRows)
            }
        }
    }

    /**
     * Deep variant of [refreshExpandedNonFollowedJobs]: re-fetches jobs of EVERY expanded
     * pipeline regardless of state (followed, terminal, etc.). Triggered by the manual
     * Refresh button so the user can pull GitLab-side changes that the light polling skips
     * (artifacts uploaded after a job finished, jobs deleted manually, etc.).
     */
    private suspend fun refreshExpandedJobsDeep() {
        val candidates = mutableListOf<Pair<DefaultMutableTreeNode, Long>>()
        withContext(Dispatchers.Main) {
            for (i in 0 until rootNode.childCount) {
                val child = rootNode.getChildAt(i) as DefaultMutableTreeNode
                val row = child.userObject as? PipelineRow ?: continue
                if (!tree.isExpanded(TreePath(child.path))) continue
                candidates += child to row.pipeline.id
            }
        }
        for ((node, id) in candidates) {
            val jobs = withContext(Dispatchers.IO) { service.fetchJobs(id) } ?: continue
            withContext(Dispatchers.Main) {
                jobsCache[id] = jobs
                if (!tree.isExpanded(TreePath(node.path))) return@withContext
                val newRows: List<Any> = if (jobs.isEmpty()) listOf(EmptyRow) else jobs.map { JobRow(it) }
                swapChildren(node, newRows)
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
                menu.addSeparator()
                if (p.status == PipelineStatus.FAILED || p.status == PipelineStatus.CANCELED) {
                    menu.add(JMenuItem("Reintentar pipeline #${p.id}", AllIcons.Actions.Restart).apply {
                        addActionListener { retryPipeline(p) }
                    })
                }
                if (!p.ref.isNullOrBlank()) {
                    menu.add(JMenuItem("Relanzar pipeline (nuevo run en ${p.ref})", AllIcons.Actions.Execute).apply {
                        addActionListener { rerunPipeline(p) }
                    })
                }
                val tagLabel = p.ref?.takeIf { p.tag && it.isNotBlank() }
                if (tagLabel != null) {
                    menu.add(JMenuItem("Borrar tag $tagLabel (solo el tag)", AllIcons.Actions.GC).apply {
                        addActionListener { confirmAndDeleteTag(tagLabel) }
                    })
                }
                val deleteLabel = if (tagLabel != null) "Borrar pipeline #${p.id} + tag $tagLabel"
                                  else "Borrar pipeline #${p.id}"
                menu.add(JMenuItem(deleteLabel, AllIcons.Actions.GC).apply {
                    addActionListener { confirmAndDelete(p) }
                })
            }
            is JobRow -> {
                val j = data.job
                if (j.hasArtifacts) {
                    val sizeLabel = j.artifactsSize?.let { " (${humanBytes(it)})" } ?: ""
                    menu.add(JMenuItem("Descargar artifacts$sizeLabel", AllIcons.Actions.Download).apply {
                        addActionListener { downloadArtifactsFor(j) }
                    })
                    menu.addSeparator()
                }
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

    /**
     * Create a NEW pipeline run on the same ref (branch/tag) on a background thread. This is
     * the "run again" for green pipelines — retry only covers failed/canceled jobs.
     * Non-destructive, so no confirmation dialog. Refresh so the new run appears in the list.
     */
    private fun rerunPipeline(p: Pipeline) {
        val ref = p.ref ?: return
        scope.launch(Dispatchers.IO) {
            val ok = service.createPipeline(ref)
            ApplicationManager.getApplication().invokeLater {
                val (msg, type) = if (ok) {
                    "nuevo pipeline lanzado en $ref" to com.intellij.notification.NotificationType.INFORMATION
                } else {
                    "no se pudo lanzar pipeline en $ref" to com.intellij.notification.NotificationType.ERROR
                }
                com.intellij.notification.NotificationGroupManager.getInstance()
                    .getNotificationGroup("GitLab Pipeline Watcher")
                    .createNotification(msg, type)
                    .notify(project)
                if (ok) service.refresh()
            }
        }
    }

    /**
     * Retry the failed/canceled jobs of a pipeline on a background thread. Non-destructive,
     * so no confirmation dialog. Refresh on success so the row flips back to running.
     */
    private fun retryPipeline(p: Pipeline) {
        scope.launch(Dispatchers.IO) {
            val ok = service.retryPipeline(p.id)
            ApplicationManager.getApplication().invokeLater {
                val (msg, type) = if (ok) {
                    "pipeline #${p.id} relanzado" to com.intellij.notification.NotificationType.INFORMATION
                } else {
                    "no se pudo relanzar pipeline #${p.id}" to com.intellij.notification.NotificationType.ERROR
                }
                com.intellij.notification.NotificationGroupManager.getInstance()
                    .getNotificationGroup("GitLab Pipeline Watcher")
                    .createNotification(msg, type)
                    .notify(project)
                if (ok) service.refresh()
            }
        }
    }

    /** Confirm, then delete ONLY the tag (the pipeline row stays) on a background thread. */
    private fun confirmAndDeleteTag(tagName: String) {
        val ok = com.intellij.openapi.ui.Messages.showYesNoDialog(
            project,
            "Vas a borrar el tag $tagName en GitLab (el pipeline se conserva).\n" +
                "La acción no se puede deshacer.\n\n" +
                "¿Continuar?",
            "Borrar tag",
            com.intellij.openapi.ui.Messages.getWarningIcon(),
        )
        if (ok != com.intellij.openapi.ui.Messages.YES) return
        scope.launch(Dispatchers.IO) {
            val deleted = service.deleteTag(tagName)
            ApplicationManager.getApplication().invokeLater {
                val (msg, type) = if (deleted) {
                    "tag $tagName borrado" to com.intellij.notification.NotificationType.INFORMATION
                } else {
                    "no se pudo borrar tag $tagName" to com.intellij.notification.NotificationType.ERROR
                }
                com.intellij.notification.NotificationGroupManager.getInstance()
                    .getNotificationGroup("GitLab Pipeline Watcher")
                    .createNotification(msg, type)
                    .notify(project)
            }
        }
    }

    /**
     * Confirm with the user, then delete the pipeline (and its tag if it's a tag pipeline)
     * on a background thread. Refresh on success so the row disappears from the tree.
     */
    private fun confirmAndDelete(p: Pipeline) {
        val tagName = p.ref?.takeIf { p.tag && it.isNotBlank() }
        val target = if (tagName != null) "pipeline #${p.id} y el tag $tagName"
                     else "pipeline #${p.id}"
        val ok = com.intellij.openapi.ui.Messages.showYesNoDialog(
            project,
            "Vas a borrar $target en GitLab.\n" +
                "Esto incluye los jobs y sus artifacts. La acción no se puede deshacer.\n\n" +
                "¿Continuar?",
            "Borrar pipeline",
            com.intellij.openapi.ui.Messages.getWarningIcon(),
        )
        if (ok != com.intellij.openapi.ui.Messages.YES) return
        scope.launch(Dispatchers.IO) {
            val (pipelineOk, tagOk) = service.deletePipelineAndTag(p.id, tagName)
            ApplicationManager.getApplication().invokeLater {
                val parts = mutableListOf<String>()
                parts += if (pipelineOk) "pipeline #${p.id} borrado" else "no se pudo borrar pipeline #${p.id}"
                if (tagName != null) {
                    parts += when (tagOk) {
                        true -> "tag $tagName borrado"
                        false -> "no se pudo borrar tag $tagName"
                        null -> "tag $tagName no intentado"
                    }
                }
                val anyFail = !pipelineOk || tagOk == false
                val type = if (anyFail) com.intellij.notification.NotificationType.ERROR
                           else com.intellij.notification.NotificationType.INFORMATION
                com.intellij.notification.NotificationGroupManager.getInstance()
                    .getNotificationGroup("GitLab Pipeline Watcher")
                    .createNotification(parts.joinToString(" · "), type)
                    .notify(project)
                if (pipelineOk) service.refresh()
            }
        }
    }

    /**
     * Open a Save dialog seeded with the artifacts filename, then stream the zip from GitLab
     * to disk on the IO dispatcher. User feedback via balloons — success shows the destination,
     * failure shows a generic "no se pudo descargar" (the detailed cause is in idea.log).
     */
    private fun downloadArtifactsFor(job: PipelineJob) {
        if (!job.hasArtifacts) return
        val suggestedName = job.artifactsFilename ?: "artifacts-${job.id}.zip"
        val descriptor = com.intellij.openapi.fileChooser.FileSaverDescriptor(
            "Descargar artifacts",
            "Selecciona dónde guardar el archivo",
            "zip",
        )
        val saver = com.intellij.openapi.fileChooser.FileChooserFactory.getInstance()
            .createSaveFileDialog(descriptor, project)
        val chosen = saver.save(null as java.nio.file.Path?, suggestedName) ?: return
        val dest = chosen.file
        scope.launch(Dispatchers.IO) {
            val ok = service.downloadJobArtifacts(job.id, dest)
            ApplicationManager.getApplication().invokeLater {
                val type = if (ok) com.intellij.notification.NotificationType.INFORMATION
                           else com.intellij.notification.NotificationType.ERROR
                val msg = if (ok) "Artifacts guardados en ${dest.absolutePath}"
                          else "No se pudo descargar los artifacts de #${job.id}"
                com.intellij.notification.NotificationGroupManager.getInstance()
                    .getNotificationGroup("GitLab Pipeline Watcher")
                    .createNotification(msg, type)
                    .notify(project)
            }
        }
    }

    private fun humanBytes(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = bytes.toDouble() / 1024
        var i = 0
        while (v >= 1024 && i < units.size - 1) { v /= 1024; i++ }
        return String.format("%.1f %s", v, units[i])
    }

    private fun copyToClipboard(text: String, label: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
        com.intellij.notification.NotificationGroupManager.getInstance()
            .getNotificationGroup("GitLab Pipeline Watcher")
            .createNotification("Copiado al portapapeles: $label", com.intellij.notification.NotificationType.INFORMATION)
            .notify(project)
    }

    /**
     * A tag-pipeline is "stale" (its tag has been repointed since this run) when there's a
     * newer pipeline with the same tag ref. Computed purely from the local list — no extra
     * API calls. ponytail: assumes the per_page=20 window is enough to see the retag; if a
     * retag happens long after the original pipeline scrolled off the window, we miss it.
     */
    private fun computeStaleTagIds(pipelines: List<Pipeline>): Set<Long> {
        val byRef = pipelines.filter { it.tag && !it.ref.isNullOrBlank() }.groupBy { it.ref!! }
        return byRef.values.flatMap { group ->
            if (group.size <= 1) emptyList()
            else group.sortedByDescending { it.id }.drop(1).map { it.id }
        }.toSet()
    }

    /**
     * Incremental, id-keyed diff against the current tree — NEVER calls [DefaultTreeModel.reload].
     * `reload()` collapses every node without firing collapse events and the follow-up
     * `expandPath` re-opens them — that open/close cycle was the visible flash+collapse the
     * user saw on every refresh that changed the list. Here surviving rows are updated in
     * place (no structure event → expansion/selection/scroll untouched) and only genuinely
     * new/removed rows fire fine-grained insert/remove events.
     */
    private fun rebuildTree(pipelines: List<Pipeline>) {
        val staleIds = computeStaleTagIds(pipelines)
        val newIds = pipelines.mapTo(mutableSetOf()) { it.id }
        var removed = 0; var updated = 0; var inserted = 0
        // 1. Drop rows whose pipeline vanished from the list.
        var i = 0
        while (i < rootNode.childCount) {
            val node = rootNode.getChildAt(i) as DefaultMutableTreeNode
            val id = (node.userObject as? PipelineRow)?.pipeline?.id
            if (id == null || id !in newIds) {
                rootNode.remove(i)
                treeModel.nodesWereRemoved(rootNode, intArrayOf(i), arrayOf(node))
                removed++
                eventLog.info("tree: removed pid=$id at idx=$i")
            } else i++
        }
        // 2. Walk the new list: id matches the node at that index → update in place;
        //    otherwise insert a fresh node there.
        for ((idx, p) in pipelines.withIndex()) {
            val cachedJobs = jobsCache[p.id]
            val newRow = PipelineRow(
                p,
                staleTag = p.id in staleIds,
                mixedAmber = cachedJobs?.let { computeMixedAmber(it) } ?: false,
            )
            val existing = if (idx < rootNode.childCount) rootNode.getChildAt(idx) as DefaultMutableTreeNode else null
            if (existing != null && (existing.userObject as? PipelineRow)?.pipeline?.id == p.id) {
                if (existing.userObject != newRow) {
                    val old = existing.userObject as? PipelineRow
                    existing.userObject = newRow
                    treeModel.nodeChanged(existing)
                    updated++
                    eventLog.info(
                        "tree: nodeChanged pid=${p.id} " +
                            "status=${old?.pipeline?.status}→${p.status} " +
                            "amber=${old?.mixedAmber}→${newRow.mixedAmber} stale=${old?.staleTag}→${newRow.staleTag}"
                    )
                }
                if (cachedJobs != null) {
                    val rows: List<Any> = if (cachedJobs.isEmpty()) listOf(EmptyRow) else cachedJobs.map { JobRow(it) }
                    swapChildren(existing, rows)
                }
            } else {
                val node = DefaultMutableTreeNode(newRow)
                if (cachedJobs != null) attachJobs(node, cachedJobs)
                else node.add(DefaultMutableTreeNode(LoadingRow))
                rootNode.insert(node, idx)
                treeModel.nodesWereInserted(rootNode, intArrayOf(idx))
                inserted++
                eventLog.info(
                    "tree: inserted pid=${p.id} at idx=$idx " +
                        "(existingAtIdx=${(existing?.userObject as? PipelineRow)?.pipeline?.id ?: "none"}, " +
                        "reExpand=${p.id in expandedPipelineIds})"
                )
                if (p.id in expandedPipelineIds) tree.expandPath(TreePath(node.path))
            }
        }
        // 3. Trim leftovers past the end. ponytail: a REORDERED surviving row re-enters as a
        // fresh insert in step 2 and its old instance falls out here — jobsCache +
        // expandedPipelineIds rebuild it identically, and ids never reorder in practice
        // (list is sorted by id desc), so no fancier LCS diff.
        while (rootNode.childCount > pipelines.size) {
            val last = rootNode.childCount - 1
            val node = rootNode.getChildAt(last) as DefaultMutableTreeNode
            rootNode.remove(last)
            treeModel.nodesWereRemoved(rootNode, intArrayOf(last), arrayOf(node))
            removed++
            eventLog.info("tree: trimmed trailing pid=${((node.userObject) as? PipelineRow)?.pipeline?.id} at idx=$last")
        }
        if (removed + updated + inserted > 0) {
            eventLog.info("tree: render mutated — removed=$removed updated=$updated inserted=$inserted (n=${pipelines.size})")
        }
        // The INVISIBLE root starts collapsed (and re-collapses if it ever hits 0 children);
        // fine-grained inserts don't auto-expand it — only reload()/structure events did — so
        // without this the whole tree renders "Nothing to show" despite having rows.
        if (rootNode.childCount > 0) tree.expandPath(TreePath(rootNode.path))
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

    /**
     * Replace a node's children using fine-grained model events instead of [DefaultTreeModel.reload].
     * `reload(node)` fires `treeStructureChanged`, which most L&Fs handle by collapsing the subtree
     * — and even with a follow-up `expandPath` that produced a visible "spinner gone → empty →
     * content" flash when the LoadingRow got replaced. `nodesWereRemoved` + `nodesWereInserted`
     * keep the expansion state intact, so the LoadingRow swaps to JobRows in one paint cycle.
     */
    private fun swapChildren(parent: DefaultMutableTreeNode, newRows: List<Any>) {
        // No-op when nothing changed — the deep refresh calls this unconditionally and an
        // identical remove+insert cycle still repaints (visible flicker) for zero benefit.
        val current = (0 until parent.childCount)
            .map { (parent.getChildAt(it) as DefaultMutableTreeNode).userObject }
        if (current == newRows) return
        val pid = (parent.userObject as? PipelineRow)?.pipeline?.id
        // Same row count → update userObject in place + nodeChanged per differing row. That
        // repaints ONLY those rows: no remove/insert events, no relayout, no flash. This is the
        // common path — jobs mutate (duration ticks up, status flips) far more often than they
        // appear/disappear.
        if (current.size == newRows.size) {
            var changed = 0
            for (idx in newRows.indices) {
                if (current[idx] != newRows[idx]) {
                    val child = parent.getChildAt(idx) as DefaultMutableTreeNode
                    child.userObject = newRows[idx]
                    treeModel.nodeChanged(child)
                    changed++
                }
            }
            eventLog.info("tree: in-place update pid=$pid $changed/${newRows.size} rows")
            return
        }
        // Row count changed (job added/removed) → structural remove+insert. Rare, so the
        // one-frame relayout here is acceptable.
        eventLog.info(
            "tree: STRUCTURAL swap pid=$pid ${current.size}→${newRows.size} rows " +
                "old0=[${rowBrief(current.firstOrNull())}] new0=[${rowBrief(newRows.firstOrNull())}]"
        )
        val oldCount = parent.childCount
        if (oldCount > 0) {
            val removed = Array<Any>(oldCount) { parent.getChildAt(it) }
            val indices = IntArray(oldCount) { it }
            parent.removeAllChildren()
            treeModel.nodesWereRemoved(parent, indices, removed)
        }
        if (newRows.isEmpty()) return
        for (row in newRows) parent.add(DefaultMutableTreeNode(row))
        treeModel.nodesWereInserted(parent, IntArray(newRows.size) { it })
    }

    /** One-line summary of a tree row for the diagnostic log. */
    private fun rowBrief(row: Any?): String = when (row) {
        null -> "∅"
        is PipelineRow -> "P#${row.pipeline.id} ${row.pipeline.status}"
        is JobRow -> "J#${row.job.id} ${row.job.name} ${row.job.status} d=${row.job.duration} art=${row.job.hasArtifacts}"
        LoadingRow -> "Loading"
        EmptyRow -> "Empty"
        else -> row.toString()
    }

    private fun maybeLoadJobs(pipelineNode: DefaultMutableTreeNode, pipelineId: Long) {
        val first = pipelineNode.getChildAt(0) as? DefaultMutableTreeNode
        if (first != null && first.userObject !is LoadingRow) return
        if (!loadingPipelineIds.add(pipelineId)) return
        scope.launch(Dispatchers.IO) {
            val jobs = service.fetchJobs(pipelineId).orEmpty()
            ApplicationManager.getApplication().invokeLater {
                jobsCache[pipelineId] = jobs
                // Use fine-grained events so the LoadingRow → JobRows swap doesn't collapse the
                // pipeline node mid-expand (which previously left a one-frame blank gap).
                val newRows: List<Any> = if (jobs.isEmpty()) listOf(EmptyRow) else jobs.map { JobRow(it) }
                swapChildren(pipelineNode, newRows)
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
