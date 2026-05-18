package com.example.ppo

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object LlamaEngine {

    private const val TAG = "LlamaEngine"

    // Engine usability — readable from any thread, only flipped on Main (via
    // [markUnloaded]) or on the thread that owns [load]. This is the gate
    // ensureModelLoaded / generate check.
    @Volatile var isLoaded: Boolean = false
        private set

    // Tracks whether nativeFree is still owed. Decoupled from [isLoaded] so
    // onDestroy can synchronously mark the engine unusable (isLoaded=false)
    // without losing the obligation to run the slow native cleanup on a bg
    // thread afterward.
    @Volatile private var nativeLoaded: Boolean = false

    init {
        System.loadLibrary("llama-android")
        Log.i(TAG, "Native library loaded")
    }

    fun interface TokenCallback {
        fun onToken(piece: String)
    }

    private external fun nativeInit(): Boolean
    private external fun nativeLoad(modelPath: String, nThreads: Int): Boolean
    private external fun nativeGenerate(
        systemPrompt: String,
        userPrompt:   String,
        nPredict:     Int,
        temperature:  Float,
        novel:        Boolean,
        softTarget:   Int,
        hardCeiling:  Int,
        callback:     TokenCallback,
    )
    private external fun nativeRequestStop()
    private external fun nativeFree()

    fun init(): Boolean = nativeInit()

    fun load(modelPath: String, nThreads: Int = 0): Boolean =
        nativeLoad(modelPath, nThreads).also {
            if (it) { nativeLoaded = true; isLoaded = true }
        }

    suspend fun generate(
        systemPrompt: String,
        userPrompt:   String,
        nPredict:     Int     = 2048,
        temperature:  Float   = 0.3f,
        novel:        Boolean = true,
        softTarget:   Int     = 600,
        hardCeiling:  Int     = 1200,
        onToken:      (String) -> Unit,
    ) = withContext(Dispatchers.IO) {
        check(isLoaded) { "Call load() before generate()" }
        nativeGenerate(
            systemPrompt, userPrompt, nPredict, temperature, novel,
            softTarget, hardCeiling,
        ) { piece -> onToken(piece) }
    }

    /**
     * Lock-free signal to interrupt an in-flight generate. Safe to call from any
     * thread; returns instantly. Pair with [free] in Activity teardown so the IO
     * thread is already winding down by the time [free] takes the engine lock.
     */
    fun requestStop() = nativeRequestStop()

    /**
     * Synchronously mark the engine as no longer usable. After this, [isLoaded]
     * reads false from any thread — so a freshly-spawned MainActivity can never
     * see stale `true` while the bg teardown is still running. Pair with a
     * background-thread call to [free] for the actual native cleanup.
     */
    fun markUnloaded() {
        isLoaded = false
    }

    /**
     * Native cleanup. Blocks on the engine mutex until any in-flight
     * nativeLoad / nativeGenerate releases it. Idempotent — gated on
     * [nativeLoaded], not [isLoaded], so it still runs after [markUnloaded]
     * has flipped the Kotlin flag. Call from a background thread.
     */
    fun free() {
        if (!nativeLoaded) return
        nativeLoaded = false
        nativeFree()
    }
}
