# Apply plugin-owned runtime integrations after MNN defines its targets.
# CMAKE_PROJECT_MNN_INCLUDE avoids editing the pinned upstream submodule.
option(MNN_ENGINE_LOG_BRIDGE "Capture MNN logs before Android filtering" ON)
option(MNN_ENGINE_TOKENIZER_ADDED_TOKENS "Preserve MTOK added tokens during LLM generation" ON)
option(MNN_ENGINE_PREFILL_CANCELLATION "Check cancellation between prefill chunks" ON)
include("${CMAKE_CURRENT_LIST_DIR}/mnn_tokenizer_compat.cmake")
include("${CMAKE_CURRENT_LIST_DIR}/mnn_prefill_compat.cmake")

function(mnn_engine_attach_log_bridge)
    if(NOT CMAKE_SYSTEM_NAME STREQUAL "Android" OR NOT MNN_BUILD_SHARED_LIBS)
        message(FATAL_ERROR "The MNN native log bridge requires an Android shared-library build.")
    endif()
    target_sources(MNN PRIVATE
        "${CMAKE_CURRENT_FUNCTION_LIST_DIR}/../../android/src/main/cpp/mnn_log_bridge.cpp")
    target_link_options(MNN PRIVATE "-Wl,--wrap=__android_log_print")
endfunction()

if(MNN_ENGINE_LOG_BRIDGE)
    cmake_language(DEFER CALL mnn_engine_attach_log_bridge)
endif()
if(MNN_ENGINE_TOKENIZER_ADDED_TOKENS)
    cmake_language(DEFER CALL mnn_engine_attach_tokenizer_fix)
endif()
if(MNN_ENGINE_PREFILL_CANCELLATION)
    cmake_language(DEFER CALL mnn_engine_attach_prefill_fix)
endif()
