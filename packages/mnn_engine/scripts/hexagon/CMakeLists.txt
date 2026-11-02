cmake_minimum_required(VERSION 3.22)
project(mnn_engine_htp C CXX ASM)

# Keep the pinned MNN sources unchanged. The Community SDK omits the old
# utils/examples helper; MNN only needs its domain lookup and weak declaration.
add_subdirectory("${MNN_HTP_SOURCE}" htp)
if(TARGET htp_ops)
    get_target_property(host_sources htp_ops SOURCES)
    set(sdk_helper "${HEXAGON_SDK_ROOT}/utils/examples/dsp_capabilities_utils.c")
    list(FIND host_sources "${sdk_helper}" helper_index)
    if(helper_index EQUAL -1)
        message(FATAL_ERROR "The MNN HTP host source list changed; review the SDK compatibility adapter.")
    endif()
    list(REMOVE_ITEM host_sources "${sdk_helper}")
    set_property(TARGET htp_ops PROPERTY SOURCES "${host_sources}")
    target_sources(htp_ops PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}/dsp_capabilities_utils.c")
    target_include_directories(htp_ops BEFORE PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")
endif()
