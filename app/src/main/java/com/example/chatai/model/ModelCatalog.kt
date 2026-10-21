package com.example.chatai.model

import android.content.Context
import org.json.JSONObject

/**
 * One downloadable model. The catalog is a static asset so no network call is
 * needed to show what is available.
 */
data class ModelDescriptor(
    val id: String,
    val displayName: String,
    val fileName: String,
    val url: String,
    val mirrorUrl: String?,
    val sizeBytes: Long,
    val sha256: String,
    val parameters: String,
    val quantization: String,
    val minRamMb: Int,
    val recommendedNCtx: Int,
    val license: String,
    val description: String,
    /** Qwen3 models emit a reasoning block that must be hidden from the chat bubble. */
    val hasThinkingBlock: Boolean
) {
    val sizeMb: Long get() = sizeBytes / (1024 * 1024)
}

object ModelCatalog {

    @Volatile
    private var cached: List<ModelDescriptor>? = null

    fun load(context: Context): List<ModelDescriptor> =
        cached ?: synchronized(this) {
            cached ?: parse(context).also { cached = it }
        }

    fun find(context: Context, id: String): ModelDescriptor? =
        load(context).firstOrNull { it.id == id }

    private fun parse(context: Context): List<ModelDescriptor> {
        val text = context.assets.open("models.json").bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        val array = root.getJSONArray("models")
        return buildList {
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                add(
                    ModelDescriptor(
                        id = o.getString("id"),
                        displayName = o.getString("displayName"),
                        fileName = o.getString("fileName"),
                        url = o.getString("url"),
                        mirrorUrl = o.optString("mirrorUrl").takeIf { it.isNotBlank() },
                        sizeBytes = o.getLong("sizeBytes"),
                        sha256 = o.getString("sha256"),
                        parameters = o.getString("parameters"),
                        quantization = o.getString("quantization"),
                        minRamMb = o.getInt("minRamMb"),
                        recommendedNCtx = o.getInt("recommendedNCtx"),
                        license = o.getString("license"),
                        description = o.getString("description"),
                        hasThinkingBlock = o.optBoolean("hasThinkingBlock", false)
                    )
                )
            }
        }
    }
}
