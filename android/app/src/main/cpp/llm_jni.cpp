// Minimal JNI wrapper around llama.cpp: load a GGUF once, then run grammar-constrained,
// greedy completions. Callers serialise access (BrainHost runs one inference at a time).
#include <jni.h>
#include <chrono>
#include <android/log.h>
#include <string>
#include <vector>
#include "llama.h"

#define LOG_TAG "PandasticLlm"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
using Clock = std::chrono::steady_clock;

struct Llm {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    Clock::time_point deadline;
    std::vector<uint8_t> prefixState;  // memory after the fixed system prompt, restored for every SMS
};

bool decodeAll(Llm *llm, std::vector<llama_token> &tokens) {
    llama_batch batch = llama_batch_get_one(tokens.data(), static_cast<int32_t>(tokens.size()));
    return llama_decode(llm->ctx, batch) == 0;
}

std::vector<llama_token> tokenize(Llm *llm, const std::string &text, bool addSpecial) {
    int count = -llama_tokenize(llm->vocab, text.c_str(), static_cast<int32_t>(text.size()), nullptr, 0, addSpecial, true);
    std::vector<llama_token> tokens(count > 0 ? count : 0);
    if (count > 0 && llama_tokenize(llm->vocab, text.c_str(), static_cast<int32_t>(text.size()), tokens.data(), count, addSpecial, true) < 0)
        tokens.clear();
    return tokens;
}

/** Called by llama.cpp between compute steps; true stops the current decode. */
bool pastDeadline(void *data) {
    return Clock::now() > static_cast<Llm *>(data)->deadline;
}

