package com.soreverse.mcp.mcp

import com.soreverse.mcp.core.RootShell
import com.soreverse.mcp.core.UnpackManager
import com.soreverse.mcp.core.UnpackManager.toJson
import com.soreverse.mcp.core.err
import com.soreverse.mcp.core.ok
import com.soreverse.mcp.core.str
import com.soreverse.mcp.core.intValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 逆核: DEX 脱壳工具(需 root)。内存 dump 原理(frida-dexdump 同思路),
 * 从运行中的目标进程内存扫出解密后的真实 dex,绕过磁盘加固壳。
 *
 *  action:
 *   - dump:    对目标包名脱壳,把内存里的 dex dump 到 filesDir/dexdump/<pkg>/。
 *   - pslist:  列出运行中的第三方应用进程(帮你确定要脱壳的包名/pid)。
 *   - pids:    查某个包名当前的 pid。
 *
 * 脱壳后可接 jadx_decompile / baksmali_decode 分析 dump 出的 dex。
 */
object UnpackTool {

    val dexDump: ToolHandler = object : ToolHandler {
        override val meta = ToolMeta(
            "dex_unpack",
            "【DEX 脱壳(内存dump)】需 root。对运行中的加固 App 从内存扫出解密后的真实 dex(绕过磁盘上被加密/抽取的壳,原理同 frida-dexdump)。action=dump 脱壳(需先手动打开目标 App 让壳解密 dex 进内存,再传 package 脱); action=pslist 列运行中的第三方应用(找目标包名); action=pids 查包名当前 pid。脱出的 dex 会自动归档到工作区(设置→工作区)的【脱壳_时间戳】子文件夹,工作区未启用时保留在应用私有目录。脱出的 dex 可直接喂给 jadx_decompile/baksmali_decode 分析。三代指令抽取壳的方法体可能是 nop(本工具不做指令修复)。",
            "DEX unpacking via memory dump (requires root). Scans a running hardened app's memory for decrypted dex, bypassing on-disk packers (same idea as frida-dexdump). action=dump (open the target app first so the packer decrypts dex into memory, then pass package); action=pslist lists running third-party apps; action=pids finds a package's current pid. Dumped dex is archived into the Workspace (Settings→Workspace) under a subfolder named 脱壳_<timestamp>; if the Workspace is not enabled, files stay in the app private dir. Dumped dex can be fed to jadx_decompile/baksmali_decode.",
            "dynamic", ToolClass.EXTRA, heavy = true,
        ) {
            objectSchema(props {
                "action".oneOf("dump(脱壳) | pslist(列应用) | pids(查pid)", "dump", "pslist", "pids")
                "package" str "目标应用包名(action=dump/pids),如 com.example.app"
                "pkg" str "package 的别名"
                "minDexSize" int "有效 dex 最小字节,过滤内存碎片(默认 112=0x70,dex header 长度)"
                "filter" str "包名过滤关键词(action=pslist 可选)"
            })
        }

        override fun handle(ctx: ToolContext, args: JSONObject): JSONObject {
            return when (args.str("action", "dump").ifBlank { "dump" }) {
                "pslist" -> {
                    if (!RootShell.isRootAvailable()) return err("NO_ROOT", "未检测到 root,无法枚举进程")
                    val filter = args.str("filter")
                    // 列第三方应用进程(uid>=10000 是普通应用),按包名形式过滤
                    val base = "ps -A -o PID,USER,NAME 2>/dev/null"
                    val cmd = if (filter.isBlank()) {
                        "$base | grep -E '\\b(u0_a|10[0-9]{3})' 2>/dev/null || $base"
                    } else {
                        "$base | grep -i '$filter'"
                    }
                    val r = RootShell.exec(cmd, timeoutSec = 12)
                    if (!r.success && r.stdout.isBlank()) return err("PS_FAILED", r.stderr.ifBlank { "进程枚举失败" })
                    val lines = r.stdout.lines().filter { it.isNotBlank() }.take(400)
                    ok(JSONObject()
                        .put("action", "pslist")
                        .put("count", lines.size)
                        .put("hint", "找到目标包名后,先手动打开该 App,再用 action=dump package=<包名> 脱壳")
                        .put("processes", JSONArray(lines)))
                }

                "pids" -> {
                    val pkg = args.str("package").ifBlank { args.str("pkg") }
                    if (pkg.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 package", "package", "")
                    if (!RootShell.isRootAvailable()) return err("NO_ROOT", "未检测到 root")
                    val pids = UnpackManager.findPids(pkg)
                    ok(JSONObject()
                        .put("action", "pids")
                        .put("package", pkg)
                        .put("pids", JSONArray(pids))
                        .put("running", pids.isNotEmpty())
                        .put("hint", if (pids.isEmpty()) "进程未运行,请先打开目标 App" else "进程在跑,可以 action=dump 脱壳"))
                }

                else -> { // dump
                    val pkg = args.str("package").ifBlank { args.str("pkg") }
                    if (pkg.isBlank()) return err("INVALID_ARGUMENT", "缺少参数 package(目标应用包名)", "package", "")
                    if (!RootShell.isRootAvailable()) return err("NO_ROOT", "未检测到 root,脱壳需要读取目标进程内存")
                    val safeName = pkg.replace(Regex("[^A-Za-z0-9_.-]"), "_")
                    val outDir = File(ctx.context.filesDir, "dexdump/$safeName")
                    val minSize = args.intValue("minDexSize", 0x70)
                    val result = UnpackManager.dumpDex(pkg, outDir, minSize)
                    if (!result.success) return err("UNPACK_NO_DEX", result.message, "package", pkg)
                    val payload = result.toJson()

                    // 逆核: 脱壳产物归档到受限工作区「脱壳_<时间戳>」子文件夹(复用 workspace_fs 沙箱写入)；
                    // 工作区未启用时保持原行为(产物留私有目录)并给出提示。
                    val ws = ctx.settings
                    val wsUri = ws.workspaceUri
                    if (ws.workspaceEnabled && wsUri != null) {
                        val dirName = "脱壳_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                        val copied = JSONArray()
                        val errors = JSONArray()
                        result.dexFiles.forEach { (name, _) ->
                            val src = File(outDir, name)
                            if (src.isFile) {
                                runCatching {
                                    WorkspaceFileTool.copyFileIn(ctx.context.contentResolver, wsUri, "$dirName/$name", src)
                                }.onSuccess { copied.put("$dirName/$name") }
                                .onFailure { errors.put("$dirName/$name: ${it.message}") }
                            }
                        }
                        payload.put("workspaceDir", dirName)
                            .put("workspaceFiles", copied)
                            .put("workspaceHint", if (errors.length() == 0)
                                "脱壳产物已归档到工作区 $dirName/ 文件夹"
                            else
                                "部分文件复制到工作区失败，详见 workspaceCopyErrors")
                        if (errors.length() > 0) payload.put("workspaceCopyErrors", errors)
                    } else {
                        payload.put("workspaceHint", "未启用受限工作区，产物保留在应用私有目录。如需归档到工作区，请先在设置→工作区启用并选择文件夹。")
                    }
                    ok(payload)
                }
            }
        }
    }
}
