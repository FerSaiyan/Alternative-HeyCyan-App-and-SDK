package com.fersaiyan.cyanbridge.localmodels.storage

import android.content.Context
import java.io.File

/** Projectors belong to a model; importing one must not select it as a chat GGUF. */
object LocalModelProjectorStore {
    fun get(context: Context, model: InstalledLocalModel): String? =
        context.getSharedPreferences("local_model_projectors", Context.MODE_PRIVATE)
            .getString(model.id, null)?.takeIf { File(it).isFile }

    fun attach(context: Context, model: InstalledLocalModel, file: File) {
        require(file.isFile && file.extension.equals("gguf", true)) { "Projector must be a GGUF file" }
        val prefs = context.getSharedPreferences("local_model_projectors", Context.MODE_PRIVATE)
        val old = prefs.getString(model.id, null)
        prefs.edit().putString(model.id, file.absolutePath).apply()
        if (old != null && old != file.absolutePath) File(old).delete()
    }

    fun remove(context: Context, modelId: String) {
        val prefs = context.getSharedPreferences("local_model_projectors", Context.MODE_PRIVATE)
        prefs.getString(modelId, null)?.let { File(it).delete() }
        prefs.edit().remove(modelId).apply()
    }
}
