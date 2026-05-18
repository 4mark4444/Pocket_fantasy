package com.example.ppo

import org.json.JSONObject

val FEMALE_PERSONALITIES = listOf(
    "温柔", "元气", "开朗", "高冷", "害羞", "傲娇", "腹黑", "认真", "天然呆", "病娇",
)

val MALE_PERSONALITIES = listOf(
    "温柔", "忠犬", "热血", "高冷", "傲娇", "腹黑", "霸道", "成熟", "害羞", "病娇",
)

val UNION_PERSONALITIES: List<String> =
    (FEMALE_PERSONALITIES + MALE_PERSONALITIES.filterNot { it in FEMALE_PERSONALITIES })

sealed class Gender {
    object Female : Gender()
    object Male   : Gender()
    data class Custom(val name: String, val description: String) : Gender()
}

sealed class Personality {
    data class Preset(val label: String) : Personality()
    data class Custom(val name: String, val description: String) : Personality()
}

data class Character(
    val name:        String,
    val gender:      Gender,
    val personality: Personality,
    val sizeBar:     Float,          // 体型/身高 — slider in −1..+1

    val figure:      Float  = 0f,    // 身型: −1 纤瘦 ↔ +1 丰腴
    val bust:        Float  = 0f,    // 胸围: −1 平坦 ↔ +1 丰满
    val hips:        Float  = 0f,    // 臀型: −1 纤巧 ↔ +1 软弹

    val sensLips:    Float = 0f,     // sensitivity sliders, 0..1
    val sensBust:    Float = 0f,
    val sensHips:    Float = 0f,
    val sensIntim:   Float = 0f,

    // 生殖器 — auto-derived from gender for Female/Male; user-set for Custom.
    val hasMaleGenital:   Boolean = false,
    val hasFemaleGenital: Boolean = false,
    val genitalSize:      Float = 0f,  // bipolar (male). −1 small ↔ +1 large
    val genitalTight:     Float = 0f,  // bipolar (female). −1 loose ↔ +1 tight
)

fun Character.displayName(): String = name.ifBlank {
    when (gender) {
        is Gender.Female -> "她"
        is Gender.Male   -> "他"
        is Gender.Custom -> "ta"
    }
}

data class CharacterPair(val me: Character, val ta: Character) {
    fun toJson(): String = JSONObject().apply {
        put("me", characterToJson(me))
        put("ta", characterToJson(ta))
    }.toString()

    companion object {
        fun fromJson(s: String): CharacterPair {
            val o = JSONObject(s)
            return CharacterPair(
                me = characterFromJson(o.getJSONObject("me")),
                ta = characterFromJson(o.getJSONObject("ta")),
            )
        }
    }
}

private fun characterToJson(c: Character): JSONObject = JSONObject().apply {
    put("name",        c.name)
    put("gender",      genderToJson(c.gender))
    put("personality", personalityToJson(c.personality))
    put("sizeBar",     c.sizeBar.toDouble())

    put("figure", c.figure.toDouble())
    put("bust",   c.bust.toDouble())
    put("hips",   c.hips.toDouble())

    put("sensLips",  c.sensLips.toDouble())
    put("sensBust",  c.sensBust.toDouble())
    put("sensHips",  c.sensHips.toDouble())
    put("sensIntim", c.sensIntim.toDouble())

    put("hasMaleGenital",   c.hasMaleGenital)
    put("hasFemaleGenital", c.hasFemaleGenital)
    put("genitalSize",      c.genitalSize.toDouble())
    put("genitalTight",     c.genitalTight.toDouble())
}

private fun characterFromJson(o: JSONObject): Character = Character(
    name        = o.getString("name"),
    gender      = genderFromJson(o.getJSONObject("gender")),
    personality = personalityFromJson(o.getJSONObject("personality")),
    sizeBar     = o.getDouble("sizeBar").toFloat(),

    figure = o.optDouble("figure", 0.0).toFloat(),
    bust   = o.optDouble("bust",   0.0).toFloat(),
    hips   = o.optDouble("hips",   0.0).toFloat(),

    sensLips  = o.optDouble("sensLips",  0.0).toFloat(),
    sensBust  = o.optDouble("sensBust",  0.0).toFloat(),
    sensHips  = o.optDouble("sensHips",  0.0).toFloat(),
    sensIntim = o.optDouble("sensIntim", 0.0).toFloat(),

    hasMaleGenital   = o.optBoolean("hasMaleGenital",   false),
    hasFemaleGenital = o.optBoolean("hasFemaleGenital", false),
    genitalSize      = o.optDouble ("genitalSize",      0.0).toFloat(),
    genitalTight     = o.optDouble ("genitalTight",     0.0).toFloat(),
)

private fun genderToJson(g: Gender): JSONObject = JSONObject().apply {
    when (g) {
        is Gender.Female -> put("type", "Female")
        is Gender.Male   -> put("type", "Male")
        is Gender.Custom -> {
            put("type",        "Custom")
            put("name",        g.name)
            put("description", g.description)
        }
    }
}

private fun genderFromJson(o: JSONObject): Gender = when (val t = o.getString("type")) {
    "Female" -> Gender.Female
    "Male"   -> Gender.Male
    "Custom" -> Gender.Custom(o.getString("name"), o.getString("description"))
    else     -> error("Unknown gender type: $t")
}

private fun personalityToJson(p: Personality): JSONObject = JSONObject().apply {
    when (p) {
        is Personality.Preset -> {
            put("type",  "Preset")
            put("label", p.label)
        }
        is Personality.Custom -> {
            put("type",        "Custom")
            put("name",        p.name)
            put("description", p.description)
        }
    }
}

private fun personalityFromJson(o: JSONObject): Personality = when (val t = o.getString("type")) {
    "Preset" -> Personality.Preset(o.getString("label"))
    "Custom" -> Personality.Custom(o.getString("name"), o.getString("description"))
    else     -> error("Unknown personality type: $t")
}
