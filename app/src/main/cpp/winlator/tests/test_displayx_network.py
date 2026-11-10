#!/usr/bin/env python3
"""Run Java-X11's production DisplayX network loop with JNI/AHB boundary models."""
import pathlib
import subprocess
import tempfile
root = pathlib.Path(__file__).resolve().parents[1]
xpp = root.parent / 'ludashix11'
source = (root / 'renderer/displayx.cpp').read_text()
network = source[source.index('void DisplayX::networkThreadLoop()'):source.index('void DisplayX::windowThreadLoop()')]
complete = source[source.index('void DisplayX::onCompleteCallback('):source.index('void DisplayX::cursorThreadLoop()')]
harness = r'''
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/epoll.h>
#include <sys/eventfd.h>
#include <unistd.h>
#include <cassert>
#include <array>
#include <atomic>
#include <cmath>
#include <condition_variable>
#include <map>
#include <memory>
#include <mutex>
#include <thread>
#include <chrono>
#include <android/hardware_buffer.h>
#include "displayx_present_queue.hpp"
#include "displayx_protocol.hpp"
struct Drawable { int id{},width{},height{},stride{},format{},sync_fence{}; bool isDisplayX{},isDirectContent{};void* drawableObj{};AHardwareBuffer* ahb{}; };
struct Window { int id{},width{32},height{32}; uint64_t generation{1}; Window* parent{},*surface{}; Drawable* externalContent{};void* windowObj{}; };
struct WindowManager { std::map<int,Window*> windows;Window* getWindow(int id){auto i=windows.find(id);return i==windows.end()?nullptr:i->second;} };
struct JNIEnv { template<class...T> void CallVoidMethod(T...){} };
struct Cache { JNIEnv env; int updateFrameRating{};JNIEnv* getEnv(){return &env;}void detachEnv(JNIEnv*){} };
struct Server { std::atomic<bool> isShowFPS{false};void* xserverDisplayActivity{}; };
struct ASurfaceTransactionStats {};
struct DisplayX {
 using PresentRequest=DisplayXPresentRequest;
 struct DisplayXSwapchain {
  uint8_t id{}; Window* window{};uint32_t windowId{},imageCount{},presentMode{},format{},colorSpace{},width{},height{};uint64_t windowGeneration{};int fd{};
  std::array<float,12> hdr{};bool hasHdr{};
  std::vector<std::unique_ptr<Drawable>> images;
  ~DisplayXSwapchain(){for(auto& image:images)if(image->ahb)AHardwareBuffer_release(image->ahb);}
 };
 struct OnCompleteContext {std::vector<std::unique_ptr<PresentRequest>> requests;};
 struct Lock {std::mutex mutex;auto lock(){return std::unique_lock<std::mutex>(mutex);}void notify(){}} windowLock;
 int serverFd{-1},epollFd{-1},stopFd{eventfd(0,EFD_CLOEXEC)};
 std::atomic_bool stopped{false},presentRR{false};
 WindowManager* windowManager{};Cache cacheValue,*cache{&cacheValue};Server server,*xServer{&server};
 DisplayXPresentQueue presentRequests;
 std::unordered_map<uint64_t,std::unique_ptr<DisplayXSwapchain>> clientSwapchains;
 void networkThreadLoop();static void onCompleteCallback(void*,ASurfaceTransactionStats*);
};
constexpr int VK_PRESENT_MODE_FIFO_KHR=2;
static int connectPeer(){
 sockaddr_un a{};a.sun_family=AF_UNIX;memcpy(a.sun_path+1,"displayx",8);
 for(int n=0;n<1000;n++){
  int fd=socket(AF_UNIX,SOCK_SEQPACKET,0);
  if(connect(fd,reinterpret_cast<sockaddr*>(&a),offsetof(sockaddr_un,sun_path)+9)==0){timeval t{2,0};setsockopt(fd,SOL_SOCKET,SO_RCVTIMEO,&t,sizeof(t));return fd;}
  close(fd);std::this_thread::sleep_for(std::chrono::milliseconds(1));
 }
 assert(false);return -1;
}
static void create(int fd,uint32_t window){
 using namespace displayx_wire;std::vector<uint8_t> p;append(p,uint32_t(Create));append(p,uint8_t(7));append(p,uint32_t(1));append(p,window);append(p,uint32_t(2));append(p,uint32_t(37));append(p,uint32_t(1000104008));assert(sendPacket(fd,p));
 AHardwareBuffer_Desc d{32,32,1,1,0,32};assert(send(fd,&d,sizeof(d),0)==sizeof(d));
}
static void present(int fd,uint64_t serial){
 using namespace displayx_wire;std::vector<uint8_t> p;append(p,uint32_t(Present));append(p,uint8_t(7));append(p,uint32_t(0));append(p,serial);assert(sendPacket(fd,p));
}
static uint64_t completion(int fd){
 uint8_t p[100];assert(recv(fd,p,sizeof(p),0)==25);uint32_t code,count;uint64_t serial;
 memcpy(&code,p,4);memcpy(&count,p+4,4);memcpy(&serial,p+9,8);assert(code==4&&count==1&&p[8]==7);return serial;
}
'''
main = r'''
int main(){
 Window root,a,b;a.id=1;b.id=2;a.parent=b.parent=&root;WindowManager wm;wm.windows={{1,&a},{2,&b}};
 DisplayX renderer;renderer.windowManager=&wm;
 std::thread worker([&]{renderer.networkThreadLoop();});
 int first=connectPeer(),second=connectPeer();create(first,1);create(second,2);
 using namespace displayx_wire;std::vector<uint8_t> hdr;append(hdr,uint32_t(Hdr));append(hdr,uint8_t(7));
 for(int i=0;i<12;i++)append(hdr,float(i+1));assert(sendPacket(first,hdr));
 present(first,101);present(second,202);
 std::deque<std::unique_ptr<DisplayXPresentRequest>> requests;
 for(int n=0;n<1000;n++){
  {auto lock=renderer.windowLock.lock();auto batch=renderer.presentRequests.buildWindowTree();for(auto& r:batch)requests.push_back(std::move(r));}
  if(requests.size()==2)break;std::this_thread::sleep_for(std::chrono::milliseconds(1));
 }
 assert(requests.size()==2);
 auto* callback=new DisplayX::OnCompleteContext;
 for(auto& request:requests){assert(request->colorSpace==1000104008);if(request->window==&a)assert(request->hasHdr&&request->hdr[11]==12);callback->requests.push_back(std::move(request));}
 DisplayX::onCompleteCallback(callback,nullptr);
 assert(completion(first)==101&&completion(second)==202); // Same swapchain ID, distinct peers.
 close(first);close(second);renderer.stopped=true;uint64_t stop=1;write(renderer.stopFd,&stop,sizeof(stop));worker.join();close(renderer.stopFd);
 puts("PASS: Java DisplayX SEQPACKET/AHB import, HDR metadata, per-client completion and shutdown");
}
'''
with tempfile.TemporaryDirectory(prefix='displayx-network-') as d:
    folder = pathlib.Path(d)
    (folder/'test.cpp').write_text(harness+network+complete+main)
    subprocess.run(['g++','-std=c++17','-O1','-I'+str(root/'renderer'),'-I'+str(xpp/'tests/stubs'),str(folder/'test.cpp'),'-pthread','-o',str(folder/'test')],check=True)
    subprocess.run([str(folder/'test')],check=True)
