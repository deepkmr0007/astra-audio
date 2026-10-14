// JNI bridge between Kotlin (app.astra.engine.whisper.WhisperNative) and whisper.cpp.
//
// Design rules:
//  - No calls back into Java from native threads. Progress, cancellation and newly finished
//    segments are exchanged through atomics / a mutex-protected queue that Kotlin polls.
//  - Text crosses the boundary as raw UTF-8 BYTES (jbyteArray), never via NewStringUTF:
//    Whisper tokens can split a multi-byte Devanagari character, which would be invalid
//    "modified UTF-8" and abort the VM. Kotlin decodes and re-assembles words safely.
//  - Logging from whisper/ggml is silenced: nothing (and certainly no transcript text) is logged.
//  - No networking code is compiled in (whisper.cpp is built without curl).

#include <jni.h>

#include <atomic>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "whisper.h"
#include "ggml.h"
#include "ggml-backend.h"

namespace {

struct PendingSegment {
    int64_t t0;
    int64_t t1;
    std::string text;
};

struct Handle {
    whisper_context *ctx = nullptr;
    std::atomic<bool> cancel{false};
    std::atomic<int> progress{0};
    std::mutex mutex;
    std::vector<PendingSegment> pending;
};

void silent_log(enum ggml_log_level, const char *, void *) {}

Handle *H(jlong h) { return reinterpret_cast<Handle *>(static_cast<intptr_t>(h)); }
void *P(jlong h) { return reinterpret_cast<void *>(static_cast<intptr_t>(h)); }
Handle *U(void *user) { return static_cast<Handle *>(user); }

jbyteArray toBytes(JNIEnv *env, const char *s) {
    if (s == nullptr) s = "";
    const auto n = static_cast<jsize>(std::strlen(s));
    jbyteArray arr = env->NewByteArray(n);
    if (arr != nullptr && n > 0) env->SetByteArrayRegion(arr, 0, n, reinterpret_cast<const jbyte *>(s));
    return arr;
}

std::string fromJString(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars ? chars : "";
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

bool abortCallback(void *user) {
    return U(user)->cancel.load();
}

void progressCallback(whisper_context *, whisper_state *, int progress, void *user) {
    U(user)->progress.store(progress);
}

void newSegmentCallback(whisper_context *ctx, whisper_state *state, int nNew, void *user) {
    Handle *h = U(user);
    const int n = whisper_full_n_segments_from_state(state);
    std::lock_guard<std::mutex> lock(h->mutex);
    for (int i = n - nNew; i < n; ++i) {
        if (i < 0) continue;
        h->pending.push_back(PendingSegment{
            whisper_full_get_segment_t0_from_state(state, i),
            whisper_full_get_segment_t1_from_state(state, i),
            whisper_full_get_segment_text_from_state(state, i) ? whisper_full_get_segment_text_from_state(state, i) : ""});
    }
    (void) ctx;
}

}  // namespace

extern "C" {

JNIEXPORT jstring JNICALL
Java_app_astra_engine_whisper_WhisperNative_nativeVersion(JNIEnv *env, jclass) {
    return env->NewStringUTF(whisper_version());
}

JNIEXPORT jstring JNICALL
Java_app_astra_engine_whisper_WhisperNative_nativeSystemInfo(JNIEnv *env, jclass) {
    return env->NewStringUTF(whisper_print_system_info());
}

// Loads the CPU backend. With ASTRA_CPU_VARIANTS, ggml scores every libggml-cpu-<variant>.so in the
// app's native library directory against this CPU's features and loads only the best match.
// Safe to call repeatedly; returns the number of compute devices available afterwards.
JNIEXPORT jint JNICALL
Java_app_astra_engine_whisper_WhisperNative_nativeLoadBackends(JNIEnv *env, jclass, jstring libDir) {
    static std::once_flag once;
    const std::string dir = fromJString(env, libDir);
    std::call_once(once, [&]() {
        whisper_log_set(silent_log, nullptr);
        ggml_log_set(silent_log, nullptr);
#if defined(ASTRA_CPU_VARIANTS)
        ggml_backend_load_all_from_path(dir.empty() ? nullptr : dir.c_str());
#endif
    });
    return static_cast<jint>(ggml_backend_dev_count());
}

// Human-readable description of the compute backend in use, e.g.
// "CPU (android_armv8.2_2): NEON DOTPROD FP16_VA" — shown on the About and job screens.
JNIEXPORT jstring JNICALL
Java_app_astra_engine_whisper_WhisperNative_nativeBackendInfo(JNIEnv *env, jclass) {
    std::string out;
    for (size_t i = 0; i < ggml_backend_reg_count(); ++i) {
        ggml_backend_reg_t reg = ggml_backend_reg_get(i);
        if (!out.empty()) out += "; ";
        out += ggml_backend_reg_name(reg);
        typedef ggml_backend_feature *(*get_features_t)(ggml_backend_reg_t);
        auto get_features = (get_features_t) ggml_backend_reg_get_proc_address(reg, "ggml_backend_get_features");
        if (get_features) {
            std::string feats;
            for (ggml_backend_feature *f = get_features(reg); f && f->name; ++f) {
                if (std::strcmp(f->value, "1") == 0) { feats += " "; feats += f->name; }
                else if (std::strcmp(f->name, "CPU_VARIANT") == 0 || std::strcmp(f->name, "BACKEND_NAME") == 0) { feats += " ["; feats += f->value; feats += "]"; }
            }
            if (!feats.empty()) out += ":" + feats;
        }
    }
    if (out.empty()) out = "no backend loaded";
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jlong JNICALL
Java_app_astra_engine_whisper_WhisperNative_nativeInit(JNIEnv *env, jclass, jstring modelPath) {
    whisper_log_set(silent_log, nullptr);
    if (ggml_backend_dev_count() == 0) return 0;  // CPU backend failed to load
    const std::string path = fromJString(env, modelPath);
    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;      // CPU only: no GPU backend is compiled for Android in this build.
    cparams.flash_attn = true;    // fused attention kernels: faster and less memory on CPU
    whisper_context *ctx = whisper_init_from_file_with_params(path.c_str(), cparams);
    if (ctx == nullptr) return 0;
    auto *h = new Handle();
    h->ctx = ctx;
    return static_cast<jlong>(reinterpret_cast<intptr_t>(h));
}

JNIEXPORT void JNICALL
Java_app_astra_engine_whisper_WhisperNative_nativeFree(JNIEnv *, jclass, jlong handle) {
    Handle *h = H(handle);
    if (h == nullptr) return;
    if (h->ctx) whisper_free(h->ctx);
    delete h;
}

JNIEXPORT jboolean JNICALL
Java_app_astra_engine_whisper_WhisperNative_nativeIsMultilingual(JNIEnv *, jclass, jlong handle) {
    return whisper_is_multilingual(H(handle)->ctx) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_app_astra_engine_whisper_WhisperNative_nativeRequestCancel(JNIEnv *, jclass, jlong handle) {
    H(handle)->cancel.store(true);
}

JNIEXPORT jint JNICALL
Java_app_astra_engine_whisper_WhisperNative_nativeProgress(JNIEnv *, jclass, jlong handle) {
    return H(handle)->progress.load();
}

// Returns pending finished segments as [t0, t1, bytes...] triples flattened into an Object[]:
// even indices = long[2]{t0,t1}, odd indices = byte[] UTF-8 text. Empty array when none.
JNIEXPORT jobjectArray JNICALL
Java_app_astra_engine_whisper_WhisperNative_nativeDrainSegments(JNIEnv *env, jclass, jlong handle) {
    Handle *h = H(handle);
    std::vector<PendingSegment> taken;
    {
        std::lock_guard<std::mutex> lock(h->mutex);
        taken.swap(h->pending);
    }
    jclass objectClass = env->FindClass("java/lang/Object");
    jobjectArray out = env->NewObjectArray(static_cast<jsize>(taken.size() * 2), objectClass, nullptr);
    for (size_t i = 0; i < taken.size(); ++i) {
        jlongArray times = env->NewLongArray(2);
        jlong t[2] = {taken[i].t0, taken[i].t1};
        env->SetLongArrayRegion(times, 0, 2, t);
        env->SetObjectArrayElement(out, static_cast<jsize>(2 * i), times);
        jbyteArray text = toBytes(env, taken[i].text.c_str());
        env->SetObjectArrayElement(out, static_cast<jsize>(2 * i + 1), text);
        env->DeleteLocalRef(times);
        env->DeleteLocalRef(text);
    }
    return out;
}

// Runs whisper_full on 16 kHz mono float PCM. Returns 0 on success, -1 cancelled, >0 whisper error.
JNIEXPORT jint JNICALL
Java_app_astra_engine_whisper_WhisperNative_nativeTranscribe(
        JNIEnv *env, jclass, jlong handle, jfloatArray samples,
        jint nThreads, jstring language, jboolean translate,
        jint beamSize, jint bestOf, jboolean temperatureFallback,
        jstring initialPrompt, jboolean tokenTimestamps, jboolean speakerTurns) {
    Handle *h = H(handle);
    h->cancel.store(false);
    h->progress.store(0);
    {
        std::lock_guard<std::mutex> lock(h->mutex);
        h->pending.clear();
    }

    const bool beam = beamSize > 0;
    whisper_full_params p = whisper_full_default_params(beam ? WHISPER_SAMPLING_BEAM_SEARCH : WHISPER_SAMPLING_GREEDY);
    p.n_threads = nThreads > 0 ? nThreads : 4;
    p.print_realtime = false;
    p.print_progress = false;
    p.print_timestamps = false;
    p.print_special = false;
    p.translate = translate == JNI_TRUE;
    p.no_timestamps = false;
    p.token_timestamps = tokenTimestamps == JNI_TRUE;
    p.tdrz_enable = speakerTurns == JNI_TRUE;
    p.detect_language = false;  // true would stop after detection; "auto" language below detects AND transcribes.
    if (beam) {
        p.beam_search.beam_size = beamSize;
    } else {
        p.greedy.best_of = bestOf > 0 ? bestOf : 1;
    }
    if (temperatureFallback != JNI_TRUE) p.temperature_inc = 0.0f;

    const std::string lang = fromJString(env, language);
    p.language = (lang.empty() || lang == "auto") ? "auto" : lang.c_str();
    const std::string prompt = fromJString(env, initialPrompt);
    p.initial_prompt = prompt.empty() ? nullptr : prompt.c_str();

    p.abort_callback = abortCallback;
    p.abort_callback_user_data = P(handle);
    p.progress_callback = progressCallback;
    p.progress_callback_user_data = P(handle);
    p.new_segment_callback = newSegmentCallback;
    p.new_segment_callback_user_data = P(handle);

    const jsize n = env->GetArrayLength(samples);
    jfloat *data = env->GetFloatArrayElements(samples, nullptr);
    if (data == nullptr) return 2;
    const int rc = whisper_full(h->ctx, p, data, n);
    env->ReleaseFloatArrayElements(samples, data, JNI_ABORT);

    if (h->cancel.load()) return -1;
    if (rc == 0) h->progress.store(100);
    return rc;
}

JNIEXPORT jstring JNICALL
Java_app_astra_engine_whisper_WhisperNative_nativeDetectedLanguage(JNIEnv *env, jclass, jlong handle) {
    const int id = whisper_full_lang_id(H(handle)->ctx);
    const char *s = id >= 0 ? whisper_lang_str(id) : nullptr;
    return s ? env->NewStringUTF(s) : nullptr;
}

JNIEXPORT jint JNICALL
Java_app_astra_engine_whisper_WhisperNative_nativeSegmentCount(JNIEnv *, jclass, jlong handle) {
    return whisper_full_n_segments(H(handle)->ctx);
}

// long[3] = {t0_centiseconds, t1_centiseconds, speakerTurnNext(0/1)}
JNIEXPORT jlongArray JNICALL
Java_app_astra_engine_whisper_WhisperNative_nativeSegmentInfo(JNIEnv *env, jclass, jlong handle, jint i) {
    whisper_context *ctx = H(handle)->ctx;
    jlong v[3] = {whisper_full_get_segment_t0(ctx, i), whisper_full_get_segment_t1(ctx, i),
                  whisper_full_get_segment_speaker_turn_next(ctx, i) ? 1 : 0};
    jlongArray out = env->NewLongArray(3);
    env->SetLongArrayRegion(out, 0, 3, v);
    return out;
}

JNIEXPORT jbyteArray JNICALL
Java_app_astra_engine_whisper_WhisperNative_nativeSegmentText(JNIEnv *env, jclass, jlong handle, jint i) {
    return toBytes(env, whisper_full_get_segment_text(H(handle)->ctx, i));
}

JNIEXPORT jint JNICALL
Java_app_astra_engine_whisper_WhisperNative_nativeTokenCount(JNIEnv *, jclass, jlong handle, jint i) {
    return whisper_full_n_tokens(H(handle)->ctx, i);
}

// Token text as raw bytes. Special tokens (id >= EOT) return null.
JNIEXPORT jbyteArray JNICALL
Java_app_astra_engine_whisper_WhisperNative_nativeTokenText(JNIEnv *env, jclass, jlong handle, jint i, jint j) {
    whisper_context *ctx = H(handle)->ctx;
    if (whisper_full_get_token_id(ctx, i, j) >= whisper_token_eot(ctx)) return nullptr;
    return toBytes(env, whisper_full_get_token_text(ctx, i, j));
}

// float[3] = {probability, t0_centiseconds, t1_centiseconds}; timings are -1 if not computed.
JNIEXPORT jfloatArray JNICALL
Java_app_astra_engine_whisper_WhisperNative_nativeTokenData(JNIEnv *env, jclass, jlong handle, jint i, jint j, jboolean withTimes) {
    whisper_token_data d = whisper_full_get_token_data(H(handle)->ctx, i, j);
    jfloat v[3] = {d.p, withTimes ? static_cast<jfloat>(d.t0) : -1.0f, withTimes ? static_cast<jfloat>(d.t1) : -1.0f};
    jfloatArray out = env->NewFloatArray(3);
    env->SetFloatArrayRegion(out, 0, 3, v);
    return out;
}

}  // extern "C"
