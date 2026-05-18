// JNI bridge for llama.cpp inference on Android.
// Adapted from examples/llama.android/lib/src/main/cpp/ai_chat.cpp.

#include <algorithm>
#include <android/log.h>
#include <atomic>
#include <jni.h>
#include <mutex>
#include <string>
#include <unistd.h>
#include <unordered_set>

#include "chat.h"
#include "common.h"
#include "llama.h"

#define LOG_TAG "LlamaEngine"
#define LOGi(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGe(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGd(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// ─── Thread count tuning (matches official example) ───────────────────────────
static constexpr int N_THREADS_MIN      = 2;
static constexpr int N_THREADS_MAX      = 4;
static constexpr int N_THREADS_HEADROOM = 2;

static constexpr int   DEFAULT_CTX   = 4096;
static constexpr int   BATCH_SIZE    = 512;

// ─── Global inference state ───────────────────────────────────────────────────
static llama_model               * g_model     = nullptr;
static llama_context             * g_context   = nullptr;
static llama_batch                 g_batch;
static common_chat_templates_ptr   g_templates;
static bool                        g_loaded    = false;

// Serializes nativeLoad/nativeGenerate/nativeFree so the Main thread cannot tear
// down g_context/g_model while the IO thread is mid-llama_decode. The stop flag
// is the cooperative-cancel signal nativeGenerate polls inside its loops; setting
// it from any thread (no lock) lets the engine wind down before nativeFree blocks.
static std::mutex        g_engine_mutex;
static std::atomic<bool> g_should_stop{false};

// ─── llama log → Android logcat ───────────────────────────────────────────────
static void android_log_cb(ggml_log_level level, const char * text, void *) {
    int prio;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: prio = ANDROID_LOG_ERROR; break;
        case GGML_LOG_LEVEL_WARN:  prio = ANDROID_LOG_WARN;  break;
        case GGML_LOG_LEVEL_INFO:  prio = ANDROID_LOG_INFO;  break;
        default:                   prio = ANDROID_LOG_DEBUG; break;
    }
    __android_log_write(prio, LOG_TAG, text);
}

// ggml polls this between tensor ops during llama_decode. Returning true aborts
// the in-flight decode within ~one op — used to make prefill (which is otherwise
// a single uninterruptible llama_decode for prompts ≤ BATCH_SIZE) cancellable.
static bool engine_abort_cb(void *) { return g_should_stop.load(); }

// ─── nativeInit ───────────────────────────────────────────────────────────────
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_ppo_LlamaEngine_nativeInit(JNIEnv *, jobject) {
    llama_log_set(android_log_cb, nullptr);
    llama_backend_init();
    LOGi("llama backend initialized");
    return JNI_TRUE;
}

// ─── nativeLoad ───────────────────────────────────────────────────────────────
// Loads a GGUF model file and prepares context, batch, and chat templates.
// Sampler is created per-call in nativeGenerate.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_ppo_LlamaEngine_nativeLoad(
        JNIEnv * env, jobject, jstring jModelPath, jint jNThreads)
{
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    const char * model_path = env->GetStringUTFChars(jModelPath, nullptr);
    LOGi("stage: model load begin (%s)", model_path);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;

    g_model = llama_model_load_from_file(model_path, mparams);
    env->ReleaseStringUTFChars(jModelPath, model_path);

    if (!g_model) {
        LOGe("llama_model_load_from_file failed");
        return JNI_FALSE;
    }

    // Thread count: auto-detect if jNThreads == 0
    const int n_cpu = (int) sysconf(_SC_NPROCESSORS_ONLN);
    const int n_threads = (jNThreads > 0)
        ? (int) jNThreads
        : std::max(N_THREADS_MIN, std::min(N_THREADS_MAX, n_cpu - N_THREADS_HEADROOM));
    LOGi("Using %d threads (CPUs online: %d)", n_threads, n_cpu);

    // Context
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx           = DEFAULT_CTX;
    cparams.n_batch         = BATCH_SIZE;
    cparams.n_ubatch        = BATCH_SIZE;
    cparams.n_threads       = n_threads;
    cparams.n_threads_batch = n_threads;
    cparams.abort_callback      = engine_abort_cb;
    cparams.abort_callback_data = nullptr;

    g_context = llama_init_from_model(g_model, cparams);
    if (!g_context) {
        LOGe("llama_init_from_model failed");
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }

    // Batch
    g_batch = llama_batch_init(BATCH_SIZE, 0, 1);

    // Chat templates (reads from GGUF metadata — Qwen 2.5 embeds ChatML here)
    g_templates = common_chat_templates_init(g_model, "");

    g_loaded = true;
    LOGi("stage: model load complete");
    return JNI_TRUE;
}

