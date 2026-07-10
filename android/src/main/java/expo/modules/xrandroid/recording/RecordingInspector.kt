package expo.modules.xrandroid.recording

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import expo.modules.xrandroid.ACTIVE_PROFILE
import expo.modules.xrandroid.WAV_HEADER_BYTES
import expo.modules.xrandroid.bitrateMbps
import expo.modules.xrandroid.microsToSeconds
import expo.modules.xrandroid.nanosToMillis
import expo.modules.xrandroid.nanosToSeconds
import org.json.JSONObject
import java.io.File

// The take store's NATIVE seams — the rest of the catalog (listing, manifest reads) is TS
// (xr.catalog). See README → Catalog · inspect · export. catalogRoot() gives TS the storage
// base; inspectTake() is the on-demand deep re-probe (MediaExtractor stats + per-stream
// boottime spans + overlap), used only as the pre-v3 fallback — v3+ reads baked `stats` from
// the manifest. The same computeStats() is baked in at record-stop by TakeRecorder.
internal object RecordingInspector {

  private const val TAG = "XrRecInspector"
  private val AUDIO_SAMPLE_RATE = ACTIVE_PROFILE.sampleRate.toDouble()

  // The app-private storage base dir (where take_ dirs live), or null off-device. TS
  // enumerates + reads manifests from here.
  fun catalogRoot(ctx: Context?): String? = ctx?.getExternalFilesDir(null)?.absolutePath

  // --- full inspect (deep re-probe fallback) -------------------------------

  fun inspectTake(ctx: Context?, name: String): Map<String, Any?> {
    val base = ctx?.getExternalFilesDir(null) ?: return mapOf("error" to "no_storage")
    val dir = File(base, name)
    if (!dir.isDirectory) return mapOf("error" to "not_found", "name" to name)
    val files = dir.listFiles()?.toList().orEmpty()

    return mapOf(
      "name" to dir.name,
      "path" to dir.absolutePath,
      "totalBytes" to files.sumOf { it.length() },
      "hasManifest" to files.any { it.name == "manifest.json" },
    ) + computeStats(dir)
  }

  // The heavy per-file stats — MediaExtractor probing + boottime spans + cross-stream
  // alignment — as a standalone map {videos, audios, dataTracks, alignment}. Reused by
  // inspectTake (on demand) and TakeRecorder (baked into the manifest at record-stop, when
  // the files are freshly finalized). Pure over the take dir; no ctx/permissions.
  fun computeStats(dir: File): Map<String, Any?> {
    val files = dir.listFiles()?.toList().orEmpty()

    // Collect (streamLabel → [startBootNs, endBootNs]) as we inspect, for alignment.
    val spans = LinkedHashMap<String, LongArray>()

    val videos = files.filter { it.name.endsWith(".mp4") }.sortedBy { it.name }.map { f ->
      inspectVideo(f, dir, spans)
    }
    val audios = files.filter { it.name.endsWith(".wav") }.sortedBy { it.name }.map { f ->
      inspectAudio(f, dir, spans)
    }
    val dataTracks = files
      .filter {
        it.name.endsWith(".jsonl") && !it.name.contains("_frames") && !it.name.contains(".clocksync")
      }
      .sortedBy { it.name }
      .map { f -> inspectTrack(f, spans) }

    return mapOf(
      "videos" to videos,
      "audios" to audios,
      "dataTracks" to dataTracks,
      "alignment" to buildAlignment(spans),
    )
  }

