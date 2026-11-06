// SpektraLab-Windows-NANATATA d667dcf adaptation, GPL-3.0-or-later.
// Sources/licences/hashes: research/spektralab/. Full-resolution float32 planes;
// unchanged PSF, FIR tap order and double IIR recurrence. No fast-math/decimation.
#pragma once
#include "../../research/spektralab/windows/engine/src/core/halation_psf.hpp"
#include <chrono>
#include <cstring>
#include <memory>

namespace lfm::mobile {
struct CineSpatialMetrics { int64_t micros=0; bool cache_hit=false; size_t bytes=0; };
inline thread_local CineSpatialMetrics cine_spatial_metrics;
struct CineSpatialKey {
    uint64_t a=0x9e3779b185ebca87ULL,b=0xc2b2ae3d27d4eb4fULL;
    bool operator==(const CineSpatialKey& other)const{return a==other.a && b==other.b;}
    void word(uint64_t x) {
        a^=x*0x9e3779b185ebca87ULL;a=(a<<27)|(a>>37);a=a*5+0x52dce729;
        b^=x*0xc2b2ae3d27d4eb4fULL;b=(b<<31)|(b>>33);b=b*5+0x38495ab5;
    }
    void bytes(const void* data,size_t n) {
        const auto* p=static_cast<const uint8_t*>(data);word(n);
        while(n>=8){uint64_t x;std::memcpy(&x,p,8);word(x);p+=8;n-=8;}
        if(n){uint64_t x=0;std::memcpy(&x,p,n);word(x);}
    }
};
struct CineSpatialCache {
    std::mutex mutex;
    CineSpatialKey key;
    std::shared_ptr<const Field> response;
    uint64_t generation=0;
};
inline CineSpatialCache& cine_cache(){static CineSpatialCache cache;return cache;}
inline void release_cine_spatial_cache(){auto& c=cine_cache();std::lock_guard lock(c.mutex);c.response.reset();}

/** One reusable plane. FIR runs vertical then horizontal; IIR horizontal then vertical.
 * Adjacent columns share a block so every cache line is consumed contiguously. Each
 * column still executes the original forward/backward recurrence in the original order.
 */
inline void spektra_blur(const std::vector<float>& input,uint32_t w,uint32_t h,double sigma,
                         const Check& check,std::vector<float>& out) {
    out.resize(input.size());
    if(!(sigma>0)){std::copy(input.begin(),input.end(),out.begin());return;}
    if(sigma<3.) {
        const int r=int(3*sigma+.5);std::vector<double> kernel(2*r+1);double total=0;
        for(int k=-r;k<=r;++k){kernel[k+r]=std::exp(-.5*k*k/(sigma*sigma));total+=kernel[k+r];}
        for(auto& v:kernel)v/=total;
        auto reflect=[](int i,int n){const int period=2*n;i=(i%period+period)%period;return i<n?i:period-1-i;};
        constexpr uint32_t block=32;
        parallel_rows(h,uint64_t(w)*kernel.size(),check,[&](uint32_t y){
            std::array<const float*,19> rows{};
            for(int k=-r;k<=r;++k)rows[k+r]=input.data()+size_t(reflect(int(y)+k,int(h)))*w;
            auto* dst=out.data()+size_t(y)*w;
            for(uint32_t x=0;x<w;x+=block){
                if((x&1023u)==0)check();const uint32_t n=std::min(block,w-x);std::array<double,block> sum{};
                for(int k=0;k<=2*r;++k)for(uint32_t j=0;j<n;++j)sum[j]+=rows[k][x+j]*kernel[k];
                for(uint32_t j=0;j<n;++j)dst[x+j]=float(sum[j]);
            }
        });
        parallel_rows(h,uint64_t(w)*kernel.size(),check,[&](uint32_t y){
            auto* dst=out.data()+size_t(y)*w;std::vector<float> line(size_t(w)+2*r);
            std::copy(dst,dst+w,line.data()+r);
            for(int k=0;k<r;++k){line[k]=dst[reflect(k-r,int(w))];line[w+r+k]=dst[reflect(int(w)+k,int(w))];}
            for(uint32_t x=0;x<w;x+=block){
                if((x&1023u)==0)check();const uint32_t n=std::min(block,w-x);std::array<double,block> sum{};
                for(int k=0;k<=2*r;++k)for(uint32_t j=0;j<n;++j)sum[j]+=line[x+j+k]*kernel[k];
                for(uint32_t j=0;j<n;++j)dst[x+j]=float(sum[j]);
            }
        });
        return;
    }
    const double q=.98711*sigma-.96330,q2=q*q,q3=q2*q;
    const double b0=1.57825+2.44413*q+1.4281*q2+.422205*q3;
    const double b1=(2.44413*q+2.85619*q2+1.26661*q3)/b0;
    const double b2=-(1.4281*q2+1.26661*q3)/b0,b3=.422205*q3/b0,B=1-b1-b2-b3;
    parallel_rows(h,uint64_t(w)*8,check,[&](uint32_t y){
        const auto* src=input.data()+size_t(y)*w;auto* dst=out.data()+size_t(y)*w;
        double v1=src[0],v2=v1,v3=v1;
        for(uint32_t x=0;x<w;++x){
            if((x&1023u)==0)check();const double v=B*src[x]+b1*v1+b2*v2+b3*v3;
            dst[x]=float(v);v3=v2;v2=v1;v1=v;
        }
        v1=dst[w-1];v2=v1;v3=v1;
        for(int x=int(w)-1;x>=0;--x){
            if((x&1023)==0)check();const double v=B*dst[x]+b1*v1+b2*v2+b3*v3;
            dst[x]=float(v);v3=v2;v2=v1;v1=v;
        }
    });
    constexpr uint32_t block=64;
    parallel_rows((w+block-1)/block,uint64_t(h)*block*8,check,[&](uint32_t group){
        const uint32_t x=group*block,n=std::min(block,w-x);
        std::array<double,block> v1{},v2{},v3{};
        for(uint32_t j=0;j<n;++j)v1[j]=v2[j]=v3[j]=out[x+j];
        for(uint32_t y=0;y<h;++y){
            if((y&63u)==0)check();auto* row=out.data()+size_t(y)*w+x;
            for(uint32_t j=0;j<n;++j){const double v=B*row[j]+b1*v1[j]+b2*v2[j]+b3*v3[j];
                row[j]=float(v);v3[j]=v2[j];v2[j]=v1[j];v1[j]=v;}
        }
        for(uint32_t j=0;j<n;++j)v1[j]=v2[j]=v3[j]=out[size_t(h-1)*w+x+j];
        for(int y=int(h)-1;y>=0;--y){
            if((y&63)==0)check();auto* row=out.data()+size_t(y)*w+x;
            for(uint32_t j=0;j<n;++j){const double v=B*row[j]+b1*v1[j]+b2*v2[j]+b3*v3[j];
                row[j]=float(v);v3[j]=v2[j];v2[j]=v1[j];v1[j]=v;}
        }
    });
}

inline Spatial prepare_spektralab_spatial(const Image& image,const Model& model,const Effects& effects,
                                        const std::array<float,256>& decode_lut,const Check& check) {
    Effects lens_effects=effects;lens_effects.halation=false;
    Spatial spatial;
    if(!effects.halation) {
        if(effects.diffusion>0)spatial=prepare_fotu_spatial(image,model,lens_effects,decode_lut,check);
        return spatial;
    }
    const auto started=std::chrono::steady_clock::now();cine_spatial_metrics={};
    const uint32_t w=image.width,h=image.height;const size_t count=size_t(w)*h;
    CineSpatialKey key;key.word(w);key.word(h);key.word(model.response.size);
    key.bytes(model.response.minimum.data(),sizeof(Colour));key.bytes(model.response.maximum.data(),sizeof(Colour));
    key.bytes(model.response.values.data(),model.response.values.size()*sizeof(float));
    key.bytes(&effects.diffusion,sizeof(float));key.bytes(&effects.format_mm,sizeof(float));key.bytes(&effects.exposure_gain,sizeof(float));
    for(uint32_t y=0;y<h;++y){check();key.bytes(image.pixels+size_t(y)*image.stride,size_t(w)*4);}
    auto& cache=cine_cache();
    {
        std::lock_guard lock(cache.mutex);
        if(cache.response && cache.key==key){
            spatial.prepared_response=cache.response;spatial.response_generation=cache.generation;spatial.complete_response=true;
            cine_spatial_metrics={std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now()-started).count(),true,count*3*sizeof(float)};
            return spatial;
        }
        // Evict before allocating a different image, rather than doubling the working set.
        cache.response.reset();
    }
    if(effects.diffusion>0)spatial=prepare_fotu_spatial(image,model,lens_effects,decode_lut,check);
    const ByteResponseSampler sampler(model.response);
    auto field=std::make_shared<Field>(w,h);
    std::vector<float> maxima(h);
    parallel_rows(h,uint64_t(w)*16,check,[&](uint32_t y){
        float peak=0;
        for(uint32_t x=0;x<w;++x){
            if((x&1023u)==0)check();const auto* p=image.pixels+size_t(y)*image.stride+size_t(x)*4;
            Colour raw=effects.diffusion==0?sampler.sample(p):film_response(model,
                lens_colour(read_linear(p,decode_lut),spatial.lens.sample(x,y,image),spatial.softness));
            for(int c=0;c<3;++c){raw[c]*=effects.exposure_gain;field->values[(size_t(y)*w+x)*3+c]=raw[c];}
            peak=std::max(peak,std::max({raw[0],raw[1],raw[2]}));
        }
        maxima[y]=peak;
    });
    const float clip=*std::max_element(maxima.begin(),maxima.end()),log_clip=std::log2(std::max(clip,1e-20f));
    auto ease=[](float v){v=std::clamp(v,0.f,1.f);return v*v*(3-2*v);};
    parallel_rows(h,uint64_t(w)*8,check,[&](uint32_t y){
        for(uint32_t x=0;x<w;++x){
            if((x&1023u)==0)check();auto* v=field->values.data()+(size_t(y)*w+x)*3;
            const float m=std::max({v[0],v[1],v[2]});float gain=1;
            if(m>0){const float lm=std::log2(m);
                const float near=ease((lm-log_clip-float(spk::kHeadroomClipFromEv))/float(spk::kHeadroomClipToEv-spk::kHeadroomClipFromEv));
                const float floor=ease(lm-std::log2(.18f)-1.5f);gain=std::exp2(4.f*near*floor);}
            for(int c=0;c<3;++c)v[c]*=gain;
        }
    });
    spatial.lens={};spatial.softness=0; // Already represented in the complete exposure field.
    const double px_um=effects.format_mm*(24./36.)*1000./std::min(w,h);
    constexpr double core[3]={2.2,2.,1.6},tail[3]={9.3,9.7,9.1},tw[3]={.78,.65,.67};
    constexpr double amp[3]={.1633,.6496,.1870},ratio[3]={.5360,1.5236,2.7684};
    std::vector<float> raw(count),blurred;
    for(int c=0;c<3;++c){
        auto extract=[&]{parallel_rows(h,uint64_t(w)*4,check,[&](uint32_t y){for(uint32_t x=0;x<w;++x){
            const size_t i=size_t(y)*w+x;raw[i]=field->values[3*i+c];field->values[3*i+c]=0;}});};
        extract();
        auto add=[&](double sigma,double weight){
            spektra_blur(raw,w,h,sigma,check,blurred);
            parallel_rows(h,uint64_t(w)*4,check,[&](uint32_t y){for(uint32_t x=0;x<w;++x){
                const size_t i=size_t(y)*w+x;field->values[3*i+c]+=float(weight)*blurred[i];}});
        };
        add(core[c]/px_um,1-tw[c]);
        for(int k=0;k<3;++k)add(ratio[k]*tail[c]/px_um,amp[k]*tw[c]);
        extract();
        for(int k=0;k<spk::kBackReflectionComponents;++k)add(spk::kBackReflectionSigmaOverBase[k][c]*120./px_um,spk::kBackReflectionWeight[k][c]);
        const float a=float(spk::kBackReflectionStrength[c]);
        parallel_rows(h,uint64_t(w)*4,check,[&](uint32_t y){for(uint32_t x=0;x<w;++x){const size_t i=size_t(y)*w+x;
            field->values[3*i+c]=std::max(0.f,(raw[i]+a*field->values[3*i+c])/(1+a));}});
    }
    check();spatial.prepared_response=field;spatial.complete_response=true;
    {
        std::lock_guard lock(cache.mutex);spatial.response_generation=++cache.generation;
        constexpr size_t limit=384u*1024u*1024u;
        if(field->values.size()*sizeof(float)<=limit){cache.key=key;cache.response=field;}
    }
    cine_spatial_metrics={std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now()-started).count(),false,count*3*sizeof(float)};
    return spatial;
}
} // namespace lfm::mobile
