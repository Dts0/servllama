#include <iomanip>
#include <iostream>
#include <memory>
#include <string>
#include <vector>

#include "tokenizer.hpp"

// Exercises the same single-token decode API used by Llm generation, without
// loading model weights or initializing a backend. Inputs are explicit test data.
int main(int argc, char** argv) {
    if (argc < 3) {
        std::cerr << "Usage: tokenizer_probe tokenizer.mtok text [text ...]\n";
        return 2;
    }
    std::unique_ptr<MNN::Transformer::Tokenizer> tokenizer(
        MNN::Transformer::Tokenizer::createTokenizer(argv[1]));
    if (!tokenizer) return 2;
    int failed = 0;
    for (int arg = 2; arg < argc; ++arg) {
        const std::string expected(argv[arg]);
        const auto ids = tokenizer->encode(expected);
        std::string actual;
        for (int id : ids) actual += tokenizer->decode(id);
        const bool passed = actual == expected;
        std::cout << (passed ? "PASS" : "FAIL") << " text=" << std::quoted(expected) << " ids=[";
        for (size_t i = 0; i < ids.size(); ++i) std::cout << (i ? "," : "") << ids[i];
        std::cout << "] decoded=" << std::quoted(actual) << '\n';
        if (!passed) ++failed;
    }
    std::cout << "Round trips: " << argc - 2 << ", failed: " << failed << '\n';
    return failed ? 1 : 0;
}
