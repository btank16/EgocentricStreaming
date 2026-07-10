package expo.modules.xrandroid.recording

import android.content.Context
import android.util.Log
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

// Bundles a take directory into a single .zip for sharing (see README → Catalog · inspect ·
// export). A take is a dir of related files only meaningful together, so the whole dir is
// zipped into one shareable artifact. Written to the app cache dir (transient — the OS can reclaim it).
internal object RecordingExporter {

  private const val TAG = "XrRecExporter"

  fun zipTake(ctx: Context?, name: String): Map<String, Any?> {
    ctx ?: return mapOf("status" to "no_context")
    val base = ctx.getExternalFilesDir(null) ?: return mapOf("status" to "no_storage")
    val dir = File(base, name)
    if (!dir.isDirectory) return mapOf("status" to "not_found", "name" to name)

    val outDir = File(ctx.cacheDir, "exports").apply { mkdirs() }
    val zipFile = File(outDir, "$name.zip")
    return try {
      ZipOutputStream(BufferedOutputStream(zipFile.outputStream())).use { zos ->
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.name }.orEmpty()
        for (f in files) {
          // Entry path keeps the take dir as the top-level folder, so it unzips tidily.
          zos.putNextEntry(ZipEntry("$name/${f.name}"))
          FileInputStream(f).use { it.copyTo(zos) }
          zos.closeEntry()
        }
      }
      mapOf("status" to "ok", "path" to zipFile.absolutePath, "sizeBytes" to zipFile.length())
    } catch (e: Exception) {
      Log.w(TAG, "zip $name failed: ${e.message}")
      try { zipFile.delete() } catch (e2: Exception) {}
      mapOf("status" to "error", "message" to (e.message ?: "zip_failed"))
    }
  }
}
