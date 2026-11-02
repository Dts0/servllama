#pragma once

#include <atomic>
#include <functional>
#include <memory>
#include <mutex>
#include <string>

#include "llm/llm.hpp"

class MnnLlmSessionAdapter {
public:
    struct Metrics {
        int promptTokens = 0;
        int completionTokens = 0;
        int64_t prefillUs = 0;
        int64_t decodeUs = 0;
        int64_t sampleUs = 0;
        std::string finishReason = "stop";
    };

    MnnLlmSessionAdapter(std::string configPath, std::string configJson);
    ~MnnLlmSessionAdapter();

    MnnLlmSessionAdapter(const MnnLlmSessionAdapter&) = delete;
    MnnLlmSessionAdapter& operator=(const MnnLlmSessionAdapter&) = delete;

    bool load(std::string* errorMessage);
    Metrics generate(
            const std::string& messagesJson,
            const std::string& requestConfigJson,
            int maxTokens,
            const std::function<bool(const std::string&)>& onToken);
    void cancel();
    // Prepare a new request. The owner serializes this with cancel(); generate()
    // deliberately preserves cancellations received before native entry.
    void reset();

private:
    void restoreRunningStatusIfTerminal();

    std::string configPath_;
    std::string configJson_;
    std::string backend_ = "cpu";
    MNN::Transformer::Llm* llm_ = nullptr;
    std::atomic<bool> cancelRequested_{false};
    std::mutex generationMutex_;
};
