// Adapted from SpektraLab-Windows-NANATATA d667dcf (GPL-3.0-or-later).
// Original equations, kernels, licences and byte hashes: research/spektralab/.
// Full render grid, channel-at-a-time planes. CPU double recurrence replaces
// the upstream GPU double-float pair; no byte/driver parity claim is made.
#pragma once
#include "../../research/spektralab/windows/engine/src/core/halation_psf.hpp"

namespace lfm::mobile {
inline std::vector<float> spektra_blur(const std::vector<float>& input, uint32_t w, uint32_t h,
                                     double sigma, const Check& check) {
    if (!(sigma>0)) return input;
    auto out=input;
    const bool fir=sigma<3.;
    std::vector<double> kernel;
    int radius=0; double b[4]{};
    if(fir) {
        radius=int(3*sigma+.5);kernel.resize(2*radius+1);double total=0;
        for(int k=-radius;k<=radius;++k) { kernel[k+radius]=std::exp(-.5*k*k/(sigma*sigma));total+=kernel[k+radius]; }
        for(auto& v:kernel)v/=total;
    } else {
        const double q=.98711*sigma-.96330,q2=q*q,q3=q2*q;
        const double b0=1.57825+2.44413*q+1.4281*q2+.422205*q3;
        b[1]=(2.44413*q+2.85619*q2+1.26661*q3)/b0;
        b[2]=-(1.4281*q2+1.26661*q3)/b0;b[3]=.422205*q3/b0;b[0]=1-b[1]-b[2]-b[3];
    }
    // FIR vertical then horizontal; IIR horizontal then vertical, like upstream.
    for(int pass=0;pass<2;++pass) {
        const bool horizontal=fir?(pass==1):(pass==0);
        const uint32_t lines=horizontal?h:w,length=horizontal?w:h;
        parallel_rows(lines,uint64_t(length)*8,check,[&](uint32_t line) {
            auto index=[&](uint32_t pos){return horizontal?size_t(line)*w+pos:size_t(pos)*w+line;};
            std::vector<float> values(length);
            for(uint32_t i=0;i<length;++i)values[i]=out[index(i)];
            if(fir) {
                auto reflect=[&](int i){ const int period=2*int(length);i=(i%period+period)%period;return i<int(length)?i:period-1-i; };
                for(uint32_t i=0;i<length;++i) {
                    if((i&1023u)==0)check();double sum=0;
                    for(int k=-radius;k<=radius;++k)sum+=values[reflect(int(i)+k)]*kernel[k+radius];
                    out[index(i)]=float(sum);
                }
            } else {
                double v1=values[0],v2=v1,v3=v1;
                for(uint32_t i=0;i<length;++i) {
                    if((i&1023u)==0)check();
                    const double v=b[0]*values[i]+b[1]*v1+b[2]*v2+b[3]*v3;
                    values[i]=float(v);v3=v2;v2=v1;v1=v;
                }
                v1=values.back();v2=v1;v3=v1;
                for(int i=int(length)-1;i>=0;--i) {
                    if((i&1023)==0)check();
                    const double v=b[0]*values[i]+b[1]*v1+b[2]*v2+b[3]*v3;
                    out[index(uint32_t(i))]=float(v);v3=v2;v2=v1;v1=v;
                }
            }
        });
    }
    return out;
}

inline Spatial prepare_spektralab_spatial(const Image& image,const Model& model,const Effects& effects,
                                        const std::array<float,256>& decode_lut,const Check& check) {
    // Existing black-mist lens is independent; it keeps its existing bounded grid.
    Effects lens_effects=effects;lens_effects.halation=false;
    Spatial spatial;
    if(effects.diffusion>0) spatial=prepare_fotu_spatial(image,model,lens_effects,decode_lut,check);
    if(!effects.halation)return spatial;
    const uint32_t w=image.width,h=image.height;
    const size_t count=size_t(w)*h;
    const ByteResponseSampler sampler(model.response);
    auto response=[&](uint32_t x,uint32_t y) {
        const auto* p=image.pixels+size_t(y)*image.stride+size_t(x)*4;
        Colour raw=effects.diffusion==0?sampler.sample(p):film_response(model,
            lens_colour(read_linear(p,decode_lut),spatial.lens.sample(x,y,image),spatial.softness));
        for(float& v:raw)v*=effects.exposure_gain;
        return raw;
    };
    // Frame maximum is shared, not a per-tile max: no seams or local brightness gates.
    float clip=0;
    for(uint32_t y=0;y<h;++y) {check();for(uint32_t x=0;x<w;++x){const auto v=response(x,y);clip=std::max(clip,std::max({v[0],v[1],v[2]}));}}
    const float log_clip=std::log2(std::max(clip,1e-20f));
    auto ease=[](float v){v=std::clamp(v,0.f,1.f);return v*v*(3-2*v);};
    spatial.halation=Field(w,h);spatial.complete_response=true;
    // Film gate uses actual render pixels and physical 35mm short side; panorama is not cropped.
    const double px_um=effects.format_mm*(24./36.)*1000./std::min(w,h);
    constexpr double core[3]={2.2,2.,1.6},tail[3]={9.3,9.7,9.1},tw[3]={.78,.65,.67};
    constexpr double amp[3]={.1633,.6496,.1870},ratio[3]={.5360,1.5236,2.7684};
    for(int c=0;c<3;++c) {
        std::vector<float> raw(count);
        parallel_rows(h,uint64_t(w)*16,check,[&](uint32_t y) {
            for(uint32_t x=0;x<w;++x) {
                const Colour v=response(x,y);const float m=std::max({v[0],v[1],v[2]});float gain=1;
                if(m>0) {
                    const float lm=std::log2(m);
                    const float near=ease((lm-log_clip-float(spk::kHeadroomClipFromEv))/float(spk::kHeadroomClipToEv-spk::kHeadroomClipFromEv));
                    const float floor=ease(lm-std::log2(.18f)-1.5f);
                    gain=std::exp2(4.f*near*floor);
                }
                raw[size_t(y)*w+x]=v[c]*gain;
            }
        });
        // Scatter core + three-component exponential tail, then base back-reflection.
        auto add=[&](const std::vector<float>& values,double weight) {
            for(size_t i=0;i<count;++i){if((i&16383u)==0)check();spatial.halation.values[3*i+c]+=float(weight)*values[i];}
        };
        add(spektra_blur(raw,w,h,core[c]/px_um,check),1-tw[c]);
        for(int k=0;k<3;++k)add(spektra_blur(raw,w,h,ratio[k]*tail[c]/px_um,check),amp[k]*tw[c]);
        for(size_t i=0;i<count;++i){raw[i]=spatial.halation.values[3*i+c];spatial.halation.values[3*i+c]=0;}
        for(int k=0;k<spk::kBackReflectionComponents;++k)
            add(spektra_blur(raw,w,h,spk::kBackReflectionSigmaOverBase[k][c]*120./px_um,check),spk::kBackReflectionWeight[k][c]);
        const float a=float(spk::kBackReflectionStrength[c]);
        for(size_t i=0;i<count;++i)spatial.halation.values[3*i+c]=std::max(0.f,(raw[i]+a*spatial.halation.values[3*i+c])/(1+a));
    }
    return spatial;
}
} // namespace lfm::mobile
