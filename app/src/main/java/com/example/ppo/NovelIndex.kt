package com.example.ppo

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.random.Random

object NovelIndex {

    data class Entry(
        val id:                   String,
        val memoryFile:           String,
        val name:                 String,
        val coverColorSeed:       Long,
        val createdAt:            Long,
        val pairJson:             String,
        val seedBg:               String,
        val seedBeg:              String,
        val seedAct:              String,
        /**
         * User-edited system-prompt template for this novel, or null if the user
         * has never opened the prompt editor for it (in which case generation falls
         * back to [SystemPromptBuilder.buildBody]). May contain probabilistic
         * markup of the form `⟦pN:…⟧` (N ∈ {25,50,75}); see [renderProbabilisticTemplate].
         * The format spec is always appended at generation time and is NOT stored here.
         */
        val systemPromptTemplate: String? = null,
        /**
         * User-edited sampling temperature for this novel, or null to use the
         * GameViewModel default (0.6f). Editing the slider back to 0.6 normalizes
         * to null on save so unmodified novels keep `novel_index.json` clean.
         */
        val temperature: Float? = null,
        /**
         * Absolute path to a user-picked cover image under `filesDir/covers/<id>`,
         * or null when the cover should render as the colored tile derived from
         * [coverColorSeed]. Set by [setCoverImage], cleared on novel delete.
         */
        val coverImagePath: String? = null,
    )

    private const val INDEX_FILE = "novel_index.json"

