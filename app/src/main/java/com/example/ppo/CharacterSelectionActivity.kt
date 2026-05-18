package com.example.ppo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.example.ppo.ui.theme.PPOTheme
import java.io.File
import kotlin.random.Random

class CharacterSelectionActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PromptTemplates.load(applicationContext)
        StoryPools.load(applicationContext)
        NovelIndex.load(applicationContext)

        setContent {
            PPOTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    CharacterSelectionScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        onSubmit = ::launchMain,
                        onBack   = ::finish,
                    )
                }
            }
        }
    }

    private fun launchMain(pair: CharacterPair, bg: String, beg: String, act: String) {
        val entry = NovelIndex.create(applicationContext, pair, bg, beg, act)
        val dir = getExternalFilesDir(null)
            ?: error("getExternalFilesDir returned null")
        val memoryFile = File(dir, entry.memoryFile)
        val systemPrompt = SystemPromptBuilder.build(pair, Random.Default)
        MemoryReader.writeSeedStub(memoryFile, pair, systemPrompt, bg, beg, act)

        val intent = Intent(this, MainActivity::class.java)
            .putExtra(MEMORY_FILE_KEY, entry.memoryFile)
            .putExtra(NOVEL_ID_KEY,    entry.id)
        startActivity(intent)
        finish()
    }
}

// ── Per-stage form state ─────────────────────────────────────────────────────
private sealed class GenderChoice {
    object Female : GenderChoice()
    object Male   : GenderChoice()
    object Custom : GenderChoice()
}

private sealed class PersonalityChoice {
    data class Preset(val label: String) : PersonalityChoice()
    object Custom : PersonalityChoice()
}

private fun personalityListFor(g: GenderChoice): List<String> = when (g) {
    GenderChoice.Female -> FEMALE_PERSONALITIES
    GenderChoice.Male   -> MALE_PERSONALITIES
    GenderChoice.Custom -> UNION_PERSONALITIES
}

private fun personalityVariantFor(g: GenderChoice): String = when (g) {
    GenderChoice.Male -> "m"
    else              -> "f"
}

private fun taPronounFor(g: GenderChoice): String = when (g) {
    GenderChoice.Female -> "她"
    GenderChoice.Male   -> "他"
    GenderChoice.Custom -> "ta"
}

private class CharacterFormState {
    var name                         by mutableStateOf("")
    var genderChoice                 by mutableStateOf<GenderChoice>(GenderChoice.Female)
    var customGenderName             by mutableStateOf("")
    var customGenderDescription      by mutableStateOf("")
    var personalityChoice            by mutableStateOf<PersonalityChoice>(
        PersonalityChoice.Preset(FEMALE_PERSONALITIES.first())
    )
    var customPersonalityName        by mutableStateOf("")
    var customPersonalityDescription by mutableStateOf("")

    // 身体
    var sizeBar  by mutableStateOf(0f)
    var figure   by mutableStateOf(0f)
    var bust     by mutableStateOf(0f)
    var hips     by mutableStateOf(0f)

    // 敏感度
    var sensLips  by mutableStateOf(0f)
    var sensBust  by mutableStateOf(0f)
    var sensHips  by mutableStateOf(0f)
    var sensIntim by mutableStateOf(0f)

    // 生殖器 — custom genders pick freely; Female/Male resolved at toCharacter() time
    var customGenitalMale   by mutableStateOf(false)
    var customGenitalFemale by mutableStateOf(false)
    var genitalSize         by mutableStateOf(0f)  // bipolar male
    var genitalTight        by mutableStateOf(0f)  // bipolar female

    /** Switch gender and reset personality to the first preset of the new list. */
    fun setGender(g: GenderChoice) {
        genderChoice = g
        personalityChoice = PersonalityChoice.Preset(personalityListFor(g).first())
    }

    fun toCharacter(): Character? {
        val gender: Gender = when (genderChoice) {
            GenderChoice.Female -> Gender.Female
            GenderChoice.Male   -> Gender.Male
            GenderChoice.Custom -> {
                if (customGenderName.isBlank() || customGenderDescription.isBlank()) return null
                Gender.Custom(customGenderName.trim(), customGenderDescription.trim())
            }
        }
        val personality: Personality = when (val pc = personalityChoice) {
            PersonalityChoice.Custom -> {
                if (customPersonalityName.isBlank() || customPersonalityDescription.isBlank()) return null
                Personality.Custom(customPersonalityName.trim(), customPersonalityDescription.trim())
            }
            is PersonalityChoice.Preset -> Personality.Preset(pc.label)
        }
        val (hasM, hasF) = when (genderChoice) {
            GenderChoice.Female -> false to true
            GenderChoice.Male   -> true to false
            GenderChoice.Custom -> customGenitalMale to customGenitalFemale
        }
        return Character(
            name        = name.trim(),
            gender      = gender,
            personality = personality,
            sizeBar     = sizeBar,

            figure = figure,
            bust   = bust,
            hips   = hips,

            sensLips  = sensLips,
            sensBust  = sensBust,
            sensHips  = sensHips,
            sensIntim = sensIntim,

            hasMaleGenital   = hasM,
            hasFemaleGenital = hasF,
            genitalSize      = if (hasM) genitalSize  else 0f,
            genitalTight     = if (hasF) genitalTight else 0f,
        )
    }
}

