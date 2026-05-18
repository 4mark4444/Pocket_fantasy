package com.example.ppo

import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.ppo.ui.theme.PPOTheme

const val MEMORY_FILE_KEY = "com.example.ppo.MEMORY_FILE"
const val NOVEL_ID_KEY    = "com.example.ppo.NOVEL_ID"

class MainActivity : ComponentActivity() {

    private val allVMs = mutableStateListOf<GameViewModel>()

    private lateinit var memoryFile: File
    private lateinit var pair: CharacterPair
    private lateinit var novelId: String
    private val random = kotlin.random.Random.Default
    private val promptProvider: () -> String = {
        val entry    = NovelIndex.find(novelId)
        val template = entry?.systemPromptTemplate ?: SystemPromptBuilder.buildTemplate(pair)
        renderProbabilisticTemplate(template, random) + "\n" + PromptTemplates.formatSpec()
    }
    // Per-novel temperature lookup. Read on every runGeneration via the VM's
    // temperatureProvider, so edits in the prompt editor take effect on the
    // next continuation without re-installing the provider.
    private val temperatureProvider: () -> Float = {
        NovelIndex.find(novelId)?.temperature ?: 0.6f
    }

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        PromptTemplates.load(applicationContext)

        val memoryFilename = intent.getStringExtra(MEMORY_FILE_KEY)
            ?: error("MainActivity launched without $MEMORY_FILE_KEY extra")
        novelId = intent.getStringExtra(NOVEL_ID_KEY)
            ?: error("MainActivity launched without $NOVEL_ID_KEY extra")
        val dir = getExternalFilesDir(null)
            ?: error("getExternalFilesDir returned null")
        memoryFile = File(dir, memoryFilename)

        val records = MemoryReader.read(memoryFile)

