#include "mobile_film_gles.hpp"
#include <jni.h>
#include <android/bitmap.h>
#include <atomic>
#include <limits>
#include <new>
#include <string>

namespace {
struct Cancelled {};
struct PendingJavaError {};
struct Task {
    std::atomic_bool cancelled{false};
    void check() const { if (cancelled.load()) throw Cancelled{}; }
};
void java_error(JNIEnv* env, const char* type, const std::string& message) {
    if (env->ExceptionCheck()) return;
    const jclass cls = env->FindClass(type);
    if (cls) { env->ThrowNew(cls, message.c_str()); env->DeleteLocalRef(cls); }
}
std::vector<float> copy(JNIEnv* env, jfloatArray array, size_t count) {
    if (!array || count > size_t(std::numeric_limits<jsize>::max()) || env->GetArrayLength(array) != jsize(count))
        throw std::invalid_argument("Mobile film array length is invalid");
    std::vector<float> values(count);
    env->GetFloatArrayRegion(array, 0, jsize(count), values.data());
    if (env->ExceptionCheck()) throw PendingJavaError{};
    for (float value : values) if (!std::isfinite(value)) throw std::invalid_argument("Non-finite mobile film array");
    return values;
}
lfm::mobile::Model read_model(JNIEnv* env, jobjectArray tables, jintArray sizes, jfloatArray bounds, const Task& task) {
    if (!tables || env->GetArrayLength(tables) != 3 || !sizes || env->GetArrayLength(sizes) != 3)
        throw std::invalid_argument("Mobile film requires three stage tables");
    int dimensions[3]{};
    env->GetIntArrayRegion(sizes, 0, 3, dimensions);
    if (env->ExceptionCheck()) throw PendingJavaError{};
    const auto ranges = copy(env, bounds, 18);
    auto stage = [&](int index) {
        task.check();
        const int n = dimensions[index];
        if (n < 2 || n > 65) throw std::invalid_argument("Mobile film table dimension unsupported");
        lfm::mobile::Table result{n, {}, {}, {}};
        for (int c = 0; c < 3; ++c) {
            result.minimum[c] = ranges[size_t(index) * 6 + c];
            result.maximum[c] = ranges[size_t(index) * 6 + 3 + c];
            if (!(result.maximum[c] > result.minimum[c]) || !std::isfinite(result.maximum[c] - result.minimum[c]))
                throw std::invalid_argument("Mobile film table range invalid");
        }
        auto array = static_cast<jfloatArray>(env->GetObjectArrayElement(tables, index));
        if (env->ExceptionCheck()) throw PendingJavaError{};
        try { result.values = copy(env, array, size_t(n) * n * n * 3); }
        catch (...) { if (array) env->DeleteLocalRef(array); throw; }
        if (array) env->DeleteLocalRef(array);
        return result;
    };
    return {stage(0), stage(1), stage(2)};
}
lfm::mobile::Effects read_effects(JNIEnv* env, jfloatArray controls) {
    const auto values = copy(env, controls, 60);
    if ((values[0] != 0 && values[0] != .125f && values[0] != .25f && values[0] != .5f) ||
        (values[1] != 0 && values[1] != 1) || values[2] < -3 || values[2] > 3 ||
        (values[3] != 0 && values[3] != 1) || values[4] < .0001f || values[4] > 10 || values[23] < 10 || values[23] > 100)
        throw std::invalid_argument("Mobile film controls invalid");
    lfm::mobile::Effects result{};
    result.diffusion = values[0]; result.halation = values[1] == 1;
    result.exposure_gain = std::pow(2.f, values[2]); result.grain = values[3] == 1;
    result.particle_area = values[4]; result.format_mm = values[23];
    if ((values[24] != 0 && values[24] != 1) || values[25] < 1 || values[25] > 8 ||
        values[25] != std::floor(values[25]) || values[26] < 0 || values[26] > 1 || values[27] < 0 || values[27] > 40)
        throw std::invalid_argument("Mobile spatial profile invalid");
    result.cinestill = values[24] == 1; result.halation_bounces = int(values[25]);
    if(values[59]<0 || values[59]>2 || (!result.cinestill && values[59]!=0))
        throw std::invalid_argument("Invalid CineStill JPEG halation look");
    result.remjet_look=values[59];
    if (result.cinestill) {
        for (int c = 0; c < 3; ++c) {
            result.fotu_sigma_mm[c] = values[38+c];
            if (values[38+c] < .000001f || values[38+c] > 2)
                throw std::invalid_argument("Invalid physical halation sigma");
            float weights = 0, matrix = 0;
            for (int j = 0; j < 3; ++j) {
                const float weight = values[41+3*c+j], routed = values[50+3*c+j];
                if (weight < -1e-6f || weight > 1 || routed < 0 || routed > 1)
                    throw std::invalid_argument("Invalid physical halation matrix");
                result.fotu_weights[c][j] = weight; result.fotu_matrix[c][j] = routed;
                weights += weight; matrix += routed;
            }
            if (std::abs(weights-1) > 1e-5f || matrix > 1)
                throw std::invalid_argument("Invalid physical halation partition");
        }
    }
    result.halation_decay = values[26]; result.grain_spacing_um = values[27];
    if (values[28] < 0 || values[28] > 1) throw std::invalid_argument("Invalid scatter amount");
    result.scatter_amount = values[28];
    for (int c = 0; c < 3; ++c) {
        result.scatter_core_um[c] = values[29+c]; result.scatter_tail_um[c] = values[32+c];
        result.scatter_tail_weight[c] = values[35+c];
        if (values[29+c] < 0 || values[29+c] > 1000 || values[32+c] < 0 || values[32+c] > 1000 ||
            values[35+c] < 0 || values[35+c] > 1) throw std::invalid_argument("Invalid emulsion scatter profile");
    }
    for (int c = 0; c < 3; ++c) {
        result.particle_scale[c] = values[5 + c]; result.floor[c] = values[8 + c];
        result.uniformity[c] = values[11 + c]; result.density_max[c] = values[14 + c];
        result.halation_strength[c] = values[17 + c]; result.halation_sigma_um[c] = values[20 + c];
        if (result.particle_scale[c] < .0001f || result.particle_scale[c] > 20 || result.floor[c] < 0 || result.floor[c] > 1 ||
            result.uniformity[c] < 0 || result.uniformity[c] > 1 || result.density_max[c] <= 0 || result.density_max[c] > 10 ||
            result.halation_strength[c] < 0 || result.halation_strength[c] > 2 || result.halation_sigma_um[c] < 0 || result.halation_sigma_um[c] > 1000)
            throw std::invalid_argument("Mobile film effect channels invalid");
    }
    return result;
}
struct BitmapLock {
    JNIEnv* env; jobject bitmap; void* pixels = nullptr;
    BitmapLock(JNIEnv* e, jobject b) : env(e), bitmap(b) {
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
            throw std::runtime_error("Cannot lock mobile film input");
    }
    ~BitmapLock() { AndroidBitmap_unlockPixels(env, bitmap); }
};
}

