#pragma once

#include <remote.h>

// Preserve MNN's check for devices without this optional FastRPC entry point.
#pragma weak remote_session_control
#pragma weak remote_handle_control

domain *get_domain(int domain_id);

// Query the cDSP ISA before selecting or opening a DSP skeleton.
__attribute__((visibility("default"))) int mnn_engine_query_hexagon_arch(int *architecture);
