#include "mnn_hexagon_model_check.hpp"
#include "MNN_generated.h"
#include "nlohmann/json.hpp"

#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <unistd.h>

namespace {
using json = nlohmann::json;
namespace fs = std::filesystem;

void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

std::unique_ptr<MNN::OpT> attention(bool c4, bool streaming = false) {
    auto op = std::make_unique<MNN::OpT>();
    op->name = "test/attention";
    op->type = MNN::OpType_Attention;
    op->outputIndexes = streaming ? std::vector<int>{7, 8, 9} : std::vector<int>{7};
    MNN::AttentionParamT param;
    param.output_c4 = c4;
    op->main.Set(std::move(param));
    return op;
}

std::unique_ptr<MNN::OpT> convolution(bool symmetric) {
    auto op = std::make_unique<MNN::OpT>();
    op->type = MNN::OpType_Convolution;
    MNN::Convolution2DT param;
    param.quanParameter = std::make_unique<MNN::IDSTQuanT>();
    param.quanParameter->type = 1;
    param.quanParameter->aMaxOrBits = 4;
    param.quanParameter->readType = symmetric ? 0 : 16384;
    op->main.Set(std::move(param));
    return op;
}

void writeGraph(const fs::path& path, MNN::NetT& net) {
    flatbuffers::FlatBufferBuilder builder;
    builder.Finish(MNN::Net::Pack(builder, &net));
    std::ofstream output(path, std::ios::binary);
    output.write(reinterpret_cast<const char*>(builder.GetBufferPointer()), builder.GetSize());
}

void expectFailure(const fs::path& config) {
    try {
        (void)inspectMnnHexagonModel(config.string(), "{}");
    } catch (const std::runtime_error&) {
        return;
    }
    throw std::runtime_error("Missing or malformed graph must fail inspection");
}
}  // namespace

int main() {
    char pattern[] = "/tmp/mnn-hexagon-check-XXXXXX";
    const char* directory = mkdtemp(pattern);
    if (!directory) return 1;
    const fs::path root(directory);
    int cases = 0;
    try {
        const auto config = root / "config.json";
        std::ofstream(config) << "{}";
        MNN::NetT net;
        net.oplists.emplace_back(attention(true));
        net.oplists.emplace_back(convolution(true));
        writeGraph(root / "llm.mnn", net);
        auto info = inspectMnnHexagonModel(config.string(), "{}");
        require(info.nonC4AttentionOps == 0,
                "Accept symmetric W4/C4 graph without requiring external weights");
        ++cases;

        net.oplists[0] = attention(false);
        net.oplists[1] = convolution(false);
        writeGraph(root / "llm.mnn", net);
        info = inspectMnnHexagonModel(config.string(), "{}");
        require(info.nonC4AttentionOps == 1 && info.firstNonC4Attention == "test/attention",
                "Identify the incompatible Attention layout and first operator");
        ++cases;

        net.oplists[0] = attention(true);
        writeGraph(root / "llm.mnn", net);
        info = inspectMnnHexagonModel(config.string(), "{}");
        require(info.nonC4AttentionOps == 0,
                "Asymmetric W4 alone must not be rejected as incompatible attention");
        ++cases;

        net.oplists[0] = attention(false, true);
        writeGraph(root / "llm.mnn", net);
        require(inspectMnnHexagonModel(config.string(), "{}").nonC4AttentionOps == 0,
                "Do not misclassify the distinct streaming-state Attention path");
        ++cases;

        auto subgraph = std::make_unique<MNN::SubGraphProtoT>();
        subgraph->nodes.emplace_back(attention(false));
        net.subgraphs.emplace_back(std::move(subgraph));
        writeGraph(root / "llm.mnn", net);
        require(inspectMnnHexagonModel(config.string(), "{}").nonC4AttentionOps == 1,
                "Inspect subgraph operations too");
        ++cases;

        fs::create_directory(root / "custom");
        writeGraph(root / "custom" / "alternate.mnn", net);
        std::ofstream(config) << json{{"base_dir", (root / "custom").string() + "/"}};
        info = inspectMnnHexagonModel(config.string(), R"({"llm_model":"alternate.mnn","base_dir":"/ignored-override/"})");
        require(info.nonC4AttentionOps == 1, "Use the primary base_dir and effective model filename, as LlmConfig does");
        ++cases;

        info = inspectMnnHexagonModel((root / "llm.mnn").string(), R"({"llm_model":"llm.mnn"})");
        require(info.nonC4AttentionOps == 1, "Preserve the direct .mnn config path convention");
        ++cases;

        std::ofstream(config) << "{}";
        std::ofstream(root / "llm.mnn", std::ios::binary) << std::string(64, '\0');
        expectFailure(config);
        ++cases;
        fs::remove(root / "llm.mnn");
        expectFailure(config);
        ++cases;
        std::cout << cases << " Hexagon model compatibility regression cases passed\n";
        fs::remove_all(root);
        return 0;
    } catch (const std::exception& error) {
        std::cerr << "FAILED: " << error.what() << '\n';
        fs::remove_all(root);
        return 1;
    }
}
