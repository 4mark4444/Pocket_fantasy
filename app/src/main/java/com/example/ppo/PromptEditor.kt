package com.example.ppo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptEditorSheet(
    initialTemplate:    String,
    initialMemory:      String,
    initialTemperature: Float,
    onSave:    (template: String, memory: String, temperature: Float) -> Unit,
    onDismiss: () -> Unit,
) {
    var tfv by remember {
        mutableStateOf(TextFieldValue(initialTemplate, TextRange(initialTemplate.length)))
    }
    var memoryText  by remember { mutableStateOf(initialMemory) }
    var temperature by remember { mutableStateOf(initialTemperature) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // Trigger flags for the memory / temperature tutorials — flipped on first
    // user touch of the respective control within this sheet open. Reset on each
    // open because `remember` re-initializes when the sheet leaves composition.
    var memoryInteracted   by remember { mutableStateOf(false) }
    var temperatureChanged by remember { mutableStateOf(false) }

    val tertiary  = MaterialTheme.colorScheme.tertiary
    val transform = remember(tertiary) { ProbabilityVisualTransformation(tertiary) }

    // Probability-chip enable state — derived from the *template* field only.
    val containingSpan = findSpanContaining(tfv.text, tfv.selection.start)
    val selectionIsPlain: Boolean = run {
        val s = tfv.selection
        if (s.collapsed) false
        else {
            val from = minOf(s.start, s.end)
            val to   = maxOf(s.start, s.end)
            val sub  = tfv.text.substring(from, to)
            !sub.contains(PROMPT_OPEN_BRACKET) && !sub.contains(PROMPT_CLOSE_BRACKET)
        }
    }
    val canWrap  = containingSpan != null || selectionIsPlain
    val canClear = containingSpan != null

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState       = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            // ── Header ─────────────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text     = "编辑模型参数",
                    style    = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "关闭")
                }
            }

            Spacer(modifier = Modifier.padding(top = 8.dp))

            // ── Section 1: System prompt template ──────────────────────────
            Text(
                text  = "系统提示",
                style = MaterialTheme.typography.titleSmall,
            )

            Spacer(modifier = Modifier.padding(top = 4.dp))

            TutorialBanner(
                text    = "选中文字后点按 25 / 50 / 75，可控制这一段在每次生成时随机出现的概率。",
                prefKey = TUTORIAL_EDIT_TEMPLATE,
            )

            Spacer(modifier = Modifier.padding(top = 4.dp))

            OutlinedTextField(
                value          = tfv,
                onValueChange  = { tfv = it; errorMessage = null },
                visualTransformation = transform,
                textStyle      = MaterialTheme.typography.bodyMedium,
                minLines       = 8,
                maxLines       = 16,
                modifier       = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.padding(top = 8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ALLOWED_PROBABILITIES.forEach { pct ->
                    AssistChip(
                        onClick = {
                            tfv          = applyProbability(tfv, pct)
                            errorMessage = null
                        },
                        enabled = canWrap,
                        label   = { Text("${pct}%") },
                    )
                }
                AssistChip(
                    onClick = {
                        tfv          = clearProbability(tfv)
                        errorMessage = null
                    },
                    enabled = canClear,
                    label   = { Text("清除") },
                )
            }

            errorMessage?.let { msg ->
                Spacer(modifier = Modifier.padding(top = 4.dp))
                Text(
                    text  = msg,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(modifier = Modifier.padding(top = 16.dp))

            // ── Section 2: Model memory (= the 【故事背景】 block) ──────────
            Text(
                text  = "模型记忆",
                style = MaterialTheme.typography.titleSmall,
            )

            Spacer(modifier = Modifier.padding(top = 4.dp))

            if (memoryInteracted) {
                TutorialBanner(
                    text    = "修改模型记忆可以让模型遗忘或强调某些内容。",
                    prefKey = TUTORIAL_EDIT_MEMORY,
                )
                Spacer(modifier = Modifier.padding(top = 4.dp))
            }

            OutlinedTextField(
                value         = memoryText,
                onValueChange = { memoryText = it; memoryInteracted = true },
                textStyle     = MaterialTheme.typography.bodyMedium,
                minLines      = 4,
                maxLines      = 8,
                modifier      = Modifier
                    .fillMaxWidth()
                    .onFocusChanged { if (it.isFocused) memoryInteracted = true },
            )

            Spacer(modifier = Modifier.padding(top = 16.dp))

            // ── Section 3: Sampling temperature ────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text     = "模型温度",
                    style    = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text  = "%.2f".format(temperature),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            if (temperatureChanged) {
                Spacer(modifier = Modifier.padding(top = 4.dp))
                TutorialBanner(
                    text    = "调整温度可让故事更有变化。但不建议调整，可能导致生成异常。",
                    prefKey = TUTORIAL_EDIT_TEMPERATURE,
                )
            }

            Slider(
                value         = temperature,
                onValueChange = { temperature = it; temperatureChanged = true },
                valueRange    = 0f..1.0f,
                steps         = 19,        // 21 stops at 0.05 increments: 0.00 … 1.00
                modifier      = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.padding(top = 12.dp))

            // ── Footer ─────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text("取消") }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(onClick = {
                    val err = validateTemplate(tfv.text)
                    if (err != null) errorMessage = err
                    else              onSave(tfv.text, memoryText, temperature)
                }) { Text("保存") }
            }
        }
    }
}

