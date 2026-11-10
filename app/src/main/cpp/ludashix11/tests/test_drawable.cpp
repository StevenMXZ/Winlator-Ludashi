#include "native_drawable.h"
#include "core/exceptions.h"
#include <iostream>
#include <random>
#include <chrono>
#include <cstring>
using namespace xorg;

static std::vector<u8> Read(NativeDrawable& d) {
    std::vector<u8> out(size_t(d.GetWidth())*d.GetHeight()*4);
    d.ReadPixels(out.data(),0,0,d.GetWidth(),d.GetHeight(),d.GetWidth()); return out;
}
static void Check(bool condition) { if (!condition) throw std::runtime_error("drawable regression"); }
static void Reference(NativeDrawable& dst, NativeDrawable& src, int sx,int sy,int dx,int dy,int w,int h) {
    std::vector<u8> copy(size_t(w)*h*4);
    src.ReadPixels(copy.data(),sx,sy,w,h,w); dst.WritePixels(copy.data(),0,0,dx,dy,w,h,w);
}
int main(int argc,char**) {
    std::mt19937 rng(42);
    std::vector<u8> initial(31*23*4); for(auto& b:initial) b=rng();
    for(bool self : {false,true}) for(int trial=0;trial<500;++trial) {
        NativeDrawable src(31,23,24), actual(31,23,24), expected(31,23,24);
        src.WritePixels(initial.data(),0,0,0,0,31,23,31);
        actual.WritePixels(initial.data(),0,0,0,0,31,23,31);
        expected.WritePixels(initial.data(),0,0,0,0,31,23,31);
        auto retained=actual.Pixels(); const auto before=*retained;
        int sx=rng()%31,sy=rng()%23,w=1+rng()%(31-sx),h=1+rng()%(23-sy);
        int dx=int(rng()%62)-31,dy=int(rng()%46)-23;
        Reference(expected,self?expected:src,sx,sy,dx,dy,w,h);
        actual.CopyAreaFrom(self?actual:src,sx,sy,dx,dy,w,h);
        Check(Read(actual)==Read(expected)); Check(*retained==before);
    }
    // Explicit overlap in all directions, stride boundaries and clipping.
    for(auto [dx,dy] : {std::pair{0,1},{0,-1},{1,0},{-1,0},{3,4},{-3,-4}}) {
        NativeDrawable a(31,23,24), b(31,23,24);
        a.WritePixels(initial.data(),0,0,0,0,31,23,31); b.WritePixels(initial.data(),0,0,0,0,31,23,31);
        Reference(b,b,0,0,dx,dy,31,23); a.CopyAreaFrom(a,0,0,dx,dy,31,23);
        Check(Read(a)==Read(b));
    }
    NativeDrawable d(31,23,24);
    bool bad=false; try {d.CopyAreaFrom(d,-1,0,0,0,1,1);} catch(const BadMatchError&) {bad=true;} Check(bad);
    bad=false; try {d.CopyAreaFrom(d,30,22,0,0,2,2);} catch(const BadMatchError&) {bad=true;} Check(bad);
    bad=false; try {d.CopyAreaFrom(d,0,0,0,0,0,1);} catch(const BadMatchError&) {bad=true;} Check(bad);
    auto snapshot=d.Pixels(); auto revision=d.Revision();
    d.FillRect(100,100,1,1,42); d.FillRect(0,0,0,1,42); d.FillRect(-5,0,1,1,42);
    Check(d.Pixels()==snapshot && d.Revision()==revision);
    // AHB source and destination retain the previous mapping/copy fallback.
    auto* ahb=new AHardwareBuffer; ahb->desc={31,23,1,5,0,32}; ahb->pixels.resize(32*23*4);
    {
        NativeDrawable hardware(31,23,24,ahb), cpu(31,23,24);
        hardware.WritePixels(initial.data(),0,0,0,0,31,23,31);
        cpu.CopyAreaFrom(hardware,0,0,0,0,31,23); Check(Read(cpu)==initial);
        hardware.CopyAreaFrom(cpu,0,0,0,0,31,23); Check(Read(hardware)==initial);
    }
    AHardwareBuffer_release(ahb);
    std::cout << "PASS: CPU CopyArea reference comparison, 1000 random rectangles, overlap, clipping, COW snapshots, bounds and AHB fallback\n";
    if(argc>1) {
        for(int edge : {64,720}) {
            NativeDrawable src(1280,720,24), dst(1280,720,24);
            for(bool reference : {true,false}) {
                const int count=edge==64?20000:400;
                const auto start=std::chrono::steady_clock::now();
                for(int n=0;n<count;++n) {
                    if(reference) Reference(dst,src,0,0,0,0,edge==64?64:1280,edge);
                    else dst.CopyAreaFrom(src,0,0,0,0,edge==64?64:1280,edge);
                }
                const double ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count();
                std::cout << "CopyArea host edge="<<edge<<" path="<<(reference?"previous":"direct")<<" iterations="<<count<<" ms="<<ms<<"\n";
            }
        }
    }
}
