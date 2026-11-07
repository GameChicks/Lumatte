// Lumatte high-precision RAW working cache. ProPhoto D65 basis is the inverse of
// the pinned LibRaw_constants::prophoto_rgb in third_party/libraw/upstream/src/tables/colorconst.cpp.
// Original LibRaw copyright/licences remain in third_party/libraw/. No upstream bytes modified.
#pragma once
#include <array>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <stdexcept>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

namespace lfm {
inline float raw_decode(float v){return v<=.04045f?v/12.92f:std::pow((v+.055f)/1.055f,2.4f);}
inline float raw_encode(float v){return v<=.0031308f?v*12.92f:1.055f*std::pow(v,1.f/2.4f)-.055f;}
inline std::array<float,3> wide_to_srgb(const std::array<float,3>& v) {
    static const auto inverse=[] {
        constexpr double m[3][3]={{.529317,.330092,.140588},{.098368,.873465,.028169},{.016879,.117663,.865457}};
        std::array<std::array<float,3>,3> r{};
        const double d=m[0][0]*(m[1][1]*m[2][2]-m[1][2]*m[2][1])-m[0][1]*(m[1][0]*m[2][2]-m[1][2]*m[2][0])+m[0][2]*(m[1][0]*m[2][1]-m[1][1]*m[2][0]);
        for(int i=0;i<3;++i)for(int j=0;j<3;++j)
            r[i][j]=float((m[(j+1)%3][(i+1)%3]*m[(j+2)%3][(i+2)%3]-m[(j+1)%3][(i+2)%3]*m[(j+2)%3][(i+1)%3])/d);
        return r;
    }();
    std::array<float,3> r{};for(int i=0;i<3;++i)for(int j=0;j<3;++j)r[i]+=inverse[i][j]*v[j];return r;
}
inline uint32_t pack_raw10(const uint16_t* rgb) {
    uint32_t packed=0;
    for(int c=0;c<3;++c)packed|=uint32_t(std::lround(std::clamp(raw_encode(rgb[c]/65535.f),0.f,1.f)*1023))<<(10*c);
    return packed;
}
inline std::array<float,3> unpack_raw10(uint32_t value) {
    static const auto table=[] {std::array<float,1024> a{};for(int i=0;i<1024;++i)a[i]=raw_decode(i/1023.f);return a;}();
    return {table[value&1023],table[(value>>10)&1023],table[(value>>20)&1023]};
}

/** Read-only mmap: 10 bits per gamma-encoded wide-gamut channel in one 32-bit word.
 * No full-frame float copy. Decode/interpolate in linear light, then convert for the sRGB models.
 */
class Raw10Image {
    int fd=-1;void* mapping=MAP_FAILED;size_t length=0;
public:
    uint32_t width=0,height=0;
    explicit Raw10Image(const char* path) {
        fd=::open(path,O_RDONLY|O_CLOEXEC);if(fd<0)throw std::runtime_error("RAW10 working file missing");
        struct stat info{};
        if(fstat(fd,&info)!=0 || info.st_size<24){::close(fd);fd=-1;throw std::runtime_error("Invalid RAW10 working file");}
        length=size_t(info.st_size);mapping=mmap(nullptr,length,PROT_READ,MAP_PRIVATE,fd,0);
        if(mapping==MAP_FAILED){::close(fd);fd=-1;throw std::runtime_error("Cannot map RAW10 working file");}
        uint32_t header[4]{};std::memcpy(header,static_cast<const uint8_t*>(mapping)+8,16);
        width=header[0];height=header[1];
        if(std::memcmp(mapping,"LFM10RGB",8)!=0 || header[2]!=1 || header[3]!=4 || !width || !height ||
            uint64_t(width)*height>60000000ULL || uint64_t(width)*height*4+24!=length) {
            munmap(mapping,length);::close(fd);mapping=MAP_FAILED;fd=-1;throw std::runtime_error("Invalid RAW10 working format");
        }
    }
    ~Raw10Image(){if(mapping!=MAP_FAILED)munmap(mapping,length);if(fd>=0)::close(fd);}
    Raw10Image(const Raw10Image&)=delete;Raw10Image& operator=(const Raw10Image&)=delete;
    const void* bytes()const{return mapping;}size_t byte_size()const{return length;}
    std::array<float,3> sample(uint32_t x,uint32_t y,uint32_t output_w,uint32_t output_h,int orientation)const {
        const double u=(double(x)+.5)/output_w,v=(double(y)+.5)/output_h;
        double sx=u,sy=v;
        switch(orientation){case 2:sx=1-u;break;case 3:sx=1-u;sy=1-v;break;case 4:sy=1-v;break;
            case 5:sx=v;sy=u;break;case 6:sx=v;sy=1-u;break;case 7:sx=1-v;sy=1-u;break;case 8:sx=1-v;sy=u;break;}
        const double px=std::clamp(sx*width-.5,0.,double(width-1)),py=std::clamp(sy*height-.5,0.,double(height-1));
        const auto x0=uint32_t(px),y0=uint32_t(py),x1=std::min(x0+1,width-1),y1=std::min(y0+1,height-1);
        const float fx=float(px-x0),fy=float(py-y0);
        const auto* data=reinterpret_cast<const uint32_t*>(static_cast<const uint8_t*>(mapping)+24);
        auto a=unpack_raw10(data[size_t(y0)*width+x0]),b=unpack_raw10(data[size_t(y0)*width+x1]);
        auto c=unpack_raw10(data[size_t(y1)*width+x0]),d=unpack_raw10(data[size_t(y1)*width+x1]);
        std::array<float,3> wide{};for(int i=0;i<3;++i)wide[i]=(a[i]*(1-fx)+b[i]*fx)*(1-fy)+(c[i]*(1-fx)+d[i]*fx)*fy;
        return wide_to_srgb(wide);
    }
};
}
