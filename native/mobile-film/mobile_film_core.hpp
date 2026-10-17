#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <functional>
#include <stdexcept>
#include <vector>
#include <atomic>
#include <exception>
#include <mutex>
#include <thread>

namespace lfm::mobile {
using Colour = std::array<float, 3>;
using Check = std::function<void()>;
// Workers only touch disjoint rows and shared immutable data. Check must be native/thread-safe.
// Join all workers before propagating failure, so no bitmap is unlocked/recycled prematurely.
template<class Row> inline void parallel_rows(uint32_t rows, uint64_t work_per_row,
                                             const Check& check, const Row& process) {
    const uint32_t workers = std::min({4u, rows, std::max(1u, std::thread::hardware_concurrency())});
    if (workers < 2 || uint64_t(rows) * work_per_row < 262144) {
        for (uint32_t y = 0; y < rows; ++y) { check(); process(y); }
        return;
    }
    std::atomic_uint32_t next{0};
    std::atomic_bool stopped{false};
    std::exception_ptr failure;
    std::mutex failure_lock;
    {
        struct JoinedThreads {
            std::vector<std::thread> values;
            ~JoinedThreads() { for (auto& thread : values) if (thread.joinable()) thread.join(); }
        } threads;
        threads.values.reserve(workers);
        try {
            for (uint32_t i = 0; i < workers; ++i) threads.values.emplace_back([&] {
                try {
                    while (!stopped.load(std::memory_order_relaxed)) {
                        const uint32_t first = next.fetch_add(4, std::memory_order_relaxed);
                        if (first >= rows) break;
                        for (uint32_t y = first; y < std::min(first + 4, rows); ++y) {
                            if (stopped.load(std::memory_order_relaxed)) break;
                            check(); process(y);
                        }
                    }
                } catch (...) {
                    { std::lock_guard lock(failure_lock); if (!failure) failure = std::current_exception(); }
                    stopped.store(true, std::memory_order_relaxed);
                }
            });
        } catch (...) {
            stopped.store(true, std::memory_order_relaxed);
            throw; // The guard joins the workers already created, including partial startup.
        }
    }
    if (failure) std::rethrow_exception(failure);
    check();
}
struct Image { uint8_t* pixels; uint32_t width, height, stride; };
struct Table {
    int size;
    Colour minimum, maximum;
    std::vector<float> values;
    Colour sample(Colour input) const {
        int lo[3], hi[3]; float mix[3];
        for (int c = 0; c < 3; ++c) {
            if (!std::isfinite(input[c])) throw std::runtime_error("Non-finite film stage input");
            const float p = std::clamp((input[c] - minimum[c]) / (maximum[c] - minimum[c]), 0.f, 1.f) * (size - 1);
            lo[c] = int(p); hi[c] = std::min(lo[c] + 1, size - 1); mix[c] = p - lo[c];
        }
        auto index = [&](int x, int y, int z) { return size_t(x + size * (y + size * z)) * 3; };
        const size_t a = index(lo[0], lo[1], lo[2]), b = index(hi[0], lo[1], lo[2]);
        const size_t c = index(lo[0], hi[1], lo[2]), d = index(hi[0], hi[1], lo[2]);
        const size_t e = index(lo[0], lo[1], hi[2]), f = index(hi[0], lo[1], hi[2]);
        const size_t g = index(lo[0], hi[1], hi[2]), h = index(hi[0], hi[1], hi[2]);
        Colour out{};
        for (int ch = 0; ch < 3; ++ch) {
            const float low = (values[a + ch] * (1 - mix[0]) + values[b + ch] * mix[0]) * (1 - mix[1]) +
                (values[c + ch] * (1 - mix[0]) + values[d + ch] * mix[0]) * mix[1];
            const float high = (values[e + ch] * (1 - mix[0]) + values[f + ch] * mix[0]) * (1 - mix[1]) +
                (values[g + ch] * (1 - mix[0]) + values[h + ch] * mix[0]) * mix[1];
            out[ch] = low * (1 - mix[2]) + high * mix[2];
        }
        return out;
    }
};
struct Model { Table response, development, scan; };
/** Exact trilinear response with byte-axis coordinates hoisted out of CineStill's pixel loops. */
struct ByteResponseSampler {
    const Table& table;
    std::array<std::array<size_t,256>,3> lower{},upper{};
    std::array<std::array<float,256>,3> fraction{};
    explicit ByteResponseSampler(const Table& source):table(source) {
        size_t stride=3;
        for(int c=0;c<3;++c) {
            for(int v=0;v<256;++v) {
                const float p=std::clamp((float(v)/255.f-table.minimum[c])/(table.maximum[c]-table.minimum[c]),0.f,1.f)*(table.size-1);
                const int lo=int(p);
                lower[c][v]=size_t(lo)*stride;upper[c][v]=size_t(std::min(lo+1,table.size-1))*stride;
                fraction[c][v]=p-lo;
            }
            stride*=size_t(table.size);
        }
    }
    Colour sample(const uint8_t* pixel)const {
        const auto& v=table.values;
        const size_t x=lower[0][pixel[0]],xx=upper[0][pixel[0]],y=lower[1][pixel[1]],yy=upper[1][pixel[1]],z=lower[2][pixel[2]],zz=upper[2][pixel[2]];
        const float fx=fraction[0][pixel[0]],fy=fraction[1][pixel[1]],fz=fraction[2][pixel[2]];
        Colour out{};
        for(int c=0;c<3;++c) {
            const float low=(v[x+y+z+c]*(1-fx)+v[xx+y+z+c]*fx)*(1-fy)+(v[x+yy+z+c]*(1-fx)+v[xx+yy+z+c]*fx)*fy;
            const float high=(v[x+y+zz+c]*(1-fx)+v[xx+y+zz+c]*fx)*(1-fy)+(v[x+yy+zz+c]*(1-fx)+v[xx+yy+zz+c]*fx)*fy;
            out[c]=low*(1-fz)+high*fz;
        }
        return out;
    }
};
struct Effects {
    float diffusion, exposure_gain;
    bool halation, grain;
    float particle_area, format_mm;
    Colour particle_scale, floor, uniformity, density_max, halation_strength, halation_sigma_um;
    bool cinestill;
    int halation_bounces;
    float halation_decay, grain_spacing_um;
    float scatter_amount;
    Colour scatter_core_um, scatter_tail_um, scatter_tail_weight;
    Colour fotu_sigma_mm;
    std::array<Colour, 3> fotu_weights, fotu_matrix;
    float remjet_look = 0;
};

inline float decode(float value) { return value <= .04045f ? value / 12.92f : std::pow((value + .055f) / 1.055f, 2.4f); }
inline float encode(float value) { return value <= .0031308f ? value * 12.92f : 1.055f * std::pow(value, 1.f / 2.4f) - .055f; }
inline float luminance(const Colour& rgb) { return .2126f * rgb[0] + .7152f * rgb[1] + .0722f * rgb[2]; }
inline Colour read_linear(const uint8_t* pixel, const std::array<float, 256>& decode_lut) {
    return {decode_lut[pixel[0]], decode_lut[pixel[1]], decode_lut[pixel[2]]};
}

struct Field {
    uint32_t width = 0, height = 0;
    std::vector<float> values;
    Field() = default;
    Field(uint32_t w, uint32_t h) : width(w), height(h), values(size_t(w) * h * 3) {}
    Colour sample(uint32_t x, uint32_t y, const Image& image) const {
        if (values.empty()) return {};
        const float px = std::clamp((float(x) + .5f) * width / image.width - .5f, 0.f, float(width - 1));
        const float py = std::clamp((float(y) + .5f) * height / image.height - .5f, 0.f, float(height - 1));
        const uint32_t x0 = uint32_t(px), y0 = uint32_t(py), x1 = std::min(x0 + 1, width - 1), y1 = std::min(y0 + 1, height - 1);
        const float fx = px - x0, fy = py - y0;
        const size_t a = (size_t(y0) * width + x0) * 3, b = (size_t(y0) * width + x1) * 3;
        const size_t c = (size_t(y1) * width + x0) * 3, d = (size_t(y1) * width + x1) * 3;
        Colour output{};
        for (int ch = 0; ch < 3; ++ch)
            output[ch] = (values[a + ch] * (1 - fx) + values[b + ch] * fx) * (1 - fy) +
                (values[c + ch] * (1 - fx) + values[d + ch] * fx) * fy;
        return output;
    }
};

inline Field gaussian(const Field& source, Colour sigmas, const Check& check) {
    Field horizontal(source.width, source.height), output(source.width, source.height);
    std::array<std::vector<float>, 3> weights;
    int radius[3];
    for (int c = 0; c < 3; ++c) {
        const float sigma = std::clamp(sigmas[c], .1f, 24.f);
        radius[c] = int(std::ceil(sigma * 3));
        weights[c].resize(size_t(radius[c] * 2 + 1));
        double sum = 0;
        for (int i = -radius[c]; i <= radius[c]; ++i) {
            float& weight = weights[c][size_t(i + radius[c])];
            weight = std::exp(-.5f * i * i / (sigma * sigma)); sum += weight;
        }
        for (float& weight : weights[c]) weight = float(weight / sum);
    }
    parallel_rows(source.height, uint64_t(source.width) * 12, check, [&](uint32_t y) {
        for (uint32_t x = 0; x < source.width; ++x) for (int c = 0; c < 3; ++c) {
            double sum = 0;
            for (int offset = -radius[c]; offset <= radius[c]; ++offset) {
                const uint32_t sx = uint32_t(std::clamp(int(x) + offset, 0, int(source.width) - 1));
                sum += source.values[(size_t(y) * source.width + sx) * 3 + c] * weights[c][size_t(offset + radius[c])];
            }
            horizontal.values[(size_t(y) * source.width + x) * 3 + c] = float(sum);
        }
    });
    parallel_rows(source.height, uint64_t(source.width) * 12, check, [&](uint32_t y) {
        for (uint32_t x = 0; x < source.width; ++x) for (int c = 0; c < 3; ++c) {
            double sum = 0;
            for (int offset = -radius[c]; offset <= radius[c]; ++offset) {
                const uint32_t sy = uint32_t(std::clamp(int(y) + offset, 0, int(source.height) - 1));
                sum += horizontal.values[(size_t(sy) * source.width + x) * 3 + c] * weights[c][size_t(offset + radius[c])];
            }
            output.values[(size_t(y) * source.width + x) * 3 + c] = float(sum);
        }
    });
    return output;
}

/** Area reduction in linear/response light, never in encoded JPEG values. */
inline Field reduce_field(const Field& source, uint32_t w, uint32_t h, const Check& check) {
    Field output(w, h);
    parallel_rows(h, uint64_t(source.width) * source.height / h, check, [&](uint32_t y) {
        const uint32_t y0 = uint32_t(uint64_t(y) * source.height / h), y1 = uint32_t(uint64_t(y + 1) * source.height / h);
        for (uint32_t x = 0; x < w; ++x) {
            const uint32_t x0 = uint32_t(uint64_t(x) * source.width / w), x1 = uint32_t(uint64_t(x + 1) * source.width / w);
            double sum[3]{};
            for (uint32_t sy = y0; sy < y1; ++sy) for (uint32_t sx = x0; sx < x1; ++sx)
                for (int c = 0; c < 3; ++c) sum[c] += source.values[(size_t(sy) * source.width + sx) * 3 + c];
            const double count = double(x1 - x0) * (y1 - y0);
            for (int c = 0; c < 3; ++c) output.values[(size_t(y) * w + x) * 3 + c] = float(sum[c] / count);
        }
    });
    return output;
}

/** Wide scatter gets its own coarser grid; narrow components retain the analysis grid. */
inline Field scatter_gaussian(const Field& source, Colour sigmas, const Check& check) {
    for (float& sigma : sigmas) sigma = std::clamp(sigma, .1f, 24.f);
    uint32_t stride = 1;
    const float narrowest = *std::min_element(sigmas.begin(), sigmas.end());
    while (stride < 8 && narrowest / float(stride * 2) >= 1.f) stride *= 2;
    if (stride == 1 || std::min(source.width, source.height) < stride * 2) return gaussian(source, sigmas, check);
    const uint32_t w = std::max(1u, source.width / stride), h = std::max(1u, source.height / stride);
    const Field reduced = reduce_field(source, w, h, check);
    const float actual_stride = std::max(float(source.width) / w, float(source.height) / h);
    for (float& sigma : sigmas) {
        // Area averaging and linear reconstruction consume approximately 1/4 coarse-pixel variance.
        sigma = std::sqrt(std::max(.0625f, sigma * sigma / (actual_stride * actual_stride) - .25f));
    }
    const Field blurred = gaussian(reduced, sigmas, check);
    Field output(source.width, source.height);
    const Image dimensions{nullptr, source.width, source.height, 0};
    parallel_rows(source.height, uint64_t(source.width) * 4, check, [&](uint32_t y) {
        for (uint32_t x = 0; x < source.width; ++x) {
            const Colour rgb = blurred.sample(x, y, dimensions);
            for (int c = 0; c < 3; ++c) output.values[(size_t(y) * source.width + x) * 3 + c] = rgb[c];
        }
    });
    return output;
}

struct Spatial {
    Field lens, halation, remjet;
    float softness = 0;
    Colour halation_mix{};
    bool spectral_return = false;
    std::array<Colour, 3> return_matrix{};
};
inline Colour lens_colour(Colour linear, const Colour& glow, float softness) {
    for (int c = 0; c < 3; ++c) linear[c] = std::clamp(linear[c] * (1 - softness) + glow[c], 0.f, 1.f);
    return linear;
}
inline Colour film_response(const Model& model, Colour linear) {
    for (float& c : linear) c = std::clamp(encode(c), 0.f, 1.f);
    return model.response.sample(linear);
}

/** JPEG highlight surrogate, not reconstructed HDR radiance. Weak blue-only highlights contribute less. */
inline float remjet_seed(const Colour& linear, float gain) {
    const float peak = std::max({linear[0], linear[1]*.85f, linear[2]*.65f}) * gain;
    const float t = std::clamp((peak-.55f)/.40f,0.f,1.f);
    return t*t*(3-2*t) * std::clamp((peak-.35f)/.65f,0.f,3.f);
}

/** Three box passes approximate narrow/body/tail Gaussian components in O(pixels), independent of radius. */
inline Field remjet_spread(Field source, Colour sigmas, const Check& check) {
    int radius[3];
    for (int c=0;c<3;++c) radius[c]=std::max(1,int((std::sqrt(4*sigmas[c]*sigmas[c]+1)-1)*.5f+.5f));
    for (int pass=0;pass<6;++pass) {
        const bool horizontal=(pass&1)==0;
        Field output(source.width,source.height);
        const uint32_t lines=horizontal?source.height:source.width, length=horizontal?source.width:source.height;
        parallel_rows(lines,uint64_t(length)*3,check,[&](uint32_t line) {
            auto index=[&](int position,int c) {
                const uint32_t p=uint32_t(std::clamp(position,0,int(length)-1));
                return (horizontal?(size_t(line)*source.width+p):(size_t(p)*source.width+line))*3+c;
            };
            for(int c=0;c<3;++c) {
                const int r=radius[c]; double sum=0;
                for(int k=-r;k<=r;++k)sum+=source.values[index(k,c)];
                for(uint32_t p=0;p<length;++p) {
                    if((p&1023u)==0)check();
                    output.values[index(int(p),c)]=float(sum/(2*r+1));
                    sum+=double(source.values[index(int(p)+r+1,c)])-source.values[index(int(p)-r,c)];
                }
            }
        });
        source=std::move(output);
    }
    return source;
}

inline Colour apply_remjet_look(Colour scanned,const Colour& spread,float direct,float amount) {
    const float rim=std::max(0.f,.25f*spread[0]+.65f*spread[1]+.10f*spread[2]-direct)*amount;
    constexpr Colour tint{.65f,.014f,.001f};
    for(int c=0;c<3;++c)scanned[c]=encode(std::max(0.f,decode(std::clamp(scanned[c],0.f,1.f)))+tint[c]*rim);
    return scanned;
}

/** Fotufilm default halation's decimated, collapsed three-box convolution.
 * Adapted from Schedule/Cpu.h and FotufilmHalide.h (Apache-2.0, MUAStudio Inc.).
 * Preserve the discrete radius, edge normalization and scale-specific grids.
 */
inline Field fotu_spread(const Field& source, float sigma_pixels, uint32_t& stride, const Check& check) {
    const int radius = sigma_pixels > .3f ? int((std::sqrt(4*sigma_pixels*sigma_pixels+1)-1)*.5f) : 0;
    const float sigma = std::sqrt(float(radius*(radius+1)));
    stride = 1;
    while (stride < 8 && float(2*stride)*2.5f <= sigma) stride *= 2;
    int r = radius;
    if (stride > 1) {
        const float variance = std::max(.25f, sigma*sigma/float(stride*stride)-.25f);
        r = std::max(1, int((std::sqrt(4*variance+1)-1)*.5f+.5f));
    }
    const uint32_t w = (source.width+stride-1)/stride, h = (source.height+stride-1)/stride;
    Field down(w,h);
    parallel_rows(h, uint64_t(w)*stride*stride*3, check, [&](uint32_t y) {
        for (uint32_t x=0; x<w; ++x) {
            Colour sum{};
            const uint32_t x1=std::min((x+1)*stride,source.width), y1=std::min((y+1)*stride,source.height);
            for (uint32_t sy=y*stride; sy<y1; ++sy) for (uint32_t sx=x*stride; sx<x1; ++sx)
                for (int c=0;c<3;++c) sum[c]+=source.values[(size_t(sy)*source.width+sx)*3+c];
            const float count=float((x1-x*stride)*(y1-y*stride));
            for(int c=0;c<3;++c) down.values[(size_t(y)*w+x)*3+c]=sum[c]/count;
        }
    });
    if (r==0) return down; // Subpixel scale keeps its energy in place, as upstream.
    std::vector<float> kernel(1,1.f);
    const float box=1.f/(2*r+1);
    for(int pass=0;pass<3;++pass) {
        std::vector<float> next(kernel.size()+2*r,0.f);
        for(size_t i=0;i<kernel.size();++i) for(int j=0;j<=2*r;++j) next[i+j]+=kernel[i]*box;
        kernel=std::move(next);
    }
    const int reach=3*r;
    Field horizontal(w,h), output(w,h);
    // Constant exterior with valid-tap normalization, not edge replication per box pass.
    parallel_rows(h,uint64_t(w)*kernel.size()*3,check,[&](uint32_t y) {
        for(uint32_t x=0;x<w;++x) {
            Colour sum{}; float norm=0;
            for(int k=-reach;k<=reach;++k) {
                const int sx=int(x)+k;
                if(sx<0 || sx>=int(w))continue;
                const float weight=kernel[size_t(k+reach)]; norm+=weight;
                for(int c=0;c<3;++c) sum[c]+=down.values[(size_t(y)*w+sx)*3+c]*weight;
            }
            for(int c=0;c<3;++c)horizontal.values[(size_t(y)*w+x)*3+c]=sum[c]/std::max(norm,1e-12f);
        }
    });
    parallel_rows(h,uint64_t(w)*kernel.size()*3,check,[&](uint32_t y) {
        for(uint32_t x=0;x<w;++x) {
            Colour sum{}; float norm=0;
            for(int k=-reach;k<=reach;++k) {
                const int sy=int(y)+k;
                if(sy<0 || sy>=int(h))continue;
                const float weight=kernel[size_t(k+reach)]; norm+=weight;
                for(int c=0;c<3;++c)sum[c]+=horizontal.values[(size_t(sy)*w+x)*3+c]*weight;
            }
            for(int c=0;c<3;++c)output.values[(size_t(y)*w+x)*3+c]=sum[c]/std::max(norm,1e-12f);
        }
    });
    return output;
}

inline Colour fotu_sample(const Field& field, uint32_t x, uint32_t y, uint32_t stride) {
    const float px=(float(x)+.5f)/stride-.5f, py=(float(y)+.5f)/stride-.5f;
    const int x0=int(std::floor(px)),y0=int(std::floor(py));
    const float fx=px-x0,fy=py-y0;
    Colour out{};float norm=0;
    for(int dy=0;dy<2;++dy)for(int dx=0;dx<2;++dx) {
        const int sx=x0+dx,sy=y0+dy;
        if(sx<0 || sy<0 || sx>=int(field.width) || sy>=int(field.height))continue;
        const float weight=(dx?fx:1-fx)*(dy?fy:1-fy);norm+=weight;
        for(int c=0;c<3;++c)out[c]+=weight*field.values[(size_t(sy)*field.width+sx)*3+c];
    }
    for(float& c:out)c/=std::max(norm,1e-12f);
    return out;
}

inline Spatial prepare_fotu_spatial(const Image& image,const Model& model,const Effects& effects,
                                   const std::array<float,256>& decode_lut,const Check& check) {
    Spatial spatial;
    constexpr uint32_t edge=1536;
    const double scale=std::min(1.0,double(edge)/std::max(image.width,image.height));
    const uint32_t w=std::min(edge,std::max(1u,uint32_t(std::ceil(image.width*scale))));
    const uint32_t h=std::min(edge,std::max(1u,uint32_t(std::ceil(image.height*scale))));
    Field light,response,emission;
    const bool remjet=effects.halation && effects.remjet_look>0;
    if(remjet)emission=Field(w,h);
    if(effects.diffusion>0)light=Field(w,h);
    const ByteResponseSampler byte_response(model.response);
    if(effects.halation)response=Field(w,h);
    // Nonlinear exposure BEFORE spatial reduction. No max-pooling or invented highlight gain.
    parallel_rows(h,uint64_t(image.width)*image.height/h,check,[&](uint32_t gy) {
        const uint32_t y0=uint32_t(uint64_t(gy)*image.height/h),y1=uint32_t(uint64_t(gy+1)*image.height/h);
        for(uint32_t gx=0;gx<w;++gx) {
            const uint32_t x0=uint32_t(uint64_t(gx)*image.width/w),x1=uint32_t(uint64_t(gx+1)*image.width/w);
            double sum[3]{},raw_sum[3]{},seed_sum=0;
            for(uint32_t y=y0;y<y1;++y)for(uint32_t x=x0;x<x1;++x) {
                if((x&1023u)==0)check();
                const uint8_t* pixel=image.pixels+size_t(y)*image.stride+size_t(x)*4;
                if(effects.diffusion>0 || remjet) {
                    const Colour rgb=read_linear(pixel,decode_lut);
                    if(effects.diffusion>0)for(int c=0;c<3;++c)sum[c]+=rgb[c];
                    if(remjet)seed_sum+=remjet_seed(rgb,effects.exposure_gain);
                }
                if(effects.halation && effects.diffusion==0) {
                    const Colour raw=byte_response.sample(pixel);
                    for(int c=0;c<3;++c)raw_sum[c]+=raw[c];
                }
            }
            const double count=double(x1-x0)*(y1-y0);
            for(int c=0;c<3;++c) {
                if(effects.diffusion>0)light.values[(size_t(gy)*w+gx)*3+c]=float(sum[c]/count);
                if(effects.halation && effects.diffusion==0)response.values[(size_t(gy)*w+gx)*3+c]=float(raw_sum[c]/count);
                if(remjet)emission.values[(size_t(gy)*w+gx)*3+c]=float(seed_sum/count);
            }
        }
    });
    if(effects.diffusion>0) {
        spatial.softness=effects.diffusion*.45f;
        spatial.lens=Field(w,h);
        for(const auto [sigma,weight]:std::array<std::array<float,2>,3>{{{.75f,.20f},{3.5f,.35f},{10.f,.45f}}}) {
            const Field blurred=scatter_gaussian(light,{sigma,sigma,sigma},check);
            for(size_t i=0;i<blurred.values.size();++i)spatial.lens.values[i]+=spatial.softness*.55f*weight*blurred.values[i];
        }
        if(effects.halation)parallel_rows(h,uint64_t(image.width)*image.height/h,check,[&](uint32_t gy) {
            const uint32_t y0=uint32_t(uint64_t(gy)*image.height/h),y1=uint32_t(uint64_t(gy+1)*image.height/h);
            for(uint32_t gx=0;gx<w;++gx) {
                const uint32_t x0=uint32_t(uint64_t(gx)*image.width/w),x1=uint32_t(uint64_t(gx+1)*image.width/w);
                double sum[3]{};
                for(uint32_t y=y0;y<y1;++y)for(uint32_t x=x0;x<x1;++x) {
                    if((x&1023u)==0)check();
                    const Colour rgb=read_linear(image.pixels+size_t(y)*image.stride+size_t(x)*4,decode_lut);
                    const Colour raw=film_response(model,lens_colour(rgb,spatial.lens.sample(x,y,image),spatial.softness));
                    for(int c=0;c<3;++c)sum[c]+=raw[c];
                }
                const double count=double(x1-x0)*(y1-y0);
                for(int c=0;c<3;++c)response.values[(size_t(gy)*w+gx)*3+c]=float(sum[c]/count);
            }
        });
    }
    if(!effects.halation)return spatial;
    light.values.clear();light.values.shrink_to_fit();
    Field scattered(w,h);
    // FilmFormat.still35 uses the rendered short side / 24mm, including non-3:2 images.
    const float px_per_mm=std::min(w,h)/(effects.format_mm*(24.f/36.f));
    for(int k=0;k<3;++k) {
        uint32_t stride;
        const Field part=fotu_spread(response,effects.fotu_sigma_mm[k]*px_per_mm,stride,check);
        parallel_rows(h,uint64_t(w)*12,check,[&](uint32_t y) {
            for(uint32_t x=0;x<w;++x) {
                const Colour value=fotu_sample(part,x,y,stride);
                for(int c=0;c<3;++c)scattered.values[(size_t(y)*w+x)*3+c]+=effects.fotu_weights[c][k]*value[c];
            }
        });
    }
    // Carry M * scattered; subtract M * full-resolution direct in BOTH final backends.
    // Do not subtract an averaged direct field or return the old diagonal-only mix.
    spatial.halation=Field(w,h);spatial.spectral_return=true;spatial.return_matrix=effects.fotu_matrix;
    parallel_rows(h,uint64_t(w)*12,check,[&](uint32_t y) {
        for(uint32_t x=0;x<w;++x)for(int c=0;c<3;++c)for(int j=0;j<3;++j)
            spatial.halation.values[(size_t(y)*w+x)*3+c]+=effects.fotu_matrix[c][j]*scattered.values[(size_t(y)*w+x)*3+j];
    });
    if(remjet) {
        response={}; scattered={}; // Reuse their memory budget for the bounded JPEG look field.
        // Spread the main red body visibly beyond the light, with a short soft falloff.
        // Keep most energy here rather than a large, low-contrast coloured bloom tail.
        spatial.remjet=remjet_spread(std::move(emission),{.10f*px_per_mm,.18f*px_per_mm,.28f*px_per_mm},check);
    }
    return spatial;
}

inline Spatial prepare_spatial(const Image& image, const Model& model, const Effects& effects,
                               const std::array<float, 256>& decode_lut, const Check& check) {
    Spatial spatial;
    const bool active_halation = effects.halation && std::any_of(effects.halation_strength.begin(),
        effects.halation_strength.end(), [](float value) { return value > 0; });
    if (effects.diffusion == 0 && !active_halation) return spatial;
    if (effects.cinestill) return prepare_fotu_spatial(image,model,effects,decode_lut,check);
    const uint32_t analysis_edge = 512u;
    const double scale = std::min(1.0, double(analysis_edge) / std::max(image.width, image.height));
    const uint32_t w = std::min(analysis_edge, std::max(1u, uint32_t(std::ceil(image.width * scale))));
    const uint32_t h = std::min(analysis_edge, std::max(1u, uint32_t(std::ceil(image.height * scale))));
    // One full-image pass shared by lens diffusion and film return. No absolute highlight cutoff.
    // Linear area means preserve the energy of small lights before the compact response lookup.
    Field light(w, h);
    parallel_rows(h, uint64_t(image.width) * image.height / h, check, [&](uint32_t gy) {
        const uint32_t y0 = uint32_t(uint64_t(gy) * image.height / h), y1 = uint32_t(uint64_t(gy + 1) * image.height / h);
        for (uint32_t gx = 0; gx < w; ++gx) {
            const uint32_t x0 = uint32_t(uint64_t(gx) * image.width / w), x1 = uint32_t(uint64_t(gx + 1) * image.width / w);
            double sum[3]{};
            for (uint32_t y = y0; y < y1; ++y) {
                check();
                const uint8_t* row = image.pixels + size_t(y) * image.stride;
                for (uint32_t x = x0; x < x1; ++x) {
                    if ((x & 1023u) == 0) check();
                    const Colour rgb = read_linear(row + size_t(x) * 4, decode_lut);
                    for (int c = 0; c < 3; ++c) sum[c] += rgb[c];
                }
            }
            const double count = double(x1 - x0) * (y1 - y0);
            for (int c = 0; c < 3; ++c) light.values[(size_t(gy) * w + gx) * 3 + c] = float(sum[c] / count);
        }
    });
    if (effects.diffusion > 0) {
        // Illustrative black-mist response: sharp, scattered and absorbed shares are separate.
        // Grades change loading, not blur radius; these are not measurements of a branded filter.
        const float intercepted = effects.diffusion * .45f;
        const float scattered = intercepted * .55f;
        spatial.softness = intercepted;
        spatial.lens = Field(w, h);
        for (const auto [sigma, weight] : std::array<std::array<float, 2>, 3>{{{.75f, .20f}, {3.5f, .35f}, {10.f, .45f}}}) {
            const Field blurred = scatter_gaussian(light, {sigma, sigma, sigma}, check);
            for (size_t i = 0; i < blurred.values.size(); ++i) {
                if ((i & 16383u) == 0) check();
                spatial.lens.values[i] += scattered * weight * blurred.values[i];
            }
        }
    }
    if (!active_halation) return spatial;
    Field response(w, h);
    parallel_rows(h, uint64_t(w) * 12, check, [&](uint32_t y) {
        for (uint32_t x = 0; x < w; ++x) {
            const size_t offset = (size_t(y) * w + x) * 3;
            Colour rgb{light.values[offset], light.values[offset + 1], light.values[offset + 2]};
            if (effects.diffusion > 0) {
                const Colour glow{spatial.lens.values[offset], spatial.lens.values[offset + 1], spatial.lens.values[offset + 2]};
                rgb = lens_colour(rgb, glow, spatial.softness);
            }
            const Colour raw = film_response(model, rgb);
            for (int c = 0; c < 3; ++c) response.values[offset + c] = std::max(0.f, raw[c]);
        }
    });
    Field original_response;
    if (effects.scatter_amount > 0) {
        // SpektraFilm's default emulsion scatter: Gaussian core plus exponential
        // tail, with its pinned three-Gaussian exponential surrogate. Bounded-grid approximation.
        original_response = response;
        const float pixel_um = effects.format_mm * 1000 / std::max(w, h);
        Colour core_sigma{};
        for (int c = 0; c < 3; ++c) core_sigma[c] = effects.scatter_core_um[c] / pixel_um;
        const Field core = scatter_gaussian(response, core_sigma, check);
        Field tail(w, h);
        for (const auto [weight, factor] : std::array<std::array<float, 2>, 3>{{{.1633f, .5360f}, {.6496f, 1.5236f}, {.1870f, 2.7684f}}}) {
            Colour tail_sigma{};
            for (int c = 0; c < 3; ++c) tail_sigma[c] = effects.scatter_tail_um[c] * factor / pixel_um;
            const Field part = scatter_gaussian(response, tail_sigma, check);
            for (size_t i = 0; i < tail.values.size(); ++i) tail.values[i] += weight * part.values[i];
        }
        for (size_t i = 0; i < response.values.size(); ++i) {
            const float weight = effects.scatter_tail_weight[i % 3];
            response.values[i] = (1 - effects.scatter_amount) * response.values[i] +
                effects.scatter_amount * ((1 - weight) * core.values[i] + weight * tail.values[i]);
        }
    }
    // The caller resolves stock versus explicit creative look. Do not add another gain here:
    // ordinary stocks keep their supplied returned/direct ratios, including zero channels.
    Colour sigma{};
    for (int c = 0; c < 3; ++c) {
        const float returned = effects.halation_strength[c];
        spatial.halation_mix[c] = returned / (1.f + returned);
        sigma[c] = std::max(.35f, effects.halation_sigma_um[c] * std::max(w, h) / (effects.format_mm * 1000));
    }
    spatial.halation = Field(w, h);
    // Positive centred scales form a smooth return profile. No broad-minus-core shell or clipping.
    // Ordinary films use SpektraFilm's sqrt(k) bounce widths and normalized decay.
    // CineStill has already returned through its own upstream physical branch above.
    std::vector<std::array<float, 2>> kernels;
    {
        float total = 0.f;
        for (int k = 0; k < effects.halation_bounces; ++k) total += std::pow(effects.halation_decay, float(k));
        for (int k = 0; k < effects.halation_bounces; ++k)
            kernels.push_back({std::sqrt(float(k + 1)), std::pow(effects.halation_decay, float(k)) / total});
    }
    for (const auto [scale_factor, weight] : kernels) {
        Colour scales{};
        for (int c = 0; c < 3; ++c) scales[c] = sigma[c] * scale_factor;
        const Field blurred = scatter_gaussian(response, scales, check);
        for (size_t i = 0; i < blurred.values.size(); ++i) {
            if ((i & 16383u) == 0) check();
            spatial.halation.values[i] += weight * spatial.halation_mix[i % 3] * blurred.values[i];
        }
    }
    if (!effects.cinestill) {
        // Carry the bounded-field correction E2-E0 into the full-resolution pixel.
        // This also carries E1's scatter without another texture or full image pass.
        for (size_t i = 0; i < spatial.halation.values.size(); ++i) {
            const float before = original_response.values.empty() ? response.values[i] : original_response.values[i];
            spatial.halation.values[i] += response.values[i] * (1 - spatial.halation_mix[i % 3]) - before;
        }
        spatial.halation_mix = {};
    }
    return spatial;
}

inline uint32_t hash(uint32_t value) {
    value = (value ^ (value >> 16)) * 0x85ebca6bu;
    value = (value ^ (value >> 13)) * 0xc2b2ae35u;
    return value ^ (value >> 16);
}
inline float normal_at(int64_t x, int64_t y, uint32_t seed, uint32_t salt) {
    uint32_t base = uint32_t(x) * 73856093u ^ uint32_t(y) * 19349663u ^ seed ^ salt;
    float sum = 0;
    for (uint32_t i = 0; i < 3; ++i) sum += float(hash(base + i * 0x9e3779b9u) & 65535u) / 65535.f - .5f;
    return sum * 2.f; // Three uniforms, mean 0 and approximately unit variance; not exact Gaussian.
}
inline float grain_noise(double gx, double gy, uint32_t seed, uint32_t salt, double spacing) {
    gx /= spacing; gy /= spacing;
    const auto x = int64_t(std::floor(gx)), y = int64_t(std::floor(gy));
    const float fx = float(gx - x), fy = float(gy - y);
    const float a = (1-fx)*(1-fy), b = fx*(1-fy), c = (1-fx)*fy, d = fx*fy;
    const float energy = std::sqrt(a*a + b*b + c*c + d*d);
    return (a*normal_at(x,y,seed,salt) + b*normal_at(x+1,y,seed,salt) +
            c*normal_at(x,y+1,seed,salt) + d*normal_at(x+1,y+1,seed,salt)) / energy;
}
inline void apply_grain(Colour& density, const Effects& effects, double gx, double gy,
                        float pixel_area, uint32_t seed, double spacing) {
    if (!effects.grain) return;
    const float shared = grain_noise(gx, gy, seed, 0x7f4a7c15u, spacing);
    for (int c = 0; c < 3; ++c) {
        const float maximum = effects.density_max[c] + effects.floor[c];
        const float p = std::clamp((density[c] + effects.floor[c]) / maximum, 0.f, 1.f);
        const float n_inverse = effects.particle_area * effects.particle_scale[c] / pixel_area;
        const float deviation = maximum * std::sqrt(std::max(0.f, p * (1 - p * effects.uniformity[c]) * n_inverse));
        const float noise = .35f * shared + .9367497f * grain_noise(gx, gy, seed, 0x9e3779b9u * uint32_t(c + 1), spacing);
        density[c] += deviation * noise;
    }
}

inline void render_cpu(Image image, const Model& model, const Effects& effects,
                       uint32_t source_width, uint32_t source_height, uint64_t grain_seed, const Check& check) {
    std::array<float, 256> decode_lut{};
    for (int i = 0; i < 256; ++i) decode_lut[size_t(i)] = decode(float(i) / 255);
    const Spatial spatial = prepare_spatial(image, model, effects, decode_lut, check);
    const float pixel_um = effects.format_mm * 1000 / std::max(image.width, image.height);
    const uint32_t seed = uint32_t(grain_seed) ^ hash(uint32_t(grain_seed >> 32));
    const double grain_spacing = effects.grain_spacing_um > 0 ? std::max(.7,
        double(effects.grain_spacing_um) * std::max(source_width, source_height) / (effects.format_mm * 1000)) : 1.4;
    // All spatial input fields are complete. In-place output never feeds a later pixel.
    parallel_rows(image.height, image.width, check, [&](uint32_t y) {
        uint8_t* row = image.pixels + size_t(y) * image.stride;
        const double gy = (double(y) + .5) * source_height / image.height - .5;
        for (uint32_t x = 0; x < image.width; ++x) {
            if ((x & 255u) == 0) check();
            uint8_t* pixel = row + size_t(x) * 4;
            Colour raw = effects.diffusion == 0 ? model.response.sample({pixel[0] / 255.f, pixel[1] / 255.f, pixel[2] / 255.f}) :
                film_response(model, lens_colour(read_linear(pixel, decode_lut), spatial.lens.sample(x, y, image), spatial.softness));
            const Colour halo = spatial.halation.sample(x, y, image);
            if (spatial.spectral_return) {
                const Colour direct=raw;
                for(int c=0;c<3;++c) {
                    raw[c]+=halo[c];
                    for(int j=0;j<3;++j)raw[c]-=spatial.return_matrix[c][j]*direct[j];
                }
                for(float& c:raw)c=std::log10(std::max(0.f,c)*effects.exposure_gain+1e-10f);
            } else for (int c = 0; c < 3; ++c) raw[c] = std::log10(std::max(0.f,
                raw[c] * (1.f - spatial.halation_mix[c]) + halo[c]) * effects.exposure_gain + 1e-10f);
            Colour density = model.development.sample(raw);
            const double gx = (double(x) + .5) * source_width / image.width - .5;
            apply_grain(density, effects, gx, gy, pixel_um * pixel_um, seed, grain_spacing);
            Colour result = model.scan.sample(density);
            for(float c:result)if(!std::isfinite(c))throw std::runtime_error("Non-finite mobile film output");
            if(!spatial.remjet.values.empty())result=apply_remjet_look(result,spatial.remjet.sample(x,y,image),
                remjet_seed(read_linear(pixel,decode_lut),effects.exposure_gain),effects.remjet_look);
            for (int c = 0; c < 3; ++c) {
                if (!std::isfinite(result[c])) throw std::runtime_error("Non-finite mobile film output");
                pixel[c] = uint8_t(std::lround(std::clamp(result[c], 0.f, 1.f) * 255));
            }
        }
    });
    check();
}
} // namespace lfm::mobile
