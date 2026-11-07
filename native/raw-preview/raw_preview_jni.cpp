#include <jni.h>
#include <android/bitmap.h>
#include "../common/raw10.hpp"
#include "raw_sensor.hpp"
#include <libraw/libraw.h>
#include <algorithm>
#include <cstdio>
#include <memory>
#include <new>
#include <string>
#include <vector>
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

// Explicit opt-in sensor processing. Preview extraction above retains its original fast path.
extern "C" JNIEXPORT jobject JNICALL
Java_com_lumixfilmmirror_media_LibRawPreview_nativeDecode(
    JNIEnv* env,jobject,jstring input_path,jboolean half_size,jstring working_path,jobject probe) {
    auto error=[&](const char* message)->jobject {
        if(!env->ExceptionCheck()) {auto cls=env->FindClass("java/io/IOException");if(cls)env->ThrowNew(cls,message);}
        return nullptr;
    };
    try {
        UtfChars input(env,input_path);
        UtfChars working(env,working_path);
        if(!input.value || !working.value || !probe)return error("RAW input unavailable");
        auto cls=env->GetObjectClass(probe);
        if(!cls)return nullptr;
        auto method=env->GetMethodID(cls,"isCancelled","()Z");env->DeleteLocalRef(cls);
        if(!method)return nullptr;
        Cancellation stop{env,probe,method};
        auto image=lfm::raw::decode_sensor(input.value,half_size==JNI_TRUE,[&]{return stop.requested();});
        std::unique_ptr<FILE,decltype(&std::fclose)> output(std::fopen(working.value,"wb"),&std::fclose);
        if(!output)return error("Cannot save RAW10 working file");
        const uint32_t header[4]={image->width,image->height,1,4};
        if(std::fwrite("LFM10RGB",1,8,output.get())!=8 || std::fwrite(header,1,16,output.get())!=16)
            return error("Cannot save RAW10 working header");
        std::vector<uint32_t> packed(image->width);
        auto bitmapClass=env->FindClass("android/graphics/Bitmap");
        auto configClass=env->FindClass("android/graphics/Bitmap$Config");
        if(!bitmapClass || !configClass)return nullptr;
        auto field=env->GetStaticFieldID(configClass,"ARGB_8888","Landroid/graphics/Bitmap$Config;");
        auto create=env->GetStaticMethodID(bitmapClass,"createBitmap","(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
        if(!field || !create)return nullptr;
        auto config=env->GetStaticObjectField(configClass,field);
        auto bitmap=env->CallStaticObjectMethod(bitmapClass,create,jint(image->width),jint(image->height),config);
        if(!bitmap || env->ExceptionCheck())return nullptr;
        AndroidBitmapInfo info{};void* data=nullptr;
        if(AndroidBitmap_getInfo(env,bitmap,&info)!=ANDROID_BITMAP_RESULT_SUCCESS ||
            AndroidBitmap_lockPixels(env,bitmap,&data)!=ANDROID_BITMAP_RESULT_SUCCESS)return error("Cannot allocate RAW working image");
        bool stopped=false,write_failed=false;
        for(unsigned y=0;y<image->height;++y) {
            if(stop.requested()){stopped=true;break;}
            auto* row=static_cast<unsigned char*>(data)+size_t(y)*info.stride;
            const auto* src=reinterpret_cast<const uint16_t*>(image->data)+size_t(y)*image->width*3;
            for(unsigned x=0;x<image->width;++x){
                packed[x]=lfm::pack_raw10(src+3*x);
                auto rgb=lfm::wide_to_srgb(lfm::unpack_raw10(packed[x]));
                for(int c=0;c<3;++c)row[4*x+c]=uint8_t(std::lround(std::clamp(lfm::raw_encode(rgb[c]),0.f,1.f)*255));
                row[4*x+3]=255;
            }
            if(std::fwrite(packed.data(),sizeof(uint32_t),packed.size(),output.get())!=packed.size()){stopped=true;write_failed=true;break;}
        }
        AndroidBitmap_unlockPixels(env,bitmap);
        if(stopped) {
            if(!env->ExceptionCheck()){auto recycle=env->GetMethodID(bitmapClass,"recycle","()V");if(recycle)env->CallVoidMethod(bitmap,recycle);}
            return error(write_failed?"Cannot save RAW10 working pixels":"RAW decoding cancelled");
        }
        if(std::fflush(output.get())!=0 || fsync(fileno(output.get()))!=0) {
            auto recycle=env->GetMethodID(bitmapClass,"recycle","()V");if(recycle)env->CallVoidMethod(bitmap,recycle);
            return error("Cannot finalize RAW10 working file");
        }
        return bitmap;
    }catch(const std::bad_alloc&){return error("Insufficient memory for RAW decoding; choose embedded JPEG");}
    catch(const std::exception& e){return error(e.what());}
    catch(...){return error("RAW sensor decoding failed");}
}
