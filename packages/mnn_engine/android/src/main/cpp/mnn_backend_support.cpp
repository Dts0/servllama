#include "mnn_backend_support.hpp"

#include <cstdlib>
#include <dlfcn.h>
#include <mutex>
#include <stdexcept>
#include <unistd.h>

#include "MNN/Interpreter.hpp"

#ifndef MNN_ENGINE_OPENCL
#define MNN_ENGINE_OPENCL 0
#endif
#ifndef MNN_ENGINE_VULKAN
#define MNN_ENGINE_VULKAN 0
#endif
#ifndef MNN_ENGINE_HEXAGON
#define MNN_ENGINE_HEXAGON 0
#endif

namespace {
std::mutex probeMutex;
std::string nativeDir;
std::string dspDir;

class DynamicLibrary {
public:
    explicit DynamicLibrary(const std::string& path) : handle_(dlopen(path.c_str(), RTLD_NOW | RTLD_LOCAL)) {}
    ~DynamicLibrary() { if (handle_) dlclose(handle_); }
    explicit operator bool() const { return handle_ != nullptr; }
    template <typename T> T symbol(const char* name) const {
        return reinterpret_cast<T>(dlsym(handle_, name));
    }
private:
    void* handle_;
};

std::string loaderError() {
    const char* error = dlerror();
    return error ? error : "Dynamic library could not be loaded";
}

MnnBackendCapability probeHexagon() {
    MnnBackendCapability result{"hexagon", MNN_ENGINE_HEXAGON != 0, false, "notBuilt", ""};
    if (!result.compiled) return result;
    const auto stubPath = nativeDir + "/libMNN_htpops.so";
    if (nativeDir.empty() || dspDir.empty() || access(stubPath.c_str(), R_OK) != 0 ||
        access((dspDir + "/libMNN_htpops_skel.so").c_str(), R_OK) != 0) {
        result.reason = "runtimeLibrariesMissing";
        result.detail = "This package does not contain the Hexagon stub and DSP runtime assets.";
        return result;
    }
    DynamicLibrary rpc("libcdsprpc.so");
    result.reason = "driverUnavailable";
    if (!rpc) {
        result.detail = loaderError();
        return result;
    }
    for (const char* name : {"rpcmem_init", "rpcmem_deinit", "rpcmem_alloc", "rpcmem_free",
                             "rpcmem_to_fd", "fastrpc_mmap", "fastrpc_munmap"}) {
        if (!rpc.symbol<void*>(name)) {
            result.detail = std::string("The device FastRPC library is missing ") + name;
            return result;
        }
    }
    DynamicLibrary stub(stubPath);
    if (!stub) {
        result.reason = "runtimeLibrariesMissing";
        result.detail = loaderError();
        return result;
    }
    const auto open = stub.symbol<int (*)(int, int)>("open_dsp_session");
    const auto init = stub.symbol<int (*)()>("init_htp_backend");
    const auto close = stub.symbol<void (*)()>("close_dsp_session");
    if (!open || !init || !close || !stub.symbol<void*>("htp_ops_rpc_getInfo") ||
        !stub.symbol<void*>("htp_rpc_execute_command_group")) {
        result.reason = "runtimeLibrariesMissing";
        result.detail = "The Hexagon stub is incompatible with this MNN build.";
        return result;
    }
    // Probe only while no model is resident. These exports own a process-wide
    // DSP session; probing while generating would close the live session.
    rpc.symbol<void (*)()>("rpcmem_init")();
    const int openStatus = open(3 /* CDSP_DOMAIN_ID */, 1 /* unsigned PD */);
    const int initStatus = openStatus == 0 ? init() : -1;
    close();
    rpc.symbol<void (*)()>("rpcmem_deinit")();
    if (openStatus != 0 || initStatus != 0) {
        result.reason = "deviceUnsupported";
        result.detail = "Could not initialize the Hexagon DSP session (open=" + std::to_string(openStatus) +
                        ", init=" + std::to_string(initStatus) +
                        "). Check FP16 HMX support, DSP architecture and OEM FastRPC access.";
        return result;
    }
    result.available = true;
    result.reason = "available";
    return result;
}

MnnBackendCapability probeUnlocked(const std::string& backend) {
    if (backend == "hexagon") return probeHexagon();
    MNNForwardType type = MNN_FORWARD_CPU;
    bool compiled = true;
    if (backend == "opencl") {
        type = MNN_FORWARD_OPENCL;
        compiled = MNN_ENGINE_OPENCL != 0;
    } else if (backend == "vulkan") {
        type = MNN_FORWARD_VULKAN;
        compiled = MNN_ENGINE_VULKAN != 0;
    } else if (backend != "cpu") {
        throw std::invalid_argument("Unsupported MNN backend: " + backend);
    }
    MnnBackendCapability result{backend, compiled, false, "notBuilt", ""};
    if (!compiled) return result;
    if (type == MNN_FORWARD_CPU) {
        result.available = true;
    } else {
        MNN::ScheduleConfig config;
        MNN::BackendConfig backendConfig;
        backendConfig.precision = MNN::BackendConfig::Precision_Low;
        backendConfig.memory = MNN::BackendConfig::Memory_Low;
        config.type = type;
        // Match Llm::initRuntime's OpenCL buffer / record-queue mode.
        config.numThread = type == MNN_FORWARD_OPENCL ? (4 | 64 | 512) : 4;
        config.backendConfig = &backendConfig;
        const auto runtimes = MNN::Interpreter::createRuntime({config});
        const auto found = runtimes.first.find(type);
        result.available = found != runtimes.first.end() && found->second != nullptr;
    }
    result.reason = result.available ? "available" : "driverUnavailable";
    if (!result.available) {
        result.detail = "MNN could not create the requested " + backend +
                        " runtime. Check the device driver and Android native-library access.";
    }
    return result;
}
} // namespace

