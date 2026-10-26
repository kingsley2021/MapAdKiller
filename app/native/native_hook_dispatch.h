#pragma once
#include <cstdint>
#include <mutex>

enum class NativeHost {Other,Baidu,Amap};
// Framework callback and explicit Java host configuration can arrive in either
// order. Setup is serialized; file/asset operations never enter this mutex.
class NativeHookDispatch {
public:
    using Installer=bool (*)(NativeHost,Hook,uint32_t);
    explicit NativeHookDispatch(Installer installer):installer_(installer){}
    void bind(Hook hook,uint32_t api){
        if(!hook)return;
        std::lock_guard<std::mutex> guard(mutex_);
        if(!hook_){hook_=hook;api_=api;}
        install();
    }
    bool request(NativeHost host){return request(host,[]{});}
    template<class Prepare> bool request(NativeHost host,Prepare prepare){
        std::lock_guard<std::mutex> guard(mutex_);
        if(host==NativeHost::Other || (host_!=NativeHost::Other && host_!=host))return false;
        host_=host;
        prepare();
        return install();
    }
    /** True once the framework has handed over its inline-hook primitive. */
    bool bound(){
        std::lock_guard<std::mutex> guard(mutex_);
        return hook_!=nullptr;
    }
private:
    bool install(){
        // The framework is the only source of the inline-hook primitive, so reporting success
        // without it is worse than failing: the Java side would believe the filter is armed
        // while every asset open still passes through untouched. That is exactly how a dead
        // 地图标注 switch used to look "installed" (configure returned accepted=1 with no hook
        // bound at all), so the unbound case now fails loudly.
        if(!hook_)return false;
        if(host_==NativeHost::Other)return true;
        if(!attempted_){attempted_=true;success_=installer_(host_,hook_,api_);}
        return success_;
    }
    Installer installer_;
    std::mutex mutex_;
    Hook hook_=nullptr;
    uint32_t api_=0;
    NativeHost host_=NativeHost::Other;
    bool attempted_=false,success_=false;
};
