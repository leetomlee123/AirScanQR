package top.css.airscanqr

import android.util.Base64

/**
 * Receives one AirScan-QR transfer.
 *
 * A frame is `TaskID|FileName|TotalFrames|CurrentIndex|DataPayload`.
 * [DataPayload] is a slice of the Base64 text of the file (the web sender
 * slices the Base64 string, not the raw bytes). Parts after the fourth `|`
 * are joined back so a `|` inside the payload is not lost.
 */
class TransferSession {
    var taskId: String? = null
        private set
    var fileName: String = ""
        private set
    var totalFrames: Int = 0
        private set

    private val frames = HashMap<Int, String>()

    val receivedCount: Int get() = frames.size

    val isComplete: Boolean get() = totalFrames > 0 && frames.size == totalFrames

    /** True when [raw] belongs to a different transfer than the one in progress. */
    fun isForeignTask(raw: String): Boolean {
        val current = taskId ?: return false
        val incoming = raw.split('|', limit = 2).firstOrNull() ?: return false
        return incoming.isNotEmpty() && incoming != current
    }

    fun ingest(raw: String): IngestResult {
        val parts = raw.split('|')
        if (parts.size < 5) return IngestResult.Ignored

        val id = parts[0]
        val name = parts[1]
        val total = parts[2].toIntOrNull() ?: return IngestResult.Ignored
        val index = parts[3].toIntOrNull() ?: return IngestResult.Ignored
        val payload = parts.subList(4, parts.size).joinToString("|")

        if (id.isEmpty() || total !in 1..MAX_FRAMES || index !in 0 until total) {
            return IngestResult.Ignored
        }
        if (taskId != null && id != taskId) return IngestResult.ForeignTask
        if (payload.isEmpty()) return IngestResult.Ignored

        if (taskId == null) {
            taskId = id
            fileName = name.ifBlank { "unnamed_file.txt" }
            totalFrames = total
        } else if (total != totalFrames) {
            return IngestResult.Ignored
        }

        val known = frames.putIfAbsent(index, payload) != null
        return if (known) IngestResult.Duplicate else IngestResult.Accepted
    }

    fun assemble(): ByteArray {
        check(isComplete) { "transfer is not complete ($receivedCount/$totalFrames)" }
        val encoded = StringBuilder()
        for (i in 0 until totalFrames) {
            encoded.append(frames.getValue(i))
        }
        val text = encoded.toString()
        if (text.length > MAX_BASE64_CHARS) {
            throw IllegalStateException("payload exceeds ${MAX_BASE64_CHARS} Base64 chars")
        }
        return try {
            Base64.decode(text, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            throw IllegalStateException("payload is not valid Base64", e)
        }
    }

    fun reset() {
        taskId = null
        fileName = ""
        totalFrames = 0
        frames.clear()
    }

    enum class IngestResult { Accepted, Duplicate, Ignored, ForeignTask }

    companion object {
        const val MAX_FRAMES = 20_000
        const val MAX_BASE64_CHARS = 32 * 1024 * 1024
    }
}