MnnHexagonDeviceInfo detectMnnHexagonArchitecture(const std::string& nativeLibraryDir) {
    std::lock_guard<std::mutex> lock(probeMutex);
    MnnHexagonDeviceInfo result{"", "notBuilt", ""};
    if (!MNN_ENGINE_HEXAGON) return result;
    result.reason = "runtimeLibrariesMissing";
    const auto stubPath = nativeLibraryDir + "/libMNN_htpops.so";
    if (nativeLibraryDir.empty() || access(stubPath.c_str(), R_OK) != 0) {
        result.detail = "This package does not contain the Hexagon Android stub.";
        return result;
    }
    DynamicLibrary rpc("libcdsprpc.so");
    if (!rpc) {
        result.reason = "driverUnavailable";
        result.detail = loaderError();
        return result;
    }
    if (!rpc.symbol<void*>("remote_handle_control")) {
        result.reason = "driverUnavailable";
        result.detail = "The device FastRPC driver cannot query the DSP architecture.";
        return result;
    }
    DynamicLibrary stub(stubPath);
    if (!stub) {
        result.detail = loaderError();
        return result;
    }
    const auto query = stub.symbol<int (*)(int*)>("mnn_engine_query_hexagon_arch");
    if (!query) {
        result.detail = "The Hexagon stub is missing the architecture query. Rebuild the DSP runtime bundle.";
        return result;
    }
    int architecture = 0;
    const int status = query(&architecture);
    if (status != 0 || architecture <= 0) {
        result.reason = "deviceUnsupported";
        result.detail = "FastRPC could not identify the cDSP architecture (status=" + std::to_string(status) + ").";
        return result;
    }
    result.architecture = "v" + std::to_string(architecture);
    result.reason = "available";
    return result;
}

void configureMnnBackendPaths(const std::string& nativeLibraryDir, const std::string& dspLibraryDir) {
    std::lock_guard<std::mutex> lock(probeMutex);
    nativeDir = nativeLibraryDir;
    dspDir = dspLibraryDir;
    if (!dspDir.empty()) {
        const std::string paths = dspDir + ";/vendor/lib/rfsa/adsp;/vendor/dsp;/system/lib/rfsa/adsp;/dsp";
        if (setenv("ADSP_LIBRARY_PATH", paths.c_str(), 1) != 0) {
            throw std::runtime_error("Could not configure the Hexagon DSP library path");
        }
    }
}

MnnBackendCapability probeMnnBackend(const std::string& backend) {
    std::lock_guard<std::mutex> lock(probeMutex);
    return probeUnlocked(backend);
}

std::vector<MnnBackendCapability> probeMnnBackends() {
    std::lock_guard<std::mutex> lock(probeMutex);
    std::vector<MnnBackendCapability> result;
    for (const char* backend : {"cpu", "opencl", "vulkan", "hexagon"}) {
        result.push_back(probeUnlocked(backend));
    }
    return result;
}
