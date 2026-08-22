package com.cras.app.voice

import java.io.File

/** Default retention bounds for locally retained retry recordings. */
const val DEFAULT_MAX_RETAINED_RECORDINGS = 5
const val DEFAULT_MAX_RETAINED_TOTAL_BYTES = 8L * 1024 * 1024 // 8 MB

/**
 * A Voice capture recording kept on-device so the Operator can retry a failed
 * send without re-recording.
 */
data class RetainedRecording(
    val id: String,
    val fileName: String,
    val sizeBytes: Long,
    val createdAtEpochMs: Long,
)

/**
 * Bounded local retention of retry recordings. Newest captures are always
 * kept; the oldest are evicted when either the count or total size bound is
 * exceeded. The Operator can delete single recordings or clear everything.
 */
interface VoiceRecordingStore {
    fun save(wav: ByteArray, createdAtEpochMs: Long = System.currentTimeMillis()): RetainedRecording
    fun list(): List<RetainedRecording>
    fun latest(): RetainedRecording?
    fun readBytes(id: String): ByteArray?
    fun delete(id: String)
    fun clearAll()
}

/**
 * Directory-backed [VoiceRecordingStore]. The recording start time is encoded
 * into the file name so ordering never depends on filesystem mtime
 * resolution. Safe for use from a single caller; the voice flow serializes access.
 */
class DirectoryVoiceRecordingStore(
    private val directory: File,
    private val maxCount: Int = DEFAULT_MAX_RETAINED_RECORDINGS,
    private val maxTotalBytes: Long = DEFAULT_MAX_RETAINED_TOTAL_BYTES,
) : VoiceRecordingStore {

    init {
        require(maxCount >= 1) { "maxCount must be at least 1" }
        require(maxTotalBytes > 0) { "maxTotalBytes must be positive" }
        if (!directory.exists()) {
            directory.mkdirs()
        }
    }

    override fun save(wav: ByteArray, createdAtEpochMs: Long): RetainedRecording {
        val id = newId()
        val fileName = fileNameFor(createdAtEpochMs, id)
        val target = File(directory, fileName)
        val tmp = File(directory, "$fileName.part")
        try {
            tmp.writeBytes(wav)
            if (!tmp.renameTo(target)) {
                target.writeBytes(tmp.readBytes())
            }
        } finally {
            if (tmp.exists()) tmp.delete()
        }

        enforceBounds(excludeId = id)
        return RetainedRecording(
            id = id,
            fileName = fileName,
            sizeBytes = target.length(),
            createdAtEpochMs = createdAtEpochMs,
        )
    }

    override fun list(): List<RetainedRecording> =
        recordingFiles().map { file ->
            val parsed = parseFileName(file.name)
            RetainedRecording(
                id = parsed.second,
                fileName = file.name,
                sizeBytes = file.length(),
                createdAtEpochMs = parsed.first,
            )
        }.sortedByDescending { it.createdAtEpochMs }

    override fun latest(): RetainedRecording? = list().firstOrNull()

    override fun readBytes(id: String): ByteArray? {
        val file = fileOf(id) ?: return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    override fun delete(id: String) {
        fileOf(id)?.delete()
    }

    override fun clearAll() {
        recordingFiles().forEach { it.delete() }
    }

    fun totalBytes(): Long = recordingFiles().sumOf { it.length() }

    fun fileOf(id: String): File? =
        recordingFiles().firstOrNull { parseFileName(it.name).second == id }

    /** Evicts oldest recordings (newest-first list tail) until within both bounds. */
    private fun enforceBounds(excludeId: String? = null) {
        val ordered = list()
        var total = ordered.sumOf { it.sizeBytes }
        var count = ordered.size

        for (recording in ordered.asReversed()) {
            if (count <= maxCount && total <= maxTotalBytes) break
            if (count == 1 && recording.id == excludeId) break
            if (fileOf(recording.id)?.delete() == true) {
                total -= recording.sizeBytes
                count -= 1
            }
        }
    }

    private fun recordingFiles(): List<File> =
        directory.listFiles { f -> f.isFile && FILE_NAME_REGEX.matches(f.name) }?.toList()
            ?: emptyList()

    companion object {
        private val ID_REGEX = Regex("""^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$""")
        private val FILE_NAME_REGEX = Regex("""^voice-capture-\d+-[0-9a-fA-F\-]{36}\.wav$""")

        private fun newId(): String = java.util.UUID.randomUUID().toString()
        private fun fileNameFor(createdAtEpochMs: Long, id: String): String =
            "voice-capture-$createdAtEpochMs-$id.wav"

        private fun parseFileName(name: String): Pair<Long, String> {
            val body = name.removePrefix("voice-capture-").removeSuffix(".wav")
            val separator = body.indexOf('-')
            val epoch = body.substring(0, separator).toLongOrNull() ?: 0L
            return epoch to body.substring(separator + 1)
        }
    }
}
