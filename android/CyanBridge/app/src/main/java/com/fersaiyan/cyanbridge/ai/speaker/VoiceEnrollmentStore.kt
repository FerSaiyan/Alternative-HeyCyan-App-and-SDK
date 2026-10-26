package com.fersaiyan.cyanbridge.ai.speaker

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Properties

data class EnrollmentInfo(
    val languageTag: String,
    val inputRoute: String,
    val phraseCount: Int,
    val consentAtMs: Long,
    val completedAtMs: Long,
)

data class SpeakerTemplate(
    val modelId: String,
    val vector: FloatArray,
    val windows: Int,
    val minLeaveOneOut: Float,
    val createdAtMs: Long,
)

/**
 * The wearer's voice profile. It is biometric data: it stays in [Context.getNoBackupFilesDir]
 * (excluded from auto-backup and device transfer), is never synced, sent to a model provider or
 * logged, and [deleteAll] removes every trace.
 *
 * The enrollment phrases are kept as raw 16 kHz PCM so a template can be rebuilt for another
 * model or variant (ECAPA vs WeSpeaker, CPU vs NPU) without asking the user to enroll again.
 */
class VoiceEnrollmentStore(private val root: File) {
    constructor(context: Context) : this(File(context.noBackupFilesDir, "voiceprint"))

    private val infoFile get() = File(root, "enrollment.properties")

    private val pendingDir get() = File(root, "pending")

    private fun phraseFile(dir: File, index: Int) = File(dir, "phrase-$index.pcm")

    /** Phrases recorded during an enrollment are pending until [complete]; an abandoned re-enrollment keeps the old profile. */
    fun savePendingPhrase(index: Int, pcm: ShortArray) {
        pendingDir.mkdirs()
        val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { bytes.putShort(it) }
        writeAtomically(phraseFile(pendingDir, index)) { it.write(bytes.array()) }
    }

    fun loadPendingPhrase(index: Int): ShortArray? = readPcm(phraseFile(pendingDir, index))

    fun discardPending() {
        pendingDir.deleteRecursively()
    }

    fun loadPhrase(index: Int): ShortArray? = readPcm(phraseFile(root, index))

    fun loadPhrases(count: Int): List<ShortArray>? =
        (0 until count).map { loadPhrase(it) ?: return null }

    /** Commits the pending phrases. Templates built from an earlier enrollment are discarded. */
    fun complete(info: EnrollmentInfo) {
        require((0 until info.phraseCount).all { phraseFile(pendingDir, it).isFile }) { "Missing pending phrases" }
        root.mkdirs()
        infoFile.delete()
        templateFiles().forEach { it.delete() }
        root.listFiles { f -> f.name.startsWith("phrase-") }?.forEach { it.delete() }
        for (i in 0 until info.phraseCount) {
            check(phraseFile(pendingDir, i).renameTo(phraseFile(root, i))) { "Could not commit phrase $i" }
        }
        pendingDir.deleteRecursively()
        val props = Properties().apply {
            setProperty("version", "1")
            setProperty("languageTag", info.languageTag)
            setProperty("inputRoute", info.inputRoute)
            setProperty("phraseCount", info.phraseCount.toString())
            setProperty("consentAtMs", info.consentAtMs.toString())
            setProperty("completedAtMs", info.completedAtMs.toString())
        }
        writeAtomically(infoFile) { props.store(it, null) }
    }

    fun info(): EnrollmentInfo? {
        val file = infoFile.takeIf { it.isFile } ?: return null
        val props = Properties().apply { file.inputStream().use { load(it) } }
        return runCatching {
            EnrollmentInfo(
                languageTag = props.getProperty("languageTag", ""),
                inputRoute = props.getProperty("inputRoute", ""),
                phraseCount = props.getProperty("phraseCount").toInt(),
                consentAtMs = props.getProperty("consentAtMs").toLong(),
                completedAtMs = props.getProperty("completedAtMs").toLong(),
            )
        }.getOrNull()?.takeIf { info -> (0 until info.phraseCount).all { phraseFile(root, it).isFile } }
    }

    fun isEnrolled(): Boolean = info() != null

    fun saveTemplate(template: SpeakerTemplate) {
        root.mkdirs()
        writeAtomically(templateFile(template.modelId)) { out ->
            DataOutputStream(out).apply {
                writeInt(TEMPLATE_FORMAT)
                writeUTF(template.modelId)
                writeInt(template.windows)
                writeFloat(template.minLeaveOneOut)
                writeLong(template.createdAtMs)
                writeInt(template.vector.size)
                template.vector.forEach { writeFloat(it) }
                flush()
            }
        }
    }

    fun loadTemplate(modelId: String): SpeakerTemplate? {
        val file = templateFile(modelId).takeIf { it.isFile } ?: return null
        return runCatching {
            DataInputStream(file.inputStream().buffered()).use { input ->
                check(input.readInt() == TEMPLATE_FORMAT)
                val storedId = input.readUTF()
                check(storedId == modelId)
                val windows = input.readInt()
                val minLeaveOneOut = input.readFloat()
                val createdAtMs = input.readLong()
                val vector = FloatArray(input.readInt()) { input.readFloat() }
                SpeakerTemplate(storedId, vector, windows, minLeaveOneOut, createdAtMs)
            }
        }.getOrNull()
    }

    /** Deletes the phrases, templates and observation log. */
    fun deleteAll() {
        root.deleteRecursively()
    }

    internal fun observationLog() = File(root, "observations.csv")

    private fun readPcm(file: File): ShortArray? {
        if (!file.isFile) return null
        val buffer = ByteBuffer.wrap(file.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        return ShortArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun templateFile(modelId: String): File {
        require(modelId.matches(Regex("[A-Za-z0-9._-]+"))) { "Invalid model id" }
        return File(root, "template-$modelId.bin")
    }

    private fun templateFiles(): List<File> =
        root.listFiles { f -> f.name.startsWith("template-") }?.toList().orEmpty()

    private fun writeAtomically(target: File, write: (java.io.OutputStream) -> Unit) {
        val partial = File(target.parentFile, "${target.name}.part")
        partial.outputStream().use(write)
        if (target.exists()) target.delete()
        check(partial.renameTo(target)) { "Could not write ${target.name}" }
    }

    private companion object {
        const val TEMPLATE_FORMAT = 1
    }
}
