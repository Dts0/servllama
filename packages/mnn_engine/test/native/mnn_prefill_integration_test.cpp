#include "mnn_prefill_control.hpp"
#include "MNN/MNNForwardType.h"

#include <filesystem>
#include <iostream>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>
#include <vector>

using MNN::Transformer::Llm;
using MNN::Transformer::LlmStatus;

namespace {
void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

struct Cancellation {
    const MNN::Transformer::LlmContext* context;
    int afterTokens;
};

std::vector<int> run(Llm& llm, const std::string& prompt, int chunk, int cancelAfter, int maxTokens) {
    llm.reset();
    // Match the adapter's terminal-state transition. Upstream reset() only
    // clears KV/history; it deliberately leaves USER_CANCEL/error status alone.
    auto* state = const_cast<MNN::Transformer::LlmContext*>(llm.getContext());
    if (state->status == LlmStatus::NORMAL_FINISHED || state->status == LlmStatus::MAX_TOKENS_FINISHED ||
        state->status == LlmStatus::USER_CANCEL) {
        state->status = LlmStatus::RUNNING;
    }
    llm.set_config("{\"chunk\":" + std::to_string(chunk) + "}");
    Cancellation cancellation{llm.getContext(), cancelAfter};
    mnn_engine_set_prefill_cancel_callback([](void* data) {
        const auto& request = *static_cast<Cancellation*>(data);
        return request.afterTokens >= 0 && request.context->all_seq_len >= request.afterTokens;
    }, &cancellation);
    std::ostringstream output;
    llm.response(MNN::Transformer::ChatMessages{{"user", prompt}}, &output, "<eop>", maxTokens);
    mnn_engine_set_prefill_cancel_callback(nullptr, nullptr);
    const auto* context = llm.getContext();
    if (cancelAfter >= 0) {
        require(context->status == LlmStatus::USER_CANCEL, "Expected normal USER_CANCEL status");
        require(context->gen_seq_len == 0 && output.str().empty(), "Cancelled prefill must not decode");
        require(context->all_seq_len <= cancelAfter, "Cancellation ran past the requested chunk boundary");
    } else {
        require(context->status == LlmStatus::MAX_TOKENS_FINISHED ||
                    context->status == LlmStatus::NORMAL_FINISHED || context->status == LlmStatus::RUNNING,
                "Generation left an error status");
        require(context->gen_seq_len > 0, "Expected decoded tokens");
    }
    std::cout << "chunk=" << chunk << ", cancel_after=" << cancelAfter
              << ", prompt_tokens=" << context->prompt_len
              << ", processed_tokens=" << context->all_seq_len
              << ", generated_tokens=" << context->gen_seq_len
              << ", prefill_us=" << context->prefill_us << std::endl;
    return context->output_tokens;
}
}

int main(int argc, char** argv) {
    if (argc < 2 || argc > 5) {
        std::cerr << "Usage: mnn_prefill_integration_test /path/to/model/config.json [cpu|opencl|vulkan] [image_path] [--chunked-only]\n";
        return 2;
    }
    try {
        const std::string backend = argc >= 3 ? argv[2] : "cpu";
        std::string imagePath;
        bool compareChunkModes = true;
        for (int i = 3; i < argc; ++i) {
            if (std::string(argv[i]) == "--chunked-only") compareChunkModes = false;
            else imagePath = argv[i];
        }
        const bool hasImage = !imagePath.empty();
        require(backend == "cpu" || backend == "opencl" || backend == "vulkan", "Unsupported test backend");
        std::unique_ptr<Llm, decltype(&Llm::destroy)> llm(Llm::createLLM(argv[1]), Llm::destroy);
        require(llm != nullptr, "Could not create model");
        // On GPU this field is a tuning bitmask, not a CPU thread count.
        // Disable tuning in the correctness fixture; app defaults are tested separately.
        const int threads = backend == "cpu" ? 2 : MNN_GPU_TUNING_NONE;
        llm->set_config("{\"backend_type\":\"" + backend + "\",\"thread_num\":" + std::to_string(threads) +
            R"(,"sampler_type":"greedy","async":false,"use_mmap":false,"enable_debug":false})");
        if (backend != "cpu") {
            // OpenCL and Vulkan cache formats differ, just as in the app runtime.
            const auto cacheDirectory = ".mnn-prefill-test-" + backend;
            std::filesystem::create_directories(cacheDirectory);
            llm->set_config("{\"tmp_path\":\"" + cacheDirectory + "\"}");
        }
        require(llm->load(), "Could not load model");
        const int referenceChunk = compareChunkModes ? 0 : 128;
        std::cout << "Testing backend=" << backend << ", reference_chunk=" << referenceChunk << std::endl;
        const std::string shortPrompt = hasImage
            ? "Name the fruit and its color. Answer briefly. <img>" + imagePath + "</img>"
            : "Reply with the word hello.";
        std::string longPrompt;
        for (int i = 0; i < 48; ++i) longPrompt += "one two three four five six seven eight. ";
        longPrompt += shortPrompt;

        const auto generate = [&](const std::string& prompt, int chunk, int cancelAfter = -1) {
            return run(*llm, prompt, chunk, cancelAfter, hasImage ? 32 : 4);
        };
        const auto sameTokens = [&](const std::vector<int>& actual, const std::vector<int>& expected, const char* message) {
            if (actual == expected) return;
            std::cerr << "Expected: ";
            for (int token : expected) std::cerr << llm->tokenizer_decode(token);
            std::cerr << "\nActual: ";
            for (int token : actual) std::cerr << llm->tokenizer_decode(token);
            std::cerr << '\n';
            throw std::runtime_error(message);
        };
        const auto shortBaseline = generate(shortPrompt, referenceChunk);
        if (hasImage) {
            std::cout << "Image baseline: ";
            for (int token : shortBaseline) std::cout << llm->tokenizer_decode(token);
            std::cout << std::endl;
            sameTokens(generate(shortPrompt, referenceChunk), shortBaseline, "Repeated image changed with the same config");
        }
        const auto shortChunked = generate(shortPrompt, 128);
        sameTokens(shortChunked, shortBaseline, "Short prompt output mismatch");
        const auto longBaseline = generate(longPrompt, referenceChunk);
        const int promptTokens = llm->getContext()->prompt_len;
        require(promptTokens > 256, "Fixture must span multiple chunks");
        sameTokens(generate(longPrompt, 128), longBaseline, "Long prompt output mismatch");
        require(llm->getContext()->prompt_len == promptTokens, "Chunking changed the reported prompt length");

        generate(longPrompt, 128, 0);
        sameTokens(generate(shortPrompt, 128), shortChunked, "Early cancellation poisoned the next request");
        generate(longPrompt, 128, 128);
        require(llm->getContext()->all_seq_len == 128, "Expected exactly one completed prefill chunk");
        sameTokens(generate(shortPrompt, 128), shortChunked, "Partial KV cache survived reset");
        generate(longPrompt, 128, promptTokens);
        sameTokens(generate(shortPrompt, 128), shortChunked, "Last-chunk cancellation poisoned the next request");
        std::cout << "Real MNN prefill regression passed: backend=" << backend << ", " << promptTokens
                  << " prompt tokens, " << (compareChunkModes ? "chunked/unchunked" : "repeatable chunked")
                  << " greedy output, cancellation at 0/128/final token, reuse\n";
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAILED: " << error.what() << '\n';
        return 1;
    }
}
