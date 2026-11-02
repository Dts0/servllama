// Minimal headless transport for the pinned llama.cpp-omni server-context.
#include "common.h"
#include "arg.h"
#include "server-context.h"
#include "server-http.h"
#include <atomic>
#include <csignal>
#include <chrono>
#include <thread>
#include <cstdio>
static volatile std::sig_atomic_t interrupted = 0;
static void on_signal(int) { interrupted = 1; }
int main(int argc, char **argv) {
    common_params params;
    params.ui = false;
    common_init();
    if (!common_params_parse(argc, argv, params, LLAMA_EXAMPLE_SERVER)) return 1;
    params.ui = false; // This executable intentionally has no bundled web UI.
    if (params.n_gpu_layers == -1) params.n_gpu_layers = 0; // fork default: auto
    if (params.n_gpu_layers != 0) {
        std::fprintf(stderr, "MiniCPM runtime is CPU-only; use --gpu-layers 0.\n");
        return 2;
    }
    std::signal(SIGINT, on_signal);
    std::signal(SIGTERM, on_signal);
    llama_backend_init();
    llama_numa_init(params.numa);
    int result = 0;
    {
        server_context ctx;
        server_http_context http;
        if (!http.init(params)) return 2;
        try {
            if (!ctx.load_model(params)) return 3;
        } catch (const std::exception &) {
            std::fprintf(stderr, "Model initialization failed.\n");
            return 3;
        }
        if (interrupted) return 0;
        server_routes routes(params, ctx);
        routes.update_meta(ctx);
        http.get("/health", routes.get_health);
        http.get("/v1/health", routes.get_health);
        http.get("/props", routes.get_props);
        http.get("/models", routes.get_models);
        http.get("/v1/models", routes.get_models);
        http.get("/slots", routes.get_slots);
        http.get("/metrics", routes.get_metrics);
        http.post("/completion", routes.post_completions);
        http.post("/completions", routes.post_completions_oai);
        http.post("/v1/completions", routes.post_completions_oai);
        http.post("/chat/completions", routes.post_chat_completions);
        http.post("/v1/chat/completions", routes.post_chat_completions);
        http.post("/tokenize", routes.post_tokenize);
        http.post("/detokenize", routes.post_detokenize);
        http.post("/apply-template", routes.post_apply_template);
        http.post("/embedding", routes.post_embeddings);
        http.post("/embeddings", routes.post_embeddings_oai);
        http.post("/v1/embeddings", routes.post_embeddings_oai);
        http.register_gcp_compat();
        http.is_ready = true;
        if (!http.start()) return 4;
        std::thread inference([&]() { ctx.start_loop(); });
        while (!interrupted) std::this_thread::sleep_for(std::chrono::milliseconds(100));
        http.is_ready = false;
        // Existing HTTP readers observe !is_ready and cancel their owned tasks.
        http.stop();
        if (http.thread.joinable()) http.thread.join();
        ctx.terminate();
        inference.join();
    }
    llama_backend_free();
    return result;
}