// ─── Novel-length bias sampler ────────────────────────────────────────────────
// Custom llama_sampler that nudges the model toward closing <novel>...</novel>
// once the novel content has run for `soft_target` tokens, ramping linearly to
// a hard cap at `hard_ceiling`. Boosts every token whose decoded text starts
// with '<' — under the grammar `content ::= [^<]+`, that's the only way to
// escape the novel content block.
namespace {

struct novel_bias_ctx {
    int  soft_target;
    int  hard_ceiling;
    int  state;             // 0 = OUTSIDE, 1 = IN_NOVEL
    int  tokens_in_novel;
    std::string text_window;
    std::unordered_set<llama_token> lt_tokens;
    const llama_vocab * vocab;
};

const char * novel_bias_name(const llama_sampler *) { return "novel_bias"; }

void novel_bias_accept(llama_sampler * smpl, llama_token tok) {
    auto * c = (novel_bias_ctx *) smpl->ctx;
    std::string piece = common_token_to_piece(c->vocab, tok, /*special=*/true);
    c->text_window += piece;
    if (c->text_window.size() > 32) {
        c->text_window.erase(0, c->text_window.size() - 32);
    }
    if (c->state == 0) {
        if (c->text_window.find("<novel>") != std::string::npos) {
            c->state = 1;
            c->tokens_in_novel = 0;
            c->text_window.clear();
        }
    } else {
        c->tokens_in_novel++;
        if (c->text_window.find("</novel>") != std::string::npos) {
            c->state = 0;
            c->text_window.clear();
        }
    }
}

void novel_bias_apply(llama_sampler * smpl, llama_token_data_array * cur_p) {
    auto * c = (novel_bias_ctx *) smpl->ctx;
    if (c->state != 1 || c->tokens_in_novel < c->soft_target) return;
    const float span = (float) std::max(1, c->hard_ceiling - c->soft_target);
    const float t    = std::min(1.0f, (float)(c->tokens_in_novel - c->soft_target) / span);
    const float bias = t * 30.0f;
    for (size_t i = 0; i < cur_p->size; i++) {
        if (c->lt_tokens.count(cur_p->data[i].id)) {
            cur_p->data[i].logit += bias;
        }
    }
}

void novel_bias_reset(llama_sampler * smpl) {
    auto * c = (novel_bias_ctx *) smpl->ctx;
    c->state           = 0;
    c->tokens_in_novel = 0;
    c->text_window.clear();
}

void novel_bias_free(llama_sampler * smpl) {
    delete (novel_bias_ctx *) smpl->ctx;
}

llama_sampler_i NOVEL_BIAS_IFACE = {
    /* .name              = */ novel_bias_name,
    /* .accept            = */ novel_bias_accept,
    /* .apply             = */ novel_bias_apply,
    /* .reset             = */ novel_bias_reset,
    /* .clone             = */ nullptr,
    /* .free              = */ novel_bias_free,
    /* .backend_init      = */ nullptr,
    /* .backend_accept    = */ nullptr,
    /* .backend_apply     = */ nullptr,
    /* .backend_set_input = */ nullptr,
};

llama_sampler * novel_bias_init(
        const llama_vocab * vocab, int soft_target, int hard_ceiling)
{
    auto * c = new novel_bias_ctx{
        soft_target, hard_ceiling, 0, 0, std::string(), {}, vocab
    };
    const int n_vocab = llama_vocab_n_tokens(vocab);
    for (int t = 0; t < n_vocab; t++) {
        std::string s = common_token_to_piece(vocab, t, /*special=*/true);
        if (!s.empty() && (unsigned char) s[0] == '<') {
            c->lt_tokens.insert(t);
        }
    }
    LOGi("novel_bias: soft=%d hard=%d, %d '<'-leading tokens precomputed",
         soft_target, hard_ceiling, (int) c->lt_tokens.size());
    return llama_sampler_init(&NOVEL_BIAS_IFACE, c);
}

// llama.cpp's GBNF parser does not consume newlines inside a top-level rule
// body — see src/llama-grammar.cpp parse_space() with newline_ok=false. Each
// rule must be on a single physical line; multi-line continuation is only
// permitted inside ( ... ) groups (where parse_alternates is is_nested=true).
const char * NOVEL_GRAMMAR_STR = R"(
root    ::= "<novel>" content "</novel>" ws "<option_1>" content "</option_1>" ws "<option_2>" content "</option_2>" ws "<option_3>" content "</option_3>"
content ::= [^<]+
ws      ::= [ \n\r\t]*
)";

} // namespace

