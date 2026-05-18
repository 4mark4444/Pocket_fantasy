package com.example.ppo

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.ppo.ui.theme.PPOTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.burnoutcrew.reorderable.ReorderableItem
import org.burnoutcrew.reorderable.detectReorderAfterLongPress
import org.burnoutcrew.reorderable.rememberReorderableLazyGridState
import org.burnoutcrew.reorderable.reorderable
import java.io.File

class LandingActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PromptTemplates.load(applicationContext)
        StoryPools.load(applicationContext)
        NovelIndex.load(applicationContext)

        setContent {
            PPOTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    LandingScreen(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding),
                        onCreateNew = {
                            startActivity(Intent(this, CharacterSelectionActivity::class.java))
                        },
                        onResume = { entry ->
                            startActivity(
                                Intent(this, MainActivity::class.java)
                                    .putExtra(MEMORY_FILE_KEY, entry.memoryFile)
                                    .putExtra(NOVEL_ID_KEY,    entry.id),
                            )
                        },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LandingScreen(
    modifier:    Modifier,
    onCreateNew: () -> Unit,
    onResume:    (NovelIndex.Entry) -> Unit,
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val novels = NovelIndex.all()
    var configMode     by remember { mutableStateOf(false) }
    var renamingId     by remember { mutableStateOf<String?>(null) }
    var renamingBuffer by remember { mutableStateOf("") }
    var deletingId     by remember { mutableStateOf<String?>(null) }
    var pickingId      by remember { mutableStateOf<String?>(null) }

    val coverPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        val id = pickingId
        pickingId = null
        if (uri != null && id != null) {
            scope.launch(Dispatchers.IO) {
                val dest = File(context.filesDir, "covers")
                    .apply { mkdirs() }
                    .let { File(it, id) }
                context.contentResolver.openInputStream(uri)?.use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                NovelIndex.setCoverImage(context, id, dest.absolutePath)
            }
        }
    }

    BackHandler(enabled = configMode) { configMode = false }

    val reorderState = rememberReorderableLazyGridState(
        onMove = { from, to ->
            // Bound to novel range — exclude the trailing add-button cell.
            if (from.index in novels.indices && to.index in novels.indices) {
                NovelIndex.reorder(context, from.index, to.index)
            }
        },
    )

    Box(
        modifier = modifier.pointerInput(configMode) {
            if (!configMode) return@pointerInput
            detectTapGestures(onTap = { configMode = false })
        },
    ) {
        LazyVerticalGrid(
            state    = reorderState.gridState,
            columns  = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .reorderable(reorderState)
                .then(if (configMode) Modifier.detectReorderAfterLongPress(reorderState) else Modifier),
            contentPadding        = PaddingValues(16.dp),
            verticalArrangement   = Arrangement.spacedBy(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                TutorialBanner(
                    text    = "点按「+」新建故事；长按已有封面可重命名、换图、删除或调整顺序。",
                    prefKey = TUTORIAL_LANDING,
                )
            }
            items(novels, key = { it.id }) { entry ->
                ReorderableItem(reorderState, key = entry.id) { dragging ->
                    NovelCover(
                        entry         = entry,
                        configMode    = configMode,
                        dragging      = dragging,
                        onTap         = { if (!configMode) onResume(entry) },
                        onLongPress   = { if (!configMode) configMode = true },
                        onDelete      = { deletingId = entry.id },
                        onChangeCover = {
                            pickingId = entry.id
                            coverPicker.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                        onRename      = {
                            renamingId     = entry.id
                            renamingBuffer = entry.name
                        },
                    )
                }
            }
            if (!configMode) {
                item(span = { GridItemSpan(1) }) {
                    AddNovelButton(onClick = onCreateNew)
                }
            }
        }
    }

    if (renamingId != null) {
        AlertDialog(
            onDismissRequest = { renamingId = null },
            title = { Text("重命名") },
            text  = {
                OutlinedTextField(
                    value         = renamingBuffer,
                    onValueChange = { renamingBuffer = it },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val id   = renamingId
                    val name = renamingBuffer.trim()
                    if (id != null && name.isNotEmpty()) {
                        NovelIndex.rename(context, id, name)
                    }
                    renamingId = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { renamingId = null }) { Text("取消") }
            },
        )
    }

    if (deletingId != null) {
        val entry = novels.firstOrNull { it.id == deletingId }
        AlertDialog(
            onDismissRequest = { deletingId = null },
            title = { Text("删除") },
            text  = { Text("删除『${entry?.name.orEmpty()}』? 此操作无法撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    val id = deletingId
                    if (id != null) NovelIndex.delete(context, id)
                    deletingId = null
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deletingId = null }) { Text("取消") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NovelCover(
    entry:         NovelIndex.Entry,
    configMode:    Boolean,
    dragging:      Boolean,
    onTap:         () -> Unit,
    onLongPress:   () -> Unit,
    onDelete:      () -> Unit,
    onChangeCover: () -> Unit,
    onRename:      () -> Unit,
    modifier:      Modifier = Modifier,
) {
    val targetScale = when {
        dragging   -> 1.06f
        configMode -> 0.92f
        else       -> 1.0f
    }
    val scale by animateFloatAsState(targetValue = targetScale, label = "configScale")
    val color        = remember(entry.coverColorSeed) { coverColor(entry.coverColorSeed) }
    val onCoverColor = remember(color) { contrastTextColor(color) }

    var bitmap by remember(entry.coverImagePath) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(entry.coverImagePath) {
        bitmap = entry.coverImagePath?.let { path ->
            withContext(Dispatchers.IO) {
                val f = File(path)
                if (f.exists()) BitmapFactory.decodeFile(path)?.asImageBitmap() else null
            }
        }
    }
    val hasImage  = bitmap != null
    val titleHue  = if (hasImage) Color.White else onCoverColor

    Box(
        modifier = modifier
            .aspectRatio(2f / 3f)
            .scale(scale)
            .clip(RoundedCornerShape(12.dp))
            .background(color)
            .combinedClickable(
                onClick     = onTap,
                onLongClick = onLongPress,
            ),
    ) {
        bitmap?.let { bmp ->
            Image(
                bitmap          = bmp,
                contentDescription = null,
                contentScale    = ContentScale.Crop,
                modifier        = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            1f to Color.Black.copy(alpha = 0.55f),
                        ),
                    ),
            )
        }
        Text(
            text      = entry.name,
            color     = titleHue,
            style     = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            textAlign = TextAlign.Center,
            modifier  = Modifier
                .align(Alignment.Center)
                .padding(16.dp),
        )
        if (configMode) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                CoverActionButton(label = "删除", color = titleHue, onClick = onDelete)
                CoverActionButton(label = "换图", color = titleHue, onClick = onChangeCover)
                CoverActionButton(label = "改名", color = titleHue, onClick = onRename)
            }
        }
    }
}

@Composable
private fun CoverActionButton(
    label:   String,
    color:   Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0x40000000))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text  = label,
            color = color,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun AddNovelButton(onClick: () -> Unit) {
    val outline = MaterialTheme.colorScheme.outline
    val bg      = MaterialTheme.colorScheme.surfaceVariant
    Box(
        modifier = Modifier
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text  = "+",
            color = outline,
            style = MaterialTheme.typography.displayLarge,
        )
    }
}

private fun coverColor(seed: Long): Color {
    val hue = (((seed % 360L) + 360L) % 360L).toFloat()
    return Color.hsl(hue, 0.45f, 0.6f)
}

private fun contrastTextColor(color: Color): Color {
    val l = 0.299f * color.red + 0.587f * color.green + 0.114f * color.blue
    return if (l > 0.55f) Color(0xFF1A1A1A) else Color.White
}