  private fun inspectVideo(file: File, dir: File, spans: MutableMap<String, LongArray>): Map<String, Any?> {
    val ex = MediaExtractor()
    val base: Map<String, Any?> = try {
      ex.setDataSource(file.absolutePath)
      var fmt: MediaFormat? = null
      var idx = -1
      for (i in 0 until ex.trackCount) {
        val f = ex.getTrackFormat(i)
        if (f.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
          fmt = f; idx = i; break
        }
      }
      if (idx < 0 || fmt == null) {
        mapOf("file" to file.name, "error" to "no_video_track", "sizeBytes" to file.length())
      } else {
        ex.selectTrack(idx)
        var frames = 0
        var sync = 0
        do {
          if (ex.sampleTime < 0) break
          frames++
          if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) sync++
        } while (ex.advance())
        val durationS = if (fmt.containsKey(MediaFormat.KEY_DURATION)) {
          microsToSeconds(fmt.getLong(MediaFormat.KEY_DURATION))
        } else {
          0.0
        }
        val sizeBytes = file.length()
        mapOf(
          "file" to file.name,
          "mime" to fmt.getString(MediaFormat.KEY_MIME),
          "width" to intOr(fmt, MediaFormat.KEY_WIDTH),
          "height" to intOr(fmt, MediaFormat.KEY_HEIGHT),
          "frames" to frames,
          "syncFrames" to sync,
          "allIntra" to (frames > 0 && sync == frames),
          // Full precision — TS rounds at display (.toFixed).
          "durationS" to durationS,
          "fps" to (if (durationS > 0) frames / durationS else 0.0),
          "sizeBytes" to sizeBytes,
          "bitrateMbps" to bitrateMbps(sizeBytes, durationS),
        )
      }
    } catch (e: Exception) {
      Log.w(TAG, "inspect ${file.name} failed: ${e.message}")
      mapOf("file" to file.name, "error" to (e.message ?: "extractor_failed"), "sizeBytes" to file.length())
    } finally {
      try { ex.release() } catch (e: Exception) {}
    }

