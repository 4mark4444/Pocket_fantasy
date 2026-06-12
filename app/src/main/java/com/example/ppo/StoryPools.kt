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

    private const val PREFS    = "story_pool_history"
    private const val SEEN_KEY = "seen_scenarios"

    private var cached: Map<String, JSONArray>? = null
    private var prefs:  android.content.SharedPreferences? = null

    /**
     * Per-pool canonical identity of every story. Both role placeholders are
     * mapped to the same symbol and whitespace is stripped before hashing, so
     * the role-swapped copies that pad the pools (the A-pins-B vs B-pins-A
     * versions of the same scene) collapse to a single key. Picking either one
     * marks the *scenario* as seen — ~20% of the ff pool is mirror padding, and
     * without this the "no repeats" guarantee would still serve the same scene
     * twice in a row with the roles flipped.
     */
    private var canonical: Map<String, List<String>>? = null

    fun load(context: Context) {
        if (cached != null) return
        prefs = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        cached = POOL_KEYS.associateWith { key ->
            val text = context.assets.open("prompt/$key.json")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            JSONObject(text).getJSONArray("stories")
        }
        canonical = cached!!.mapValues { (_, stories) ->
            (0 until stories.length()).map { i ->
                val o = stories.getJSONObject(i)
                canonicalKey(
                    o.getString("background") + o.getString("beginning") + o.getString("action")
                )
            }
        }
    }

    private fun canonicalKey(s: String): String {
        val t = s
            .replace("{name}",   "\u0001")
            .replace("{p_name}", "\u0001")
            .replace(Regex("\\s+"), "")
        return Integer.toHexString(t.hashCode()) + ":" + t.length
    }

    private fun pools(): Map<String, JSONArray> =
        cached ?: error("StoryPools.load() must be called before any lookup")

    /**
     * Picks a story for the given (me, ta) pair without repeating recently used
     * scenarios. Seen scenario keys are persisted in SharedPreferences across
     * app restarts; once every scenario reachable from the candidate pools has
     * been served, the seen-set is reset for those pools and the cycle restarts.
     * Placeholders are then substituted against the two names. Blank `me` falls
     * back to "我"; blank `ta` falls back to a gender-appropriate pronoun via
     * [Character.displayName] ("她" / "他" / "ta").
     */
    fun pickRandom(me: Character, ta: Character, random: Random): Entry {
        val candidates = candidatePools(me.gender, ta.gender)
        val keys = canonical ?: error("StoryPools.load() must be called before any lookup")
        val seen = prefs?.getStringSet(SEEN_KEY, emptySet())?.toMutableSet() ?: mutableSetOf()

        // Every (pool, index) reachable for this gender pairing…
        val all = candidates.flatMap { pk -> keys.getValue(pk).indices.map { pk to it } }
        // …minus scenarios already served.
        var fresh = all.filter { (pk, i) -> keys.getValue(pk)[i] !in seen }
        if (fresh.isEmpty()) {
            // Cycle exhausted for this pairing — forget only these pools' keys so
            // other pairings keep their own progress, and start over.
            val reachable = all.map { (pk, i) -> keys.getValue(pk)[i] }.toSet()
            seen.removeAll(reachable)
            fresh = all
        }

        val (poolKey, idx) = fresh[random.nextInt(fresh.size)]
        seen.add(keys.getValue(poolKey)[idx])
        prefs?.edit()?.putStringSet(SEEN_KEY, HashSet(seen))?.apply()

        val raw = pools().getValue(poolKey).getJSONObject(idx)
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
