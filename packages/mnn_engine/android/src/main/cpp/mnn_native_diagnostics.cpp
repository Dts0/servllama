#include "mnn_native_diagnostics.hpp"
#include "mnn_log_bridge.hpp"

#include <algorithm>
#include <chrono>
#include <cstring>
#include <deque>
#include <mutex>
#include <sys/syscall.h>
#include <unistd.h>

namespace {
constexpr size_t kMaxRecords = 128;
// Keep initialization/session failures visible when a model emits one warning
// per layer. The remaining space retains the latest operation/failure context.
constexpr size_t kHeadRecords = 8;
constexpr size_t kMaxMessageBytes = 4096;
constexpr size_t kMaxBufferBytes = 64 * 1024;
struct LogBuffer {
    std::mutex mutex;
    std::deque<MnnNativeLogRecord> records;
    size_t bytes = 0;
    size_t dropped = 0;
};
LogBuffer& buffer() {
    // MNN can log while other libraries run their exit destructors.
    // Keep the bounded storage alive for the lifetime of the process.
    static auto* instance = new LogBuffer;
    return *instance;
}
std::once_flag initializeOnce;

bool supportedTag(const char* tag) {
    return tag != nullptr &&
            std::strcmp(tag, "MNNJNI") == 0;
}
}  // namespace

void initializeMnnNativeDiagnostics() {
    std::call_once(initializeOnce, [] {
        (void)buffer();
#if defined(__ANDROID__) || defined(MNN_ENGINE_LOG_BRIDGE_TEST)
        mnn_engine_set_native_log_sink(recordMnnNativeDiagnostic);
#endif
    });
}

void recordMnnNativeDiagnostic(int priority, const char* tag, const char* message) noexcept {
    if (!supportedTag(tag) || message == nullptr) return;
    try {
        const size_t length = strnlen(message, kMaxMessageBytes + 1);
        std::string text(message, std::min(length, kMaxMessageBytes));
        if (length > kMaxMessageBytes) {
            constexpr char suffix[] = " [message truncated]";
            text.resize(kMaxMessageBytes - (sizeof(suffix) - 1));
            text += suffix;
        }
        const auto timestamp = std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::system_clock::now().time_since_epoch()).count();
        const int threadId = static_cast<int>(syscall(SYS_gettid));
        auto& log = buffer();
        std::lock_guard<std::mutex> lock(log.mutex);
        while (!log.records.empty() && (log.records.size() >= kMaxRecords || log.bytes + text.size() > kMaxBufferBytes)) {
            const auto oldestTail = log.records.begin() + std::min(kHeadRecords, log.records.size() - 1);
            log.bytes -= oldestTail->message.size();
            log.records.erase(oldestTail);
            ++log.dropped;
        }
        log.records.push_back({timestamp, threadId, priority, tag, std::move(text)});
        log.bytes += log.records.back().message.size();
    } catch (...) {
        // Diagnostic allocation failure must never replace an inference error
        // or throw across the native log callback.
    }
}

MnnNativeLogSnapshot takeMnnNativeDiagnostics(int64_t sinceMillis) {
    MnnNativeLogSnapshot snapshot;
    auto& log = buffer();
    std::lock_guard<std::mutex> lock(log.mutex);
    snapshot.dropped = log.dropped;
    for (auto& record : log.records) {
        if (record.timestampMillis >= sinceMillis) snapshot.records.push_back(std::move(record));
    }
    log.records.clear();
    log.bytes = 0;
    log.dropped = 0;
    return snapshot;
}

void clearMnnNativeDiagnostics() {
    auto& log = buffer();
    std::lock_guard<std::mutex> lock(log.mutex);
    log.records.clear();
    log.bytes = 0;
    log.dropped = 0;
}
