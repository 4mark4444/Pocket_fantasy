package com.example.ppo

import android.content.Context
import org.json.JSONObject

object PromptTemplates {

    private const val ASSET = "prompt/character_prompts.json"

    private var cached: JSONObject? = null

    fun load(context: Context) {
        if (cached != null) return
        val text = context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        cached = JSONObject(text)
    }

    private fun root(): JSONObject =
        cached ?: error("PromptTemplates.load() must be called before any lookup")

    fun genderCombination(key: String): String =
        root().getJSONObject("gender_combinations").getString(key)

    fun customGenderClause(variant: String): String =
        root().getJSONObject("custom_gender_clause").getString(variant)

    fun personalityTemplate(variant: String, label: String): String? {
        val pers = root().getJSONObject("personalities")
        val obj  = pers.getJSONObject(variant)
        if (!obj.has(label)) return null
        return obj.getString(label)
    }

    fun customPersonalityClause(): String =
        root().getString("custom_personality_clause")

    /** Body slider lookup: field ∈ {size, figure, bust, hips}, dir ∈ {high, low}. */
    fun body(field: String, dir: String): String =
        root().getJSONObject("body").getJSONObject(field).getString(dir)

    /** Sensitivity slider lookup: field ∈ {lips, bust, hips, intim}. */
    fun sensitivity(field: String): String =
        root().getJSONObject("sensitivity").getString(field)

    /** Genital slider lookup: field ∈ {size, tight}, dir ∈ {high, low}. */
    fun genital(field: String, dir: String): String =
        root().getJSONObject("genital").getJSONObject(field).getString(dir)

    fun formatSpec(): String =
        root().getString("format_spec")
}
