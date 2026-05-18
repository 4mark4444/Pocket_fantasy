package com.example.ppo

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

class GameViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private fun buildUserPrompt(background: String, beginning: String, action: String) =
            "【故事背景】\n$background\n【故事开头】\n$beginning\n【续写内容】\n$action"

        // logcat truncates each line near 4 KB; chunk so long prompts are fully visible.
        private fun logLong(tag: String, msg: String) {
            val chunkSize = 3500
            if (msg.length <= chunkSize) { Log.d(tag, msg); return }
            var i = 0
            var part = 1
            val total = (msg.length + chunkSize - 1) / chunkSize
            while (i < msg.length) {
                val end = minOf(i + chunkSize, msg.length)
                Log.d(tag, "[$part/$total] " + msg.substring(i, end))
                i = end
                part++
            }
        }
    }

    // Set by initialize() / quickStart(); re-invoked at the top of every runGeneration so
    // probabilistic clauses (height tags) re-roll on every turn.
    private var promptProvider: (() -> String)? = null

    // Optional per-call temperature lookup, installed by MainActivity once per VM.
    // When null, runGeneration uses the `temperature` parameter (default 0.6f).
    // Read on every runGeneration so an edit applied between turns takes effect
    // on the next continuation without re-installing the provider.
    private var temperatureProvider: (() -> Float)? = null

    fun setTemperatureProvider(provider: () -> Float) { this.temperatureProvider = provider }

    private val modelFile = File(app.filesDir, "test_1.gguf")

    /** Memory-record id this VM owns. Assigned by MainActivity at construction. */
    var recordId: Int = -1

    // ── Compose-observable state ───────────────────────────────────────────────
    var novelText by mutableStateOf("")
        private set

    /** Tap gate: true once the record for this scene is rendered AND persisted to JSON. */
    var optionsTappable by mutableStateOf(false)

    var frozen by mutableStateOf(false)
        private set

    var selectedOptionIndex by mutableStateOf(-1)
        private set

    var selectedCustomText: String? by mutableStateOf(null)
        private set

    var uiReady by mutableStateOf(false)
        private set

    // Last generation inputs — read by MainActivity to write memory
    var lastSystemPrompt = ""; private set
    var lastBackground   = ""; private set
    var lastBeginning    = ""; private set
    var lastAction       = ""; private set

    /**
     * Public setter for [lastBackground] used by the prompt-editor "model memory"
     * section. Writing here ensures the next regenerate (in-flight + save path)
     * and the next [spawnNextTurn] (which reads `currentVm.lastBackground`) both
     * see the edited 【故事背景】 immediately, without waiting for an upsert.
     */
    fun setLastBackground(bg: String) { this.lastBackground = bg }

    private val _options   = MutableStateFlow<List<String>>(emptyList())
    private val _isLoading = MutableStateFlow(false)
    // How many <option_N> opening tags the parser has seen — drives the
    // progressive tile reveal in MainActivity. 0..3, monotonic within a generation.
    private val _optionsStarted = MutableStateFlow(0)

    val options:        StateFlow<List<String>> = _options.asStateFlow()
    val isLoading:      StateFlow<Boolean>      = _isLoading.asStateFlow()
    val optionsStarted: StateFlow<Int>          = _optionsStarted.asStateFlow()

    /** User's typed-but-unsubmitted custom input. Persisted to disk via debounce. */
    val draftInput = MutableStateFlow("")

    /**
     * Non-empty after a failed generation attempt; cleared at the top of every
     * runGeneration. MainActivity observes the empty→non-empty transition to
     * surface a snackbar, and gates the persistence upsert on this being empty.
     */
    var parseError by mutableStateOf("")
        private set

    /** Auto-retries remaining after the current attempt. Set per entry point. */
    private var attemptsLeft: Int = 0

    /**
     * Invoked once when generation has failed and no retries remain. MainActivity
     * uses this to either keep the seed scene up (recordId == 0) or destroy the
     * failed continuation block and unfreeze the previous scene.
     */
    var onFinalFailure: (() -> Unit)? = null

    // ── Two-thread pipeline ────────────────────────────────────────────────────
    private val tokenQueue = ConcurrentLinkedQueue<String>()
    @Volatile private var modelFinished = false

    /**
     * Set true by [abortGeneration] before signalling the JNI to stop. Read by:
     *  (a) the runGeneration post-display block — skips the malformed-output check
     *      and the auto-retry path so the abort cleanly returns control instead of
     *      surfacing a parse-error snackbar; and
     *  (b) MainActivity's memory listener — skips the upsert + `optionsTappable=true`
     *      flip so the half-generated text never lands on disk and the UI doesn't
     *      look "complete".
     * Cleared at the top of every fresh [runGeneration].
     */
    @Volatile var aborting: Boolean = false
        private set

    // ── Parser state — only ever touched by the display coroutine on Main ──────
    private enum class ParseState { IDLE, IN_TAG, IN_NOVEL, IN_OPTION }
    private var parseState       = ParseState.IDLE
    private val tagBuffer        = StringBuilder()
    private val optionBuffer     = StringBuilder()
    private var currentOptionIdx = -1

    private val optionOpenRegex  = Regex("<option_([123])>")
    private val optionCloseRegex = Regex("</option_([123])>")

    private fun resetParser() {
        parseState       = ParseState.IDLE
        tagBuffer.clear()
        optionBuffer.clear()
        currentOptionIdx = -1
    }

    // ── Display coroutine ──────────────────────────────────────────────────────
    private suspend fun runDisplayLoop() {
        while (!modelFinished || tokenQueue.isNotEmpty()) {
            val piece = tokenQueue.poll()
            if (piece == null) {
                yield()
                continue
            }

            val novelAccum = StringBuilder()

            for (ch in piece) {
                when (parseState) {
                    ParseState.IDLE -> {
                        if (ch == '<') startTag()
                    }
                    ParseState.IN_TAG -> {
                        tagBuffer.append(ch)
                        if (ch == '>') flushTag()
                    }
                    ParseState.IN_NOVEL -> {
                        if (ch == '<') {
                            if (novelAccum.isNotEmpty()) {
                                novelText += novelAccum.toString()
                                novelAccum.clear()
                            }
                            startTag()
                        } else {
                            novelAccum.append(ch)
                        }
                    }
                    ParseState.IN_OPTION -> {
                        if (ch == '<') startTag()
                        else optionBuffer.append(ch)
                    }
                }
            }

            if (novelAccum.isNotEmpty()) {
                novelText += novelAccum.toString()
            }

            yield()
        }
    }

    private fun startTag() {
        parseState = ParseState.IN_TAG
        tagBuffer.clear()
        tagBuffer.append('<')
    }

    private fun flushTag() {
        val tag = tagBuffer.toString()
        tagBuffer.clear()
        Log.d("GameViewModel", "tag='$tag'")
        when {
            tag == "<novel>"  -> parseState = ParseState.IN_NOVEL
            tag == "</novel>" -> parseState = ParseState.IDLE
            optionOpenRegex.matches(tag) -> {
                val n = optionOpenRegex.find(tag)!!.groupValues[1].toInt()  // 1..3
                currentOptionIdx = n - 1
                optionBuffer.clear()
                if (n > _optionsStarted.value) _optionsStarted.value = n
                parseState = ParseState.IN_OPTION
            }
            optionCloseRegex.matches(tag) -> {
                val idx  = optionCloseRegex.find(tag)!!.groupValues[1].toInt() - 1
                val text = optionBuffer.toString()
                val cur  = _options.value.toMutableList()
                while (cur.size <= idx) cur.add("")
                cur[idx] = text
                _options.value = cur
                optionBuffer.clear()
                parseState = ParseState.IDLE
                // optionsTappable stays false until MainActivity flips it post-upsert.
            }
            else -> parseState = ParseState.IDLE
        }
    }

    // ── Copy model from assets to internal storage on first launch ─────────────
    private suspend fun ensureModelExtracted(): Boolean = withContext(Dispatchers.IO) {
        if (modelFile.exists()) return@withContext true
        Log.i("GameViewModel", "Extracting model from assets to ${modelFile.absolutePath}")
        try {
            getApplication<Application>().assets.open("model/test_1.gguf").use { input ->
                modelFile.outputStream().use { output -> input.copyTo(output) }
            }
            Log.i("GameViewModel", "Model extracted successfully")
            true
        } catch (e: Exception) {
            Log.e("GameViewModel", "Failed to extract model from assets", e)
            modelFile.delete()
            false
        }
    }

    // ── Init: backend + model load (once, shared), then kick off generation ──────
    fun initialize(
        promptProvider: () -> String,
        background:     String,
        beginning:      String,
        action:         String,
    ) {
        this.promptProvider = promptProvider
        viewModelScope.launch {
            withContext(Dispatchers.IO) { LlamaEngine.init() }
            uiReady = true
            val err = ensureModelLoaded()
            if (err != null) {
                novelText = err
                return@launch
            }
            attemptsLeft = 2          // up to 3 total attempts for the seed scene
            runGeneration(background, beginning, action)
        }
    }

    fun quickStart(promptProvider: () -> String, background: String, beginning: String, action: String) {
        this.promptProvider = promptProvider
        uiReady = true
        attemptsLeft = 0              // continuations: no auto-retry
        viewModelScope.launch {
            // Defensive — covers the case where MainActivity was destroyed and
            // recreated between turn N and turn N+1 (e.g. config change, or
            // back-out-and-re-enter). The previous activity's bg-thread free()
            // can race resume()'s isLoaded check; if resume lost that race, the
            // engine is gone by the time we get here. Idempotent when loaded.
            val err = ensureModelLoaded()
            if (err != null) {
                novelText  = err
                parseError = err
                onFinalFailure?.invoke()
                return@launch
            }
            runGeneration(background, beginning, action)
        }
    }

    /**
     * Past-scene restoration on resume. No model load. Sets all observable fields directly
     * so NovelScreen renders a fully-frozen card with the chosen option darkened.
     */
    fun restoreFrozen(
        novelText:           String,
        options:             List<String>,
        selectedOptionIndex: Int,
        selectedCustomText:  String?,
    ) {
        this.novelText           = novelText
        _options.value           = options
        _optionsStarted.value    = options.size
        this.selectedOptionIndex = selectedOptionIndex
        this.selectedCustomText  = selectedCustomText
        frozen                   = true
        uiReady                  = true
        optionsTappable          = false
    }

    /**
     * Latest-scene restoration on resume of a *completed* record. Loads the model
     * (idempotent — `LlamaEngine.load` short-circuits if already loaded), restores
     * display state, and flips `optionsTappable=true` so the user can pick.
     */
    fun resume(
        promptProvider: () -> String,
        background:     String,
        beginning:      String,
        action:         String,
        novelText:      String,
        options:        List<String>,
    ) {
        this.promptProvider = promptProvider
        this.lastBackground = background
        this.lastBeginning  = beginning
        this.lastAction     = action
        this.novelText      = novelText
        _options.value      = options
        _optionsStarted.value = options.size
        viewModelScope.launch {
            withContext(Dispatchers.IO) { LlamaEngine.init() }
            uiReady = true
            val err = ensureModelLoaded()
            if (err != null) {
                Log.e("GameViewModel", "resume: $err")
                return@launch
            }
            optionsTappable = true
        }
    }

    private suspend fun ensureModelLoaded(): String? {
        if (LlamaEngine.isLoaded) {
            Log.i("LlamaEngine", "stage: model already loaded — skipping load")
            return null
        }
        if (!ensureModelExtracted()) return "Failed to extract model from the app package."
        Log.i("LlamaEngine", "stage: kotlin requesting model load")
        val ok = withContext(Dispatchers.IO) { LlamaEngine.load(modelFile.absolutePath) }
        Log.i("LlamaEngine", "stage: kotlin load returned ok=$ok")
        if (!ok) return "Failed to load model. Check logcat for details."
        return null
    }

    fun freeze(selectedIndex: Int = -1, customText: String? = null) {
        selectedOptionIndex = selectedIndex
        selectedCustomText  = customText
        frozen              = true
        optionsTappable     = false
    }

    fun destroy() {
        uiReady             = false
        novelText           = ""
        _options.value      = emptyList()
        _optionsStarted.value = 0
        optionsTappable     = false
        frozen              = false
        selectedOptionIndex = -1
        selectedCustomText  = null
        draftInput.value    = ""
    }

    fun regenerate() {
        attemptsLeft = 0              // manual retry path: user already chose to retry
        runGeneration(lastBackground, lastBeginning, lastAction)
    }

    /**
     * Abort an in-flight generation cleanly. No-op when nothing is generating.
     *  1. Set [aborting] so the post-display block in [runGeneration] short-circuits
     *     before the malformed/retry path, and so the memory listener skips its upsert.
     *  2. Signal the JNI to stop (lock-free).
     *  3. Suspend until the runGeneration coroutine has actually released — i.e.
     *     `_isLoading` flipped to false.
     *  4. Clear partial display state. `lastBackground/Beginning/Action` are kept so
     *     the caller can immediately `regenerate()` with the same user prompt.
     * The [aborting] flag is cleared by the next [runGeneration]'s reset block.
     */
    suspend fun abortGeneration() {
        if (!_isLoading.value) return
        aborting = true
        LlamaEngine.requestStop()
        isLoading.first { !it }
        novelText           = ""
        _options.value      = emptyList()
        _optionsStarted.value = 0
        optionsTappable     = false
        selectedOptionIndex = -1
        selectedCustomText  = null
    }

    /** Inverse of [freeze]; used when a failed continuation block is destroyed. */
    fun unfreeze() {
        selectedOptionIndex = -1
        selectedCustomText  = null
        frozen              = false
        optionsTappable     = true
    }

    // ── Public API ─────────────────────────────────────────────────────────────
    fun runGeneration(
        background:   String,
        beginning:    String,
        action:       String,
        temperature:  Float   = 0.6f,
        // GBNF grammar on: the sampler can only emit tokens that keep output on
        // a valid <novel>…</novel><option_N>…</option_N> path, so the model
        // cannot drop the '>' on a close tag (this was the root cause of the
        // empty-option corruption observed on Qwen3.5-2B). If grammar init ever
        // fails at the JNI layer the callback emits an [ERROR:…] token and the
        // post-display malformed check below catches it as novelText.isEmpty().
        novel:        Boolean = true,
    ) {
        if (_isLoading.value) return

        val provider = promptProvider
            ?: error("promptProvider was not set — call initialize()/quickStart() first")
        // Re-invoke every call so probabilistic clauses re-roll per generation.
        val systemPrompt = provider()

        // If MainActivity installed a per-novel temperature lookup, prefer it.
        // The caller-supplied `temperature` arg stays as the fallback for tests
        // and any future callsite that wants to override.
        val effectiveTemperature = temperatureProvider?.invoke() ?: temperature

        lastSystemPrompt = systemPrompt
        lastBackground   = background
        lastBeginning    = beginning
        lastAction       = action
        val userPrompt = buildUserPrompt(background, beginning, action)

        logLong("Prompt", "=== SYSTEM PROMPT (${systemPrompt.length} chars) ===\n$systemPrompt")
        logLong("Prompt", "=== USER PROMPT (${userPrompt.length} chars) ===\n$userPrompt")
        Log.i("LlamaEngine", "stage: temperature=$effectiveTemperature")

        viewModelScope.launch {
            _isLoading.value    = true
            novelText           = ""
            _options.value      = emptyList()
            _optionsStarted.value = 0
            optionsTappable     = false
            selectedOptionIndex = -1
            selectedCustomText  = null
            parseError          = ""
            aborting            = false
            tokenQueue.clear()
            modelFinished       = false
            resetParser()

            val rawOutput = StringBuilder()
            launch(Dispatchers.IO) {
                try {
                    LlamaEngine.generate(
                        systemPrompt = systemPrompt,
                        userPrompt   = userPrompt,
                        temperature  = effectiveTemperature,
                        novel        = novel,
                    ) { piece ->
                        Log.d("LlamaRaw", piece)
                        rawOutput.append(piece)
                        tokenQueue.offer(piece)
                    }
                    Log.d("LlamaRaw", "=== FULL OUTPUT ===\n$rawOutput")
                } catch (t: Throwable) {
                    Log.e("GameViewModel", "LlamaEngine.generate threw", t)
                    parseError = "生成失败：模型出错"
                } finally {
                    // ALWAYS set, even on exception — this is what unblocks runDisplayLoop.
                    modelFinished = true
                }
            }

            runDisplayLoop()

            // External abort path: skip the malformed check and the auto-retry loop.
            // The memory listener also consults `aborting` and skips its upsert+flip.
            if (aborting) {
                _isLoading.value = false
                return@launch
            }

            // Completeness check — flag truncations / format violations that the parser
            // couldn't recover from. Junk tags (e.g. <thinking>, <option999999>) are
            // already silently dropped by flushTag()'s else branch and do NOT trip this
            // check unless they prevented the structural three-option output.
            val malformed =
                parseState != ParseState.IDLE     ||   // ended mid-tag-content
                tagBuffer.isNotEmpty()            ||   // ended mid-tag-name (e.g. "<option_")
                novelText.isEmpty()               ||   // never opened <novel>
                _options.value.size != 3          ||   // missing at least one option
                _options.value.any { it.isBlank() }    // an option committed with empty text
            if (parseError.isEmpty() && malformed) {
                Log.w("GameViewModel",
                    "malformed output: parseState=$parseState, tagBuffer='$tagBuffer', " +
                    "novelText.len=${novelText.length}, options.size=${_options.value.size}")
                parseError = "生成失败：输出格式异常"
            }

            if (parseError.isNotEmpty()) {
                if (attemptsLeft > 0) {
                    attemptsLeft--
                    // Flip loading false so the listener observes the failed transition
                    // (and skips upsert because parseError is set), then give the snackbar
                    // a beat to render this attempt's error before the next runGeneration
                    // clears parseError in its reset block.
                    _isLoading.value = false
                    delay(500)
                    runGeneration(background, beginning, action, temperature, novel)
                    return@launch
                }
                _isLoading.value = false
                onFinalFailure?.invoke()
                return@launch
            }

            _isLoading.value = false
        }
    }
}
