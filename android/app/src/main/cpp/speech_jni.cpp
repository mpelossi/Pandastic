#include <jni.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <algorithm>
#include <memory>
#include <string>
#include <thread>
#include <vector>
#include "whisper.h"

namespace {
struct Cancellation {
    JavaVM * vm;
    jobject flag;
    jmethodID get;
    bool cancelled() const {
        // ggml can call the abort hook on a worker it created itself.
        JNIEnv * env = nullptr;
        bool attached = vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_EDETACHED;
        if (attached && vm->AttachCurrentThread(&env, nullptr) != JNI_OK) return true;
        const bool value = env->CallBooleanMethod(flag, get);
        if (attached) vm->DetachCurrentThread();
        return value;
    }
};
void fail(JNIEnv * env, const char * message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message);
}
void log_message(ggml_log_level level, const char * text, void *) {
    // Never log transcripts or microphone samples.
    if (level == GGML_LOG_LEVEL_ERROR) __android_log_print(ANDROID_LOG_ERROR, "PandasticSpeech", "%s", text);
}
}

extern "C" JNIEXPORT jstring JNICALL
Java_org_pandastic_relay_OfflineSpeech_transcribe(JNIEnv * env, jclass, jobject manager,
        jshortArray pcm, jint length, jstring language, jobject cancelled) {
    try {
        if (length <= 0 || length > 16000 * 30 || length > env->GetArrayLength(pcm)) {
            fail(env, "Invalid speech sample length"); return nullptr;
        }
        JavaVM * vm;
        env->GetJavaVM(&vm);
        Cancellation cancellation{vm, env->NewGlobalRef(cancelled), env->GetMethodID(env->GetObjectClass(cancelled), "get", "()Z")};
        struct FlagCleanup { JNIEnv * env; jobject ref; ~FlagCleanup() { env->DeleteGlobalRef(ref); } } flag{env, cancellation.flag};
        if (cancellation.cancelled()) return env->NewStringUTF("");
        std::vector<jshort> shorts(length);
        env->GetShortArrayRegion(pcm, 0, length, shorts.data());
        std::vector<float> samples(length);
        for (int i = 0; i < length; ++i) samples[i] = shorts[i] / 32768.0f;
        const char * lang = env->GetStringUTFChars(language, nullptr);
        const std::string selected = std::string(lang) == "sw" ? "sw" : "en";
        env->ReleaseStringUTFChars(language, lang);

        AAsset * asset = AAssetManager_open(AAssetManager_fromJava(env, manager), "speech/ggml-tiny-q5_1.bin", AASSET_MODE_STREAMING);
        if (!asset) { fail(env, "Bundled speech model missing"); return nullptr; }
        whisper_model_loader loader{};
        loader.context = asset;
        loader.read = [](void * p, void * out, size_t size) -> size_t {
            const int count = AAsset_read(static_cast<AAsset *>(p), out, size);
            return count > 0 ? static_cast<size_t>(count) : 0;
        };
        loader.eof = [](void * p) { return AAsset_getRemainingLength64(static_cast<AAsset *>(p)) == 0; };
        loader.close = [](void * p) { AAsset_close(static_cast<AAsset *>(p)); };
        whisper_log_set(log_message, nullptr);
        auto context_params = whisper_context_default_params();
        context_params.use_gpu = false;
        std::unique_ptr<whisper_context, decltype(&whisper_free)> context(whisper_init_with_params(&loader, context_params), whisper_free);
        if (!context) { fail(env, "Could not load Tiny speech model"); return nullptr; }
        if (cancellation.cancelled()) return env->NewStringUTF("");
        auto params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
        params.n_threads = std::max(1u, std::min(4u, std::thread::hardware_concurrency()));
        params.language = selected.c_str();
        params.translate = false;
        params.no_context = true;
        params.no_timestamps = true;
        params.print_special = params.print_progress = params.print_realtime = params.print_timestamps = false;
        params.suppress_nst = true;
        params.temperature_inc = 0;
        params.abort_callback = [](void * p) { return static_cast<Cancellation *>(p)->cancelled(); };
        params.abort_callback_user_data = &cancellation;
        params.encoder_begin_callback = [](whisper_context *, whisper_state *, void * p) { return !static_cast<Cancellation *>(p)->cancelled(); };
        params.encoder_begin_callback_user_data = &cancellation;
        const int result = whisper_full(context.get(), params, samples.data(), length);
        if (cancellation.cancelled()) return env->NewStringUTF("");
        if (result != 0) { fail(env, "Offline speech inference failed"); return nullptr; }
        std::string text;
        for (int i = 0; i < whisper_full_n_segments(context.get()); ++i) {
            if (whisper_full_get_segment_no_speech_prob(context.get(), i) < 0.6f)
                text += whisper_full_get_segment_text(context.get(), i);
        }
        // Whisper emits normal UTF-8; JNI NewStringUTF expects modified UTF-8.
        jbyteArray bytes = env->NewByteArray(text.size());
        env->SetByteArrayRegion(bytes, 0, text.size(), reinterpret_cast<const jbyte *>(text.data()));
        jclass strings = env->FindClass("java/lang/String");
        return static_cast<jstring>(env->NewObject(strings, env->GetMethodID(strings, "<init>", "([BLjava/lang/String;)V"), bytes, env->NewStringUTF("UTF-8")));
    } catch (const std::exception & error) {
        fail(env, error.what()); return nullptr;
    }
}
