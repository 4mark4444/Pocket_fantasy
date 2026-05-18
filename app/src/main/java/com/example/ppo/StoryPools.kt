package com.example.ppo

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.random.Random

object StoryPools {

    data class Entry(
        val background: String,
        val beginning:  String,
        val action:     String,
    )

    private val POOL_KEYS = listOf("ff", "mm", "fm", "mf")

    private var cached: Map<String, JSONArray>? = null

    fun load(context: Context) {
        if (cached != null) return
        cached = POOL_KEYS.associateWith { key ->
            val text = context.assets.open("prompt/$key.json")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONObject(text).getJSONArray("stories")
        }
    }

    private fun pools(): Map<String, JSONArray> =
        cached ?: error("StoryPools.load() must be called before any lookup")

    /**
     * Picks a random story for the given (me, ta) pair, then substitutes
     * placeholders against the two names. Blank `me` falls back to "我";
     * blank `ta` falls back to a gender-appropriate pronoun via
     * [Character.displayName] ("她" / "他" / "ta").
     */
    fun pickRandom(me: Character, ta: Character, random: Random): Entry {
        val candidates = candidatePools(me.gender, ta.gender)
        val poolKey = candidates.random(random)
        val stories = pools().getValue(poolKey)
        val raw = stories.getJSONObject(random.nextInt(stories.length()))
        return substitute(
            background = raw.getString("background"),
            beginning  = raw.getString("beginning"),
            action     = raw.getString("action"),
            meName     = me.name.ifBlank { "我" },
            taName     = ta.displayName(),
        )
    }

    private fun candidatePools(me: Gender, ta: Gender): List<String> = when {
        me is Gender.Custom && ta is Gender.Custom -> POOL_KEYS
        me is Gender.Custom -> when (ta) {
            is Gender.Female -> listOf("ff", "mf")   // pools where the partner (ta) is F
            is Gender.Male   -> listOf("mm", "fm")   // pools where the partner (ta) is M
            else             -> POOL_KEYS            // unreachable
        }
        ta is Gender.Custom -> when (me) {
            is Gender.Female -> listOf("ff", "fm")   // pools where the protagonist (me) is F
            is Gender.Male   -> listOf("mm", "mf")   // pools where the protagonist (me) is M
            else             -> POOL_KEYS            // unreachable
        }
        me is Gender.Female && ta is Gender.Female -> listOf("ff")
        me is Gender.Male   && ta is Gender.Male   -> listOf("mm")
        me is Gender.Female && ta is Gender.Male   -> listOf("fm")
        me is Gender.Male   && ta is Gender.Female -> listOf("mf")
        else -> error("unreachable: me=$me ta=$ta")
    }

    private fun substitute(
        background: String,
        beginning:  String,
        action:     String,
        meName:     String,
        taName:     String,
    ): Entry {
        val map = mapOf("{name}" to meName, "{p_name}" to taName)
        fun apply(s: String): String =
            map.entries.fold(s) { acc, (k, v) -> acc.replace(k, v) }
        return Entry(
            background = apply(background),
            beginning  = apply(beginning),
            action     = apply(action),
        )
    }
}
