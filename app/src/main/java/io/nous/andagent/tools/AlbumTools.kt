package io.nous.andagent.tools

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Album (MediaStore images) tools. Read-only scanning here; deletion goes
 * through MediaStore.createDeleteRequest (system-confirmed) in a separate tool.
 */
class AlbumScanTool(private val context: Context) : Tool {
    override val name = "album_scan"
    override val description = "Scan the photo library. Returns: total count, total size, per-folder (bucket) stats " +
        "and optionally the largest N photos. Use for organizing/cleanup analysis."
    override val parameters = schemaOf(
        "top_n" to intParam("If set, also list the N largest photos (path, size, date)"),
    )

    override suspend fun execute(args: JsonObject): String {
        val topN = args["top_n"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DISPLAY_NAME,
        )
        data class Row(val bucket: String, val size: Long, val date: Long, val name: String)
        val rows = mutableListOf<Row>()
        context.contentResolver.query(collection, projection, null, null, null)?.use { c ->
            val iBucket = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val iSize = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val iDate = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            while (c.moveToNext()) {
                rows.add(
                    Row(
                        bucket = c.getString(iBucket) ?: "unknown",
                        size = c.getLong(iSize),
                        date = c.getLong(iDate) ?: 0L,
                        name = c.getString(iName) ?: "?",
                    )
                )
            }
        } ?: return "ERROR: cannot access MediaStore (permission denied?)"
        if (rows.isEmpty()) return "photo library empty or unreadable"

        val totalSize = rows.sumOf { it.size }
        val byBucket = rows.groupBy { it.bucket }
        val sb = StringBuilder()
        sb.append("total_photos=${rows.size} total_size=${"%.2f".format(totalSize / 1e9)}GB\n")
        sb.append("folders (${byBucket.size}):\n")
        byBucket.entries.sortedByDescending { it.value.sumOf { r -> r.size } }.forEach { (bucket, list) ->
            sb.append("  $bucket: ${list.size} photos, ${"%.2f".format(list.sumOf { it.size } / 1e9)}GB\n")
        }
        if (topN > 0) {
            sb.append("largest $topN:\n")
            rows.sortedByDescending { it.size }.take(topN).forEach {
                sb.append("  ${it.bucket}/${it.name} ${"%.1f".format(it.size / 1e6)}MB ${it.date}\n")
            }
        }
        return sb.toString()
    }
}

/**
 * Screenshot detection + oldest-photo stats for cleanup workflows.
 */
class AlbumStatsTool(private val context: Context) : Tool {
    override val name = "album_stats"
    override val description = "Quick photo stats: screenshots count/size, photos per year, blurred candidates skipped. Lighter than album_scan."
    override val parameters = schemaOf()

    override suspend fun execute(args: JsonObject): String {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.RELATIVE_PATH,
        )
        var shotCount = 0L; var shotBytes = 0L
        val perYear = mutableMapOf<Int, Pair<Int, Long>>() // year -> (count, bytes)
        context.contentResolver.query(collection, projection, null, null, null)?.use { c ->
            val iSize = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val iDate = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val iPath = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            while (c.moveToNext()) {
                val size = c.getLong(iSize)
                val path = c.getString(iPath) ?: ""
                val date = c.getLong(iDate) ?: 0L
                if (path.contains("Screenshot", ignoreCase = true)) { shotCount++; shotBytes += size }
                if (date > 0) {
                    val year = java.util.Calendar.getInstance().apply { timeInMillis = date }.get(java.util.Calendar.YEAR)
                    val cur = perYear[year] ?: (0 to 0L)
                    perYear[year] = (cur.first + 1) to (cur.second + size)
                }
            }
        } ?: return "ERROR: cannot access MediaStore (permission denied?)"
        val sb = StringBuilder()
        sb.append("screenshots: $shotCount photos, ${"%.2f".format(shotBytes / 1e9)}GB\n")
        sb.append("by year:\n")
        perYear.toSortedMap().forEach { (y, v) -> sb.append("  $y: ${v.first} photos, ${"%.2f".format(v.second / 1e9)}GB\n") }
        return sb.toString()
    }
}
