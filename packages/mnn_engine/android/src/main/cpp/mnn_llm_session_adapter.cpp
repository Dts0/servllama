#include "mnn_llm_session_adapter.hpp"
#include "mnn_backend_support.hpp"
#include "mnn_native_diagnostics.hpp"
#include "mnn_hexagon_model_check.hpp"
#include "mnn_prefill_control.hpp"

#include <algorithm>
#include <sstream>
#include <stdexcept>
#include <streambuf>
#include <utility>

#include "nlohmann/json.hpp"

using MNN::Transformer::ChatMessages;
using MNN::Transformer::LlmStatus;
using nlohmann::json;

namespace {

constexpr int kNoTokenLimit = -1;

class PrefillCancellationScope {
public:
    explicit PrefillCancellationScope(std::atomic<bool>& cancelled) {
        mnn_engine_set_prefill_cancel_callback([](void* flag) {
            return static_cast<std::atomic<bool>*>(flag)->load();
        }, &cancelled);
    }
    ~PrefillCancellationScope() { mnn_engine_set_prefill_cancel_callback(nullptr, nullptr); }
};

const char* statusName(LlmStatus status) {
    switch (status) {
        case LlmStatus::NOT_LOADED: return "NOT_LOADED";
        case LlmStatus::RUNNING: return "RUNNING";
        case LlmStatus::NORMAL_FINISHED: return "NORMAL_FINISHED";
        case LlmStatus::MAX_TOKENS_FINISHED: return "MAX_TOKENS_FINISHED";
        case LlmStatus::USER_CANCEL: return "USER_CANCEL";
        case LlmStatus::INTERNAL_ERROR: return "INTERNAL_ERROR";
        case LlmStatus::TIMEOUT: return "TIMEOUT";
    }
    return "UNKNOWN";
}

bool hasRuntimeError(const MNN::Transformer::LlmContext* context) {
    return context == nullptr || context->status == LlmStatus::NOT_LOADED ||
            context->status == LlmStatus::INTERNAL_ERROR || context->status == LlmStatus::TIMEOUT;
}

std::string contextSummary(const MNN::Transformer::LlmContext* context) {
    if (context == nullptr) return "context=unavailable";
    std::ostringstream message;
    // Counts only: never log prompts, token IDs, or generated text.
    message << "status=" << statusName(context->status) << '(' << static_cast<int>(context->status) << ')'
            << ", prompt_tokens=" << context->prompt_len << ", generated_tokens=" << context->gen_seq_len;
    return message.str();
}

[[noreturn]] void failGeneration(const std::string& reason, const std::string& details) {
    // The Kotlin boundary logs this error once, together with MNN's own logs.
    throw std::runtime_error(reason + "; " + details);
}

class Utf8StreamProcessor {
public:
    explicit Utf8StreamProcessor(std::function<void(const std::string&)> callback)
        : callback_(std::move(callback)) {}

    void process(const char* data, size_t size) {
        buffer_.append(data, size);
        size_t offset = 0;
        std::string complete;
        while (offset < buffer_.size()) {
            const int length = charLength(static_cast<unsigned char>(buffer_[offset]));
            if (length == 0 || offset + static_cast<size_t>(length) > buffer_.size()) {
                break;
            }
            complete.append(buffer_, offset, static_cast<size_t>(length));
            offset += static_cast<size_t>(length);
        }
        buffer_.erase(0, offset);
        if (!complete.empty()) callback_(complete);
    }

    void flush() {
        if (!buffer_.empty()) {
            callback_(buffer_);
            buffer_.clear();
        }
    }

private:
    static int charLength(unsigned char value) {
        if ((value & 0x80U) == 0) return 1;
        if ((value & 0xE0U) == 0xC0U) return 2;
        if ((value & 0xF0U) == 0xE0U) return 3;
        if ((value & 0xF8U) == 0xF0U) return 4;
        return 0;
    }

