#include "mnn_log_bridge.hpp"

#include <android/log.h>
#include <atomic>
#include <cstdarg>
#include <cstdio>
#include <cstring>

namespace {
std::atomic<MnnNativeLogSink> nativeLogSink{nullptr};
constexpr size_t kMaxMessageBytes = 4096;
}  // namespace

extern "C" void mnn_engine_set_native_log_sink(MnnNativeLogSink sink) {
    nativeLogSink.store(sink);
}

// libMNN's own log calls are routed here by --wrap=__android_log_print.
// The sink runs BEFORE Android's priority/property filters and is independent
// of the process-wide liblog logger callback. Normal Android output is kept.
extern "C" int __wrap___android_log_print(int priority, const char* tag, const char* format, ...) {
    va_list args;
    va_start(args, format);
    const auto sink = nativeLogSink.load();
    if (sink != nullptr) {
        char message[kMaxMessageBytes + 1];
        va_list copy;
        va_copy(copy, args);
        const int length = vsnprintf(message, sizeof(message), format, copy);
        va_end(copy);
        if (length >= 0) {
            if (static_cast<size_t>(length) > kMaxMessageBytes) {
                constexpr char suffix[] = " [message truncated]";
                std::memcpy(message + kMaxMessageBytes - (sizeof(suffix) - 1), suffix, sizeof(suffix));
            }
            sink(priority, tag, message);
        }
    }
    const int result = __android_log_vprint(priority, tag, format, args);
    va_end(args);
    return result;
}
