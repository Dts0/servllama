#include "mnn_native_diagnostics.hpp"

#include <chrono>
#include <iostream>
#include <limits>
#include <set>
#include <stdexcept>
#include <thread>

namespace {
void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}
int64_t nowMillis() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::system_clock::now().time_since_epoch()).count();
}
}

int main() {
    int cases = 0;
    try {
        const auto startedAt = nowMillis();
        recordMnnNativeDiagnostic(6, "MNNJNI", "Resize error: code=3\n");
        recordMnnNativeDiagnostic(6, "request", "unrelated message");
        recordMnnNativeDiagnostic(6, nullptr, "missing tag");
        recordMnnNativeDiagnostic(6, "MNNJNI", nullptr);
        auto snapshot = takeMnnNativeDiagnostics(startedAt);
        require(snapshot.records.size() == 1, "Retain only MNN records");
        const auto& record = snapshot.records.front();
        require(record.priority == 6 && record.threadId > 0 && record.timestampMillis >= startedAt,
                "Preserve error severity, time and source thread");
        require(record.message == "Resize error: code=3\n", "Preserve error codes and line breaks");
        require(takeMnnNativeDiagnostics(0).records.empty(), "Reading logs must drain the buffer");
        ++cases;

        recordMnnNativeDiagnostic(4, "MNNJNI", "old operation");
        require(takeMnnNativeDiagnostics(std::numeric_limits<int64_t>::max()).records.empty(),
                "Exclude records outside the operation's time range");
        require(takeMnnNativeDiagnostics(0).records.empty(), "Expired records must not leak into a later operation");
        ++cases;

        for (int i = 0; i < 140; ++i) {
            recordMnnNativeDiagnostic(6, "MNNJNI", ("failure " + std::to_string(i)).c_str());
        }
        snapshot = takeMnnNativeDiagnostics(0);
        require(snapshot.records.size() == 128 && snapshot.dropped == 12, "Bound the retained record count");
        require(snapshot.records.front().message == "failure 0" && snapshot.records[7].message == "failure 7" &&
                    snapshot.records[8].message == "failure 20" && snapshot.records.back().message == "failure 139",
                "Keep the initial cause and the latest failure context");
        require(takeMnnNativeDiagnostics(0).dropped == 0, "Draining must reset the dropped count");
        ++cases;

        const std::string oversized(6000, 'x');
        recordMnnNativeDiagnostic(6, "MNNJNI", oversized.c_str());
        snapshot = takeMnnNativeDiagnostics(0);
        require(snapshot.records.size() == 1 && snapshot.records.front().message.size() == 4096,
                "Bound a single native message");
        require(snapshot.records.front().message.find("[message truncated]") != std::string::npos,
                "Mark truncated messages explicitly");
        ++cases;

        const std::string large(4096, 'y');
        for (int i = 0; i < 24; ++i) recordMnnNativeDiagnostic(6, "MNNJNI", large.c_str());
        snapshot = takeMnnNativeDiagnostics(0);
        require(snapshot.records.size() == 16 && snapshot.dropped == 8, "Bound message storage to 64 KiB");
        ++cases;

        std::vector<std::thread> producers;
        for (int worker = 0; worker < 4; ++worker) {
            producers.emplace_back([worker] {
                for (int i = 0; i < 16; ++i) {
                    const auto text = std::to_string(worker) + ":" + std::to_string(i);
                    recordMnnNativeDiagnostic(6, "MNNJNI", text.c_str());
                }
            });
        }
        for (auto& producer : producers) producer.join();
        snapshot = takeMnnNativeDiagnostics(0);
        std::set<std::string> messages;
        for (const auto& item : snapshot.records) messages.insert(item.message);
        require(messages.size() == 64 && snapshot.dropped == 0, "Keep concurrent records intact");
        ++cases;

        for (int i = 0; i < 140; ++i) recordMnnNativeDiagnostic(6, "MNNJNI", "previous operation");
        clearMnnNativeDiagnostics();
        recordMnnNativeDiagnostic(6, "MNNJNI", "new operation");
        snapshot = takeMnnNativeDiagnostics(0);
        require(snapshot.records.size() == 1 && snapshot.dropped == 0 &&
                snapshot.records.front().message == "new operation", "Reset context at each operation boundary");
        ++cases;

        std::cout << cases << " native log buffer cases passed\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAILED: " << error.what() << '\n';
        return 1;
    }
}