    std::string buffer_;
    std::function<void(const std::string&)> callback_;
};

class SteppingStreamBuffer : public std::streambuf {
public:
    explicit SteppingStreamBuffer(const std::function<bool(const std::string&)>& callback)
        : callback_(callback), utf8_([this](const std::string& text) {
              if (!text.empty() && callback_) {
                  stopRequested_ = stopRequested_ || callback_(text);
              }
          }) {}

    bool stopRequested() const { return stopRequested_; }
    bool pendingEop() const { return pendingEop_; }
    bool finished() const { return finished_; }

    void discardPendingEop() { pendingEop_ = false; }

    void finalizePendingEop() {
        if (pendingEop_) {
            pendingEop_ = false;
            finished_ = true;
        }
        if (!pending_.empty()) {
            utf8_.process(pending_.data(), pending_.size());
            pending_.clear();
        }
        utf8_.flush();
    }

protected:
    std::streamsize xsputn(const char* data, std::streamsize size) override {
        pending_.append(data, static_cast<size_t>(size));
        processPending();
        return size;
    }

private:
    void processPending() {
        static const std::string sentinel = "<eop>";
        while (!pending_.empty()) {
            const size_t eop = pending_.find(sentinel);
            if (eop != std::string::npos) {
                if (eop > 0) utf8_.process(pending_.data(), eop);
                pending_.erase(0, eop + sentinel.size());
                pendingEop_ = true;
                continue;
            }
            if (pending_.size() < sentinel.size()) return;
            const size_t safeSize = pending_.size() - (sentinel.size() - 1);
            utf8_.process(pending_.data(), safeSize);
            pending_.erase(0, safeSize);
        }
    }

    const std::function<bool(const std::string&)>& callback_;
    Utf8StreamProcessor utf8_;
    std::string pending_;
    bool stopRequested_ = false;
    bool pendingEop_ = false;
    bool finished_ = false;
};

ChatMessages parseMessages(const std::string& messagesJson) {
    const json root = json::parse(messagesJson);
    if (!root.is_array() || root.empty()) {
        throw std::invalid_argument("messages must be a non-empty array");
    }
    ChatMessages messages;
    messages.reserve(root.size());
    for (const auto& item : root) {
        const std::string role = item.value("role", "");
        if (role.empty()) throw std::invalid_argument("message role is required");
        const bool hasComplexFields = item.contains("tool_calls") ||
                item.contains("reasoning_content") || item.contains("tool_call_id") || item.contains("name");
        if (hasComplexFields) {
            auto complex = item;
            if (!complex.contains("content") || complex["content"].is_null()) {
                complex["content"] = "";
            }
            if (complex.contains("tool_calls")) {
                if (!complex["tool_calls"].is_array()) {
                    throw std::invalid_argument("assistant tool_calls must be an array");
                }
                for (auto& tool_call : complex["tool_calls"]) {
                    if (!tool_call.is_object() || !tool_call.contains("function") ||
                        !tool_call["function"].is_object()) {
                        throw std::invalid_argument("assistant tool_calls contains an invalid call");
                    }
                    auto& function = tool_call["function"];
                    if (!function.contains("arguments")) {
                        throw std::invalid_argument("tool_call arguments are required");
                    }
                    if (function["arguments"].is_string()) {
                        try {
                            auto arguments = json::parse(function["arguments"].get<std::string>());
                            if (!arguments.is_object()) {
                                throw std::invalid_argument("tool_call arguments must contain a JSON object");
                            }
                            function["arguments"] = std::move(arguments);
                        } catch (const std::exception&) {
                            throw std::invalid_argument("tool_call arguments must contain a JSON object");
                        }
                    } else if (!function["arguments"].is_object()) {
                        throw std::invalid_argument("tool_call arguments must contain a JSON object");
                    }
                }
            }
            messages.emplace_back("json", complex.dump());
            continue;
        }
        const std::string content = item.contains("content") && !item["content"].is_null()
                ? item["content"].get<std::string>()
                : "";
        messages.emplace_back(role, content);
    }
    return messages;
}

}  // namespace

MnnLlmSessionAdapter::MnnLlmSessionAdapter(std::string configPath, std::string configJson)
    : configPath_(std::move(configPath)), configJson_(std::move(configJson)) {}

