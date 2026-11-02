# MNN 3.6.2's PipelineTokenizer::decode(int) misses added_tokens_ outside the
# base vocabulary. Keep the pinned checkout intact and patch a build copy.
function(mnn_engine_prepare_tokenizer source_path output_path)
    file(READ "${source_path}" tokenizer_source)
    set(original "std::string PipelineTokenizer::decode(int id) {\n    if (!model_) return \"\";")
    set(replacement [=[std::string PipelineTokenizer::decode(int id) {
    // Added tokens are literal protocol/text pieces, not base-vocabulary BPE
    // tokens. Preserve them before applying ByteLevel/Metaspace decoding.
    for (const auto& token : added_tokens_) {
        if (token.id == id) return token.content;
    }
    if (!model_) return "";]=])
    string(FIND "${tokenizer_source}" "${original}" match_position)
    if(match_position EQUAL -1)
        message(FATAL_ERROR "MNN tokenizer source changed; review the added-token compatibility patch.")
    endif()
    string(REPLACE "${original}" "${replacement}" tokenizer_source "${tokenizer_source}")
    get_filename_component(output_dir "${output_path}" DIRECTORY)
    file(MAKE_DIRECTORY "${output_dir}")
    file(WRITE "${output_path}" "${tokenizer_source}")
endfunction()

function(mnn_engine_attach_tokenizer_fix)
    if(NOT TARGET llm)
        message(FATAL_ERROR "The tokenizer compatibility patch requires MNN_BUILD_LLM=ON.")
    endif()
    set(tokenizer_dir "${CMAKE_CURRENT_SOURCE_DIR}/transformers/llm/engine/src/tokenizer")
    set(original "${tokenizer_dir}/tokenizer.cpp")
    set(patched "${CMAKE_CURRENT_BINARY_DIR}/mnn_engine_compat/tokenizer.cpp")
    get_target_property(llm_sources llm SOURCES)
    list(FIND llm_sources "${original}" source_index)
    if(source_index EQUAL -1)
        message(FATAL_ERROR "MNN llm target no longer contains tokenizer.cpp; review the compatibility patch.")
    endif()
    mnn_engine_prepare_tokenizer("${original}" "${patched}")
    list(REMOVE_ITEM llm_sources "${original}")
    list(APPEND llm_sources "${patched}")
    set_property(TARGET llm PROPERTY SOURCES "${llm_sources}")
    target_include_directories(llm PRIVATE "${tokenizer_dir}")
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${original}")
endfunction()

# Also usable by the standalone regression/probe build, without MNN targets.
if(DEFINED MNN_TOKENIZER_SOURCE AND DEFINED MNN_TOKENIZER_OUTPUT)
    mnn_engine_prepare_tokenizer("${MNN_TOKENIZER_SOURCE}" "${MNN_TOKENIZER_OUTPUT}")
endif()