// ─── nativeGenerate ───────────────────────────────────────────────────────────
// Formats system + user prompt with the chat template, builds a fresh sampler
// chain for this call (temperature + optional grammar + optional novel-length
// bias + dist), runs the full generation loop, calls jCallback per token.
extern "C" JNIEXPORT void JNICALL
Java_com_example_ppo_LlamaEngine_nativeGenerate(
        JNIEnv * env, jobject,
        jstring jSystemPrompt, jstring jUserPrompt,
        jint jNPredict,
        jfloat jTemp, jboolean jNovel,
        jint jSoftTarget, jint jHardCeiling,
        jobject jCallback)
{
    // Held for the entire generation; nativeFree blocks on this mutex so it
    // cannot tear g_context/g_model down while llama_decode is running.
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    g_should_stop.store(false);

    if (!g_loaded) {
        LOGe("nativeGenerate called but model not loaded");
        return;
    }

    // Look up the Kotlin callback method once before the generation loop.
    jclass    cbClass = env->GetObjectClass(jCallback);
    jmethodID onToken = env->GetMethodID(cbClass, "onToken", "(Ljava/lang/String;)V");
    if (!onToken) {
        LOGe("Could not find onToken method on callback object");
        return;
    }

    // ── Build sampler chain for this call ─────────────────────────────────────
    // Order: temp → (grammar → novel_bias)? → dist. novel_bias sits after
    // grammar so its bias only adds to grammar-valid tokens, and after temp so
    // the bias is in absolute logit units.
    const llama_vocab * vocab = llama_model_get_vocab(g_model);
    llama_sampler_chain_params lparams = llama_sampler_chain_default_params();
    llama_sampler * sampler = llama_sampler_chain_init(lparams);
    llama_sampler_chain_add(sampler, llama_sampler_init_temp((float) jTemp));
    if (jNovel == JNI_TRUE) {
        llama_sampler * grammar_smpl =
            llama_sampler_init_grammar(vocab, NOVEL_GRAMMAR_STR, "root");
        if (!grammar_smpl) {
            LOGe("grammar init failed; aborting generation");
            llama_sampler_free(sampler);
            jstring err = env->NewStringUTF("[ERROR: grammar init failed]");
            env->CallVoidMethod(jCallback, onToken, err);
            env->DeleteLocalRef(err);
            return;
        }
        llama_sampler_chain_add(sampler, grammar_smpl);
        llama_sampler_chain_add(sampler,
            novel_bias_init(vocab, (int) jSoftTarget, (int) jHardCeiling));
    }
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // ── Format system + user message with the model's chat template ───────────
    const char * sys_raw  = env->GetStringUTFChars(jSystemPrompt, nullptr);
    const char * user_raw = env->GetStringUTFChars(jUserPrompt,   nullptr);

    std::vector<common_chat_msg> history;
    if (sys_raw && strlen(sys_raw) > 0) {
        common_chat_msg sys_msg;
        sys_msg.role    = "system";
        sys_msg.content = sys_raw;
        history.push_back(sys_msg);
    }

    common_chat_msg user_msg;
    user_msg.role    = "user";
    user_msg.content = user_raw;

    // add_ass=true appends the assistant turn opener so the model fills it in
    common_chat_templates_inputs tinputs;
    tinputs.use_jinja            = false;
    tinputs.enable_thinking      = false;
    tinputs.add_bos              = false;
    tinputs.add_eos              = false;
    if (!history.empty()) tinputs.messages = history;
    tinputs.messages.push_back(user_msg);
    tinputs.add_generation_prompt = true;
    const std::string formatted = common_chat_templates_apply(g_templates.get(), tinputs).prompt;

    env->ReleaseStringUTFChars(jSystemPrompt, sys_raw);
    env->ReleaseStringUTFChars(jUserPrompt,   user_raw);

    LOGd("Formatted prompt:\n%s", formatted.c_str());

    // ── Tokenize ──────────────────────────────────────────────────────────────
    auto tokens = common_tokenize(g_context, formatted,
                                  /* add_special */ true,
                                  /* parse_special */ true);
    const int n_tokens = (int) tokens.size();
    LOGi("Prompt tokens: %d, n_predict: %d", n_tokens, (int) jNPredict);

    if (n_tokens == 0) {
        jstring err = env->NewStringUTF("[ERROR: tokenization produced 0 tokens]");
        env->CallVoidMethod(jCallback, onToken, err);
        env->DeleteLocalRef(err);
        llama_sampler_free(sampler);
        return;
    }

    // ── Clear KV cache from any prior call ────────────────────────────────────
    llama_memory_clear(llama_get_memory(g_context), false);

    // ── Prefill: decode the prompt in BATCH_SIZE chunks ───────────────────────
    LOGi("stage: prefill begin (n_tokens=%d, batch=%d)", n_tokens, BATCH_SIZE);
    llama_pos pos = 0;
    for (int i = 0; i < n_tokens; ) {
        if (g_should_stop.load()) {
            LOGi("stage: prefill stopped at i=%d (between-chunk poll)", i);
            llama_sampler_free(sampler);
            return;
        }
        const int chunk = std::min(n_tokens - i, BATCH_SIZE);
        common_batch_clear(g_batch);
        for (int j = 0; j < chunk; j++) {
            const bool want_logit = (j == chunk - 1) && (i + chunk == n_tokens);
            common_batch_add(g_batch, tokens[i + j], pos + j, {0}, want_logit);
        }
        if (llama_decode(g_context, g_batch) != 0) {
            if (g_should_stop.load()) {
                LOGi("prefill stage: aborted via abort_callback at i=%d", i);
                llama_sampler_free(sampler);
                return;
            }
            LOGe("llama_decode failed during prefill at i=%d", i);
            jstring err = env->NewStringUTF("[ERROR: prefill failed]");
            env->CallVoidMethod(jCallback, onToken, err);
            env->DeleteLocalRef(err);
            llama_sampler_free(sampler);
            return;
        }
        i   += chunk;
        pos += chunk;
    }

    LOGi("stage: prefill complete");

    // ── Generation loop ───────────────────────────────────────────────────────
    LOGi("stage: generation begin (n_predict=%d)", (int) jNPredict);
    std::string token_cache; // accumulate incomplete UTF-8 sequences

    const int n_predict = (int) jNPredict;
    for (int step = 0; step < n_predict; step++) {
        if (g_should_stop.load()) {
            LOGi("stage: generation stopped at step %d", step);
            llama_sampler_free(sampler);
            return;
        }
        // llama_sampler_sample() already calls llama_sampler_accept() internally
        // (see llama-sampler.cpp:870). Calling it again here was double-accepting
        // every token, which the grammar sampler crashed on (second walk tries to
        // re-consume the same piece against the already-advanced stack).
        const llama_token new_tok = llama_sampler_sample(sampler, g_context, -1);

        if (llama_vocab_is_eog(llama_model_get_vocab(g_model), new_tok)) {
            LOGi("EOG at step %d", step);
            break;
        }

        // Token → text (may be a partial UTF-8 byte for multi-byte chars)
        const std::string piece = common_token_to_piece(g_context, new_tok);
        token_cache += piece;

        // Flush only when token_cache ends on a complete UTF-8 codepoint.
        // Walk back past any continuation bytes (10xxxxxx) to find the leading byte,
        // then check that we have received as many bytes as the leading byte requires.
        {
            int n = (int) token_cache.size();
            int i = n - 1;
            while (i > 0 && ((unsigned char)token_cache[i] & 0xC0) == 0x80) i--;
            unsigned char lead = (unsigned char) token_cache[i];
            int need = 1;
            if      ((lead & 0xE0) == 0xC0) need = 2;  // 110xxxxx → 2-byte
            else if ((lead & 0xF0) == 0xE0) need = 3;  // 1110xxxx → 3-byte
            else if ((lead & 0xF8) == 0xF0) need = 4;  // 11110xxx → 4-byte
            if (n - i >= need) {
                jstring jPiece = env->NewStringUTF(token_cache.c_str());
                env->CallVoidMethod(jCallback, onToken, jPiece);
                env->DeleteLocalRef(jPiece);
                token_cache.clear();
            }
        }

        // Feed the new token back into the context
        common_batch_clear(g_batch);
        common_batch_add(g_batch, new_tok, pos, {0}, true);
        if (llama_decode(g_context, g_batch) != 0) {
            if (g_should_stop.load()) {
                LOGi("generation stage: aborted via abort_callback at step %d", step);
                llama_sampler_free(sampler);
                return;
            }
            LOGe("llama_decode failed at generation step %d", step);
            break;
        }
        pos++;
    }

    // Flush anything left in the cache
    if (!token_cache.empty()) {
        jstring jPiece = env->NewStringUTF(token_cache.c_str());
        env->CallVoidMethod(jCallback, onToken, jPiece);
        env->DeleteLocalRef(jPiece);
    }

    llama_sampler_free(sampler);
    LOGi("stage: generation complete");
}

