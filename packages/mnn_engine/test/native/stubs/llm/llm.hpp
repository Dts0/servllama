#pragma once

// A controllable LLM boundary for the adapter's error/streaming regression
// tests. Actual MNN headers and ABI are verified by the Android native build.
#include <cstdint>
#include <functional>
#include <ostream>
#include <stdexcept>
#include <string>
#include <utility>
#include <vector>
#include "nlohmann/json.hpp"

namespace MNN::Transformer { struct LlmContext; }
namespace mnn_engine { bool cancelPrefill(MNN::Transformer::LlmContext*); }

namespace MNN::Transformer {
using ChatMessages = std::vector<std::pair<std::string, std::string>>;
enum class LlmStatus {
    NOT_LOADED = -1, RUNNING = 0, NORMAL_FINISHED = 1, MAX_TOKENS_FINISHED = 2,
    USER_CANCEL = 3, INTERNAL_ERROR = 4, TIMEOUT = 5,
};
struct LlmContext {
    LlmStatus status = LlmStatus::NOT_LOADED;
    int prompt_len = 0;
    int gen_seq_len = 0;
    int all_seq_len = 0;
    int64_t prefill_us = 0;
    int64_t decode_us = 0;
    int64_t sample_us = 0;
    std::vector<int> output_tokens;
};
struct FakeLlmBehavior {
    LlmStatus loadStatus = LlmStatus::RUNNING;
    LlmStatus prefillStatus = LlmStatus::RUNNING;
    LlmStatus decodeStatus = LlmStatus::MAX_TOKENS_FINISHED;
    bool advanceToken = true;
    bool emitEndMarker = false;
    int prefillCalls = 0;
    int decodeCalls = 0;
    int loadCalls = 0;
    int prefillBatches = 1;
    int processedBatches = 0;
    std::function<void(int)> onPrefillBatch;
    std::string modelConfig = "{}";
    std::string effectiveConfig = "{}";
};
inline FakeLlmBehavior fakeLlm;

class Llm {
public:
    static Llm* createLLM(const std::string&) { return new Llm; }
    static void destroy(Llm* llm) { delete llm; }
    bool set_config(const std::string& config) {
        config_.update(nlohmann::json::parse(config));
        fakeLlm.effectiveConfig = config_.dump();
        return true;
    }
    std::string dump_config() { return config_.dump(); }
    bool load() {
        ++fakeLlm.loadCalls;
        context_.status = fakeLlm.loadStatus;
        return true;
    }
    const LlmContext* getContext() const { return &context_; }
    void reset() {
        // As in upstream reset(), terminal/error status is not cleared here.
        context_.gen_seq_len = 0;
        context_.all_seq_len = 0;
        context_.output_tokens.clear();
    }
    void response(const ChatMessages&, std::ostream* output, const char* end, int maxTokens) {
        if (maxTokens != 0) throw std::logic_error("Expected prefill-only response");
        ++fakeLlm.prefillCalls;
        output_ = output;
        endMarker_ = end;
        context_.prompt_len = 7 * fakeLlm.prefillBatches;
        context_.prefill_us = 0;
        context_.status = LlmStatus::RUNNING;
        for (int i = 0; i < fakeLlm.prefillBatches; ++i) {
            if (cancelPrefill()) return;
            ++fakeLlm.processedBatches;
            context_.all_seq_len += 7;
            context_.prefill_us += 10;
            if (fakeLlm.onPrefillBatch) fakeLlm.onPrefillBatch(i);
            context_.status = fakeLlm.prefillStatus;
            if (cancelPrefill()) return;
        }
    }
    void generate(int maxTokens) {
        if (maxTokens != 1) throw std::logic_error("Expected one-token step");
        ++fakeLlm.decodeCalls;
        if (fakeLlm.advanceToken) {
            ++context_.gen_seq_len;
            context_.output_tokens.push_back(42);
            if (fakeLlm.decodeStatus != LlmStatus::NORMAL_FINISHED) {
                *output_ << "generated text " << std::flush;
            }
        }
        context_.status = fakeLlm.decodeStatus;
        if (fakeLlm.emitEndMarker) *output_ << endMarker_ << std::flush;
    }
private:
    bool cancelPrefill() { return mnn_engine::cancelPrefill(&context_); }
    nlohmann::json config_ = nlohmann::json::parse(fakeLlm.modelConfig);
    LlmContext context_;
    std::ostream* output_ = nullptr;
    std::string endMarker_;
};
}  // namespace MNN::Transformer