/**
 * Paints every `⟦pN:…⟧` span in the source text with [color] + semibold so the user
 * can see the boundary of each probabilistic region. Uses [OffsetMapping.Identity] —
 * markers stay visible in the source, so cursor positions don't need translation.
 */
internal class ProbabilityVisualTransformation(
    private val color: Color,
) : VisualTransformation {
    override fun filter(text: androidx.compose.ui.text.AnnotatedString): TransformedText {
        val src = text.text
        val annotated = buildAnnotatedString {
            append(src)
            PROBABILITY_SPAN_REGEX.findAll(src).forEach { m ->
                addStyle(
                    style = SpanStyle(color = color, fontWeight = FontWeight.SemiBold),
                    start = m.range.first,
                    end   = m.range.last + 1,
                )
            }
        }
        return TransformedText(annotated, OffsetMapping.Identity)
    }
}

/** Match (if any) of `⟦pN:…⟧` whose body strictly contains [pos]. */
internal fun findSpanContaining(text: String, pos: Int): MatchResult? =
    PROBABILITY_SPAN_REGEX.findAll(text).firstOrNull { m ->
        pos > m.range.first && pos <= m.range.last
    }

/**
 * If the cursor is inside an existing span, change its probability to [pct].
 * Otherwise wrap the current selection (if non-empty and bracket-free).
 * No-op if neither condition holds.
 */
internal fun applyProbability(tfv: TextFieldValue, pct: Int): TextFieldValue {
    val src       = tfv.text
    val containing = findSpanContaining(src, tfv.selection.start)
    if (containing != null) {
        val oldPctStr = containing.groupValues[1]
        // Layout: ⟦ p <digits> :
        val pStart = containing.range.first + 1 + 1                // index of first digit
        val pEnd   = pStart + oldPctStr.length                     // index of ':'
        val newDigits = pct.toString()
        val newText   = src.substring(0, pStart) + newDigits + src.substring(pEnd)
        val delta     = newDigits.length - oldPctStr.length
        // Shift cursor only if it was past the digits.
        val newCursor = if (tfv.selection.start >= pEnd) tfv.selection.start + delta
                        else tfv.selection.start
        return tfv.copy(text = newText, selection = TextRange(newCursor))
    }
    val s = tfv.selection
    if (s.collapsed) return tfv
    val from = minOf(s.start, s.end)
    val to   = maxOf(s.start, s.end)
    val selected = src.substring(from, to)
    if (selected.contains(PROMPT_OPEN_BRACKET) || selected.contains(PROMPT_CLOSE_BRACKET)) return tfv
    val open    = "${PROMPT_OPEN_BRACKET}p$pct:"
    val close   = PROMPT_CLOSE_BRACKET
    val newText = src.substring(0, from) + open + selected + close + src.substring(to)
    val cursor  = from + open.length + selected.length + close.length
    return tfv.copy(text = newText, selection = TextRange(cursor))
}

/** Remove the markers (but not the content) of the span containing the cursor. */
internal fun clearProbability(tfv: TextFieldValue): TextFieldValue {
    val src = tfv.text
    val match = findSpanContaining(src, tfv.selection.start) ?: return tfv
    val open       = match.range.first                                // index of ⟦
    val close      = match.range.last                                 // index of ⟧
    val pctStr     = match.groupValues[1]
    val openLen    = 1 + 1 + pctStr.length + 1                        // ⟦, p, digits, :
    // Drop the closing bracket first to keep `open` and `openLen` valid.
    val a = src.substring(0, close) + src.substring(close + 1)
    val b = a.substring(0, open) + a.substring(open + openLen)
    val cursor = (tfv.selection.start - openLen).coerceAtLeast(open)
    return tfv.copy(text = b, selection = TextRange(cursor.coerceAtMost(b.length)))
}

/** Returns an error message in Chinese, or null if the template is well-formed. */
internal fun validateTemplate(text: String): String? {
    val stripped = PROBABILITY_SPAN_REGEX.replace(text, "")
    if (stripped.contains(PROMPT_OPEN_BRACKET) || stripped.contains(PROMPT_CLOSE_BRACKET)) {
        return "存在未匹配或嵌套的概率标记"
    }
    return null
}