// ─── nativeRequestStop ────────────────────────────────────────────────────────
// Lock-free signal — safe to call from any thread without blocking. The Kotlin
// side calls this *before* nativeFree so the IO thread starts winding down
// during Activity teardown, shrinking the wait when nativeFree finally locks.
extern "C" JNIEXPORT void JNICALL
Java_com_example_ppo_LlamaEngine_nativeRequestStop(JNIEnv *, jobject) {
    g_should_stop.store(true);
}

// ─── nativeFree ───────────────────────────────────────────────────────────────
// Set the stop flag *before* taking the engine mutex so any in-flight generate
// observes it and returns; only then do we acquire the lock and tear down. This
// is what prevents the SIGABRT seen when llama_free(g_context) happened during
// llama_decode (use-after-free in ggml_backend_sched_get_tensor_backend).
extern "C" JNIEXPORT void JNICALL
Java_com_example_ppo_LlamaEngine_nativeFree(JNIEnv *, jobject) {
    g_should_stop.store(true);
    std::lock_guard<std::mutex> lock(g_engine_mutex);
    if (!g_loaded) return;
    g_loaded = false;
    g_templates.reset();
    llama_batch_free(g_batch);
    if (g_context)  { llama_free(g_context);            g_context  = nullptr; }
    if (g_model)    { llama_model_free(g_model);        g_model    = nullptr; }
    // Backend stays initialized for the process lifetime — nativeInit may run
    // again on a fresh MainActivity entry that races this teardown, and the
    // backend init/free pair is not serialized by g_engine_mutex.
    LOGi("Model freed");
}
