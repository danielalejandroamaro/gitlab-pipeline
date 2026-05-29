package com.github.danielalejandroamaro.gitlabpipeline.startup

import com.github.danielalejandroamaro.gitlabpipeline.statusBar.LeftPipelineIndicator
import com.github.danielalejandroamaro.gitlabpipeline.statusBar.PipelineStatusBarWidgetFactory
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.impl.status.widget.StatusBarWidgetsManager
import javax.swing.JPanel

/**
 * Mounts a [LeftPipelineIndicator] at the **leftmost** position of the status bar — before
 * the NavBar/breadcrumb — so its position never shifts when the breadcrumb path grows.
 *
 * The public `StatusBar.addCustomIndicationComponent` adds the component to the left panel
 * but at the *end* (i.e. after NavBar). To pin it to index 0 we reflect into the status bar's
 * `leftPanel` field. If that fails (field renamed in a newer IntelliJ version) we degrade
 * gracefully to the public API — the indicator still ends up on the left side, just to the
 * right of the breadcrumb.
 */
class LeftIndicatorMounter : ProjectActivity {

    private val logger = thisLogger()

    override suspend fun execute(project: Project) {
        val statusBar = WindowManager.getInstance().getStatusBar(project) ?: run {
            logger.warn("Status bar not available; left indicator not mounted")
            return
        }
        val indicator = LeftPipelineIndicator(project)
        Disposer.register(project, indicator)

        ApplicationManager.getApplication().invokeLater {
            val leftPanel = findLeftPanel(statusBar)
            if (leftPanel != null) {
                leftPanel.add(indicator, 0)
                leftPanel.revalidate()
                leftPanel.repaint()
                // Left mount succeeded → mark the project state and ask StatusBarWidgetsManager
                // to re-evaluate `isAvailable` on our factory, which now returns false. The
                // manager will dispose the right-side widget through the public API path,
                // avoiding the internal `StatusBar.removeWidget(String)` call.
                project.service<LeftIndicatorMountState>().leftMounted = true
                runCatching {
                    project.service<StatusBarWidgetsManager>()
                        .updateWidget(PipelineStatusBarWidgetFactory::class.java)
                }.onFailure { logger.info("StatusBarWidgetsManager.updateWidget failed: ${it.message}") }
                logger.info("Left pipeline indicator mounted at leftPanel index 0; right widget hidden via isAvailable")
            } else {
                // No public API to mount on the left panel in 2026.1+ status bar.
                // If the internal `leftPanel` field is ever renamed we degrade to the right-side
                // widget which is registered with `order="last"` in plugin.xml — i.e. it lands
                // at the rightmost end of the status bar (a.k.a. "first" reading right-to-left).
                logger.warn("Could not access status bar leftPanel via reflection; right-side widget will show at the rightmost position")
            }
        }
    }

    /**
     * Reflect into the status bar to find its left `JPanel`. The field is named `leftPanel`
     * in [com.intellij.openapi.wm.impl.status.IdeStatusBarImpl] across the recent platform
     * versions; if a release renames it we just return null.
     */
    private fun findLeftPanel(statusBar: Any): JPanel? {
        var cls: Class<*>? = statusBar.javaClass
        while (cls != null) {
            val field = runCatching { cls.getDeclaredField("leftPanel") }.getOrNull()
            if (field != null) {
                field.isAccessible = true
                return runCatching { field.get(statusBar) as? JPanel }.getOrNull()
            }
            cls = cls.superclass
        }
        return null
    }
}