    private val entries = mutableStateListOf<Entry>()
    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val dir = context.getExternalFilesDir(null) ?: return
        val indexFile = File(dir, INDEX_FILE)
        if (indexFile.exists()) {
            val migrated = parse(dir, indexFile)
            if (migrated) write(context)
        } else {
            autoImport(dir)
            write(context)
        }
    }

    fun all(): SnapshotStateList<Entry> = entries

    fun find(id: String): Entry? = entries.firstOrNull { it.id == id }

    /**
     * Persist a new system-prompt template (or clear it when [template] is null).
     * Used by the prompt-editor modal in `MainActivity`.
     */
    fun updateTemplate(context: Context, id: String, template: String?) {
        val idx = entries.indexOfFirst { it.id == id }
        if (idx == -1) return
        entries[idx] = entries[idx].copy(systemPromptTemplate = template)
        write(context)
    }

    /**
     * Persist a new sampling temperature (or clear it when [temp] is null, which
     * makes the next generation fall back to the GameViewModel default).
     */
    fun updateTemperature(context: Context, id: String, temp: Float?) {
        val idx = entries.indexOfFirst { it.id == id }
        if (idx == -1) return
        entries[idx] = entries[idx].copy(temperature = temp)
        write(context)
    }

    fun create(
        context: Context,
        pair:    CharacterPair,
        bg:      String,
        beg:     String,
        act:     String,
    ): Entry {
        val now = System.currentTimeMillis()
        val entry = Entry(
            id             = UUID.randomUUID().toString(),
            memoryFile     = "memory_$now.json",
            name           = defaultName(pair),
            coverColorSeed = Random.nextLong(),
            createdAt      = now,
            pairJson       = pair.toJson(),
            seedBg         = bg,
            seedBeg        = beg,
            seedAct        = act,
        )
        entries.add(entry)
        write(context)
        return entry
    }

    fun rename(context: Context, id: String, newName: String) {
        val idx = entries.indexOfFirst { it.id == id }
        if (idx == -1) return
        entries[idx] = entries[idx].copy(name = newName)
        write(context)
    }

    fun rerollCover(context: Context, id: String) {
        val idx = entries.indexOfFirst { it.id == id }
        if (idx == -1) return
        entries[idx] = entries[idx].copy(coverColorSeed = Random.nextLong())
        write(context)
    }

    /**
     * Persist a path to a user-picked cover image (or clear it when [path] is null).
     * The file should already exist at [path]; this just records the pointer and saves
     * the index. Used by the photo-picker in `LandingActivity`.
     */
    fun setCoverImage(context: Context, id: String, path: String?) {
        val idx = entries.indexOfFirst { it.id == id }
        if (idx == -1) return
        entries[idx] = entries[idx].copy(coverImagePath = path)
        write(context)
    }

    fun delete(context: Context, id: String) {
        val idx = entries.indexOfFirst { it.id == id }
        if (idx == -1) return
        val entry = entries[idx]
        entries.removeAt(idx)
        write(context)
        val dir = context.getExternalFilesDir(null) ?: return
        val file = File(dir, entry.memoryFile)
        if (file.exists()) file.delete()
        val cover = File(context.filesDir, "covers/${entry.id}")
        if (cover.exists()) cover.delete()
    }

    fun reorder(context: Context, fromIdx: Int, toIdx: Int) {
        if (fromIdx == toIdx) return
        if (fromIdx !in entries.indices || toIdx !in entries.indices) return
        val item = entries.removeAt(fromIdx)
        entries.add(toIdx, item)
        write(context)
    }

    /** Returns true if any legacy entry was migrated, signalling caller to persist. */
    private fun parse(dir: File, file: File): Boolean {
        var migrated = false
        try {
            val o = JSONObject(file.readText())
            val arr = o.getJSONArray("novels")
            entries.clear()
            for (i in 0 until arr.length()) {
                val n = arr.getJSONObject(i)
                val memoryFile = n.getString("memory_file")
                var pairJson = n.optString("pair_json", "")
                var seedBg   = n.optString("seed_bg",   "")
                var seedBeg  = n.optString("seed_beg",  "")
                var seedAct  = n.optString("seed_act",  "")
                if (pairJson.isEmpty()) {
                    val first = MemoryReader.read(File(dir, memoryFile)).firstOrNull()
                    if (first != null) {
                        pairJson = first.character.toJson()
                        seedBg   = first.background
                        seedBeg  = first.beginning
                        seedAct  = first.inputAction
                        migrated = true
                        Log.i("NovelIndex", "Migrated legacy entry from $memoryFile")
                    }
                }
                val templateRaw = n.optString("system_prompt_template", "")
                val tempRaw     = n.optDouble("temperature", Double.NaN)
                val coverRaw    = n.optString("cover_image_path", "")
                entries.add(
                    Entry(
                        id                   = n.getString("id"),
                        memoryFile           = memoryFile,
                        name                 = n.getString("name"),
                        coverColorSeed       = n.getLong("cover_color_seed"),
                        createdAt            = n.getLong("created_at"),
                        pairJson             = pairJson,
                        seedBg               = seedBg,
                        seedBeg              = seedBeg,
                        seedAct              = seedAct,
                        systemPromptTemplate = if (templateRaw.isEmpty()) null else templateRaw,
                        temperature          = if (tempRaw.isNaN()) null else tempRaw.toFloat(),
                        coverImagePath       = if (coverRaw.isEmpty()) null else coverRaw,
                    )
                )
            }
        } catch (e: Exception) {
            Log.w("NovelIndex", "Failed to parse $INDEX_FILE", e)
        }
        return migrated
    }

    private fun autoImport(dir: File) {
        val files = dir.listFiles { f ->
            f.isFile && f.name.startsWith("memory_") && f.name.endsWith(".json")
        } ?: return
        for (f in files.sortedBy { it.name }) {
            val records = MemoryReader.read(f)
            if (records.isEmpty()) {
                Log.w("NovelIndex", "Skipping empty/unparseable ${f.name}")
                continue
            }
            val first = records[0]
            entries.add(
                Entry(
                    id             = UUID.randomUUID().toString(),
                    memoryFile     = f.name,
                    name           = defaultName(first.character),
                    coverColorSeed = Random.nextLong(),
                    createdAt      = f.lastModified(),
                    pairJson       = first.character.toJson(),
                    seedBg         = first.background,
                    seedBeg        = first.beginning,
                    seedAct        = first.inputAction,
                )
            )
            Log.i("NovelIndex", "Auto-imported ${f.name} as ${entries.last().name}")
        }
    }

    private fun write(context: Context) {
        val dir = context.getExternalFilesDir(null) ?: return
        val arr = JSONArray()
        for (e in entries) {
            arr.put(JSONObject().apply {
                put("id",               e.id)
                put("memory_file",      e.memoryFile)
                put("name",             e.name)
                put("cover_color_seed", e.coverColorSeed)
                put("created_at",       e.createdAt)
                put("pair_json",        e.pairJson)
                put("seed_bg",          e.seedBg)
                put("seed_beg",         e.seedBeg)
                put("seed_act",         e.seedAct)
                e.systemPromptTemplate?.let { put("system_prompt_template", it) }
                e.temperature?.let { put("temperature", it.toDouble()) }
                e.coverImagePath?.let { put("cover_image_path", it) }
            })
        }
        val out = JSONObject().apply { put("novels", arr) }
        File(dir, INDEX_FILE).writeText(out.toString(2))
    }

    private fun defaultName(pair: CharacterPair): String {
        val me = pair.me.name.ifBlank { "我" }
        val ta = pair.ta.displayName()
        return "$me & $ta"
    }
}
