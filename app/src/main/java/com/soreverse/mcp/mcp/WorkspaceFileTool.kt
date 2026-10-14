package com.soreverse.mcp.mcp

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Base64
import com.soreverse.mcp.core.AppLog
import com.soreverse.mcp.core.bool
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.intValue
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * 逆核: 受限工作区文件工具（workspace_fs）。
 *
 * AI 只能操作用户在「设置 → 工作区」里选定的那个 SAF 目录树内的文件：
 *   - 所有路径必须是工作区内的相对路径；绝对路径、`..`、反斜杠逃逸一律拒绝；
 *   - 每个文档操作都锚定在 tree URI 上（DocumentsContract 构建，系统级隔离），
 *     不存在任何能触及该文件夹之外的代码路径；
 *   - 工作区未启用或未选文件夹时直接返回错误，不落盘任何操作。
 */
object WorkspaceFileTool {

    val fs: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "workspace_fs",
            "【受限工作区文件】在「设置→工作区」所选文件夹内操作文件。action=list 列目录(默认,path 可空=根目录); action=read 读文件内容(文本自动 UTF-8,二进制返回 base64); action=write 新建/覆盖文件(text 或 base64,父目录自动创建); action=mkdir 建子目录; action=delete 删除(删目录需 recursive=true)。path 一律为工作区内的相对路径,严禁绝对路径或 ../。未启用/未选文件夹时返回 WORKSPACE_DISABLED / WORKSPACE_NOT_CONFIGURED。该工具只能访问所选文件夹,无法触碰文件夹之外的任何文件。",
            "Restricted workspace file ops. Operates ONLY inside the folder picked in Settings→Workspace. action=list (default) lists a directory (path optional, empty=root); action=read reads a file (UTF-8 text, base64 for binary); action=write creates/overwrites a file (text or base64, parent dirs auto-created); action=mkdir creates a directory; action=delete removes a file (or dir with recursive=true). path is always a relative path inside the workspace; absolute paths and ../ are rejected. Returns WORKSPACE_DISABLED / WORKSPACE_NOT_CONFIGURED when not enabled/configured. This tool can never touch files outside the picked folder.",
            "files", ToolClass.EXTRA,
        ) { objectSchema(props {
            "action".oneOf("list (default) | read | write | mkdir | delete", "list", "read", "write", "mkdir", "delete")
            "path" str "相对路径（相对工作区根目录）。list 默认列出根目录；read/write/mkdir/delete 必填。"
            "content" str "写入内容（action=write；encoding=base64 时为 base64 字符串）"
            "encoding".oneOf("text (default) | base64", "text", "base64")
            "recursive" bool "删除目录时是否递归（action=delete；默认 false，目录必须显式 true）"
            "maxBytes" int "读取上限（action=read，默认 524288=512KB，最大 10MB）"
        }) }
        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            if (!ctx.settings.workspaceEnabled) {
                return err("WORKSPACE_DISABLED", "工作区未启用：请先在设置→工作区打开「允许 AI 访问工作区」", "workspace", "")
            }
            val treeUri = ctx.settings.workspaceUri
                ?: return err("WORKSPACE_NOT_CONFIGURED", "未选择工作区文件夹：请先在设置→工作区选择一个文件夹", "workspace", "")
            val resolver = ctx.context.contentResolver
            val path = args.str("path")
            return runCatching {
                when (args.str("action", "list")) {
                    "list" -> list(resolver, treeUri, path)
                    "read" -> read(resolver, treeUri, path, args.intValue("maxBytes", 512 * 1024).coerceIn(1, 10 * 1024 * 1024))
                    "write" -> write(resolver, treeUri, path, args.str("content"), args.str("encoding", "text"))
                    "mkdir" -> mkdir(resolver, treeUri, path)
                    "delete" -> delete(resolver, treeUri, path, args.bool("recursive", false))
                    else -> err("UNKNOWN_ACTION", "未知 action: ${args.str("action")}", "action", args.str("action"))
                }
            }.getOrElse { e ->
                AppLog.w("workspace_fs 失败: ${e.message ?: e.javaClass.simpleName}")
                err("WORKSPACE_FS_ERROR", "工作区文件操作失败: ${e.message ?: e.javaClass.simpleName}", "path", path)
            }
        }
    }

    // ── 沙箱路径解析 ──

    /** 规范化相对路径为段列表；任何逃逸（绝对路径 / .. / 盘符）返回 null。 */
    private fun normalize(rel: String): List<String>? {
        if (rel.startsWith("/") || rel.startsWith("\\\\")) return null
        val cleaned = rel.replace('\\', '/')
        val parts = cleaned.split('/').filter { it.isNotBlank() && it != "." }
        if (parts.any { it == ".." }) return null
        if (parts.any { it.contains(':') }) return null
        return parts
    }

    private fun rootDocUri(treeUri: Uri): Uri {
        val rootId = DocumentsContract.getTreeDocumentId(treeUri)
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
    }

    private fun docIdOf(uri: Uri): String = DocumentsContract.getDocumentId(uri)

    private fun findChild(resolver: ContentResolver, treeUri: Uri, parentUri: Uri, name: String): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docIdOf(parentUri))
        resolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null, null, null,
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (c.moveToNext()) {
                if (c.getString(nameCol) == name) return DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(idCol))
            }
        }
        return null
    }

    /** 逐段定位；createDirs=true 时缺失的目录段自动创建。返回 null 表示不存在（且未创建）。 */
    private fun resolve(resolver: ContentResolver, treeUri: Uri, parts: List<String>, createDirs: Boolean): Uri? {
        var current = rootDocUri(treeUri)
        for (name in parts) {
            val child = findChild(resolver, treeUri, current, name)
            if (child != null) {
                current = child
            } else if (createDirs) {
                val created = DocumentsContract.createDocument(
                    resolver, current, DocumentsContract.Document.MIME_TYPE_DIR, name,
                ) ?: return null
                current = created
            } else {
                return null
            }
        }
        return current
    }

    private fun queryMime(resolver: ContentResolver, uri: Uri): String? =
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_MIME_TYPE), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    private fun guessMime(name: String): String {
        val map = mapOf(
            "txt" to "text/plain", "log" to "text/plain", "ini" to "text/plain", "conf" to "text/plain",
            "json" to "application/json", "md" to "text/markdown", "xml" to "application/xml",
            "html" to "text/html", "htm" to "text/html", "css" to "text/css", "js" to "text/javascript",
            "csv" to "text/csv", "yml" to "text/yaml", "yaml" to "text/yaml",
            "sh" to "text/x-sh", "py" to "text/x-python", "kt" to "text/x-kotlin", "java" to "text/x-java",
            "c" to "text/x-c", "cpp" to "text/x-c++", "h" to "text/x-c", "gradle" to "text/plain",
            "kts" to "text/plain", "properties" to "text/plain", "toml" to "text/plain",
        )
        val ext = name.substringAfterLast('.', "").lowercase()
        return map[ext] ?: "application/octet-stream"
    }

    private fun isText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        var suspicious = 0
        for (b in bytes) {
            val u = b.toInt() and 0xFF
            if (u == 0) return false
            // 控制字符：0x00-0x06、0x0E-0x1F；0x80+ 可能是 UTF-8 多字节正文，不算可疑
            if (u < 0x07 || (u in 0x0E..0x1F)) suspicious++
        }
        return suspicious * 10 < bytes.size
    }

    private fun readBytes(resolver: ContentResolver, uri: Uri, maxBytes: Int): ByteArray {
        resolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "无法打开 $uri" }
            val out = ByteArrayOutputStream(32 * 1024)
            val buffer = ByteArray(64 * 1024)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total += count
                if (total > maxBytes) throw IllegalArgumentException("文件超过 $maxBytes 字节读取上限")
                out.write(buffer, 0, count)
            }
            return out.toByteArray()
        }
    }

    private fun deleteRecursive(resolver: ContentResolver, treeUri: Uri, dirUri: Uri) {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docIdOf(dirUri))
        resolver.query(
            childrenUri,
            arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_MIME_TYPE),
            null, null, null,
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val mimeCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            while (c.moveToNext()) {
                val child = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(idCol))
                if (c.getString(mimeCol) == DocumentsContract.Document.MIME_TYPE_DIR) {
                    deleteRecursive(resolver, treeUri, child)
                } else {
                    DocumentsContract.deleteDocument(resolver, child)
                }
            }
        }
        DocumentsContract.deleteDocument(resolver, dirUri)
    }

    // ── actions ──

    /**
     * 逆核: 把本地文件流式复制进工作区（自动建父目录，沙箱路径校验），不占用双倍内存。
     * 供脱壳等工具把产物归档到用户指定的工作区文件夹（如 脱壳_<时间戳>/dump_0.dex）。
     */
    internal fun copyFileIn(resolver: ContentResolver, treeUri: Uri, relativePath: String, src: java.io.File) {
        val parts = normalize(relativePath)
            ?: throw IllegalArgumentException("路径不合法：只允许工作区内的相对路径（拒绝绝对路径 / .. / 盘符）")
        if (parts.isEmpty()) throw IllegalArgumentException("copyFileIn 需要一个文件路径")
        val name = parts.last()
        val parentUri = resolve(resolver, treeUri, parts.dropLast(1), createDirs = true)
            ?: throw IllegalArgumentException("无法在工作区创建父目录")
        val existing = findChild(resolver, treeUri, parentUri, name)
        val docUri = existing
            ?: DocumentsContract.createDocument(resolver, parentUri, guessMime(name), name)
            ?: throw IllegalArgumentException("无法在工作区创建文件: $relativePath")
        resolver.openOutputStream(docUri, "wt").use { out ->
            requireNotNull(out) { "无法打开工作区写入流: $relativePath" }
            src.inputStream().use { ins ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = ins.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                }
            }
        }
    }

    private fun list(resolver: ContentResolver, treeUri: Uri, path: String): JSONObject {
        val parts = normalize(path)
            ?: return err("INVALID_PATH", "路径不合法：只允许工作区内的相对路径（拒绝绝对路径 / .. / 盘符）", "path", path)
        val dirUri = resolve(resolver, treeUri, parts, createDirs = false)
            ?: return err("DIR_NOT_FOUND", "目录不存在: ${path.ifBlank { "/" }}", "path", path)
        val entries = JSONArray()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docIdOf(dirUri))
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            ),
            null, null, null,
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedCol = c.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (c.moveToNext()) {
                val name = c.getString(nameCol) ?: continue
                val isDir = c.getString(mimeCol) == DocumentsContract.Document.MIME_TYPE_DIR
                val size = if (c.isNull(sizeCol)) 0L else c.getLong(sizeCol)
                val modified = if (modifiedCol >= 0 && !c.isNull(modifiedCol)) c.getLong(modifiedCol) else 0L
                entries.put(JSONObject()
                    .put("name", name)
                    .put("path", if (path.isBlank()) name else "$path/$name")
                    .put("type", if (isDir) "dir" else "file")
                    .put("size", size)
                    .put("modified", modified))
            }
        }
        return ok(JSONObject()
            .put("tool", "workspace_fs")
            .put("action", "list")
            .put("path", path.ifBlank { "/" })
            .put("count", entries.length())
            .put("entries", entries))
    }

    private fun read(resolver: ContentResolver, treeUri: Uri, path: String, maxBytes: Int): JSONObject {
        val parts = normalize(path)
            ?: return err("INVALID_PATH", "路径不合法：只允许工作区内的相对路径（拒绝绝对路径 / .. / 盘符）", "path", path)
        if (parts.isEmpty()) return err("INVALID_ARGUMENT", "read 需要一个文件路径", "path", path)
        val fileUri = resolve(resolver, treeUri, parts, createDirs = false)
            ?: return err("FILE_NOT_FOUND", "文件不存在: $path", "path", path)
        if (queryMime(resolver, fileUri) == DocumentsContract.Document.MIME_TYPE_DIR) {
            return err("IS_DIRECTORY", "目标是一个目录，请用 list 查看", "path", path)
        }
        val bytes = readBytes(resolver, fileUri, maxBytes)
        val textual = isText(bytes)
        return ok(JSONObject()
            .put("tool", "workspace_fs")
            .put("action", "read")
            .put("path", path)
            .put("size", bytes.size)
            .put("encoding", if (textual) "text" else "base64")
            .put("content", if (textual) String(bytes, Charsets.UTF_8) else Base64.encodeToString(bytes, Base64.NO_WRAP)))
    }

    private fun write(resolver: ContentResolver, treeUri: Uri, path: String, content: String, encoding: String): JSONObject {
        val parts = normalize(path)
            ?: return err("INVALID_PATH", "路径不合法：只允许工作区内的相对路径（拒绝绝对路径 / .. / 盘符）", "path", path)
        if (parts.isEmpty()) return err("INVALID_ARGUMENT", "write 需要一个文件路径", "path", path)
        val bytes = when (encoding) {
            "base64" -> try {
                Base64.decode(content, Base64.DEFAULT)
            } catch (e: IllegalArgumentException) {
                return err("INVALID_BASE64", "base64 解码失败: ${e.message}", "encoding", encoding)
            }
            else -> content.toByteArray(Charsets.UTF_8)
        }
        val name = parts.last()
        val parentUri = resolve(resolver, treeUri, parts.dropLast(1), createDirs = true)
            ?: return err("WRITE_FAILED", "无法创建父目录: ${parts.dropLast(1).joinToString("/")}", "path", path)
        val existing = findChild(resolver, treeUri, parentUri, name)
        val docUri = existing
            ?: DocumentsContract.createDocument(resolver, parentUri, guessMime(name), name)
            ?: return err("WRITE_FAILED", "无法创建文件: $path", "path", path)
        resolver.openOutputStream(docUri, "wt").use { out ->
            requireNotNull(out) { "无法打开写入流: $path" }
            out.write(bytes)
        }
        return ok(JSONObject()
            .put("tool", "workspace_fs")
            .put("action", "write")
            .put("path", path)
            .put("size", bytes.size)
            .put("created", existing == null))
    }

    private fun mkdir(resolver: ContentResolver, treeUri: Uri, path: String): JSONObject {
        val parts = normalize(path)
            ?: return err("INVALID_PATH", "路径不合法：只允许工作区内的相对路径（拒绝绝对路径 / .. / 盘符）", "path", path)
        if (parts.isEmpty()) return err("INVALID_ARGUMENT", "mkdir 需要一个目录路径", "path", path)
        val name = parts.last()
        val parentUri = resolve(resolver, treeUri, parts.dropLast(1), createDirs = true)
            ?: return err("MKDIR_FAILED", "无法创建父目录", "path", path)
        val existing = findChild(resolver, treeUri, parentUri, name)
        if (existing != null) {
            if (queryMime(resolver, existing) == DocumentsContract.Document.MIME_TYPE_DIR) {
                return ok(JSONObject().put("tool", "workspace_fs").put("action", "mkdir").put("path", path).put("created", false))
            }
            return err("ALREADY_EXISTS", "同名文件已存在: $path", "path", path)
        }
        val created = DocumentsContract.createDocument(resolver, parentUri, DocumentsContract.Document.MIME_TYPE_DIR, name)
            ?: return err("MKDIR_FAILED", "无法创建目录: $path", "path", path)
        return ok(JSONObject().put("tool", "workspace_fs").put("action", "mkdir").put("path", path).put("created", true).put("uri", created.toString()))
    }

    private fun delete(resolver: ContentResolver, treeUri: Uri, path: String, recursive: Boolean): JSONObject {
        val parts = normalize(path)
            ?: return err("INVALID_PATH", "路径不合法：只允许工作区内的相对路径（拒绝绝对路径 / .. / 盘符）", "path", path)
        if (parts.isEmpty()) return err("INVALID_ARGUMENT", "不能删除工作区根目录", "path", path)
        val target = resolve(resolver, treeUri, parts, createDirs = false)
            ?: return err("NOT_FOUND", "目标不存在: $path", "path", path)
        val mime = queryMime(resolver, target)
        if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
            if (!recursive) return err("DIR_NOT_EMPTY", "删除目录需要 recursive=true", "path", path)
            deleteRecursive(resolver, treeUri, target)
        } else {
            DocumentsContract.deleteDocument(resolver, target)
        }
        return ok(JSONObject().put("tool", "workspace_fs").put("action", "delete").put("path", path).put("deleted", true))
    }
}