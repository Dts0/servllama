#pragma once

#include "llm/llm.hpp"

using MnnPrefillCancelCallback = bool (*)(void*);

// The callback is scoped to the calling generation thread. Only that thread
// updates LlmContext; the cancelling thread only sets the adapter's atomic flag.
extern "C" __attribute__((visibility("default")))
void mnn_engine_set_prefill_cancel_callback(MnnPrefillCancelCallback callback, void* userData);

namespace mnn_engine {

bool cancelPrefill(MNN::Transformer::LlmContext* context);

// A multimodal embedding is built once, then sliced by forwardVec(). Its
// position IDs and deep-stack inputs must use the same offset as each slice.
extern thread_local int prefillBasePosition;

class PrefillScope {
public:
    explicit PrefillScope(int base) : previous_(prefillBasePosition) { prefillBasePosition = base; }
    ~PrefillScope() { prefillBasePosition = previous_; }
    PrefillScope(const PrefillScope&) = delete;
    PrefillScope& operator=(const PrefillScope&) = delete;

private:
    int previous_;
};

}  // namespace mnn_engine
