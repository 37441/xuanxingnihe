package com.soreverse.mcp

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.soreverse.mcp.core.AppLog
import com.soreverse.mcp.core.SettingsStore
import com.soreverse.mcp.engine.WorkDirectory

/**
 * 逆核: 受限工作区设置页。
 *
 * 用户在这里选一个文件夹授权给 AI。AI 的 workspace_fs 工具只能在这个 SAF 目录树内
 * 列目录/读/写/建目录/删除，系统级保证无法访问文件夹之外的任何路径。
 */
@Composable
internal fun SettingsWorkspacePage(t: UiText, settings: SettingsStore) {
    val context = LocalContext.current
    var workspaceUri by remember { mutableStateOf(settings.workspaceUri) }
    var enabled by remember { mutableStateOf(settings.workspaceEnabled) }

    val pickTree = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }.onFailure { AppLog.w("无法持久化工作区目录权限: ${it.message}") }
            settings.workspaceUri = uri
            workspaceUri = uri
        }
    }

    val persisted = workspaceUri != null &&
        context.contentResolver.persistedUriPermissions.any { it.uri == workspaceUri }

    PageScroll {
        GlassGroup(title = if (t.zh) "工作区" else "Workspace", footer = if (t.zh)
            "AI 只能在这个文件夹内操作（列目录 / 读 / 写 / 建目录 / 删除），无法访问该文件夹之外的任何文件。想要 AI 修改或生成什么，先把对应文件放进来。"
        else
            "AI can only operate inside this folder (list / read / write / mkdir / delete). It cannot reach anything outside. Drop files here first if you want AI to modify them.") {
            ToggleRow(
                if (t.zh) "允许 AI 访问工作区" else "Allow AI to access workspace",
                enabled,
            ) { on ->
                settings.workspaceEnabled = on
                enabled = on
            }
            GroupDivider()
            NavRow(
                title = workspaceUri?.let(WorkDirectory::displayPath)
                    ?: if (t.zh) "未选择文件夹" else "No folder selected",
                subtitle = if (persisted)
                    (if (t.zh) "已获得持久化读写授权（重启不失效）" else "Persisted read/write permission granted")
                else if (t.zh) "点击选择 AI 可操作的沙箱文件夹" else "Tap to pick the sandbox folder",
                icon = Icons.Default.FolderOpen,
                trailing = if (t.zh) "选择" else "Choose",
                onClick = { pickTree.launch(null) },
            )
        }
        GlassGroup(title = if (t.zh) "权限边界" else "Permission boundary") {
            Text(
                if (t.zh)
                    "· 只能看到/修改该文件夹内已存在的文件，以及 AI 自己新建的文件\n· 无法读取、修改或删除文件夹之外的任何内容（系统级隔离）\n· 关闭开关后，AI 的文件操作会立即被拒绝\n· 如需 AI 处理新文件，请先通过系统文件管理器把它放进该文件夹"
                else
                    "· Only files already inside the folder, or created by AI itself\n· Cannot read/modify/delete anything outside (system-level isolation)\n· Turning the switch off immediately blocks all AI file operations\n· Put new files into the folder first if you want AI to process them",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(14.dp),
            )
        }
        if (workspaceUri != null) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                SecondaryActionButton(
                    if (t.zh) "清除授权" else "Clear access",
                    {
                        runCatching {
                            context.contentResolver.releasePersistableUriPermission(
                                workspaceUri!!,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                            )
                        }
                        settings.workspaceUri = null
                        settings.workspaceEnabled = false
                        workspaceUri = null
                        enabled = false
                        Toast.makeText(context, if (t.zh) "已清除授权" else "Access cleared", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}