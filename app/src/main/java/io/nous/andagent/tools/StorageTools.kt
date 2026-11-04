package io.nous.andagent.tools

import android.content.Context
import android.os.Environment
import android.os.StatFs
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Storage analysis tools for cleanup workflows. Read-only: scan returns data,
 * deletion still goes through the shell tool (with its confirmation gate).
 */
class StorageScanTool(private val context: Context) : Tool {
    override val name = "storage_scan"
    override val description = "Analyze storage: free/total space, biggest app caches, top N largest user-visible files " +
        "(Downloads/Movies etc.), and per-directory usage under shared storage."
    override val parameters = schemaOf(
        "top_n" to intParam("List the N largest files under public directories (default 10)"),
        "path" to strParam("Optional: analyze a specific directory instead of the public roots"),
    )

    override suspend fun execute(args: JsonObject): String {
        val topN = args["top_n"]?.jsonPrimitive?.content?.toIntOrNull() ?: 10
        val specific = args["path"]?.jsonPrimitive?.content
        val stat = StatFs(Environment.getDataDirectory().path)
        val sb = StringBuilder()
        sb.append("storage: free=${"%.1f".format(stat.availableBytes / 1e9)}GB / total=${"%.1f".format(stat.totalBytes / 1e9)}GB\n")

        val roots: List<File> = if (specific != null) {
            listOf(File(specific))
        } else {
            listOfNotNull(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS),
            ).filter { it.exists() }
        }
        for (root in roots) {
            val files = root.walkTopDown().filter { it.isFile }.toList()
            val total = files.sumOf { it.length() }
            sb.append("${root.path}: ${files.size} files, ${"%.2f".format(total / 1e9)}GB\n")
            files.sortedByDescending { it.length() }.take(topN).forEach {
                sb.append("  ${"%.1f".format(it.length() / 1e6)}MB  ${it.path}\n")
            }
        }
        // app caches
        context.cacheDir.walkTopDown().filter { it.isFile }.toList().let { files ->
            sb.append("app_cache: ${files.size} files, ${"%.2f".format(files.sumOf { it.length() } / 1e6)}MB\n")
        }
        return sb.toString()
    }
}
