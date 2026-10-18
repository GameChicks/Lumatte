#include <jni.h>
#include <android/bitmap.h>
#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <limits>
#include <new>
#include <stdexcept>
#include <string>
#include <vector>

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
std::vector<float> copy(JNIEnv* env, jfloatArray values, size_t count) {
    if (count == 0) return {};
    if (!values || count > size_t(std::numeric_limits<jsize>::max()) || env->GetArrayLength(values) != jsize(count))
        throw std::invalid_argument("LUT table dimensions do not match its data");
    std::vector<float> result(count);
    env->GetFloatArrayRegion(values, 0, jsize(count), result.data());
    if (env->ExceptionCheck()) throw PendingJavaError{};
    for (float value : result) if (!std::isfinite(value)) throw std::invalid_argument("LUT table contains non-finite data");
    return result;
}
struct Table {
    int one_size = 0, three_size = 0;
    float strength = 0;
    std::vector<float> one, three, bounds;

    static Table read(JNIEnv* env, jint one_size, jint three_size, jfloatArray one,
                      jfloatArray three, jfloatArray bounds, float strength) {
        if (!std::isfinite(strength) || strength < 0 || strength > 1)
            throw std::invalid_argument("LUT strength is outside 0..1");
        if (strength == 0 || (one_size == 0 && three_size == 0)) return {};
        if ((one_size != 0 && (one_size < 2 || one_size > 65536)) ||
            (three_size != 0 && (three_size < 2 || three_size > 65)))
            throw std::invalid_argument("Unsupported LUT table dimensions");
        Table table;
        table.one_size = one_size; table.three_size = three_size; table.strength = strength;
        table.one = copy(env, one, size_t(one_size) * 3);
        table.three = copy(env, three, size_t(three_size) * three_size * three_size * 3);
        table.bounds = copy(env, bounds, 13);
        if (table.bounds[12] != 0.0f && (one_size != 0 || three_size != 17))
            throw std::invalid_argument("Invalid VLT grid");
        for (int c = 0; c < 3; ++c)
            if (!(table.bounds[3 + c] > table.bounds[c]) || !(table.bounds[9 + c] > table.bounds[6 + c]))
                throw std::invalid_argument("Invalid LUT input range");
        return table;
    }
    static float coordinate(float value, float minimum, float maximum, int size) {
        const float position = (value - minimum) / (maximum - minimum);
        if (std::isnan(position)) throw std::runtime_error("LUT range arithmetic produced an invalid colour");
        return std::clamp(position, 0.0f, 1.0f) * float(size - 1);
    }
    void apply(std::array<float, 3>& input) const {
        if (strength == 0) return;
        auto output = input;
        if (one_size != 0) for (int c = 0; c < 3; ++c) {
            const float position = coordinate(input[c], bounds[c], bounds[3 + c], one_size);
            const int lower = int(std::floor(position)), upper = std::min(lower + 1, one_size - 1);
            const float fraction = position - float(lower);
            output[c] = one[size_t(lower) * 3 + c] * (1.0f - fraction) + one[size_t(upper) * 3 + c] * fraction;
        }
        if (three_size != 0) {
            auto position = [&](int c) {
                if (bounds[12] == 0.0f) return coordinate(output[c], bounds[6+c], bounds[9+c], three_size);
                const float v = std::clamp(output[c], 0.0f, 1.0f) * 4095.0f;
                return v <= 3840.0f ? v / 256.0f : 15.0f + (v - 3840.0f) / 255.0f;
            };
            const float r = position(0), g = position(1), b = position(2);
            const int r0 = int(std::floor(r)), g0 = int(std::floor(g)), b0 = int(std::floor(b));
            const int r1 = std::min(r0 + 1, three_size - 1), g1 = std::min(g0 + 1, three_size - 1), b1 = std::min(b0 + 1, three_size - 1);
            const float fr = r - float(r0), fg = g - float(g0), fb = b - float(b0);
            auto index = [&](int x, int y, int z) { return size_t(x + three_size * (y + three_size * z)) * 3; };
            const size_t i000 = index(r0, g0, b0), i100 = index(r1, g0, b0), i010 = index(r0, g1, b0), i110 = index(r1, g1, b0);
            const size_t i001 = index(r0, g0, b1), i101 = index(r1, g0, b1), i011 = index(r0, g1, b1), i111 = index(r1, g1, b1);
            for (int c = 0; c < 3; ++c) {
                const float low = (three[i000 + c] * (1.0f - fr) + three[i100 + c] * fr) * (1.0f - fg) +
                    (three[i010 + c] * (1.0f - fr) + three[i110 + c] * fr) * fg;
                const float high = (three[i001 + c] * (1.0f - fr) + three[i101 + c] * fr) * (1.0f - fg) +
                    (three[i011 + c] * (1.0f - fr) + three[i111 + c] * fr) * fg;
                output[c] = low * (1.0f - fb) + high * fb;
            }
        }
        for (int c = 0; c < 3; ++c) input[c] += (output[c] - input[c]) * strength;
    }
};
struct BitmapLock {
    JNIEnv* env;
    jobject bitmap;
    void* pixels = nullptr;
    BitmapLock(JNIEnv* e, jobject b) : env(e), bitmap(b) {
        if (AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS)
            throw std::runtime_error("Cannot access LUT bitmap");
    }
    ~BitmapLock() { AndroidBitmap_unlockPixels(env, bitmap); }
};
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_lumixfilmmirror_media_NativeLutBridge_createTask(JNIEnv* env, jobject) {
    try { return reinterpret_cast<jlong>(new Task{}); }
    catch (const std::bad_alloc&) { java_error(env, "java/lang/OutOfMemoryError", "Cannot allocate LUT task"); return 0; }
}
extern "C" JNIEXPORT void JNICALL
Java_com_lumixfilmmirror_media_NativeLutBridge_cancelTask(JNIEnv*, jobject, jlong handle) {
    if (auto* task = reinterpret_cast<Task*>(handle)) task->cancelled = true;
}
extern "C" JNIEXPORT void JNICALL
Java_com_lumixfilmmirror_media_NativeLutBridge_releaseTask(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Task*>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_lumixfilmmirror_media_NativeLutBridge_render(JNIEnv* env, jobject, jlong handle, jobject bitmap,
    jint first_one_size, jint first_three_size, jfloatArray first_one, jfloatArray first_three, jfloatArray first_bounds, jfloat first_strength,
    jint second_one_size, jint second_three_size, jfloatArray second_one, jfloatArray second_three, jfloatArray second_bounds, jfloat second_strength,
    jfloat grain, jint source_width, jint source_height, jint seed, jboolean dithering) {
    try {
        const auto* task = reinterpret_cast<Task*>(handle);
        if (!task || source_width <= 0 || source_height <= 0 || !std::isfinite(grain) || grain < 0 || grain > 1)
            throw std::invalid_argument("Invalid LUT render request");
        task->check();
        const Table first = Table::read(env, first_one_size, first_three_size, first_one, first_three, first_bounds, first_strength);
        task->check();
        const Table second = Table::read(env, second_one_size, second_three_size, second_one, second_three, second_bounds, second_strength);
        AndroidBitmapInfo info{};
        if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
            info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width == 0 || info.height == 0 ||
            (info.flags & ANDROID_BITMAP_FLAGS_IS_HARDWARE))
            throw std::invalid_argument("LUT input must be a mutable RGBA bitmap");
        BitmapLock lock(env, bitmap);
        // In-place scanlines: no full-frame float image or Java getPixels/setPixels transfers.
        for (uint32_t y = 0; y < info.height; ++y) {
            task->check();
            auto* row = static_cast<uint8_t*>(lock.pixels) + size_t(y) * info.stride;
            for (uint32_t x = 0; x < info.width; ++x) {
                auto* pixel = row + size_t(x) * 4;
                std::array<float, 3> rgb{pixel[0] / 255.0f, pixel[1] / 255.0f, pixel[2] / 255.0f};
                first.apply(rgb); second.apply(rgb);
                if (grain > 0) {
                    const uint32_t sx = uint32_t(uint64_t(x) * uint32_t(source_width) / info.width / 3);
                    const uint32_t sy = uint32_t(uint64_t(y) * uint32_t(source_height) / info.height / 3);
                    uint32_t hash = uint32_t(seed) ^ (sx * 73856093u) ^ (sy * 19349663u);
                    hash = (hash ^ (hash >> 16)) * uint32_t(-2048144789);
                    hash = (hash ^ (hash >> 13)) * uint32_t(-1028477387);
                    hash ^= hash >> 16;
                    const float light = std::clamp(.2126f * rgb[0] + .7152f * rgb[1] + .0722f * rgb[2], 0.0f, 1.0f);
                    const float noise = (float((hash >> 8) & 65535u) / 65535.0f - .5f) * grain * .12f * (.35f + .65f * (1.0f - light));
                    for (float& channel : rgb) channel += noise;
                }
                if (dithering) {
                    static constexpr int bayer[16] = {0,8,2,10,12,4,14,6,3,11,1,9,15,7,13,5};
                    const float noise = ((float(bayer[(y & 3u) * 4u + (x & 3u)]) + .5f) / 16.0f - .5f) / 255.0f;
                    for (float& channel : rgb) channel += noise;
                }
                for (int c = 0; c < 3; ++c) {
                    if (std::isnan(rgb[c])) throw std::runtime_error("LUT arithmetic produced an invalid colour");
                    pixel[c] = uint8_t(std::lround(std::clamp(rgb[c], 0.0f, 1.0f) * 255.0f));
                }
                // Preserve the existing input alpha, matching the Kotlin path.
            }
        }
        task->check();
    } catch (const PendingJavaError&) { /* Keep the original JVM exception. */ }
    catch (const Cancelled&) { java_error(env, "java/util/concurrent/CancellationException", "LUT rendering cancelled"); }
    catch (const std::bad_alloc&) { java_error(env, "java/lang/OutOfMemoryError", "LUT task requires more memory"); }
    catch (const std::invalid_argument& error) { java_error(env, "java/lang/IllegalArgumentException", error.what()); }
    catch (const std::exception& error) { java_error(env, "java/lang/IllegalStateException", error.what()); }
    catch (...) { java_error(env, "java/lang/IllegalStateException", "LUT rendering failed"); }
}
