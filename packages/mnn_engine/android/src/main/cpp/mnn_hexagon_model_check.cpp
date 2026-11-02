#include "mnn_hexagon_model_check.hpp"

#include <cerrno>
#include <cstring>
#include <fcntl.h>
#include <fstream>
#include <limits>
#include <stdexcept>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include "MNN_generated.h"
#include "nlohmann/json.hpp"

namespace {
using json = nlohmann::json;

std::string baseDirectory(const std::string& path) {
    const auto slash = path.find_last_of("/\\");
    return slash == std::string::npos ? "./" : path.substr(0, slash + 1);
}

std::string modelPath(const std::string& configPath, const json& effectiveConfig) {
    // Match LlmConfig: the primary config sets base_dir before llm_config and
    // runtime overrides are merged. Model filenames are then concatenated.
    std::string base;
    if (configPath.size() >= 5 && configPath.compare(configPath.size() - 5, 5, ".json") == 0) {
        std::ifstream input(configPath);
        if (!input) throw std::runtime_error("Cannot inspect MNN config: " + configPath);
        const auto primary = json::parse(input);
        base = primary.value("base_dir", baseDirectory(configPath));
    } else if (configPath.size() >= 4 && configPath.compare(configPath.size() - 4, 4, ".mnn") == 0) {
        base = baseDirectory(configPath);
    } else {
        base = configPath;
    }
    return base + effectiveConfig.value("llm_model", "llm.mnn");
}

class GraphMapping {
public:
    explicit GraphMapping(const std::string& path) {
        const int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
        if (fd < 0) throw std::runtime_error("Cannot inspect MNN graph: " + path + ": " + std::strerror(errno));
        struct stat info {};
        if (fstat(fd, &info) != 0 || info.st_size <= 0 ||
                info.st_size >= std::numeric_limits<flatbuffers::soffset_t>::max()) {
            close(fd);
            throw std::runtime_error("Invalid MNN graph size: " + path);
        }
        size_ = static_cast<size_t>(info.st_size);
        data_ = mmap(nullptr, size_, PROT_READ, MAP_PRIVATE, fd, 0);
        const int mapError = errno;
        close(fd);
        if (data_ == MAP_FAILED) {
            throw std::runtime_error("Cannot map MNN graph for inspection: " + path + ": " + std::strerror(mapError));
        }
    }
    ~GraphMapping() { munmap(data_, size_); }
    GraphMapping(const GraphMapping&) = delete;
    GraphMapping& operator=(const GraphMapping&) = delete;
    const uint8_t* data() const { return static_cast<const uint8_t*>(data_); }
    size_t size() const { return size_; }
private:
    void* data_ = MAP_FAILED;
    size_t size_ = 0;
};

void inspectOps(const flatbuffers::Vector<flatbuffers::Offset<MNN::Op>>* ops, MnnHexagonModelInfo& result) {
    if (ops == nullptr) return;
    for (const auto* op : *ops) {
        if (op->type() == MNN::OpType_Attention) {
            const auto* attention = op->main_as_AttentionParam();
            // MNN 3.6.1's standard Hexagon Attention always emits C4 and
            // requires C4 V input. Streaming state Attention is a separate
            // multi-output path; do not infer its layout from this flag.
            const bool standardAttention = op->outputIndexes() == nullptr || op->outputIndexes()->size() <= 1;
            if (standardAttention && (attention == nullptr || !attention->output_c4())) {
                ++result.nonC4AttentionOps;
                if (result.firstNonC4Attention.empty()) {
                    result.firstNonC4Attention = op->name() ? op->name()->str() : "<unnamed>";
                }
            }
        }
    }
}
}  // namespace

MnnHexagonModelInfo inspectMnnHexagonModel(const std::string& configPath, const std::string& effectiveConfig) {
    const auto path = modelPath(configPath, json::parse(effectiveConfig));
    const GraphMapping graph(path);
    flatbuffers::Verifier verifier(graph.data(), graph.size());
    if (!MNN::VerifyNetBuffer(verifier)) throw std::runtime_error("Invalid MNN graph: " + path);
    const auto* net = MNN::GetNet(graph.data());
    MnnHexagonModelInfo result;
    inspectOps(net->oplists(), result);
    if (net->subgraphs() != nullptr) {
        for (const auto* subgraph : *net->subgraphs()) inspectOps(subgraph->nodes(), result);
    }
    return result;
}
