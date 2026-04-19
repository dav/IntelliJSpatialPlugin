package dev.spatial.actions

import com.intellij.codeInsight.hint.HintManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.ui.MessageType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import dev.spatial.SpatialBundle
import dev.spatial.navigation.SpatialSceneNavigator
import dev.spatial.navigation.SpatialTargetKind
import dev.spatial.scene.FocusEntity
import dev.spatial.scene.Highlight
import dev.spatial.service.SceneService
import java.nio.file.Path

class FocusCurrentFileInSpatialAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val file = currentFile(e, project)
        e.presentation.isEnabledAndVisible = project != null &&
            file != null &&
            file.isInLocalFileSystem
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val sceneService = project.service<SceneService>()
        val file = currentFile(e, project)
        if (file == null || !file.isInLocalFileSystem) {
            showFeedback(project, e.getData(CommonDataKeys.EDITOR), SpatialBundle.message("action.focusCurrentFile.noFile"))
            return
        }

        if (sceneService.scene.entities.isEmpty() && sceneService.landscape == null) {
            ToolWindowManager.getInstance(project).getToolWindow("Spatial")?.show(null)
            showFeedback(project, e.getData(CommonDataKeys.EDITOR), SpatialBundle.message("action.focusCurrentFile.noScene"))
            return
        }

        val match = SpatialSceneNavigator.findTargetForFile(
            projectBasePath = project.basePath,
            scene = sceneService.scene,
            landscape = sceneService.landscape,
            filePath = Path.of(file.path),
        )

        if (match == null) {
            showFeedback(project, e.getData(CommonDataKeys.EDITOR), SpatialBundle.message("action.focusCurrentFile.notFound"))
            return
        }

        ToolWindowManager.getInstance(project).getToolWindow("Spatial")?.show(null)
        sceneService.focusEntity(
            FocusEntity(
                entityId = match.targetId,
                distance = if (match.kind == SpatialTargetKind.LANDSCAPE_CELL) 5.5f else 4f,
                durationMs = 500,
            )
        )
        if (match.kind == SpatialTargetKind.SCENE_ENTITY) {
            sceneService.highlight(
                Highlight(
                    entityIds = listOf(match.targetId),
                    durationMs = 1400,
                    color = "#ffd166",
                )
            )
        }
    }

    private fun currentFile(e: AnActionEvent, project: com.intellij.openapi.project.Project?): VirtualFile? =
        e.getData(CommonDataKeys.VIRTUAL_FILE)
            ?: project?.let { FileEditorManager.getInstance(it).selectedFiles.firstOrNull() }

    private fun showFeedback(project: com.intellij.openapi.project.Project, editor: Editor?, message: String) {
        if (editor != null) {
            HintManager.getInstance().showInformationHint(editor, message)
        } else {
            ToolWindowManager.getInstance(project).notifyByBalloon("Spatial", MessageType.INFO, message)
        }
    }
}
