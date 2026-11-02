# Per-response native timings (ServLlama integration)

Successful `POST /v1/chat/completions` responses optionally include a top-level
`timings` object. In streaming mode it appears only on the final completion
chunk, next to `usage` and the choice's `finish_reason`, before `[DONE]`. The
completion ID stays unchanged throughout that stream. Measurements belong to
that request's native result, never a global "last generation" snapshot or logs.

The numeric field names match llama-server:

| Field | Unit / meaning |
| --- | --- |
| `prompt_n` | MNN's native prompt token count |
| `prompt_ms` | `prefill_us / 1000` |
| `prompt_per_second` | `prompt_n * 1e6 / prefill_us` (tokens/s) |
| `predicted_n` | MNN's native generated token count |
| `predicted_ms` | `decode_us / 1000` |
| `predicted_per_second` | `predicted_n * 1e6 / decode_us` (tokens/s) |

The object also records `backend: "mnn"`, `prompt_tokens_scope: "total_input"`,
`predicted_tokens_scope: "native_generated"`, and
`decode_time_scope: "native_decode"`. There is no `cache_n`: the
bridge has no independent cached/actually-processed prompt counter. These
measurements preserve MNN's native definition, including its generated-token
count, rather than estimating from output characters, SSE chunks, or TTFT.
In particular, native AR decode time excludes sampling; speculative strategies
can use different timing boundaries. These native metrics are not end-to-end
wall-clock throughput. Vision/audio preparation and transport are not claimed
to be part of the displayed phase speeds.

Only native `stop` and `length` results qualify. Each phase requires both a
positive token count and positive duration. An invalid phase's three numeric
fields are omitted; if neither phase is valid, `timings` itself is omitted.
Cancellation, errors, and unknown finish states never publish timings. This
matters for cancelled prefill: MNN may retain the full prompt count while its
timer covers only completed chunks. Consumers must display unavailable, not
zero, when fields are absent. Existing OpenAI `usage` token counts are retained:
`prompt_tokens` is native `prompt_len` (total input under this integration,
including model template/multimodal representation), and `completion_tokens`
is native `gen_seq_len`. `usage.prompt_tokens_details` explicitly contains
`cached_tokens: null` and `cache_status: "unavailable"`. The adapter resets
history before each request, but does not export an independently measured
cache-hit counter; clients must not turn this unknown count into zero.

Regression coverage: `MnnGenerationTimingsTest` checks conversion/eligibility;
`MnnOpenAiServerTimingsTest` checks real CIO HTTP/SSE responses, stable stream IDs,
request isolation, existing content/usage, cancellation, and failure behavior.
The Gradle unit-test JVM preloads Mockito's already-resolved Byte Buddy agent;
it does not depend on permission to attach to a running JVM. This test-only
configuration does not change packaged Android code or permissions.

After configuring the host Flutter/Android environment and packaging the native
artifacts, run from the ServLlama root:

```sh
cd android
./gradlew :mnn_engine:testDebugUnitTest
```
