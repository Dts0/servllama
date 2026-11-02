#include "mnn_prefill_control.hpp"

namespace {
thread_local MnnPrefillCancelCallback cancelCallback = nullptr;
thread_local void* cancelUserData = nullptr;
}

extern "C" void mnn_engine_set_prefill_cancel_callback(MnnPrefillCancelCallback callback, void* userData) {
    cancelCallback = callback;
    cancelUserData = userData;
}

namespace mnn_engine {
thread_local int prefillBasePosition = 0;

bool cancelPrefill(MNN::Transformer::LlmContext* context) {
    using MNN::Transformer::LlmStatus;
    if (context->status == LlmStatus::NOT_LOADED || context->status == LlmStatus::INTERNAL_ERROR ||
        context->status == LlmStatus::TIMEOUT || context->status == LlmStatus::USER_CANCEL) {
        return true;
    }
    if (cancelCallback != nullptr && cancelCallback(cancelUserData)) {
        context->status = LlmStatus::USER_CANCEL;
        return true;
    }
    return false;
}
}  // namespace mnn_engine
