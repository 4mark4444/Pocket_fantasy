package com.example.ppo

import kotlin.random.Random

/**
 * Probabilistic-span markup used by the prompt editor and the runtime renderer.
 *
 * In the stored template, each marked region looks like `⟦pN:content⟧` where N is one of
 * 25 / 50 / 75. The brackets are mathematical Unicode (U+27E6 / U+27E7) that should not
 * collide with normal Chinese narrative prose. At generation time [renderProbabilisticTemplate]
 * replaces each span with its content (probability N/100) or the empty string.
 */
internal val PROBABILITY_SPAN_REGEX = Regex("""⟦p(25|50|75):([\s\S]*?)⟧""")
const val PROMPT_OPEN_BRACKET  = "⟦"
const val PROMPT_CLOSE_BRACKET = "⟧"
val ALLOWED_PROBABILITIES = listOf(25, 50, 75)

/**
 * Roll every `⟦pN:…⟧` span in [template] and return the realized system-prompt body.
 * Text outside spans is emitted verbatim. Re-roll by calling again with a different draw
 * from [random].
 */
fun renderProbabilisticTemplate(template: String, random: Random): String =
    PROBABILITY_SPAN_REGEX.replace(template) { m ->
        val pct = m.groupValues[1].toInt()
        val content = m.groupValues[2]
        if (random.nextInt(100) < pct) content else ""
    }


object SystemPromptBuilder {

    /**
     * The full system prompt = roll([buildTemplate]) + a trailing newline + [PromptTemplates.formatSpec].
     * Used by the seed-stub writer and as the fallback at generation time when the novel has
     * no user-edited template. Each call re-rolls the probabilistic height-clause markers.
     */
    fun build(pair: CharacterPair, random: Random = Random.Default): String =
        renderProbabilisticTemplate(buildTemplate(pair), random) +
            "\n" + PromptTemplates.formatSpec()

    /**
     * The editable system-prompt template (everything **except** the format spec). Slider-backed
     * clauses (body / sensitivity) are emitted with `⟦pN:…⟧` probability markers determined by
     * each slider's magnitude:
     *   - `|v| ≥ 1.0`  → plain text (always appears, no marker)
     *   - `0.75 ≤ |v| < 1.0` → ⟦p75:…⟧
     *   - `0.50 ≤ |v| < 0.75` → ⟦p50:…⟧
     *   - `0.25 ≤ |v| < 0.50` → ⟦p25:…⟧
     *   - `|v| < 0.25` → omitted
     * Head clauses (hair / eye / hair length) are categorical chips and always appear when set.
     */
    fun buildTemplate(pair: CharacterPair): String {
        val parts = mutableListOf<String>()

        val meName = pair.me.name.ifBlank { "我" }
        val taName = pair.ta.displayName()

        // 1. Genre opener
        parts += PromptTemplates.genderCombination(genderComboKey(pair.me.gender, pair.ta.gender))

        // 2. Custom-gender description clause — surfaces Gender.Custom.name as {gender_type}
        val meCustom = pair.me.gender as? Gender.Custom
        val taCustom = pair.ta.gender as? Gender.Custom
        when {
            meCustom != null && taCustom != null -> {
                parts += PromptTemplates.customGenderClause("shared")
                    .replace("{me_name}",         meName)
                    .replace("{me_gender_type}",  meCustom.name)
                    .replace("{me_description}",  meCustom.description)
                    .replace("{ta_name}",         taName)
                    .replace("{ta_gender_type}",  taCustom.name)
                    .replace("{ta_description}",  taCustom.description)
            }
            meCustom != null -> {
                parts += PromptTemplates.customGenderClause("single")
                    .replace("{name}",         meName)
                    .replace("{gender_type}",  meCustom.name)
                    .replace("{description}",  meCustom.description)
            }
            taCustom != null -> {
                parts += PromptTemplates.customGenderClause("single")
                    .replace("{name}",         taName)
                    .replace("{gender_type}",  taCustom.name)
                    .replace("{description}",  taCustom.description)
            }
        }

        // 3. Body slider clauses for each character (size / figure / bust / hips)
        emitBodyClauses(pair.me, meName, parts)
        emitBodyClauses(pair.ta, taName, parts)

        // 4. Sensitivity slider clauses for each character (lips / bust / hips / intim)
        emitSensitivityClauses(pair.me, meName, parts)
        emitSensitivityClauses(pair.ta, taName, parts)

        // 5. Genital clauses for each character (auto for non-custom, user-set for custom)
        emitGenitalClauses(pair.me, meName, parts)
        emitGenitalClauses(pair.ta, taName, parts)

        // 6. Personality clause for each character
        parts += personalityClause(pair.me, meName)
        parts += personalityClause(pair.ta, taName)

        return parts.joinToString("\n")
    }

