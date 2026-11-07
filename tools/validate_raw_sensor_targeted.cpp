// Manual integration check of the exact sensor decoder called by JNI, on a synthetic DNG only.
#include "../native/raw-preview/raw_sensor.hpp"
#include "../native/common/raw10.hpp"
#include <iostream>
#include <set>
int main(int argc,char** argv)try {
    if(argc!=2)throw std::runtime_error("Pass the synthetic DNG fixture");
    int passed=0;
    for(bool half:{false,true}) {
        auto image=lfm::raw::decode_sensor(argv[1],half,[]{return false;});
        if(image->bits!=16 || image->colors!=3 || image->width!=(half?64:128) || image->height!=(half?48:96))
            throw std::runtime_error("Unexpected sensor decode precision/dimensions");
        std::set<uint32_t> values;
        const auto* rgb=reinterpret_cast<const uint16_t*>(image->data);
        for(unsigned i=0;i<unsigned(image->width)*image->height;++i)values.insert(lfm::pack_raw10(rgb+3*i));
        if(values.size()<24)throw std::runtime_error("Sensor decode lost the gradient");
        std::cout<<(half?"balanced":"full")<<": "<<image->width<<"x"<<image->height<<", 16-bit demosaic -> RGB10, "<<values.size()<<" colours\n";
        ++passed;
    }
    bool cancelled=false;try{auto image=lfm::raw::decode_sensor(argv[1],false,[]{return true;});}
    catch(const std::exception& e){cancelled=std::string(e.what()).find("cancelled")!=std::string::npos;}
    if(!cancelled)throw std::runtime_error("Sensor decode did not honour cancellation");++passed;
    bool rejected=false;try{auto image=lfm::raw::decode_sensor("/data/local/tmp/missing-lfm-raw-file",false,[]{return false;});}
    catch(const std::exception&){rejected=true;}
    if(!rejected)throw std::runtime_error("Missing RAW was silently accepted");++passed;
    std::cout<<passed<<" targeted sensor integration checks passed\n";return 0;
}catch(const std::exception& e){std::cerr<<"FAIL: "<<e.what()<<"\n";return 1;}