std::string toString(JNIEnv *env, jstring value) {
    if (value == nullptr) return {};
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

/** Drops a trailing incomplete UTF-8 sequence so NewStringUTF never sees broken bytes. */
std::string trimUtf8(const std::string &text) {
    size_t end = text.size();
    size_t i = end;
    int back = 0;
    while (i > 0 && back < 4 && (static_cast<unsigned char>(text[i - 1]) & 0xC0) == 0x80) { --i; ++back; }
    if (i == 0) return text;
    unsigned char lead = static_cast<unsigned char>(text[i - 1]);
    size_t need = lead >= 0xF0 ? 4 : lead >= 0xE0 ? 3 : lead >= 0xC0 ? 2 : 1;
    if (need > 1 && static_cast<size_t>(back) + 1 < need) return text.substr(0, i - 1);
    return text;
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_org_pandastic_relay_brain_LlmNlu_nativeLoad(JNIEnv *env, jclass, jstring path, jint nCtx, jint nThreads) {
    llama_backend_init();
    llama_model_params modelParams = llama_model_default_params();
    modelParams.load_mode = LLAMA_LOAD_MODE_MMAP;  // weights stay in the page cache instead of the app heap
    auto *llm = new Llm();
    llm->model = llama_model_load_from_file(toString(env, path).c_str(), modelParams);
    if (llm->model == nullptr) { LOGE("Could not load model"); delete llm; return 0; }
    llama_context_params contextParams = llama_context_default_params();
    contextParams.n_ctx = static_cast<uint32_t>(nCtx);   // set explicitly: the model default would allocate GBs
    contextParams.n_batch = static_cast<uint32_t>(nCtx);
    contextParams.n_threads = nThreads;
    contextParams.n_threads_batch = nThreads;
    llm->ctx = llama_init_from_model(llm->model, contextParams);
    if (llm->ctx == nullptr) { LOGE("Could not create context"); llama_model_free(llm->model); delete llm; return 0; }
    llm->vocab = llama_model_get_vocab(llm->model);
    llm->deadline = Clock::time_point::max();
    llama_set_abort_callback(llm->ctx, pastDeadline, llm);
    return reinterpret_cast<jlong>(llm);
}

namespace {
/** One greedy completion; usePrefix restores the cached system prompt (SMS reading), otherwise the prompt is whole. */
jstring complete(JNIEnv *env, Llm *llm, const std::string &prompt, const std::string &grammar, int maxTokens,
                 int timeoutMs, bool usePrefix) {

    // A slow phone must never hold up an SMS reply: past the deadline the caller falls back to keywords.
    llm->deadline = Clock::now() + std::chrono::milliseconds(timeoutMs);
    llama_memory_clear(llama_get_memory(llm->ctx), true);
    bool restored = usePrefix && !llm->prefixState.empty()
        && llama_state_seq_set_data(llm->ctx, llm->prefixState.data(), llm->prefixState.size(), 0) > 0;
    // With a cached prefix only the SMS part is evaluated; otherwise the whole prompt.
    std::vector<llama_token> tokens = tokenize(llm, prompt, !restored);
    int count = static_cast<int>(tokens.size());
    if (count == 0) return nullptr;
    if (count + maxTokens > static_cast<int>(llama_n_ctx(llm->ctx))) { LOGE("Prompt too long: %d tokens", count); return nullptr; }

    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!grammar.empty()) {
        llama_sampler *constrained = llama_sampler_init_grammar(llm->vocab, grammar.c_str(), "root");
        if (constrained == nullptr) { LOGE("Invalid grammar"); llama_sampler_free(sampler); return nullptr; }
        llama_sampler_chain_add(sampler, constrained);
    } else {  // free text: a small model left greedy repeats itself
        llama_sampler_chain_add(sampler, llama_sampler_init_penalties(llama_vocab_n_tokens(llm->vocab), 64, 1.15f, 0.0f, 0.0f));
    }
    llama_sampler_chain_add(sampler, llama_sampler_init_greedy());

    std::string output;
    bool ok = decodeAll(llm, tokens);
    llama_batch batch;
    for (int i = 0; ok && i < maxTokens && Clock::now() < llm->deadline; ++i) {
        llama_token token = llama_sampler_sample(sampler, llm->ctx, -1);
        if (llama_vocab_is_eog(llm->vocab, token)) break;
        char piece[256];
        int n = llama_token_to_piece(llm->vocab, token, piece, sizeof(piece), 0, false);
        if (n > 0) output.append(piece, n);
        batch = llama_batch_get_one(&token, 1);
        ok = llama_decode(llm->ctx, batch) == 0;
    }
    llama_sampler_free(sampler);
    if (!ok || Clock::now() >= llm->deadline) { LOGE("LLM stopped: %s", ok ? "time budget" : "decode failed"); return nullptr; }
    return env->NewStringUTF(trimUtf8(output).c_str());
}
}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_org_pandastic_relay_brain_LlmNlu_nativeComplete(JNIEnv *env, jclass, jlong handle, jstring jprompt,
                                                     jstring jgrammar, jint maxTokens, jint timeoutMs) {
    auto *llm = reinterpret_cast<Llm *>(handle);
    if (llm == nullptr) return nullptr;
    return complete(env, llm, toString(env, jprompt), toString(env, jgrammar), maxTokens, timeoutMs, true);
}

/** Free text from a whole prompt (no grammar, no cached prefix): the grounded reply writer. */
extern "C" JNIEXPORT jstring JNICALL
Java_org_pandastic_relay_brain_LlmNlu_nativeWrite(JNIEnv *env, jclass, jlong handle, jstring jprompt, jint maxTokens,
                                                  jint timeoutMs) {
    auto *llm = reinterpret_cast<Llm *>(handle);
    if (llm == nullptr) return nullptr;
    return complete(env, llm, toString(env, jprompt), std::string(), maxTokens, timeoutMs, false);
}

/** Evaluates the fixed prompt prefix once and keeps the resulting memory for later completions. */
extern "C" JNIEXPORT jboolean JNICALL
Java_org_pandastic_relay_brain_LlmNlu_nativeSetPrefix(JNIEnv *env, jclass, jlong handle, jstring jprefix) {
    auto *llm = reinterpret_cast<Llm *>(handle);
    if (llm == nullptr) return JNI_FALSE;
    llm->prefixState.clear();
    llm->deadline = Clock::time_point::max();
    llama_memory_clear(llama_get_memory(llm->ctx), true);
    std::vector<llama_token> tokens = tokenize(llm, toString(env, jprefix), true);
    if (tokens.empty() || !decodeAll(llm, tokens)) { LOGE("Prefix evaluation failed"); return JNI_FALSE; }
    llm->prefixState.resize(llama_state_seq_get_size(llm->ctx, 0));
    size_t written = llama_state_seq_get_data(llm->ctx, llm->prefixState.data(), llm->prefixState.size(), 0);
    if (written == 0) { llm->prefixState.clear(); return JNI_FALSE; }
    llm->prefixState.resize(written);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_org_pandastic_relay_brain_LlmNlu_nativeFree(JNIEnv *, jclass, jlong handle) {
    auto *llm = reinterpret_cast<Llm *>(handle);
    if (llm == nullptr) return;
    llama_free(llm->ctx);
    llama_model_free(llm->model);
    delete llm;
}