// ── Top-level screen — three-page flow ───────────────────────────────────────
@Composable
private fun CharacterSelectionScreen(
    modifier: Modifier = Modifier,
    onSubmit: (CharacterPair, String, String, String) -> Unit,
    onBack:   () -> Unit,
) {
    val meState = remember { CharacterFormState() }
    val taState = remember { CharacterFormState() }
    var page    by remember { mutableStateOf(0) }   // 0 = me, 1 = ta, 2 = story

    // Page-2 state — survives back-and-forth across pages
    var rolled    by remember { mutableStateOf<StoryPools.Entry?>(null) }
    var bgBuffer  by remember { mutableStateOf("") }
    var begBuffer by remember { mutableStateOf("") }
    var actBuffer by remember { mutableStateOf("") }
    var showRerollConfirm by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()
    LaunchedEffect(page) { scrollState.scrollTo(0) }

    Column(
        modifier = modifier
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        when (page) {
            0 -> {
                CharacterFormSection(title = "我是谁？", defaultName = "我", state = meState)
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick  = onBack,
                        modifier = Modifier.weight(1f),
                    ) { Text("返回") }
                    Button(
                        onClick  = { page = 1 },
                        enabled  = meState.toCharacter() != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("下一步") }
                }
            }
            1 -> {
                val taPronoun = taPronounFor(taState.genderChoice)
                CharacterFormSection(title = "${taPronoun}是谁？", defaultName = taPronoun, state = taState)
                Row(
                    modifier              = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OutlinedButton(
                        onClick  = { page = 0 },
                        modifier = Modifier.weight(1f),
                    ) { Text("返回") }
                    val me = meState.toCharacter()
                    val ta = taState.toCharacter()
                    Button(
                        onClick  = {
                            if (me != null && ta != null) page = 2
                        },
                        enabled  = me != null && ta != null,
                        modifier = Modifier.weight(1f),
                    ) { Text("下一步") }
                }
            }
            else -> {
                val me = meState.toCharacter()
                val ta = taState.toCharacter()

                // First-time roll on entering page 2.
                LaunchedEffect(Unit) {
                    if (rolled == null && me != null && ta != null) {
                        rolled = StoryPools.pickRandom(me, ta, Random.Default)
                    }
                }

                val entry = rolled
                if (entry != null && me != null && ta != null) {
                    val displayName = me.name.ifBlank { "我" }

                    TutorialBanner(
                        text    = "点按字段输入自己的内容；长按字段会预填模板，便于修改。",
                        prefKey = TUTORIAL_CHARACTER_STORY,
                    )

                    Text("${displayName}的故事发生在",
                         style = MaterialTheme.typography.labelLarge)
                    StoryInputField(
                        template       = entry.background,
                        buffer         = bgBuffer,
                        onBufferChange = { bgBuffer = it },
                    )

                    Text("${displayName}的故事开始于",
                         style = MaterialTheme.typography.labelLarge)
                    StoryInputField(
                        template       = entry.beginning,
                        buffer         = begBuffer,
                        onBufferChange = { begBuffer = it },
                    )

                    Text("此时${displayName}只想",
                         style = MaterialTheme.typography.labelLarge)
                    StoryInputField(
                        template       = entry.action,
                        buffer         = actBuffer,
                        onBufferChange = { actBuffer = it },
                    )

                    val anyEdited = isEdited(bgBuffer,  entry.background) ||
                                    isEdited(begBuffer, entry.beginning)  ||
                                    isEdited(actBuffer, entry.action)

                    fun reroll() {
                        rolled    = StoryPools.pickRandom(me, ta, Random.Default)
                        bgBuffer  = ""
                        begBuffer = ""
                        actBuffer = ""
                    }

                    Row(
                        modifier              = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OutlinedButton(
                            onClick  = { page = 1 },
                            modifier = Modifier.weight(1f),
                        ) { Text("返回") }
                        OutlinedButton(
                            onClick  = {
                                if (anyEdited) showRerollConfirm = true
                                else reroll()
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("再随机") }
                        Button(
                            onClick  = {
                                val bg  = bgBuffer.ifEmpty  { entry.background }
                                val beg = begBuffer.ifEmpty { entry.beginning  }
                                val act = actBuffer.ifEmpty { entry.action     }
                                onSubmit(CharacterPair(me, ta), bg, beg, act)
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("开始") }
                    }

                    if (showRerollConfirm) {
                        AlertDialog(
                            onDismissRequest = { showRerollConfirm = false },
                            title            = { Text("覆盖编辑内容？") },
                            text             = { Text("再随机一个故事会丢弃你已编辑的内容。") },
                            confirmButton    = {
                                TextButton(onClick = {
                                    showRerollConfirm = false
                                    reroll()
                                }) { Text("确定") }
                            },
                            dismissButton    = {
                                TextButton(onClick = { showRerollConfirm = false }) {
                                    Text("取消")
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun isEdited(buffer: String, template: String): Boolean =
    buffer.isNotEmpty() && buffer != template

// ── One stage ────────────────────────────────────────────────────────────────
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CharacterFormSection(
    title:       String,
    defaultName: String,
    state:       CharacterFormState,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.headlineSmall)

        // Name
        OutlinedTextField(
            value         = state.name,
            onValueChange = { state.name = it },
            label         = { Text("姓名（可选）") },
            placeholder   = { Text("默认为 $defaultName") },
            singleLine    = true,
            modifier      = Modifier.fillMaxWidth(),
        )

        // Gender
        SectionHeader("性别")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GenderTile("女",     state.genderChoice is GenderChoice.Female) { state.setGender(GenderChoice.Female) }
            GenderTile("男",     state.genderChoice is GenderChoice.Male)   { state.setGender(GenderChoice.Male) }
            GenderTile("自定义", state.genderChoice is GenderChoice.Custom) { state.setGender(GenderChoice.Custom) }
        }

        if (state.genderChoice is GenderChoice.Custom) {
            OutlinedTextField(
                value         = state.customGenderName,
                onValueChange = { state.customGenderName = it },
                label         = { Text("自定义性别名") },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value         = state.customGenderDescription,
                onValueChange = { state.customGenderDescription = it },
                label         = { Text("性别描述") },
                modifier      = Modifier.fillMaxWidth(),
            )
        }

        // ── 身体 ────────────────────────────────────────────────────────────
        SectionHeader("身体")
        LabeledSlider("体型 (身高)", state.sizeBar, -1f..1f, "娇小", "高大") { state.sizeBar = it }
        LabeledSlider("身型",        state.figure,  -1f..1f, "纤瘦", "丰腴") { state.figure  = it }
        LabeledSlider("胸围",        state.bust,    -1f..1f, "平坦", "丰满") { state.bust    = it }
        LabeledSlider("臀型",        state.hips,    -1f..1f, "纤巧", "软弹") { state.hips    = it }

        // ── 敏感度 ──────────────────────────────────────────────────────────
        SectionHeader("敏感度")
        LabeledSlider("唇",   state.sensLips,  0f..1f, "普通", "非常敏感") { state.sensLips  = it }
        LabeledSlider("胸",   state.sensBust,  0f..1f, "普通", "非常敏感") { state.sensBust  = it }
        LabeledSlider("臀",   state.sensHips,  0f..1f, "普通", "非常敏感") { state.sensHips  = it }
        LabeledSlider("亲密", state.sensIntim, 0f..1f, "普通", "非常敏感") { state.sensIntim = it }

        // ── 生殖器 ─────────────────────────────────────────────────────────
        SectionHeader("生殖器")
        GenitalSection(state)

        // ── 性格 (moved to bottom; show full description per chip) ─────────
        SectionHeader("性格")
        val variant     = personalityVariantFor(state.genderChoice)
        val displayName = state.name.ifBlank { defaultName }
        personalityListFor(state.genderChoice).forEach { label ->
            val selected = (state.personalityChoice as? PersonalityChoice.Preset)?.label == label
            val desc = personalityDescriptionFor(variant, label, displayName)
            PersonalityRow(label = label, description = desc, selected = selected) {
                state.personalityChoice = PersonalityChoice.Preset(label)
            }
        }
        PersonalityRow(
            label       = "自定义",
            description = "写一个属于自己的性格设定。",
            selected    = state.personalityChoice is PersonalityChoice.Custom,
        ) { state.personalityChoice = PersonalityChoice.Custom }

        if (state.personalityChoice is PersonalityChoice.Custom) {
            OutlinedTextField(
                value         = state.customPersonalityName,
                onValueChange = { state.customPersonalityName = it },
                label         = { Text("性格名") },
                singleLine    = true,
                modifier      = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value         = state.customPersonalityDescription,
                onValueChange = { state.customPersonalityDescription = it },
                label         = { Text("性格描述") },
                modifier      = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Looks up the personality template and substitutes the user's display name. */
private fun personalityDescriptionFor(variant: String, label: String, displayName: String): String {
    val tpl = PromptTemplates.personalityTemplate(variant, label)
        ?: PromptTemplates.personalityTemplate(if (variant == "f") "m" else "f", label)
        ?: return ""
    return tpl.replace("{name}", displayName)
}

@Composable
private fun GenitalSection(state: CharacterFormState) {
    when (state.genderChoice) {
        GenderChoice.Female -> LabeledSlider(
            "紧致度", state.genitalTight, -1f..1f, "宽松", "紧致",
        ) { state.genitalTight = it }
        GenderChoice.Male -> LabeledSlider(
            "尺寸", state.genitalSize, -1f..1f, "小巧", "硕大",
        ) { state.genitalSize = it }
        GenderChoice.Custom -> {
            Row(
                modifier              = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PersonalityChip("男性", state.customGenitalMale) {
                    state.customGenitalMale = !state.customGenitalMale
                }
                PersonalityChip("女性", state.customGenitalFemale) {
                    state.customGenitalFemale = !state.customGenitalFemale
                }
            }
            if (state.customGenitalMale) LabeledSlider(
                "尺寸", state.genitalSize, -1f..1f, "小巧", "硕大",
            ) { state.genitalSize = it }
            if (state.customGenitalFemale) LabeledSlider(
                "紧致度", state.genitalTight, -1f..1f, "宽松", "紧致",
            ) { state.genitalTight = it }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text     = text,
        style    = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun LabeledSlider(
    label:    String,
    value:    Float,
    range:    ClosedFloatingPointRange<Float>,
    lowHint:  String,
    highHint: String,
    onChange: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(label,    style = MaterialTheme.typography.bodyMedium)
            Text(
                "$lowHint  ↔  $highHint",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value         = value,
            onValueChange = onChange,
            valueRange    = range,
            modifier      = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun PersonalityRow(
    label:       String,
    description: String,
    selected:    Boolean,
    onClick:     () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer
             else          MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
             else          MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Text(
            text     = label,
            color    = fg,
            style    = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(end = 4.dp),
        )
        Text(
            text  = description,
            color = fg,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// ── Story input field ────────────────────────────────────────────────────────
//
// Visual rule:
//   - buffer empty       → placeholder shows the template in gray.
//   - buffer non-empty   → buffer rendered in black (whether typed or long-press preloaded).
//
// Gesture rule: while `buffer.isEmpty()` (regardless of focus), an invisible
// overlay captures tap (→ focus, stay blank) vs. long-press (→ preload template
// + focus). Once the user types the first character, the overlay disappears
// and the OutlinedTextField handles input normally — but if they delete back
// to empty, the overlay returns, so long-press-to-preload is available again.
//
// On defocus, if buffer == template (long-pressed but never actually edited),
// reset buffer to "" so the field reverts to its true initial state.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StoryInputField(
    template:       String,
    buffer:         String,
    onBufferChange: (String) -> Unit,
    modifier:       Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    var isFocused by remember { mutableStateOf(false) }

    val grayColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    val textColor = MaterialTheme.colorScheme.onSurface

    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value         = buffer,
            onValueChange = onBufferChange,
            placeholder   = { Text(template, color = grayColor) },
            textStyle     = MaterialTheme.typography.bodyMedium.copy(color = textColor),
            minLines      = 3,
            modifier      = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester)
                .onFocusChanged { state ->
                    val lostFocus = isFocused && !state.isFocused
                    isFocused = state.isFocused
                    if (lostFocus && buffer == template) {
                        onBufferChange("")
                    }
                },
        )
        if (buffer.isEmpty()) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .combinedClickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication        = null,
                        onClick           = {
                            onBufferChange("")
                            focusRequester.requestFocus()
                        },
                        onLongClick       = {
                            onBufferChange(template)
                            focusRequester.requestFocus()
                        },
                    )
            )
        }
    }
}

// ── Reusable pieces ──────────────────────────────────────────────────────────
@Composable
private fun GenderTile(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer
             else          MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
             else          MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .height(48.dp)
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PersonalityChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer
             else          MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
             else          MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .height(40.dp)
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = fg, style = MaterialTheme.typography.bodyMedium)
    }
}
