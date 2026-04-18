package dev.spatial.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import dev.spatial.SpatialBundle
import javax.swing.JLabel
import javax.swing.SwingConstants

class SpatialToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val component = if (SpatialBrowser.isSupported()) {
            SpatialBrowser(project, toolWindow.disposable).component
        } else {
            JLabel(SpatialBundle.message("toolWindow.browserUnavailable"), SwingConstants.CENTER)
        }
        val content = ContentFactory.getInstance().createContent(component, null, false)
        toolWindow.contentManager.addContent(content)
    }
}