MnnLlmSessionAdapter::~MnnLlmSessionAdapter() {
    cancelRequested_.store(true);
    std::lock_guard<std::mutex> lock(generationMutex_);
    if (llm_ != nullptr) {
        MNN::Transformer::Llm::destroy(llm_);
        llm_ = nullptr;
    }
}

bool MnnLlmSessionAdapter::load(std::string* errorMessage) {
    clearMnnNativeDiagnostics();
    auto config = configJson_.empty() ? json::object() : json::parse(configJson_);
    backend_ = config.value("backend_type", "cpu");
    const auto capability = probeMnnBackend(backend_);
    if (!capability.available) {
        if (errorMessage) *errorMessage = "backend_unavailable: " + capability.backend + ": " + capability.detail;
        return false;
    }
    llm_ = MNN::Transformer::Llm::createLLM(configPath_);
    if (llm_ == nullptr) {
        if (errorMessage != nullptr) *errorMessage = "createLLM failed for " + configPath_;
        return false;
    }
    auto effectiveConfig = json::parse(llm_->dump_config());
    effectiveConfig.update(config);
    // Inspect the merged model metadata, including llm_config.json. Legacy
    // GLM/integer masks need a full prefill; keep explicit chunk settings intact.
    if (!effectiveConfig.contains("chunk") && !effectiveConfig.contains("chunk_limits") &&
        effectiveConfig.value("attention_mask", "float") == "float") {
        config["chunk"] = 128;
    }
    if (!llm_->set_config(config.dump())) {
        if (errorMessage != nullptr) *errorMessage = "MNN rejected the runtime config";
        return false;
    }
    if (backend_ == "hexagon") {
        const auto modelInfo = inspectMnnHexagonModel(configPath_, llm_->dump_config());
        if (modelInfo.nonC4AttentionOps > 0) {
            const auto message = "model_backend_incompatible: MNN Hexagon requires Transformer C4 attention; found " +
                    std::to_string(modelInfo.nonC4AttentionOps) + " Attention op(s) with output_c4=false, first=" +
                    modelInfo.firstNonC4Attention +
                    ". Re-export with Transformer C4 enabled (recommended: --quant_bit 4 --quant_block 64 --sym), "
                    "or select CPU/OpenCL/Vulkan.";
            if (errorMessage != nullptr) *errorMessage = message;
            return false;
        }
    }
    const bool loaded = llm_->load();
    if (!loaded || hasRuntimeError(llm_->getContext())) {
        if (errorMessage != nullptr) {
            *errorMessage = "MNN model load failed; stage=load, backend=" + backend_ + ", " + contextSummary(llm_->getContext());
        }
        return false;
    }
    return true;
}

