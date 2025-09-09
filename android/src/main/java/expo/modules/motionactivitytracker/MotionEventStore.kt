package expo.modules.motionactivitytracker

import org.json.JSONObject
import java.io.File

object MotionEventStore {
  private const val DIR = "motion_store"
  private const val SEGMENT_MAX = 512 * 1024      // 512 KB per segment
  private const val TOTAL_MAX   = 1 * 1024 * 1024 // 1 MB total cap
  private const val FILE_PREFIX = "seg_"
  private const val BATCH_MAX   = 1000

  fun append(ctx: android.content.Context, rec: Map<String, Any>) {
    val dir = File(ctx.filesDir, DIR).apply { mkdirs() }
    val seg = nextWritable(dir)
    seg.appendText(JSONObject(rec).toString() + "\n")
    enforceCaps(dir)
  }

  fun readAllAndClear(ctx: android.content.Context): List<Map<String, Any>> {
    val dir = File(ctx.filesDir, DIR)
    val segs = dir.listFiles()?.sortedBy { it.lastModified() }.orEmpty()
    val out = arrayListOf<Map<String, Any>>()
    for (f in segs) {
      // prevent huge RAM usage if something went wrong: cap read
      val lines = f.readLines()
      for (line in lines) {
        if (out.size >= BATCH_MAX) break
        val json = JSONObject(line)
        out += mapOf(
          "timestamp" to json.getLong("ts"),
          "activityType" to json.getString("type"),
          "confidence" to json.getInt("conf"),
          "transitionType" to "UNKNOWN"
        )
      }
      f.delete()
      if (out.size >= BATCH_MAX) break
    }
    return out
  }

  private fun nextWritable(dir: File): File {
    val segs = dir.listFiles()?.sortedBy { it.lastModified() }.orEmpty()
    val tail = segs.lastOrNull()
    return if (tail != null && tail.length() < SEGMENT_MAX) tail
           else File(dir, "${FILE_PREFIX}${System.currentTimeMillis()}.log").apply { createNewFile() }
  }

  private fun enforceCaps(dir: File) {
    val segs = dir.listFiles()?.sortedBy { it.lastModified() }.orEmpty()
    var total = segs.sumOf { it.length() }
    for (f in segs) {
      if (total <= TOTAL_MAX) break
      total -= f.length()
      f.delete()
    }
  }
}