        // Safety net: NovelIndex entry exists but the per-novel memory file is gone or
        // unparseable (legacy half-novel from before the seed-stub design). Self-clean.
        if (records.isEmpty()) {
            NovelIndex.load(applicationContext)
            NovelIndex.delete(this, novelId)
            Toast.makeText(this, "已删除空白小说", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        pair = records[0].character

        NovelIndex.load(applicationContext)
        val novelName = NovelIndex.all().firstOrNull { it.id == novelId }?.name ?: ""

        // Build N-1 frozen VMs for completed prior scenes.
        // Each completed record's options is size 4: [opt1, opt2, opt3, custom_text].
        // The VM only renders the three model options as preset tiles, so pass take(3).
        val rebuilt = ArrayList<GameViewModel>(records.size)
        for (i in 0 until records.size - 1) {
            val (selIdx, custom) = deriveSelection(records[i], records[i + 1])
            val vm = GameViewModel(application).apply { recordId = records[i].id }
            vm.restoreFrozen(records[i].story, records[i].options.take(3), selIdx, custom)
            rebuilt.add(vm)
        }

        // The last record is either a stub (run generation to fill it in) or a completed
        // scene (resume — restore display, await user choice).
        val last = records.last()
        val liveVm = GameViewModel(application).apply { recordId = last.id }
        liveVm.setTemperatureProvider(temperatureProvider)
        attachMemoryListener(liveVm)
        attachFailureHandler(liveVm)
        // Hydrate input bar from options[3] (the persisted fourth-option text) before
        // attaching the listener so the .drop(1) skips this initial value.
        liveVm.draftInput.value = last.options.getOrElse(3) { "" }
        attachDraftListener(liveVm)

        if (last.options.isEmpty()) {
            // Stub — first generation hasn't completed yet.
            liveVm.initialize(promptProvider, last.background, last.beginning, last.inputAction)
        } else {
            liveVm.resume(
                promptProvider = promptProvider,
                background     = last.background,
                beginning      = last.beginning,
                action         = last.inputAction,
                novelText      = last.story,
                options        = last.options.take(3),
            )
        }

        rebuilt.add(liveVm)
        allVMs.addAll(rebuilt)
        Log.i("MainActivity", "loaded novel: ${records.size} records → ${rebuilt.size} VMs")

        val resumeMode = records.size > 1

        setContent {
            PPOTheme {
                val scrollBehavior     = TopAppBarDefaults.enterAlwaysScrollBehavior()
                val snackbarHostState  = remember { SnackbarHostState() }
                var showPromptEditor by remember { mutableStateOf(false) }
                // True iff opening the editor aborted an in-flight generation. Closing the
                // sheet (save OR cancel) then needs to restart the scene with the same
                // user prompt so the user doesn't end up looking at a blank scene.
                var pendingResume    by remember { mutableStateOf(false) }
                // Snapshot all three editor inputs at sheet-open time so the user
                // doesn't see different auto-built rolls mid-editing session and the
                // memory field doesn't reset itself if the live VM's lastBackground
                // changes during a regenerate. The template's `null` sentinel doubles
                // as the "sheet not open" gate further below.
                // Body and sensitivity clauses arrive pre-wrapped in `⟦pN:…⟧` per slider
                // bucket — see SystemPromptBuilder.

                val editorTemplate: String? = remember(showPromptEditor) {
                    if (!showPromptEditor) null
                    else NovelIndex.find(novelId)?.systemPromptTemplate
                        ?: SystemPromptBuilder.buildTemplate(pair)
                }
                val editorMemory: String = remember(showPromptEditor) {
                    if (!showPromptEditor) ""
                    else allVMs.lastOrNull()?.lastBackground?.takeIf { it.isNotEmpty() }
                        ?: MemoryReader.read(memoryFile).lastOrNull()?.background.orEmpty()
                }
                val editorTemperature: Float = remember(showPromptEditor) {
                    if (!showPromptEditor) 0.6f
                    else NovelIndex.find(novelId)?.temperature ?: 0.6f
                }
                Scaffold(
                    modifier = Modifier
                        .fillMaxSize()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    topBar = {
                        CenterAlignedTopAppBar(
                            title = {
                                Text(
                                    text     = novelName,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            navigationIcon = {
                                IconButton(onClick = { finish() }) {
                                    Icon(
                                        imageVector        = Icons.AutoMirrored.Filled.ArrowBack,
                                        contentDescription = "返回",
                                    )
                                }
                            },
                            actions = {
                                IconButton(onClick = {
                                    // Per spec: opening the editor aborts an in-flight
                                    // generation and clears the partial output. The abort
                                    // is async; the sheet opens immediately and the abort
                                    // completes in the background while the user edits.
                                    val live = allVMs.lastOrNull()
                                    if (live != null && live.isLoading.value) {
                                        pendingResume = true
                                        lifecycleScope.launch { live.abortGeneration() }
                                    }
                                    showPromptEditor = true
                                }) {
                                    Icon(
                                        imageVector        = Icons.Outlined.Edit,
                                        contentDescription = "编辑模型参数",
                                    )
                                }
                            },
                            scrollBehavior = scrollBehavior,
                        )
                    },
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                ) { innerPadding ->
                    val scrollState = rememberScrollState()
                    LaunchedEffect(Unit) {
                        if (resumeMode) scrollState.scrollTo(scrollState.maxValue)
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(innerPadding)
                            .verticalScroll(scrollState)
                    ) {
                        allVMs.forEachIndexed { i, vm ->
                            key(vm) {
                                // Per-VM error observer. Each failed attempt sets parseError
                                // to a non-empty string; the next attempt's reset block clears
                                // it back to "". snapshotFlow emits each distinct value, so a
                                // sequence of N retries surfaces N snackbars. We dispatch via
                                // lifecycleScope so the snackbar survives if Compose tears
                                // this LaunchedEffect down (e.g. when destroyFailedContinuation
                                // removes vm from allVMs).
                                LaunchedEffect(vm) {
                                    snapshotFlow { vm.parseError }.collect { msg ->
                                        if (msg.isNotEmpty()) {
                                            this@MainActivity.lifecycleScope.launch {
                                                snackbarHostState.showSnackbar(msg)
                                            }
                                        }
                                    }
                                }
                                val options by vm.options.collectAsState()
                                val optionsStarted by vm.optionsStarted.collectAsState()
                                val draftInput by vm.draftInput.collectAsState()
                                val isLoading by vm.isLoading.collectAsState()
                                val isLast = i == allVMs.lastIndex
                                NovelScreen(
                                    novelText           = vm.novelText,
                                    options             = options,
                                    optionsStarted      = optionsStarted,
                                    optionsTappable     = vm.optionsTappable,
                                    uiReady             = vm.uiReady,
                                    frozen              = vm.frozen,
                                    isLast              = isLast,
                                    isLoading           = isLoading,
                                    draftInput          = draftInput,
                                    onDraftChange       = if (isLast) { text -> vm.draftInput.value = text } else { _ -> },
                                    onLongPressNovel    = if (isLast) { { vm.regenerate() } } else { {} },
                                    onOptionSelected    = if (isLast) { idx ->
                                                              vm.freeze(selectedIndex = idx)
                                                              spawnNextTurn(vm, vm.options.value[idx])
                                                          } else { _ -> },
                                    onCustomInputSubmit = if (isLast) { text ->
                                                              vm.freeze(selectedIndex = 3, customText = text)
                                                              spawnNextTurn(vm, text)
                                                          } else { _ -> },
                                    selectedOptionIndex = vm.selectedOptionIndex,
                                    selectedCustomText  = vm.selectedCustomText,
                                )
                            }
                        }
                    }
                }

                if (showPromptEditor && editorTemplate != null) {
                    PromptEditorSheet(
                        initialTemplate    = editorTemplate,
                        initialMemory      = editorMemory,
                        initialTemperature = editorTemperature,
                        onSave    = { newTemplate, newMemory, newTemp ->
                            NovelIndex.updateTemplate(applicationContext, novelId, newTemplate)
                            // Normalize 0.6 → null so an untouched default keeps the
                            // index JSON clean (matches the systemPromptTemplate "absent
                            // ⇒ default" convention).
                            NovelIndex.updateTemperature(
                                applicationContext, novelId,
                                newTemp.takeIf { kotlin.math.abs(it - 0.6f) > 0.001f },
                            )
                            // Persist memory to disk *and* update the live VM in
                            // memory so the next regenerate / spawnNextTurn picks
                            // up the edited 【故事背景】 immediately.
                            val live = allVMs.lastOrNull()
                            if (live != null) {
                                lifecycleScope.launch(Dispatchers.IO) {
                                    MemoryReader.updateBackground(
                                        memoryFile, live.recordId, newMemory,
                                    )
                                }
                                live.setLastBackground(newMemory)
                            }
                            showPromptEditor = false
                            if (pendingResume) {
                                pendingResume = false
                                live?.regenerate()
                            }
                            // else: scene already finished — new template / memory /
                            // temperature apply at the user's next option choice via
                            // the provider lambdas.
                        },
                        onDismiss = {
                            showPromptEditor = false
                            if (pendingResume) {
                                // Cancel after an abort: restart the scene with the
                                // unchanged prompt + same user prompt.
                                pendingResume = false
                                allVMs.lastOrNull()?.regenerate()
                            }
                        },
                    )
                }
            }
        }
    }

    private fun deriveSelection(record: MemoryRecord, next: MemoryRecord): Pair<Int, String?> {
        // Only check the three model-generated options for a preset match. options[3]
        // is the user's typed fourth-option text and may coincide with the submitted
        // action on the custom path — we want those rendered as the custom-frozen tile
        // (with text), not as a phantom preset selection at index 3.
        val idx = record.options.take(3).indexOf(next.inputAction)
        return if (idx >= 0) idx to null else 3 to next.inputAction
    }

    private fun attachMemoryListener(vm: GameViewModel) {
        lifecycleScope.launch {
            var wasLoading = false
            vm.isLoading.collect { loading ->
                if (wasLoading && !loading) {
                    if (vm.aborting) {
                        // User opened the prompt editor mid-stream. The half-generated
                        // text must not land on disk and the UI must not flip to a
                        // "ready to tap" state. The save/cancel handler will regenerate.
                    } else if (vm.parseError.isEmpty()) {
                        // Combine the 3 model options + the live input-bar text into a
                        // 4-element list. options[3] is whatever the user has typed so
                        // far (likely "" right after generation completes).
                        val persistedOptions = vm.options.value + listOf(vm.draftInput.value)
                        withContext(Dispatchers.IO) {
                            MemoryReader.upsertMemory(
                                file         = memoryFile,
                                recordId     = vm.recordId,
                                pair         = pair,
                                systemPrompt = vm.lastSystemPrompt,
                                background   = vm.lastBackground,
                                beginning    = vm.lastBeginning,
                                action       = vm.lastAction,
                                story        = vm.novelText,
                                options      = persistedOptions,
                            )
                        }
                        vm.optionsTappable = true
                    }
                    // else: failed attempt. Either retrying (parseError will clear and
                    // _isLoading flips true again immediately) or final failure
                    // (onFinalFailure already fired from the VM). Skip both upsert and
                    // optionsTappable=true.
                }
                wasLoading = loading
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun attachDraftListener(vm: GameViewModel) {
        lifecycleScope.launch {
            vm.draftInput
                .drop(1)               // skip the initial / hydrated value
                .debounce(2000L)
                .collect { text ->
                    withContext(Dispatchers.IO) {
                        MemoryReader.updateOption4(memoryFile, vm.recordId, text)
                    }
                }
        }
    }

    private fun spawnNextTurn(currentVm: GameViewModel, action: String) {
        val nextBackground = if (allVMs.size == 1) {
            "${currentVm.lastBackground}\n${currentVm.lastBeginning}\n${currentVm.lastAction}"
        } else {
            "${currentVm.lastBackground}\n${currentVm.lastAction}"
        }
        val nextBeginning = currentVm.novelText
        val frozenRecordId = currentVm.recordId
        val draftSnapshot  = currentVm.draftInput.value

        // Flush whatever is in the live input bar to the just-frozen record's options[3].
        // The 2 s autosave debounce may not have fired with this value yet — without
        // the flush, an interrupted next-turn would lose the user's typed text.
        // Persisted regardless of which option was picked: if the next turn is
        // interrupted, on reopen this record becomes live again and the input bar
        // rehydrates. Once the next turn upserts successfully, this becomes dead data
        // on the frozen record (harmless — never read).
        lifecycleScope.launch(Dispatchers.IO) {
            MemoryReader.updateOption4(memoryFile, frozenRecordId, draftSnapshot)
        }

        val newVm = GameViewModel(application).apply { recordId = frozenRecordId + 1 }
        newVm.setTemperatureProvider(temperatureProvider)
        attachMemoryListener(newVm)
        attachFailureHandler(newVm)
        attachDraftListener(newVm)
        allVMs.add(newVm)
        newVm.quickStart(promptProvider, nextBackground, nextBeginning, action)
    }

    /**
     * Wire the VM's terminal-failure callback. For the seed scene (recordId == 0)
     * the snackbar is enough — the seed stub stays on disk so the user can leave
     * and reopen the novel for another shot. For continuations, destroy the failed
     * block and unfreeze the previous scene so the user re-taps an option.
     */
    private fun attachFailureHandler(vm: GameViewModel) {
        vm.onFinalFailure = {
            if (vm.recordId > 0) destroyFailedContinuation(vm)
        }
    }

    private fun destroyFailedContinuation(failed: GameViewModel) {
        val idx = allVMs.indexOf(failed)
        if (idx <= 0) return                  // defensive: id=0 never reaches here
        val previous = allVMs[idx - 1]
        allVMs.removeAt(idx)
        failed.destroy()
        previous.unfreeze()
        Log.i("MainActivity", "destroyed failed continuation recordId=${failed.recordId}")
    }

    override fun onDestroy() {
        // 1. Signal stop instantly. Observed by g_should_stop poll points *and*
        //    by the abort_callback that ggml polls inside llama_decode itself,
        //    so the in-flight prefill or generation aborts within one tensor op
        //    instead of waiting out a full batch (~11 s on this device for the
        //    479-token single-chunk prefill).
        LlamaEngine.requestStop()

        // 2. Synchronously mark the engine unusable on Main — *before* spawning
        //    the bg teardown thread, *before* super.onDestroy(). A freshly-
        //    recreated MainActivity (config change, back-and-re-enter) can
        //    otherwise observe a stale isLoaded=true while the bg free is
        //    still running, skip its own load, and later fail generate() with
        //    "Call load() before generate()" once the bg free completes.
        LlamaEngine.markUnloaded()

        // 3. Run framework destroy now — cancels lifecycleScope (kills the
        //    memory listener and draft listener) and, if finishing, clears
        //    ViewModels. No upsert can fire after this point.
        super.onDestroy()

        // 4. Native teardown on a fire-and-forget background thread. nativeFree
        //    blocks on g_engine_mutex until any in-flight nativeLoad or
        //    nativeGenerate releases it. With abort_callback wired up, prefill
        //    and generation release within hundreds of ms; the only case that
        //    still has to be waited out is an uninterruptible model load
        //    (~5–10 s). Either way the wait now lives on this thread, not Main,
        //    so input dispatching is unaffected and we no longer ANR.
        Thread({ LlamaEngine.free() }, "engine-teardown").start()
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NovelScreen(
    novelText:           String,
    options:             List<String>,
    optionsStarted:      Int,
    optionsTappable:     Boolean,
    uiReady:             Boolean,
    frozen:              Boolean,
    isLast:              Boolean,
    isLoading:           Boolean,
    draftInput:          String,
    onDraftChange:       (String) -> Unit,
    onLongPressNovel:    () -> Unit,
    onOptionSelected:    (Int) -> Unit,
    onCustomInputSubmit: (String) -> Unit,
    selectedOptionIndex: Int    = -1,
    selectedCustomText:  String? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (isLast && novelText.isEmpty() && (isLoading || !uiReady)) {
            TutorialBanner(
                text    = "首次生成需要几秒，请稍候。",
                prefKey = TUTORIAL_FIRST_GENERATION,
            )
        }
        if (uiReady) {
            if (isLast && optionsTappable && !frozen) {
                TutorialBanner(
                    text    = "长按已生成的故事内容可重新生成。",
                    prefKey = TUTORIAL_LONG_PRESS_REGENERATE,
                )
            }
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        enabled     = optionsTappable && !frozen,
                        onClick     = {},
                        onLongClick = onLongPressNovel,
                    )
            ) {
                if (isLast && novelText.isEmpty() && isLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 80.dp)
                            .padding(12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        HeartsLoader()
                    }
                } else {
                    Text(
                        text     = novelText,
                        style    = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                    )
                }
            }

            Column(modifier = Modifier.fillMaxWidth()) {
                val tileDivider = MaterialTheme.colorScheme.outline
                val revealed = optionsStarted.coerceAtMost(3)
                for (i in 0 until revealed) {
                    if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(tileDivider))
                    OptionCanvas(
                        text       = options.getOrNull(i),
                        enabled    = optionsTappable && !frozen,
                        isSelected = selectedOptionIndex == i,
                        onClick    = { onOptionSelected(i) },
                    )
                }

                val showInputRow = if (frozen) selectedOptionIndex == 3 else optionsTappable
                if (showInputRow) {
                    Box(Modifier.fillMaxWidth().height(1.dp).background(tileDivider))
                    if (frozen) {
                        // Submitted custom text, frozen darkened tile
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 64.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .background(Color(0x26000000))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text  = selectedCustomText ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        // Live input row — bound to vm.draftInput so typing autosaves.
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value         = draftInput,
                                onValueChange = onDraftChange,
                                placeholder   = { Text("或者…") },
                                modifier      = Modifier.weight(1f),
                                singleLine    = false,
                                maxLines      = 3,
                                enabled       = !frozen,
                            )
                            Spacer(Modifier.width(8.dp))
                            if (draftInput.length > 100) {
                                Text(
                                    text     = "${draftInput.length}/100",
                                    style    = MaterialTheme.typography.labelSmall,
                                    color    = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.padding(horizontal = 8.dp),
                                )
                            } else {
                                Button(
                                    onClick = {
                                        // Don't clear draftInput.value — leave it set to
                                        // the submitted text so spawnNextTurn's
                                        // draftSnapshot captures it for the sync-flush
                                        // to options[3]. The freeze hides this row, so
                                        // the non-empty value isn't shown anywhere.
                                        onCustomInputSubmit(draftInput)
                                    },
                                    enabled = draftInput.isNotEmpty() && !frozen,
                                ) { Text("确认") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionCanvas(
    text:       String?,
    enabled:    Boolean,
    isSelected: Boolean = false,
    onClick:    () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (isSelected) Modifier.background(Color(0x26000000)) else Modifier)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (!text.isNullOrBlank()) {
            Text(
                text     = text,
                style    = MaterialTheme.typography.bodySmall,
                color    = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        } else {
            OptionPulseLoader()
        }
    }
}
