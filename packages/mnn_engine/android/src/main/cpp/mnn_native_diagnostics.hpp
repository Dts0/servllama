#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

struct MnnNativeLogRecord {
    int64_t timestampMillis;
    int threadId;
    int priority;
    std::string tag;
    std::string message;
};

struct MnnNativeLogSnapshot {
    size_t dropped = 0;
    std::vector<MnnNativeLogRecord> records;
};

// Capture MNN before Android log filtering without replacing Android's logger.
void initializeMnnNativeDiagnostics();
void clearMnnNativeDiagnostics();
void recordMnnNativeDiagnostic(int priority, const char* tag, const char* message) noexcept;
MnnNativeLogSnapshot takeMnnNativeDiagnostics(int64_t sinceMillis);
