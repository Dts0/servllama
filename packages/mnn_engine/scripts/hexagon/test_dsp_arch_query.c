#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include <remote.h>
#include <AEEStdErr.h>

int mnn_engine_query_hexagon_arch(int *architecture);

#ifndef MNN_TEST_MISSING_CONTROL
static uint32_t device_arch;
static int query_status;
static int calls;

int remote_handle_control(uint32_t request, void *data, uint32_t size) {
    assert(request == DSPRPC_GET_DSP_INFO);
    assert(size == sizeof(struct remote_dsp_capability));
    struct remote_dsp_capability *info = data;
    assert(info->domain == CDSP_DOMAIN_ID);
    assert(info->attribute_ID == ARCH_VER);
    assert(info->capability == 0);
    info->capability = device_arch;
    ++calls;
    return query_status;
}
#endif

int main(void) {
    int architecture = 999;
    assert(mnn_engine_query_hexagon_arch(NULL) == AEE_EBADPARM);
#ifdef MNN_TEST_MISSING_CONTROL
    assert(mnn_engine_query_hexagon_arch(&architecture) == AEE_EUNSUPPORTEDAPI);
    assert(architecture == 0);
#else
    const unsigned int versions[] = {0x73, 0x75, 0x79, 0x81, 0x68, 0x85};
    const int expected[] = {73, 75, 79, 81, 68, 85};
    for (unsigned int i = 0; i < sizeof(versions) / sizeof(versions[0]); ++i) {
        device_arch = 0x8000 | versions[i];
        assert(mnn_engine_query_hexagon_arch(&architecture) == AEE_SUCCESS);
        assert(architecture == expected[i]);
    }
    for (unsigned int invalid = 0; invalid < 3; ++invalid) {
        device_arch = invalid == 0 ? 0 : (invalid == 1 ? 0x7a : 0xa3);
        assert(mnn_engine_query_hexagon_arch(&architecture) == AEE_EBADPARM);
        assert(architecture == 0);
    }
    query_status = AEE_EUNSUPPORTEDAPI;
    assert(mnn_engine_query_hexagon_arch(&architecture) == query_status);
    assert(architecture == 0);
    assert(calls == 10);
#endif
    puts("Hexagon architecture query tests passed (SDK headers, simulated FastRPC).");
    return 0;
}
