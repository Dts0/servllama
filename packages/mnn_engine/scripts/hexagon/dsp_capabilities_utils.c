#include <stdbool.h>
#include <stddef.h>
#include <domain_default.h>
#include <AEEStdErr.h>

#include "dsp_capabilities_utils.h"

domain *get_domain(int domain_id) {
    for (size_t index = 0; index < sizeof(supported_domains) / sizeof(supported_domains[0]); ++index) {
        if (supported_domains[index].id == domain_id) {
            return &supported_domains[index];
        }
    }
    return NULL;
}

int mnn_engine_query_hexagon_arch(int *architecture) {
    if (!architecture) return AEE_EBADPARM;
    *architecture = 0;
    if (!remote_handle_control) return AEE_EUNSUPPORTEDAPI;
    struct remote_dsp_capability info = {CDSP_DOMAIN_ID, ARCH_VER, 0};
    const int status = remote_handle_control(DSPRPC_GET_DSP_INFO, &info, sizeof(info));
    if (status != AEE_SUCCESS) return status;
    // ARCH_VER's low byte is BCD (0x79 means v79), not a decimal integer.
    const unsigned int encoded = info.capability & 0xff;
    if (encoded == 0 || (encoded >> 4) > 9 || (encoded & 0xf) > 9) return AEE_EBADPARM;
    *architecture = (int)((encoded >> 4) * 10 + (encoded & 0xf));
    return AEE_SUCCESS;
}
