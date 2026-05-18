package com.example.ppo

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class MemoryRecord(
    val id:           Int,
    val character:    CharacterPair,
    val systemPrompt: String,
    val background:   String,
    val beginning:    String,
    val inputAction:  String,
    val story:        String,
    /**
     * Size 0 (seed stub before first generation) or size 4 (post-generation).
     * options[0..2] are model-generated; options[3] is the user's fourth-option
     * (custom-input) text — may be "".
     */
    val options:      List<String>,
)

object MemoryReader {

    private const val TAG = "MemoryReader"

    fun read(file: File): List<MemoryRecord> {
        if (!file.exists()) return emptyList()
        return try {
            val array = JSONArray(file.readText())
            val out = ArrayList<MemoryRecord>(array.length())
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                val opts = o.getJSONArray("options")
                val optList = ArrayList<String>(opts.length() + 1)
                for (j in 0 until opts.length()) optList.add(opts.getString(j))
                // Legacy migration: pre-existing records stored the user's fourth-option
                // text in a sibling `draft_input` field with options size 3. Promote it
                // to options[3] so newer code can treat the array uniformly. Re-written
                // without `draft_input` on the next upsert/option-4 update.
                if (optList.size == 3) {
                    optList.add(o.optString("draft_input", ""))
                }
                out.add(
                    MemoryRecord(
                        id           = o.getInt("id"),
                        character    = CharacterPair.fromJson(o.getJSONObject("character").toString()),
                        systemPrompt = o.getString("system_prompt"),
                        background   = o.getString("background"),
                        beginning    = o.getString("beginning"),
                        inputAction  = o.getString("input_action"),
                        story        = o.getString("story"),
                        options      = optList,
                    )
                )
            }
            out
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse ${file.name}", e)
            emptyList()
        }
    }

    /** Replace the file with a single seed-stub record (id=0, empty story/options). */
    fun writeSeedStub(
        file:         File,
        pair:         CharacterPair,
        systemPrompt: String,
        background:   String,
        beginning:    String,
        action:       String,
    ) {
        val record = JSONObject().apply {
            put("id",            0)
            put("character",     JSONObject(pair.toJson()))
            put("system_prompt", systemPrompt)
            put("background",    background)
            put("beginning",     beginning)
            put("input_action",  action)
            put("story",         "")
            put("options",       JSONArray())
        }
        val array = JSONArray().apply { put(record) }
        file.writeText(array.toString(2))
        Log.i(TAG, "seed stub written: ${file.name}")
    }

    /**
     * Insert or update a record by id. If a record with the given id exists, its
     * story / options / system_prompt / background / beginning / input_action are
     * overwritten in place. Otherwise a new record with this id is appended.
     *
     * Callers pass `options` as size 4 once generation completes — options[0..2] are
     * model-generated; options[3] is the user's fourth-option text (may be "").
     * Any legacy `draft_input` field on the record is dropped (migrated on touch).
     */
    fun upsertMemory(
        file:         File,
        recordId:     Int,
        pair:         CharacterPair,
        systemPrompt: String,
        background:   String,
        beginning:    String,
        action:       String,
        story:        String,
        options:      List<String>,
    ) {
        val array = if (file.exists()) JSONArray(file.readText()) else JSONArray()
        var existingIdx = -1
        for (i in 0 until array.length()) {
            if (array.getJSONObject(i).getInt("id") == recordId) {
                existingIdx = i
                break
            }
        }
        val isUpdate = existingIdx >= 0
        val record = if (isUpdate) array.getJSONObject(existingIdx) else JSONObject()
        record.put("id",            recordId)
        record.put("character",     JSONObject(pair.toJson()))
        record.put("system_prompt", systemPrompt)
        record.put("background",    background)
        record.put("beginning",     beginning)
        record.put("input_action",  action)
        record.put("story",         story)
        record.put("options",       JSONArray(options))
        record.remove("draft_input")
        if (!isUpdate) array.put(record)
        file.writeText(array.toString(2))
        Log.i(TAG, "upsert ${file.name} id=$recordId " + if (isUpdate) "(updated)" else "(appended)")
    }

    /**
     * Overwrite the `background` field of one record. No-op if the file or
     * record id is missing. Used by the prompt-editor "model memory" section
     * so the next generation sees the edited 【故事背景】 block.
     */
    fun updateBackground(file: File, recordId: Int, bg: String) {
        if (!file.exists()) return
        val array = JSONArray(file.readText())
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            if (o.getInt("id") == recordId) {
                o.put("background", bg)
                file.writeText(array.toString(2))
                return
            }
        }
    }

    /**
     * Set the fourth-option text (`options[3]`) of one record. No-op if the file or
     * record id is missing. If the record's existing options array is shorter than 4,
     * pad with "" up to index 3 first (defensive — should not happen post-generation).
     * Drops any legacy `draft_input` field on the record (migrate-on-touch).
     */
    fun updateOption4(file: File, recordId: Int, text: String) {
        if (!file.exists()) return
        val array = JSONArray(file.readText())
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            if (o.getInt("id") == recordId) {
                val opts = o.getJSONArray("options")
                while (opts.length() < 3) opts.put("")
                if (opts.length() < 4) opts.put(text) else opts.put(3, text)
                o.remove("draft_input")
                file.writeText(array.toString(2))
                return
            }
        }
    }
}
