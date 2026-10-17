// Manual CineStill validation only. No automatic Gradle/CMake test hooks.
#include "mobile_film_gles.hpp"
#include <fstream>
#include <iostream>
#include <chrono>
#include <cstring>
#include <sys/resource.h>
using namespace lfm::mobile;
template<class T>void read(std::ifstream& in,T* data,size_t count=1) {
    in.read(reinterpret_cast<char*>(data),std::streamsize(sizeof(T)*count));
    if(!in)throw std::runtime_error("Fixture truncated");
}
Table table(std::ifstream& in) {
    uint32_t size;read(in,&size);if(size<2 || size>65)throw std::runtime_error("Bad fixture table");
    Table t{int(size),{},{},{}};read(in,t.minimum.data(),3);read(in,t.maximum.data(),3);
    t.values.resize(size_t(size)*size*size*3);read(in,t.values.data(),t.values.size());return t;
}
int main(int argc,char** argv)try {
    if(argc<3 || argc>4)throw std::runtime_error("prefix and prepare/spatial/cpu/gpu backend; optional off required");
    const std::string prefix=argv[1],backend=argv[2];
    std::ifstream input(prefix+".input",std::ios::binary);uint32_t w,h;read(input,&w);read(input,&h);
    if(w==0 || h==0 || uint64_t(w)*h>24'000'000)throw std::runtime_error("Bad fixture size");
    std::vector<uint8_t> pixels(size_t(w)*h*4);read(input,pixels.data(),pixels.size());
    Image image{pixels.data(),w,h,w*4};
    std::ifstream model_file(prefix+".model",std::ios::binary);Model model{table(model_file),table(model_file),table(model_file)};
    std::ifstream controls(prefix+".effects",std::ios::binary);float v[59];read(controls,v,59);
    Effects e{};e.diffusion=v[0];e.halation=v[1]==1;e.exposure_gain=std::pow(2.f,v[2]);
    e.grain=v[3]==1;e.particle_area=v[4];e.format_mm=v[23];e.cinestill=v[24]==1;
    e.halation_bounces=int(v[25]);e.halation_decay=v[26];e.grain_spacing_um=v[27];e.scatter_amount=v[28];
    for(int c=0;c<3;++c) {
        e.particle_scale[c]=v[5+c];e.floor[c]=v[8+c];e.uniformity[c]=v[11+c];e.density_max[c]=v[14+c];
        e.halation_strength[c]=v[17+c];e.halation_sigma_um[c]=v[20+c];
        e.scatter_core_um[c]=v[29+c];e.scatter_tail_um[c]=v[32+c];e.scatter_tail_weight[c]=v[35+c];
        e.fotu_sigma_mm[c]=v[38+c];
        for(int j=0;j<3;++j) {e.fotu_weights[c][j]=v[41+c*3+j];e.fotu_matrix[c][j]=v[50+c*3+j];}
    }
    // Existing 59-float fixtures replay the original physical path; a tail float opts into the JPEG look.
    if(controls.peek()!=std::char_traits<char>::eof())read(controls,&e.remjet_look);
    if(argc==4) {
        if(std::string(argv[3])!="off")throw std::runtime_error("Unknown halation switch");
        e.halation=false;
    }
    const auto start=std::chrono::steady_clock::now();
    const Check check=[]{};
    if(backend=="prepare") {
        std::array<float,256> decode_lut{};for(int i=0;i<256;++i)decode_lut[i]=decode(float(i)/255);
        const Spatial s=prepare_spatial(image,model,e,decode_lut,check);
        if(e.halation && s.halation.values.empty())throw std::runtime_error("Missing halation field");
    } else if(backend=="spatial") {
        std::array<float,256> decode_lut{};for(int i=0;i<256;++i)decode_lut[i]=decode(float(i)/255);
        const Spatial s=prepare_spatial(image,model,e,decode_lut,check);
        std::ofstream output(prefix+".spatial",std::ios::binary);
        for(uint32_t y=0;y<h;++y)for(uint32_t x=0;x<w;++x) {
            const uint8_t* p=pixels.data()+(size_t(y)*w+x)*4;
            const Colour direct=model.response.sample({p[0]/255.f,p[1]/255.f,p[2]/255.f});
            Colour value=direct;const Colour halo=s.halation.sample(x,y,image);
            if(s.spectral_return)for(int c=0;c<3;++c) {
                value[c]+=halo[c];for(int j=0;j<3;++j)value[c]-=s.return_matrix[c][j]*direct[j];
            } else for(int c=0;c<3;++c)value[c]=value[c]*(1-s.halation_mix[c])+halo[c];
            output.write(reinterpret_cast<const char*>(value.data()),sizeof(float)*3);
        }
        if(!output)throw std::runtime_error("Cannot write spatial output");
    } else {
        if(backend=="cpu")render_cpu(image,model,e,w,h,0x46494c4d,check);
        else if(backend=="gpu")render_gles(image,model,e,prefix,w,h,0x46494c4d,check);
        else throw std::runtime_error("Unknown backend");
        std::ofstream output(prefix+"."+backend,std::ios::binary);
        output.write(reinterpret_cast<const char*>(pixels.data()),std::streamsize(pixels.size()));
        if(!output)throw std::runtime_error("Cannot write rendered output");
        if(backend=="gpu")release_idle_gles();
    }
    rusage usage{};getrusage(RUSAGE_SELF,&usage);
    std::cout<<"{\"backend\":\""<<backend<<"\",\"halation\":"<<(e.halation?"true":"false")
        <<",\"width\":"<<w<<",\"height\":"<<h<<",\"peakRssKiB\":"<<usage.ru_maxrss<<",\"elapsedMs\":"
        <<std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count()<<"}\n";
    return 0;
}catch(const std::exception& e){std::cerr<<e.what()<<"\n";return 1;}
