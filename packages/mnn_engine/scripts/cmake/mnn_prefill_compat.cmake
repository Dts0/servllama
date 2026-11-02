# MNN 3.6.2 already implements chunked prefill, but has no cancellation hook.
# Patch build copies only, as with the tokenizer compatibility fix. Every
# anchor is checked so an upstream update requires reviewing the integration.
function(mnn_engine_prefill_replace source_var original replacement)
    string(FIND "${${source_var}}" "${original}" match_position)
    if(match_position EQUAL -1)
        message(FATAL_ERROR "MNN prefill source changed; review mnn_prefill_compat.cmake (${original}).")
    endif()
    string(REPLACE "${original}" "${replacement}" updated "${${source_var}}")
    set(${source_var} "${updated}" PARENT_SCOPE)
endfunction()

function(mnn_engine_prepare_prefill source_dir output_dir)
    file(READ "${source_dir}/llm.cpp" llm)
    file(READ "${source_dir}/omni.cpp" omni)
    foreach(source_var llm omni)
        set(${source_var} "#include \"mnn_prefill_control.hpp\"\n${${source_var}}")
    endforeach()

    mnn_engine_prefill_replace(llm
        [=[std::vector<int> Llm::generate(const std::vector<int>& input_ids, int max_tokens) {
    CHECK_LLM_RUNNING_RET(mContext, std::vector<int>());]=]
        [=[std::vector<int> Llm::generate(const std::vector<int>& input_ids, int max_tokens) {
    CHECK_LLM_RUNNING_RET(mContext, std::vector<int>());
    mContext->prompt_len = static_cast<int>(input_ids.size());
    if (mnn_engine::cancelPrefill(mContext.get())) return {};]=])

    # Omni embedding() consumes complete image/audio spans. Slice the resulting
    # embeddings in forwardVec(), never the raw multimodal token stream.
    mnn_engine_prefill_replace(llm
        "if (0 == mBlockSize || input_ids.size() <= mBlockSize) {"
        [=[if (0 == mBlockSize || input_ids.size() <= mBlockSize ||
            mConfig->is_visual() || mConfig->is_audio() || mConfig->has_talker()) {]=])
    mnn_engine_prefill_replace(llm
        "            generate(input_embeds, 0);"
        [=[            generate(input_embeds, 0);
            mContext->prompt_len = total_size;
            if (mnn_engine::cancelPrefill(mContext.get())) return {};]=])

    mnn_engine_prefill_replace(llm
        [=[std::vector<VARP> Llm::forwardVec(MNN::Express::VARP input_embeds) {
    CHECK_LLM_RUNNING_RET(mContext, std::vector<VARP>());]=]
        [=[std::vector<VARP> Llm::forwardVec(MNN::Express::VARP input_embeds) {
    CHECK_LLM_RUNNING_RET(mContext, std::vector<VARP>());
    if (mnn_engine::cancelPrefill(mContext.get())) return {};]=])
    mnn_engine_prefill_replace(llm
        "embeddings = MNN::Express::_Split(input_embeds, sizeSplits);"
        "embeddings = MNN::Express::_Split(input_embeds, sizeSplits, mSeqLenIndex);")
    mnn_engine_prefill_replace(llm
        [=[    for (int i=0; i<blockNumber; ++i) {
        logits.clear();]=]
        [=[    for (int i=0; i<blockNumber; ++i) {
        if (mnn_engine::cancelPrefill(mContext.get())) return {};
        logits.clear();]=])
    mnn_engine_prefill_replace(llm
        [=[    if (blockRemain != 0) {
        logits.clear();]=]
        [=[    if (blockRemain != 0) {
        if (mnn_engine::cancelPrefill(mContext.get())) return {};
        logits.clear();]=])
    mnn_engine_prefill_replace(llm
        [=[    Timer _t;
    auto outputs = forwardVec(input_embeds);
    if(mGenerateParam->outputs.size() < 1) {]=]
        [=[    Timer _t;
    mnn_engine::PrefillScope prefillScope(mContext->all_seq_len);
    auto outputs = forwardVec(input_embeds);
    mContext->prefill_us += _t.durationInUs();
    // Cancellation leaves a partial KV cache, which reset() discards before
    // the next request. Preserve USER_CANCEL and any real backend error.
    if (mnn_engine::cancelPrefill(mContext.get())) return mContext->output_tokens;
    if(outputs.empty() || mGenerateParam->outputs.empty()) {]=])
    mnn_engine_prefill_replace(llm
        [=[    updateContext(seqLen, 0);
    mContext->prefill_us += _t.durationInUs();
    MNN::Express::ExecutorScope::Current()->gc(); // after prefill]=]
        [=[    updateContext(seqLen, 0);
    MNN::Express::ExecutorScope::Current()->gc(); // after prefill
    if (mnn_engine::cancelPrefill(mContext.get())) return mContext->output_tokens;]=])

    # Keep 3.6.2's variable mRoPE axes and relative spatial positions while
    # indexing each prefill chunk into the complete multimodal position arrays.
    mnn_engine_prefill_replace(omni
        [=[                int offset = (relativeSpatial && axis > 0) ? 0 : mContext->all_seq_len;
                ptr[i + seq_len * axis] = axisValue(axis, i) + offset;]=]
        [=[                int base = mnn_engine::prefillBasePosition;
                int index = mContext->all_seq_len - base + i;
                int offset = (relativeSpatial && axis > 0) ? 0 : base;
                ptr[i + seq_len * axis] = axisValue(axis, index) + offset;]=])
    mnn_engine_prefill_replace(omni
        "    extraArgs.insert(extraArgs.end(), mExtraArgs.begin(), mExtraArgs.end());"
        [=[    for (auto extra : mExtraArgs) {
        const auto& dims = extra->getInfo()->dim;
        int length = hiddenState->getInfo()->dim[mSeqLenIndex];
        int offset = mContext->all_seq_len - mnn_engine::prefillBasePosition;
        if (mContext->gen_seq_len == 0 && dims.size() == 3 && dims[1] > 1 &&
            (offset != 0 || length != dims[1])) {
            int available = std::min(length, dims[1] - offset);
            auto sliced = _Slice(extra, _var<int>({0, offset, 0}, {3}),
                                _var<int>({-1, available, -1}, {3}));
            if (available < length) {
                auto padding = _Fill(_var<int>({dims[0], length - available, dims[2]}, {3}), _Scalar<float>(0.0));
                sliced = _Concat({sliced, padding}, 1);
            }
            extraArgs.push_back(sliced);
        } else {
            extraArgs.push_back(extra);
        }
    }]=])

    file(MAKE_DIRECTORY "${output_dir}")
    file(WRITE "${output_dir}/llm.cpp" "${llm}")
    file(WRITE "${output_dir}/omni.cpp" "${omni}")
endfunction()

function(mnn_engine_attach_prefill_fix)
    if(NOT TARGET llm OR NOT MNN_BUILD_LLM_OMNI)
        message(FATAL_ERROR "The prefill integration requires MNN_BUILD_LLM and MNN_BUILD_LLM_OMNI.")
    endif()
    set(source_dir "${CMAKE_CURRENT_SOURCE_DIR}/transformers/llm/engine/src")
    set(output_dir "${CMAKE_CURRENT_BINARY_DIR}/mnn_engine_compat")
    get_target_property(llm_sources llm SOURCES)
    foreach(name llm omni)
        list(FIND llm_sources "${source_dir}/${name}.cpp" source_index)
        if(source_index EQUAL -1)
            message(FATAL_ERROR "MNN llm target no longer contains ${name}.cpp; review the prefill integration.")
        endif()
        list(REMOVE_ITEM llm_sources "${source_dir}/${name}.cpp")
        list(APPEND llm_sources "${output_dir}/${name}.cpp")
    endforeach()
    mnn_engine_prepare_prefill("${source_dir}" "${output_dir}")
    set_property(TARGET llm PROPERTY SOURCES "${llm_sources}")
    set(adapter_dir "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/../../android/src/main/cpp")
    target_sources(llm PRIVATE "${adapter_dir}/mnn_prefill_control.cpp")
    target_include_directories(llm PRIVATE "${source_dir}" "${adapter_dir}")
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
        "${source_dir}/llm.cpp" "${source_dir}/omni.cpp")
endfunction()

if(DEFINED MNN_PREFILL_SOURCE_DIR AND DEFINED MNN_PREFILL_OUTPUT_DIR)
    mnn_engine_prepare_prefill("${MNN_PREFILL_SOURCE_DIR}" "${MNN_PREFILL_OUTPUT_DIR}")
endif()
