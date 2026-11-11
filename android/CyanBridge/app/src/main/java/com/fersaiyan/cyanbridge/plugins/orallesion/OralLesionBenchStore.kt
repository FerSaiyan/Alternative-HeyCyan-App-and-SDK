package com.fersaiyan.cyanbridge.plugins.orallesion

import android.content.Context
import com.fersaiyan.cyanbridge.shared.plugins.orallesion.OralLesionBenchCase
import kotlinx.serialization.json.Json
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The bench log in app-private storage (`files/oral_lesion_bench/`): `cases.jsonl` with one row per
 * case, and each case's photo in `photos/`. Rows are replaced through a temporary file. Nothing
 * leaves the phone unless the user exports a zip and shares it.
 */
internal class OralLesionBenchStore(context: Context) {
    private val appContext = context.applicationContext
    private val dir = File(appContext.filesDir, "oral_lesion_bench")
    private val log = File(dir, "cases.jsonl")
    private val photos = File(dir, "photos")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun all(): List<OralLesionBenchCase> = synchronized(LOCK) { read() }

    /** Saves [case], replacing its row; on its first save the photo is copied next to the log. */
    fun save(case: OralLesionBenchCase, photoPath: String?): OralLesionBenchCase = synchronized(LOCK) {
        photos.mkdirs()
        var saved = case
        if (photoPath != null && case.photoFile.isEmpty()) {
            val source = File(photoPath)
            val ext = source.extension.lowercase().takeIf { it in setOf("jpg", "jpeg", "png", "webp") } ?: "jpg"
            val name = "${case.caseId}.$ext"
            source.copyTo(File(photos, name), overwrite = false)
            saved = case.copy(photoFile = "photos/$name")
        }
        val rows = readStrict().filterNot { it.caseId == saved.caseId } + saved
        write(rows)
        saved
    }

    fun delete(caseId: String) = synchronized(LOCK) {
        val rows = readStrict()
        rows.firstOrNull { it.caseId == caseId }?.photoFile?.takeIf { it.isNotEmpty() }?.let { File(dir, it).delete() }
        write(rows.filterNot { it.caseId == caseId })
    }

    /** A zip of the log and photos in the cache (shared through the FileProvider), or null when empty. */
    fun exportZip(): File? = synchronized(LOCK) {
        if (!log.isFile) return null
        val out = File(appContext.cacheDir, "oral_lesion_export").apply { mkdirs() }
        out.listFiles()?.forEach { it.delete() }
        val zip = File(out, "oral_lesion_bench_${System.currentTimeMillis()}.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { stream ->
            stream.putNextEntry(ZipEntry("cases.jsonl"))
            log.inputStream().use { it.copyTo(stream) }
            stream.closeEntry()
            photos.listFiles()?.sortedBy { it.name }?.forEach { photo ->
                stream.putNextEntry(ZipEntry("photos/${photo.name}"))
                photo.inputStream().use { it.copyTo(stream) }
                stream.closeEntry()
            }
        }
        zip
    }

    private fun read(): List<OralLesionBenchCase> =
        if (!log.isFile) emptyList() else log.readLines().filter { it.isNotBlank() }.mapNotNull {
            runCatching { json.decodeFromString(OralLesionBenchCase.serializer(), it) }.getOrNull()
        }

    /** Every row, or an error when one cannot be read: rewriting the log must not drop it. */
    private fun readStrict(): List<OralLesionBenchCase> =
        if (!log.isFile) emptyList() else log.readLines().filter { it.isNotBlank() }.map {
            runCatching { json.decodeFromString(OralLesionBenchCase.serializer(), it) }
                .getOrElse { error -> throw IllegalStateException("The bench log has a row this version cannot read; export it first", error) }
        }

    private fun write(rows: List<OralLesionBenchCase>) {
        dir.mkdirs()
        val temporary = File(dir, "cases.jsonl.tmp")
        temporary.writeText(rows.joinToString("") { json.encodeToString(OralLesionBenchCase.serializer(), it) + "\n" })
        check(temporary.renameTo(log)) { "Could not update the bench log" }
    }

    private companion object {
        val LOCK = Any()
    }
}