extern "C" JNIEXPORT jlong JNICALL Java_com_lumixfilmmirror_media_NativeMobileFilmBridge_createTask(JNIEnv* env, jobject) {
    try { return reinterpret_cast<jlong>(new Task{}); }
    catch (const std::bad_alloc&) { java_error(env, "java/lang/OutOfMemoryError", "Cannot allocate mobile film task"); return 0; }
}
extern "C" JNIEXPORT void JNICALL Java_com_lumixfilmmirror_media_NativeMobileFilmBridge_cancelTask(JNIEnv*, jobject, jlong handle) {
    if (auto* task = reinterpret_cast<Task*>(handle)) task->cancelled = true;
}
extern "C" JNIEXPORT void JNICALL Java_com_lumixfilmmirror_media_NativeMobileFilmBridge_releaseTask(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Task*>(handle);
}
extern "C" JNIEXPORT void JNICALL Java_com_lumixfilmmirror_media_NativeMobileFilmBridge_releaseIdleGpu(JNIEnv*, jobject) {
    lfm::mobile::release_idle_gles();
}
extern "C" JNIEXPORT void JNICALL Java_com_lumixfilmmirror_media_NativeMobileFilmBridge_render(JNIEnv* env, jobject, jlong handle,
    jobject bitmap, jobjectArray tables, jintArray sizes, jfloatArray bounds, jfloatArray controls,
    jint source_width, jint source_height, jlong seed, jboolean prefer_gpu, jstring model_key) {
    try {
        const auto* task = reinterpret_cast<Task*>(handle);
        if (!task || !bitmap || source_width <= 0 || source_height <= 0) throw std::invalid_argument("Invalid mobile film request");
        task->check();
        const auto model = read_model(env, tables, sizes, bounds, *task);
        const auto effects = read_effects(env, controls);
        std::string key;
        if (prefer_gpu) {
            if (!model_key) throw std::invalid_argument("Film GPU model key missing");
            const char* text = env->GetStringUTFChars(model_key, nullptr);
            if (!text) throw PendingJavaError{};
            try { key = text; }
            catch (...) { env->ReleaseStringUTFChars(model_key, text); throw; }
            env->ReleaseStringUTFChars(model_key, text);
            if (key.empty()) throw std::invalid_argument("Film GPU model key empty");
        }
        AndroidBitmapInfo info{};
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS || info.width == 0 || info.height == 0 ||
            info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || (info.flags & ANDROID_BITMAP_FLAGS_IS_HARDWARE) ||
            uint64_t(info.stride) < uint64_t(info.width) * 4)
            throw std::invalid_argument("Mobile film needs a software RGBA bitmap");
        task->check();
        BitmapLock lock(env, bitmap);
        const lfm::mobile::Image image{static_cast<uint8_t*>(lock.pixels), info.width, info.height, info.stride};
        if (prefer_gpu) lfm::mobile::render_gles(image, model, effects, key,
            uint32_t(source_width), uint32_t(source_height), uint64_t(seed), [&] { task->check(); });
        else lfm::mobile::render_cpu(image, model, effects,
            uint32_t(source_width), uint32_t(source_height), uint64_t(seed), [&] { task->check(); });
    } catch (const PendingJavaError&) { /* Retain the original JVM exception. */ }
    catch (const Cancelled&) { java_error(env, "java/util/concurrent/CancellationException", "Mobile film cancelled"); }
    catch (const lfm::mobile::GlesError& error) { java_error(env, "com/lumixfilmmirror/media/MobileFilmGpuFailure", error.what()); }
    catch (const std::bad_alloc&) { java_error(env, "java/lang/OutOfMemoryError", "Mobile film requires more memory"); }
    catch (const std::invalid_argument& error) { java_error(env, "java/lang/IllegalArgumentException", error.what()); }
    catch (const std::exception& error) { java_error(env, "java/lang/IllegalStateException", error.what()); }
    catch (...) { java_error(env, "java/lang/IllegalStateException", "Mobile film rendering failed"); }
}