MnnLlmSessionAdapter::Metrics MnnLlmSessionAdapter::generate(
        const std::string& messagesJson,
        const std::string& requestConfigJson,
        int maxTokens,
        const std::function<bool(const std::string&)>& onToken) {
    std::lock_guard<std::mutex> lock(generationMutex_);
    clearMnnNativeDiagnostics();
    if (llm_ == nullptr) throw std::runtime_error("Model is not loaded");
    if (!requestConfigJson.empty() && !llm_->set_config(requestConfigJson)) {
        throw std::invalid_argument("MNN rejected request config");
    }
    if (maxTokens < kNoTokenLimit) {
        throw std::invalid_argument("maxTokens must be -1 or greater");
    }
    const ChatMessages messages = parseMessages(messagesJson);
    llm_->reset();
    restoreRunningStatusIfTerminal();
    if (hasRuntimeError(llm_->getContext())) {
        failGeneration("MNN generation cannot start; reload the model after a runtime error",
                "stage=request_begin, backend=" + backend_ + ", " + contextSummary(llm_->getContext()));
    }
    if (cancelRequested_.load()) {
        Metrics metrics;
        metrics.finishReason = "cancelled";
        return metrics;
    }

    SteppingStreamBuffer streamBuffer(onToken);
    std::ostream output(&streamBuffer);
    int generated = 0;
    const auto hasTokenBudget = [&]() {
        return maxTokens == kNoTokenLimit || generated < maxTokens;
    };
    {
        PrefillCancellationScope cancellation(cancelRequested_);
        llm_->response(messages, &output, "<eop>", 0);
    }
    // response(..., 0) performs prefill. A failed forward leaves an error
    // status, and generate(1) would just return without explaining the cause.
    if (hasRuntimeError(llm_->getContext())) {
        failGeneration("MNN generation failed during prefill",
                "stage=prefill, backend=" + backend_ + ", " + contextSummary(llm_->getContext()));
    }

    auto resolveStep = [&]() {
        auto* context = const_cast<MNN::Transformer::LlmContext*>(llm_->getContext());
        if (context == nullptr) return;
        const bool cancelled = cancelRequested_.load() || streamBuffer.stopRequested();
        if (context->status == LlmStatus::MAX_TOKENS_FINISHED && !cancelled && hasTokenBudget()) {
            context->status = LlmStatus::RUNNING;
            streamBuffer.discardPendingEop();
            return;
        }
        if (context->status == LlmStatus::NORMAL_FINISHED && !streamBuffer.pendingEop() &&
            !cancelled && hasTokenBudget()) {
            context->status = LlmStatus::RUNNING;
            return;
        }
        if (streamBuffer.pendingEop()) streamBuffer.finalizePendingEop();
    };

    resolveStep();
    while (!cancelRequested_.load() && !streamBuffer.stopRequested() &&
           !streamBuffer.finished() && hasTokenBudget()) {
        const auto* beforeContext = llm_->getContext();
        const int beforeGenerated = beforeContext == nullptr ? generated : beforeContext->gen_seq_len;
        llm_->generate(1);
        const auto* afterContext = llm_->getContext();
        const auto stepDetails = [&]() {
            return "stage=decode, backend=" + backend_ + ", before_generated=" +
                    std::to_string(beforeGenerated) + ", " + contextSummary(afterContext);
        };
        // Check errors even if this step produced a token or exhausted the
        // budget. A failing final forward must not be reported as success.
        if (hasRuntimeError(afterContext)) {
            failGeneration("MNN generation failed during decode", stepDetails());
        }
        generated = afterContext->gen_seq_len;
        resolveStep();
        if (!cancelRequested_.load() && !streamBuffer.stopRequested() &&
            !streamBuffer.finished() && hasTokenBudget() && generated <= beforeGenerated) {
            failGeneration("MNN generation stopped before reaching an end marker", stepDetails());
        }
    }
    streamBuffer.finalizePendingEop();

    Metrics metrics;
    const auto* context = llm_->getContext();
    if (context != nullptr) {
        metrics.promptTokens = context->prompt_len;
        metrics.completionTokens = context->gen_seq_len;
        metrics.prefillUs = context->prefill_us;
        metrics.decodeUs = context->decode_us;
        metrics.sampleUs = context->sample_us;
    }
    if (cancelRequested_.load() || streamBuffer.stopRequested()) {
        metrics.finishReason = "cancelled";
    } else if (maxTokens != kNoTokenLimit && generated >= maxTokens &&
               (context == nullptr || context->status != LlmStatus::NORMAL_FINISHED)) {
        metrics.finishReason = "length";
    }
    return metrics;
}

void MnnLlmSessionAdapter::cancel() {
    cancelRequested_.store(true);
}

void MnnLlmSessionAdapter::reset() {
    std::lock_guard<std::mutex> lock(generationMutex_);
    cancelRequested_.store(false);
    if (llm_ != nullptr) llm_->reset();
}

void MnnLlmSessionAdapter::restoreRunningStatusIfTerminal() {
    auto* context = llm_ == nullptr
        ? nullptr
        : const_cast<MNN::Transformer::LlmContext*>(llm_->getContext());
    if (context != nullptr &&
        (context->status == LlmStatus::MAX_TOKENS_FINISHED ||
         context->status == LlmStatus::NORMAL_FINISHED ||
         context->status == LlmStatus::USER_CANCEL)) {
        context->status = LlmStatus::RUNNING;
    }
}
