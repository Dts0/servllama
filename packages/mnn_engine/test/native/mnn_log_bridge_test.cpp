#include "mnn_log_bridge.hpp"
#include "mnn_native_diagnostics.hpp"
#include "MNN/MNNDefine.h"
#include <android/log.h>

#include <atomic>
#include <cerrno>
#include <cstdio>
#include <iostream>
#include <stdexcept>
#include <thread>

namespace {
std::atomic<int> androidCalls{0};
bool androidAllowsLogs = false;
std::string androidMessage;

void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}
}  // namespace

// Model Android filtering all native logs, including INFO and ERROR, BEFORE
// any liblog logger callback. No __android_log_set_logger symbol is supplied.
extern "C" int __android_log_vprint(int, const char*, const char* format, va_list args) {
    ++androidCalls;
    if (!androidAllowsLogs) return -EPERM;
    char message[8192];
    const int length = vsnprintf(message, sizeof(message), format, args);
    androidMessage = message;
    return length;
}

int main() {
    int cases = 0;
    try {
        MNN_ERROR("before sink registration\n");
        require(androidCalls.load() == 1 && takeMnnNativeDiagnostics(0).records.empty(),
                "Logs must keep their original Android path before the sink is registered");
        ++cases;

        initializeMnnNativeDiagnostics();
        initializeMnnNativeDiagnostics();
        auto snapshot = takeMnnNativeDiagnostics(0);
        require(snapshot.records.empty() && androidCalls.load() == 1,
                "Registering the sink must not emit synthetic diagnostic messages");
        ++cases;

        MNN_ERROR("Resize error for type = %s, name = %s, code = %d\n",
                "LinearAttention", "/layers.0/self_attn/FusedLinearAttention", 3);
        snapshot = takeMnnNativeDiagnostics(0);
        require(snapshot.records.size() == 1 && snapshot.records.front().priority == ANDROID_LOG_ERROR,
                "The actual MNN_ERROR macro must reach the sink before Android filters it");
        require(snapshot.records.front().message ==
                "Resize error for type = LinearAttention, name = /layers.0/self_attn/FusedLinearAttention, code = 3\n",
                "Preserve the formatted operator name and native error code");
        ++cases;

        MNN_PRINT("[MNN::Hexagon] vectorSize=%d, vtcmSize=%d, maxThreads=%d\n", 32, 8388608, 6);
        snapshot = takeMnnNativeDiagnostics(0);
        require(snapshot.records.size() == 1 && snapshot.records.front().priority == ANDROID_LOG_INFO,
                "MNN initialization INFO logs must also be captured when Android suppresses them");
        require(androidCalls.load() == 3, "Suppressed messages must still be forwarded to the Android API");
        ++cases;

        androidAllowsLogs = true;
        const int result = __android_log_print(ANDROID_LOG_WARN, "MNNJNI", "code=%d %s", 42, "warning");
        snapshot = takeMnnNativeDiagnostics(0);
        require(result == 15 && androidMessage == "code=42 warning" && snapshot.records.size() == 1 &&
                snapshot.records.front().message == androidMessage,
                "Preserve Android's original format arguments and return value without consuming va_list");
        ++cases;

        const std::string oversized(6000, 'x');
        MNN_ERROR("%s", oversized.c_str());
        snapshot = takeMnnNativeDiagnostics(0);
        require(snapshot.records.size() == 1 && snapshot.records.front().message.size() == 4096 &&
                snapshot.records.front().message.find("[message truncated]") != std::string::npos,
                "Bound bridge formatting and explicitly mark truncation");
        require(androidMessage.size() == 6000, "The bridge's size limit must not alter Android's own log arguments");
        ++cases;

        androidAllowsLogs = false;
        std::vector<std::thread> writers;
        for (int worker = 0; worker < 4; ++worker) {
            writers.emplace_back([worker] {
                for (int i = 0; i < 16; ++i) MNN_ERROR("worker=%d item=%d", worker, i);
            });
        }
        for (auto& writer : writers) writer.join();
        snapshot = takeMnnNativeDiagnostics(0);
        require(snapshot.records.size() == 64 && snapshot.dropped == 0,
                "Capture concurrent MNN errors independently of system logging");
        ++cases;

        std::cout << cases << " MNN log bridge cases passed with Android logging suppressed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAILED: " << error.what() << '\n';
        return 1;
    }
}
