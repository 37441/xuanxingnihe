package com.soreverse.mcp.core

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.soreverse.mcp.engine.WorkDirectory
import java.io.File
import java.security.MessageDigest

/**
 * 逆核: 工具输入文件解析器（Android 11+ scoped storage 适配）。
 *
 * 背景: 加固逆向工具（jadx / baksmali / apk_decode / apk_analyze）用 java.io.File 直接读输入，
 * 但应用没有 MANAGE_EXTERNAL_STORAGE 时无法用真实路径（/storage/emulated/0/...）读外部存储，
 * 只有用户通过 SAF 授权的目录（工作区 workspaceUri / 工作目录 treeUri）能读。
 * 本解析器把三类输入统一解析成可读的私有缓存文件：
 *   1) content:// SAF 文档 URI（必须属于某个授权树）
 *   2) 显示路径（/storage/emulated/0/xxx）或相对路径 → 匹配授权树经 SAF 读字节
 *   3) 应用私有目录真实路径 → 直接返回
 * 不在授权范围（工作区 / 工作目录 / 私有目录）内的路径一律拒绝，
 * 保证 AI 只能读取用户放进授权文件夹的文件。
 */
object FileInWorkspaceResolver {

    private const val MAX_INPUT_BYTES = 512L * 1024L * 1024L

    fun resolve(context: Context, settings: SettingsStore, input: String): File? {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return null

        // 3) 已是本地可读文件（应用私有目录等）
        val direct = File(trimmed)
        if (direct.isFile) return direct

        // 1) content:// 文档 URI，必须属于某个授权树
        if (trimmed.startsWith("content://")) {
            val tree = listOfNotNull(settings.workspaceUri, settings.treeUri)
                .firstOrNull { trimmed.startsWith(it.toString()) } ?: return null
            val bytes = readDocument(context, trimmed) ?: return null
            return cacheBytes(context, tree.toString(), trimmed, bytes)
        }

        // 2) 显示路径或相对路径 → 匹配授权树（workspaceUri 优先）
        val trees = listOfNotNull(settings.workspaceUri, settings.treeUri)
        for (tree in trees) {
            val prefix = WorkDirectory.displayPath(tree).trimEnd('/')
            val rel = when {
                trimmed.startsWith(prefix) -> trimmed.removePrefix(prefix).trim('/').ifBlank { null }
                !trimmed.startsWith("/") -> trimmed
                else -> null
            }
            if (rel != null) {
                val bytes = readFromTree(context, tree, rel) ?: continue
                return cacheBytes(context, tree.toString(), rel, bytes)
            }
        }
        return null
    }

    private fun cacheBytes(context: Context, key: String, rel: String, bytes: ByteArray): File {
        val hash = sha256(key + "\n" + rel).take(32)
        val ext = rel.substringAfterLast('.', "").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,10}")) }
        val dir = File(context.filesDir, "input_cache").apply { mkdirs() }
        return File(dir, "$hash${if (ext != null) ".$ext" else ".bin"}").apply { writeBytes(bytes) }
    }

    private fun readDocument(context: Context, uri: String): ByteArray? = runCatching {
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { input ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                if (total > MAX_INPUT_BYTES) throw IllegalArgumentException("文件超过 512MiB 读取上限")
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    }.getOrNull()

    private fun readFromTree(context: Context, treeUri: Uri, rel: String): ByteArray? = runCatching {
        val doc = locate(context, treeUri, rel) ?: return@runCatching null
        readDocument(context, doc.toString())
    }.getOrNull()

    /** 在授权树内逐段定位文档 URI；任何一段找不到即失败（无法越出该树）。 */
    private fun locate(context: Context, treeUri: Uri, rel: String): Uri? {
        val resolver = context.contentResolver
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
        var current = DocumentsContract.buildDocumentUriUsingTree(treeUri, rootId)
        for (name in rel.split('/').filter { it.isNotBlank() && it != "." && it != ".." }) {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, DocumentsContract.getDocumentId(current))
            var found: Uri? = null
            resolver.query(
                childrenUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (c.moveToNext()) {
                    if (c.getString(nameCol) == name) {
                        found = DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(idCol))
                        break
                    }
                }
            }
            if (found == null) return null
            current = found
        }
        return current
    }

    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}