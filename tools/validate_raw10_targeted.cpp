// Manual, targeted RAW10/source-adjustment regression. No automatic build/test hooks.
#include "../native/mobile-film/mobile_film_core.hpp"
#include <fstream>
#include <iostream>
#include <set>
using namespace lfm::mobile;
int checks=0;
void expect(bool yes,const char* message){if(!yes)throw std::runtime_error(message);++checks;}
uint32_t grey(unsigned v){return v|(v<<10)|(v<<20);}
void fixture(const std::string& path,unsigned w,unsigned h,const std::vector<uint32_t>& data,unsigned version=1) {
    std::ofstream out(path,std::ios::binary);const uint32_t header[]={w,h,version,4};
    out.write("LFM10RGB",8);out.write(reinterpret_cast<const char*>(header),16);
    out.write(reinterpret_cast<const char*>(data.data()),data.size()*4);
}
int main(int argc,char** argv)try {
    if(argc!=2)throw std::runtime_error("Pass a disposable fixture prefix");
    const std::string prefix=argv[1];
    std::set<unsigned> levels;unsigned previous=0;bool monotonic=true;
    for(unsigned i=0;i<16384;++i){uint16_t value=uint16_t((uint64_t(i)*65535+8191)/16383);uint16_t rgb[]={value,value,value};
        const auto p=lfm::pack_raw10(rgb);const auto code=p&1023;levels.insert(code);monotonic&=code>=previous;previous=code;
        if(((p>>10)&1023)!=code || ((p>>20)&1023)!=code)throw std::runtime_error("RGB10 channel packing cross-talk");}
    expect(levels.size()==1024,"14-bit ramp must retain all 1024 working levels, not 256");
    expect(monotonic && *levels.begin()==0 && *levels.rbegin()==1023,"RAW10 transfer must be monotonic and cover its endpoints");
    fixture(prefix+".raw10",2,1,{grey(510),grey(511)});
    auto mapped=std::make_shared<lfm::Raw10Image>((prefix+".raw10").c_str());
    std::vector<uint8_t> png(8,0);Image image{png.data(),2,1,8};Effects e{};e.raw_input=mapped;e.source_target=image;
    const auto a=read_encoded(png.data(),e),b=read_encoded(png.data()+4,e);
    expect(a[0]>.4f && b[0]>a[0],"RAW10 input must survive an all-black 8-bit display surface");
    expect(std::lround(a[0]*255)==std::lround(b[0]*255),"fixture must contain distinct 10-bit values hidden by 8-bit rounding");
    std::array<float,256> decode_lut{};for(int i=0;i<256;++i)decode_lut[i]=decode(i/255.f);
    const auto linear=read_linear(png.data(),decode_lut,e);e.input_exposure=1;
    const auto exposed=read_linear(png.data(),decode_lut,e);
    expect(std::abs(exposed[0]-linear[0]*2)<1e-6f,"RAW exposure must double linear source light before film lookup");
    e.input_exposure=0;
    fixture(prefix+".orientation",2,3,{grey(100),grey(200),grey(300),grey(400),grey(500),grey(600)});
    lfm::Raw10Image orientation((prefix+".orientation").c_str());
    const int expected[8][4]={{100,200,500,600},{200,100,600,500},{600,500,200,100},{500,600,100,200},
        {100,500,200,600},{500,100,600,200},{600,200,500,100},{200,600,100,500}};
    for(int o=1;o<=8;++o){const unsigned w=o>=5?3:2,h=o>=5?2:3;bool correct=true;
        for(int corner=0;corner<4;++corner){auto actual=orientation.sample((corner&1)?w-1:0,(corner&2)?h-1:0,w,h,o);
            correct&=std::abs(actual[0]-lfm::raw_decode(expected[o-1][corner]/1023.f))<.0001f;}
        expect(correct,"RAW EXIF orientation corner mapping failed");}
    for(int kind=0;kind<2;++kind){fixture(prefix+".bad",2,1,kind?std::vector<uint32_t>{grey(200)}:std::vector<uint32_t>{grey(100),grey(200)},kind?1:2);
        bool rejected=false;try{lfm::Raw10Image invalid((prefix+".bad").c_str());}catch(const std::exception&){rejected=true;}
        expect(rejected,"RAW10 must reject unsupported versions and truncated payloads");}
    Colour input{.137f,.612f,.923f};Effects neutral{};
    expect(adjust_source(input,neutral)==input,"zero JPEG source controls must be an exact bypass");
    Effects ev{};ev.input_exposure=-2;
    const auto dimmed=adjust_source({1,1,1},ev);
    expect(std::abs(decode(dimmed[0])-.25f)<1e-6f,"white source pixels must respond to negative EV before the look");
    std::cout<<checks<<" targeted RAW10/source checks passed\n";return 0;
}catch(const std::exception& e){std::cerr<<"FAIL: "<<e.what()<<"\n";return 1;}
