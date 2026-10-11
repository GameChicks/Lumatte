#include <jni.h>
#include <android/bitmap.h>
#include <algorithm>
#include <atomic>
#include <array>
#include <cmath>
#include <cstdint>
#include <limits>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>
#include "spektrafilm/spk_engine.h"
#include "film_diffusion.hpp"
#include "film_seed.hpp"
#include "film_shader_resources.hpp"

namespace {
struct Cancelled {};
struct Task {
    std::atomic_bool cancelled{false};
    void check() const { if (cancelled.load()) throw Cancelled{}; }
};
struct CancellationScope {
    explicit CancellationScope(Task* task) { lfm::set_film_cancellation(&task->cancelled); }
    ~CancellationScope() { lfm::set_film_cancellation(nullptr); }
};
std::mutex engine_mutex;
struct Engine {
    spk_engine* value = nullptr;
    std::string directory;
    ~Engine() { if (value) spk_engine_destroy(value); }
} engine;

void java_error(JNIEnv* env, const char* type, const std::string& message) {
    if (env->ExceptionCheck()) return;
    const jclass cls = env->FindClass(type);
    if (cls) { env->ThrowNew(cls, message.c_str()); env->DeleteLocalRef(cls); }
}
std::string utf8(JNIEnv* env, jstring input) {
    if (!input) throw std::invalid_argument("Film argument is missing");
    const char* chars = env->GetStringUTFChars(input, nullptr);
    if (!chars) throw std::bad_alloc{};
    std::string result(chars);
    env->ReleaseStringUTFChars(input, chars);
    return result;
}
struct BitmapLock {
    JNIEnv* env;
    jobject bitmap;
    void* pixels = nullptr;
    BitmapLock(JNIEnv* e, jobject b) : env(e), bitmap(b) {
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
            throw std::runtime_error("Cannot access film bitmap");
    }
    ~BitmapLock() { AndroidBitmap_unlockPixels(env, bitmap); }
};
struct Session {
    spk_session* value;
    ~Session() {
        if (value) spk_session_release(value);
    }
};
struct Result {
    spk_result value{};
    ~Result() { spk_result_free(&value); }
};
float srgb_linear(uint8_t byte) {
    static const auto table = [] {
        std::array<float, 256> values{};
        for (size_t i = 0; i < values.size(); ++i) {
            const float value = float(i) / 255.0f;
            values[i] = value <= .04045f ? value / 12.92f : std::pow((value + .055f) / 1.055f, 2.4f);
        }
        return values;
    }();
    return table[byte];
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_lumixfilmmirror_media_NativeFilmBridge_createTask(JNIEnv* env, jobject) {
    try { return reinterpret_cast<jlong>(new Task{}); }
    catch (const std::bad_alloc&) { java_error(env, "java/lang/OutOfMemoryError", "Cannot allocate film task"); return 0; }
}
extern "C" JNIEXPORT void JNICALL
Java_com_lumixfilmmirror_media_NativeFilmBridge_cancelTask(JNIEnv*, jobject, jlong handle) {
    auto* task = reinterpret_cast<Task*>(handle);
    if (!task) return;
    task->cancelled = true;
}
extern "C" JNIEXPORT void JNICALL
Java_com_lumixfilmmirror_media_NativeFilmBridge_releaseTask(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Task*>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_lumixfilmmirror_media_NativeFilmBridge_releaseIdleEngine(JNIEnv*, jobject) {
    // The same executor and mutex as render ensure all session/result handles
    // have been released before their owning GPU and its free pool are destroyed.
    std::lock_guard engine_lock(engine_mutex);
    if (engine.value) spk_engine_destroy(engine.value);
    engine.value = nullptr;
    engine.directory.clear();
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_lumixfilmmirror_media_NativeFilmBridge_shaderNames(JNIEnv* env, jobject) {
    const auto shaders = lfm::film_shaders();
    jclass cls = env->FindClass("java/lang/String");
    if (!cls) return nullptr;
    jobjectArray result = env->NewObjectArray(jsize(shaders.size()), cls, nullptr);
    env->DeleteLocalRef(cls);
    if (!result) return nullptr;
    for (size_t i = 0; i < shaders.size(); ++i) {
        const std::string name(shaders[i].name);
        jstring value = env->NewStringUTF(name.c_str());
        if (!value) return nullptr;
        env->SetObjectArrayElement(result, jsize(i), value);
        env->DeleteLocalRef(value);
        if (env->ExceptionCheck()) return nullptr;
    }
    return result;
}
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_lumixfilmmirror_media_NativeFilmBridge_shaderBytes(JNIEnv* env, jobject, jstring name) {
    try {
        const auto requested = utf8(env, name);
        for (const auto& shader : lfm::film_shaders()) if (shader.name == requested) {
            jbyteArray result = env->NewByteArray(jsize(shader.bytes.size()));
            if (result) env->SetByteArrayRegion(result, 0, jsize(shader.bytes.size()), reinterpret_cast<const jbyte*>(shader.bytes.data()));
            return result;
        }
        throw std::invalid_argument("Unknown film shader");
    } catch (const std::exception& error) { java_error(env, "java/lang/IllegalStateException", error.what()); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_com_lumixfilmmirror_media_NativeFilmBridge_render(JNIEnv* env, jobject, jlong handle,
    jstring directory, jobject bitmap, jstring params, jfloat diffusion, jlong seed) {
    auto* task = reinterpret_cast<Task*>(handle);
    try {
        if (!task) throw std::invalid_argument("Film task is missing");
        std::lock_guard engine_lock(engine_mutex);
        CancellationScope cancellation(task);
        task->check();
        const auto path = utf8(env, directory);
        const auto delta = utf8(env, params);
        if (!engine.value || engine.directory != path) {
            if (engine.value) spk_engine_destroy(engine.value);
            engine.value = nullptr;
            engine.directory = path;
            engine.value = spk_engine_create(path.c_str(), nullptr);
            if (!engine.value) throw std::runtime_error(std::string("Film Vulkan initialization failed: ") + spk_last_error(nullptr));
        }
        task->check();
        AndroidBitmapInfo info{};
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
            info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width == 0 || info.height == 0 ||
            (info.flags & ANDROID_BITMAP_FLAGS_IS_HARDWARE))
            throw std::invalid_argument("Film input must be a mutable RGBA bitmap");
        const uint64_t count = uint64_t(info.width) * info.height * 3;
        if (count > std::numeric_limits<size_t>::max() / sizeof(float)) throw std::bad_alloc{};
        std::vector<float> rgb(static_cast<size_t>(count));
        {
            BitmapLock lock(env, bitmap);
            for (uint32_t y = 0; y < info.height; ++y) {
                task->check();
                const auto* row = static_cast<const uint8_t*>(lock.pixels) + size_t(y) * info.stride;
                for (uint32_t x = 0; x < info.width; ++x) for (int c = 0; c < 3; ++c)
                    rgb[(size_t(y) * info.width + x) * 3 + c] = srgb_linear(row[size_t(x) * 4 + c]);
            }
        }
        lfm::black_diffusion(rgb, info.width, info.height, diffusion, [&] { task->check(); });
        lfm::set_film_seed(uint64_t(seed));
        const spk_image input{rgb.data(), info.width, info.height, 3};
        spk_session* opened = spk_open(engine.value, &input, delta.c_str(), nullptr);
        if (!opened) throw std::runtime_error(std::string("Film exposure preparation failed: ") + spk_last_error(engine.value));
        Session session{opened};
        task->check();
        std::vector<float>().swap(rgb);  // engine owns its source after spk_open
        Result result;
        const auto status = spk_render(opened, "full", &result.value);
        if (status == SPK_ERR_CANCELLED) throw Cancelled{};
        if (status != SPK_OK) throw std::runtime_error(std::string("Film rendering failed: ") + spk_last_error(engine.value));
        task->check();
        if (!result.value.rgba16 || result.value.width != info.width || result.value.height != info.height ||
            result.value.row_stride_px < info.width) throw std::runtime_error("Film output dimensions changed unexpectedly");
        BitmapLock lock(env, bitmap);
        for (uint32_t y = 0; y < info.height; ++y) {
            task->check();
            auto* row = static_cast<uint8_t*>(lock.pixels) + size_t(y) * info.stride;
            const uint16_t* source = result.value.rgba16 + size_t(y) * result.value.row_stride_px * 4;
            for (uint32_t x = 0; x < info.width; ++x) {
                for (int c = 0; c < 3; ++c) row[size_t(x) * 4 + c] = uint8_t((uint32_t(source[size_t(x) * 4 + c]) + 128u) / 257u);
                row[size_t(x) * 4 + 3] = 255;
            }
        }
    } catch (const Cancelled&) { java_error(env, "java/util/concurrent/CancellationException", "Film rendering cancelled"); }
    catch (const std::bad_alloc&) { java_error(env, "java/lang/OutOfMemoryError", "Film output requires more memory; choose smaller JPG dimensions"); }
    catch (const std::exception& error) { java_error(env, "java/lang/IllegalStateException", error.what()); }
    catch (...) { java_error(env, "java/lang/IllegalStateException", "Film engine failed"); }
}
