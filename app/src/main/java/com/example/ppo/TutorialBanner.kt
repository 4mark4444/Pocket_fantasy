package com.example.ppo

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Lightweight key-value store for "don't show this tutorial again" decisions.
 * Each tutorial uses a stable string key; first launch sees them, calling
 * [dismiss] persists across app restarts via SharedPreferences.
 */
object TutorialPrefs {
    private const val PREFS_NAME = "ppo_tutorials"

    fun isDismissed(context: Context, key: String): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(key, false)

    fun dismiss(context: Context, key: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(key, true)
            .apply()
    }
}

/**
 * Inline tutorial banner — shows [text] above the section it explains, with
 * two dismissal actions:
 *   关闭     — hide for this composition lifetime only (returns next time).
 *   不再显示 — persist via [TutorialPrefs.dismiss] (gone forever).
 *
 * Renders nothing if either dismissal has already fired.
 */
@Composable
fun TutorialBanner(
    text:     String,
    prefKey:  String,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var dismissed by remember(prefKey) {
        mutableStateOf(TutorialPrefs.isDismissed(context, prefKey))
    }
    if (dismissed) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text  = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Row(
            modifier              = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment     = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { dismissed = true }) { Text("关闭") }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = {
                TutorialPrefs.dismiss(context, prefKey)
                dismissed = true
            }) { Text("不再显示") }
        }
    }
}

// ── Tutorial keys (stable strings; renaming = re-showing for existing users) ──
const val TUTORIAL_LANDING               = "tutorial_landing"
const val TUTORIAL_CHARACTER_STORY       = "tutorial_character_story"
const val TUTORIAL_FIRST_GENERATION      = "tutorial_first_generation"
const val TUTORIAL_LONG_PRESS_REGENERATE = "tutorial_long_press_regenerate"
const val TUTORIAL_EDIT_TEMPLATE         = "tutorial_edit_template"
const val TUTORIAL_EDIT_MEMORY           = "tutorial_edit_memory"
const val TUTORIAL_EDIT_TEMPERATURE      = "tutorial_edit_temperature"
