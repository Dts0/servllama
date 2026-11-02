#pragma once

#include <string>
#include <vector>

struct MnnBackendCapability {
    std::string backend;
    bool compiled = false;
    bool available = false;
    std::string reason;
    std::string detail;
};

struct MnnHexagonDeviceInfo {
    std::string architecture;
    std::string reason;
    std::string detail;
};

// Call before the first MNN runtime is created. DSP resources are extracted by
// the Android layer; the Hexagon ELF must not be loaded by Android's linker.
void configureMnnBackendPaths(const std::string& nativeLibraryDir, const std::string& dspLibraryDir);
MnnHexagonDeviceInfo detectMnnHexagonArchitecture(const std::string& nativeLibraryDir);
MnnBackendCapability probeMnnBackend(const std::string& backend);
std::vector<MnnBackendCapability> probeMnnBackends();