    private fun emitBodyClauses(c: Character, name: String, parts: MutableList<String>) {
        emitBipolar(c.sizeBar, name, parts) { dir -> PromptTemplates.body("size",   dir) }
        emitBipolar(c.figure,  name, parts) { dir -> PromptTemplates.body("figure", dir) }
        emitBipolar(c.bust,    name, parts) { dir -> PromptTemplates.body("bust",   dir) }
        emitBipolar(c.hips,    name, parts) { dir -> PromptTemplates.body("hips",   dir) }
    }

    private fun emitSensitivityClauses(c: Character, name: String, parts: MutableList<String>) {
        emitPositive(c.sensLips,  name, parts) { PromptTemplates.sensitivity("lips")  }
        emitPositive(c.sensBust,  name, parts) { PromptTemplates.sensitivity("bust")  }
        emitPositive(c.sensHips,  name, parts) { PromptTemplates.sensitivity("hips")  }
        emitPositive(c.sensIntim, name, parts) { PromptTemplates.sensitivity("intim") }
    }

    private fun emitGenitalClauses(c: Character, name: String, parts: MutableList<String>) {
        val (hasM, hasF) = effectiveGenitals(c)
        if (hasM) emitBipolar(c.genitalSize,  name, parts) { dir -> PromptTemplates.genital("size",  dir) }
        if (hasF) emitBipolar(c.genitalTight, name, parts) { dir -> PromptTemplates.genital("tight", dir) }
    }

    /** Custom gender uses the explicit flags; Female/Male auto-pick their matching set. */
    private fun effectiveGenitals(c: Character): Pair<Boolean, Boolean> = when (c.gender) {
        is Gender.Female -> false to true
        is Gender.Male   -> true to false
        is Gender.Custom -> c.hasMaleGenital to c.hasFemaleGenital
    }

    private fun emitBipolar(
        value: Float, name: String, parts: MutableList<String>, lookup: (String) -> String,
    ) {
        val b = bucket(value) ?: return
        val dir = if (value > 0f) "high" else "low"
        parts += wrap(lookup(dir).replace("{name}", name), b)
    }

    private fun emitPositive(
        value: Float, name: String, parts: MutableList<String>, lookup: () -> String,
    ) {
        if (value <= 0f) return
        val b = bucket(value) ?: return
        parts += wrap(lookup().replace("{name}", name), b)
    }

    private fun genderComboKey(a: Gender, b: Gender): String = when {
        a is Gender.Custom || b is Gender.Custom         -> "custom"
        a is Gender.Female && b is Gender.Female         -> "ff"
        a is Gender.Male   && b is Gender.Male           -> "mm"
        else                                              -> "mixed"
    }

    private fun personalityClause(c: Character, effectiveName: String): String =
        when (val p = c.personality) {
            is Personality.Preset -> {
                val primary = primaryVariant(c.gender)
                val tpl = PromptTemplates.personalityTemplate(primary, p.label)
                    ?: PromptTemplates.personalityTemplate(otherVariant(primary), p.label)
                    ?: error("Personality '${p.label}' not found in templates")
                tpl.replace("{name}", effectiveName)
            }
            is Personality.Custom -> {
                PromptTemplates.customPersonalityClause()
                    .replace("{name}",        effectiveName)
                    .replace("{trait_name}",  p.name)
                    .replace("{gender_word}", genderWord(c.gender))
                    .replace("{description}", p.description)
            }
        }

    private fun primaryVariant(g: Gender): String = when (g) {
        is Gender.Female -> "f"
        is Gender.Male   -> "m"
        is Gender.Custom -> "f"
    }

    private fun otherVariant(v: String): String = if (v == "f") "m" else "f"

    private fun genderWord(g: Gender): String = when (g) {
        is Gender.Female -> "女孩"
        is Gender.Male   -> "男生"
        is Gender.Custom -> g.name
    }

    /** sizeBar's absolute magnitude rounded down to {25,50,75,100}, or null when <25%. */
    private fun bucket(bar: Float): Int? {
        if (bar == 0f) return null
        val pct = (kotlin.math.abs(bar).coerceAtMost(1f) * 100).toInt()
        return roundDown(pct)
    }

    private fun roundDown(pct: Int): Int? = when {
        pct >= 100 -> 100
        pct >= 75  -> 75
        pct >= 50  -> 50
        pct >= 25  -> 25
        else       -> null
    }

    /** 100 → plain text; 25/50/75 → wrapped in `⟦pN:…⟧`. */
    private fun wrap(content: String, bucket: Int): String =
        if (bucket >= 100) content
        else "${PROMPT_OPEN_BRACKET}p$bucket:$content$PROMPT_CLOSE_BRACKET"
}
