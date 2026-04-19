package dev.spatial.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SpatialBridgeMessage(
    val type: String,
    @SerialName("entityId")
    val entityId: String? = null,
    val path: String? = null,
    val line: Int? = null,
    val column: Int? = null,
)

object SpatialSceneBridge {

    const val TYPE_OPEN_FILE = "open-file"

    fun resolvePath(projectBasePath: String?, rawPath: String?): Path? {
        val trimmed = rawPath?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val candidate = Paths.get(trimmed)
        return if (candidate.isAbsolute) candidate.normalize()
        else projectBasePath?.let { Paths.get(it).resolve(candidate).normalize() }
    }

    fun openFile(project: Project, message: SpatialBridgeMessage): Boolean {
        val path = resolvePath(project.basePath, message.path) ?: return false
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return false
        val line = (message.line ?: 1).coerceAtLeast(1) - 1
        val column = (message.column ?: 1).coerceAtLeast(1) - 1
        ApplicationManager.getApplication().invokeLater {
            OpenFileDescriptor(project, file, line, column).navigate(true)
        }
        return true
    }
}