    // Boottime span from the per-frame sidecar (cam<N>_frames.jsonl → "sensorTs"),
    // in a single pass (first line, last line, count).
    val sidecar = File(dir, "${file.nameWithoutExtension}_frames.jsonl")
    val scan = scanLines(sidecar)
    val start = scan.first?.let { longField(it, "sensorTs") }
    val end = scan.last?.let { longField(it, "sensorTs") }
    if (start != null && end != null) spans["camera:${file.name}"] = longArrayOf(start, end)
    return base + mapOf(
      // null (not 0) when the frame sidecar is absent/empty — "unknown", distinct from a
      // real boottime value. The bridge maps Kotlin null → JS null; TS types are optional.
      "startBootNs" to start,
      "endBootNs" to end,
      "sidecarLines" to scan.count,
    )
  }

  private fun inspectAudio(file: File, dir: File, spans: MutableMap<String, LongArray>): Map<String, Any?> {
    val bytes = file.length()
    // WAV data bytes ÷ bytes-per-frame. Sourced from the profile (single source of truth
    // with the writer); parsing the WAV `fmt ` chunk instead is the deferred upgrade.
    val totalFrames = ((bytes - WAV_HEADER_BYTES).coerceAtLeast(0)) / ACTIVE_PROFILE.bytesPerFrame
    val durationS = totalFrames / AUDIO_SAMPLE_RATE

    // True sample-0 / sample-N boottime from the clock-sync sidecar (framePosition ↔ bootNs).
    val clockSync = File(dir, "${file.nameWithoutExtension}.clocksync.jsonl")
    val scan = scanLines(clockSync)
    var start: Long? = null
    var end: Long? = null
    val firstLine = scan.first
    val lastLine = scan.last
    if (firstLine != null && lastLine != null) {
      val fBoot = longField(firstLine, "bootNs")
      val fPos = longField(firstLine, "framePosition")
      val lBoot = longField(lastLine, "bootNs")
      val lPos = longField(lastLine, "framePosition")
      if (fBoot != null && fPos != null) {
        start = fBoot - (fPos / AUDIO_SAMPLE_RATE * 1e9).toLong()
      }
      if (lBoot != null && lPos != null) {
        end = lBoot + ((totalFrames - lPos) / AUDIO_SAMPLE_RATE * 1e9).toLong()
      }
    }
    if (start != null && end != null) spans["audio:${file.name}"] = longArrayOf(start, end)
    return mapOf(
      "file" to file.name,
      "sizeBytes" to bytes,
      // Full precision — TS rounds at display (.toFixed).
      "durationS" to durationS,
      // null (not 0) when the clock-sync sidecar is absent — "unknown", not boottime 0.
      "startBootNs" to start,
      "endBootNs" to end,
    )
  }

  private fun inspectTrack(file: File, spans: MutableMap<String, LongArray>): Map<String, Any?> {
    var firstTs: Long? = null
    var lastTs: Long? = null
    var count = 0
    // hands.jsonl interleaves left+right into one file; track each handedness's own
    // span+count so we can report a real per-hand rate, not just the combined one.
    val perHand = LinkedHashMap<String, LongArray>() // handedness -> [firstTs, lastTs, count]
    if (file.exists()) {
      runCatching {
        file.bufferedReader().use { r ->
          r.forEachLine { line ->
            if (line.isBlank()) return@forEachLine
            count++
            val obj = runCatching { JSONObject(line) }.getOrNull() ?: return@forEachLine
            val ts = obj.optLong("ts", Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }
              ?: return@forEachLine
            if (firstTs == null) firstTs = ts
            lastTs = ts
            val hand = obj.optString("handedness")
            if (hand.isNotEmpty()) {
              val e = perHand.getOrPut(hand) { longArrayOf(ts, ts, 0L) }
              e[1] = ts
              e[2] = e[2] + 1
            }
          }
        }
      }
    }
    val start = firstTs
    val end = lastTs
    if (start != null && end != null) spans[file.nameWithoutExtension] = longArrayOf(start, end)
    val durationS = if (start != null && end != null) nanosToSeconds(end - start) else 0.0
    val result = mutableMapOf<String, Any?>(
      "file" to file.name,
      "lines" to count,
      "sizeBytes" to file.length(),
      // null (not 0) when the track carried no parseable timestamps — "unknown", not boottime 0.
      "startBootNs" to start,
      "endBootNs" to end,
      // Full precision — TS rounds at display (.toFixed).
      "rateHz" to (if (durationS > 0) count / durationS else 0.0),
    )
    // Only when the track carries handedness (hands.jsonl) → two ~equal per-hand rates.
    if (perHand.size > 1) {
      result["perHand"] = perHand.map { (hand, e) ->
        val durHand = nanosToSeconds(e[1] - e[0])
        mapOf(
          "handedness" to hand,
          "lines" to e[2],
          "rateHz" to (if (durHand > 0) e[2] / durHand else 0.0),
        )
      }
    }
    return result
  }

  // Reference all spans to the earliest start; report each as ms offsets, plus the
  // overlap window [max(starts), min(ends)] — the usable, mutually-covered range.
  private fun buildAlignment(spans: Map<String, LongArray>): Map<String, Any?> {
    if (spans.isEmpty()) return mapOf("available" to false)
    val t0 = spans.values.minOf { it[0] }
    val overlapStart = spans.values.maxOf { it[0] }
    val overlapEnd = spans.values.minOf { it[1] }
    val streamList = spans.map { (label, se) ->
      mapOf(
        "stream" to label,
        // Full precision — TS rounds at display (.toFixed).
        "startMs" to nanosToMillis(se[0] - t0),
        "endMs" to nanosToMillis(se[1] - t0),
      )
    }
    return mapOf(
      "available" to true,
      "t0BootNs" to t0,
      "streams" to streamList,
      "overlapStartMs" to nanosToMillis(overlapStart - t0),
      "overlapEndMs" to nanosToMillis(overlapEnd - t0),
      "overlapDurationS" to nanosToSeconds((overlapEnd - overlapStart).coerceAtLeast(0)),
    )
  }

  // --- helpers -------------------------------------------------------------

  // First non-blank line, last non-blank line, and non-blank count — in ONE streaming
  // pass, without holding the whole file in memory (sidecars are large at full rate).
  private class LineScan(val first: String?, val last: String?, val count: Int)

  private fun scanLines(file: File): LineScan {
    if (!file.exists()) return LineScan(null, null, 0)
    var first: String? = null
    var last: String? = null
    var count = 0
    return try {
      file.bufferedReader().use { r ->
        r.forEachLine { line ->
          if (line.isBlank()) return@forEachLine
          if (first == null) first = line
          last = line
          count++
        }
      }
      LineScan(first, last, count)
    } catch (e: Exception) {
      LineScan(first, last, count)
    }
  }

  private fun longField(line: String, key: String): Long? = try {
    val v = JSONObject(line).optLong(key, Long.MIN_VALUE)
    if (v == Long.MIN_VALUE) null else v
  } catch (e: Exception) {
    null
  }

  private fun intOr(fmt: MediaFormat, key: String): Int =
    if (fmt.containsKey(key)) fmt.getInteger(key) else 0
}
