package com.fersaiyan.cyanbridge.localmodels.storage

import android.content.Context
import com.fersaiyan.cyanbridge.shared.prefs.AgentPrefs.LocalModelProjectors
import com.fersaiyan.cyanbridge.shared.prefs.get
import com.fersaiyan.cyanbridge.shared.prefs.remove
import com.fersaiyan.cyanbridge.shared.prefs.set
import java.io.File

/** Projectors belong to a model; importing one must not select it as a chat GGUF. */
object LocalModelProjectorStore {
    fun get(context: Context, model: InstalledLocalModel): String? =
        LocalModelProjectors.pathByModelId[model.id][context]?.takeIf { File(it).isFile }

    fun attach(context: Context, model: InstalledLocalModel, file: File) {
        require(file.isFile && file.extension.equals("gguf", true)) { "Projector must be a GGUF file" }
        val projector = LocalModelProjectors.pathByModelId[model.id]
        val old = projector[context]
        projector[context] = file.absolutePath
        if (old != null && old != file.absolutePath) File(old).delete()
    }

    fun remove(context: Context, modelId: String) {
        val projector = LocalModelProjectors.pathByModelId[modelId]
        projector[context]?.let { File(it).delete() }
        projector.remove(context)
    }
}
