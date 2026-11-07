// UI-independent opt-in sensor decode. Default embedded-JPEG reader stays separate.
#pragma once
#include <libraw/libraw.h>
#include <functional>
#include <memory>
#include <stdexcept>
#include <cstdint>

namespace lfm::raw {
using SensorImage=std::unique_ptr<libraw_processed_image_t,decltype(&LibRaw::dcraw_clear_mem)>;
inline SensorImage decode_sensor(const char* input,bool half_size,const std::function<bool()>& stopped) {
    auto check=[&]{if(stopped())throw std::runtime_error("RAW decoding cancelled");};
    check();
    LibRaw raw(LIBRAW_OPTIONS_NO_DATAERR_CALLBACK);
    raw.imgdata.rawparams.max_raw_memory_mb=512;
    raw.set_progress_handler([](void* state,LibRaw_progress,int,int){return (*static_cast<const std::function<bool()>*>(state))()?1:0;},
        const_cast<std::function<bool()>*>(&stopped));
    auto& p=raw.imgdata.params;
    p.half_size=half_size?1:0;p.user_qual=half_size?0:3;
    p.use_camera_wb=1;p.no_auto_bright=1;p.output_color=4;p.output_bps=16;p.user_flip=0;
    p.gamm[0]=1;p.gamm[1]=1;
    int status=raw.open_file(input);check();
    if(status!=LIBRAW_SUCCESS)throw std::runtime_error("Unsupported or damaged RAW file");
    if(uint64_t(raw.imgdata.sizes.raw_width)*raw.imgdata.sizes.raw_height>80000000ULL ||
        (!half_size && uint64_t(raw.imgdata.sizes.width)*raw.imgdata.sizes.height>60000000ULL))
        throw std::runtime_error("RAW exceeds mobile decoding limit; choose embedded JPEG");
    status=raw.unpack();check();
    if(status==LIBRAW_SUCCESS)status=raw.dcraw_process();check();
    if(status!=LIBRAW_SUCCESS)throw std::runtime_error(status==LIBRAW_UNSUFFICIENT_MEMORY?
        "Insufficient memory; choose balanced RAW or embedded JPEG":"RAW sensor decoding failed");
    SensorImage image(raw.dcraw_make_mem_image(&status),&LibRaw::dcraw_clear_mem);check();
    if(!image || status!=LIBRAW_SUCCESS || image->type!=LIBRAW_IMAGE_BITMAP || image->bits!=16 || image->colors!=3)
        throw std::runtime_error("RAW decoder did not produce a 16-bit RGB image");
    const uint64_t count=uint64_t(image->width)*image->height;
    if(!count || count>60000000ULL || image->data_size<count*6)throw std::runtime_error("RAW output dimensions exceed mobile limit");
    return image;
}
}
