#include <jni.h>
#include <libraw/libraw.h>
#include <algorithm>
#include <cstdio>
#include <memory>
#include <new>
#include <string>
#include <unistd.h>

namespace {
constexpr int cancelled = -1;
constexpr int io_error = -2;
constexpr int memory_error = -3;
constexpr int data_error = -4;
constexpr unsigned int max_preview_bytes = 64U * 1024U * 1024U;

struct UtfChars {
    JNIEnv* env;
    jstring text;
    const char* value;
    UtfChars(JNIEnv* e, jstring t) : env(e), text(t), value(t ? e->GetStringUTFChars(t, nullptr) : nullptr) {}
    ~UtfChars() { if (value) env->ReleaseStringUTFChars(text, value); }
};

struct Cancellation {
    JNIEnv* env;
    jobject probe;
    jmethodID method;
    bool requested() const {
        if (env->ExceptionCheck()) return true;
        const bool stopped = env->CallBooleanMethod(probe, method) == JNI_TRUE;
        return stopped || env->ExceptionCheck();
    }
};

int progress(void* context, LibRaw_progress, int, int) {
    return static_cast<Cancellation*>(context)->requested() ? 1 : 0;
}

std::unique_ptr<LibRaw> reader(Cancellation& stop) {
    auto raw = std::make_unique<LibRaw>(LIBRAW_OPTIONS_NO_DATAERR_CALLBACK);
    raw->imgdata.rawparams.max_raw_memory_mb = 64;
    raw->set_progress_handler(progress, &stop);
    return raw;
}

int failure(int status) {
    if (status == LIBRAW_CANCELLED_BY_CALLBACK) return cancelled;
    if (status == LIBRAW_UNSUFFICIENT_MEMORY) return memory_error;
    if (status == LIBRAW_IO_ERROR || status > 0) return io_error;
    return data_error;
}

int write_preview(const char* bytes, unsigned int length, const std::string& path, const Cancellation& stop) {
    using File = std::unique_ptr<FILE, decltype(&std::fclose)>;
    File output(std::fopen(path.c_str(), "wb"), &std::fclose);
    if (!output) return io_error;
    for (unsigned int offset = 0; offset < length;) {
        if (stop.requested()) return cancelled;
        const auto count = std::min(64U * 1024U, length - offset);
        if (std::fwrite(bytes + offset, 1, count, output.get()) != count) return io_error;
        offset += count;
    }
    if (std::fflush(output.get()) != 0 || ::fsync(::fileno(output.get())) != 0) return io_error;
    return std::fclose(output.release()) == 0 ? 0 : io_error;
}
}

extern "C" JNIEXPORT jint JNICALL
Java_com_lumixfilmmirror_media_LibRawPreview_nativeExtract(
    JNIEnv* env, jobject, jstring input_path, jstring output_directory, jobject probe) {
    try {
        UtfChars input(env, input_path);
        if (!input.value) return data_error;
        UtfChars directory(env, output_directory);
        if (!directory.value || !probe) return data_error;
        jclass probe_class = env->GetObjectClass(probe);
        if (!probe_class) return data_error;
        jmethodID method = env->GetMethodID(probe_class, "isCancelled", "()Z");
        env->DeleteLocalRef(probe_class);
        if (!method) return data_error;
        Cancellation stop{env, probe, method};
        if (stop.requested()) return cancelled;
        auto first = reader(stop);
        int status = first->open_file(input.value);
        if (stop.requested()) return cancelled;
        if (status == LIBRAW_FILE_UNSUPPORTED) return 0;
        if (status != LIBRAW_SUCCESS) return failure(status);
        const auto thumbnails = first->imgdata.thumbs_list;
        first.reset(); // Metadata reader is freed before allocating a compressed preview.
        const int count = std::clamp(thumbnails.thumbcount, 0, LIBRAW_THUMBNAIL_MAXCOUNT);
        int written = 0;
        int last_error = 0;
        for (int index = 0; index < count; ++index) {
            if (stop.requested()) return cancelled;
            const auto& item = thumbnails.thumblist[index];
            // Filter BEFORE unpack_thumb_ex: bitmap loaders can otherwise call sensor-like decoder paths.
            if (item.tformat != LIBRAW_INTERNAL_THUMBNAIL_JPEG) continue;
            if (item.tlength < 4 || item.tlength > max_preview_bytes) { last_error = data_error; continue; }
            auto raw = reader(stop); // A malformed candidate cannot poison the next candidate's state.
            status = raw->open_file(input.value);
            if (stop.requested()) return cancelled;
            if (status != LIBRAW_SUCCESS) { last_error = failure(status); continue; }
            if (index >= raw->imgdata.thumbs_list.thumbcount ||
                raw->imgdata.thumbs_list.thumblist[index].tformat != LIBRAW_INTERNAL_THUMBNAIL_JPEG) {
                last_error = data_error;
                continue;
            }
            status = raw->unpack_thumb_ex(index);
            if (stop.requested()) return cancelled;
            if (status != LIBRAW_SUCCESS) { last_error = failure(status); continue; }
            const auto& thumbnail = raw->imgdata.thumbnail;
            if (thumbnail.tformat != LIBRAW_THUMBNAIL_JPEG || !thumbnail.thumb ||
                thumbnail.tlength < 4 || thumbnail.tlength > max_preview_bytes ||
                static_cast<unsigned char>(thumbnail.thumb[0]) != 0xff ||
                static_cast<unsigned char>(thumbnail.thumb[1]) != 0xd8) {
                last_error = data_error;
                continue;
            }
            const std::string target = std::string(directory.value) + "/libraw-preview-" + std::to_string(written) + ".jpg";
            const int result = write_preview(thumbnail.thumb, thumbnail.tlength, target, stop);
            if (result != 0) return result;
            ++written;
        }
        // Kotlin checks actual JPEG dimensions and chooses the largest usable candidate.
        return written > 0 ? written : last_error;
    } catch (const std::bad_alloc&) { return memory_error; }
    catch (...) { return data_error; }
}
