#pragma once

using MnnNativeLogSink = void (*)(int priority, const char* tag, const char* message);

// C ABI between libMNN.so and the JNI adapter; no C++ objects cross this boundary.
extern "C" __attribute__((visibility("default")))
void mnn_engine_set_native_log_sink(MnnNativeLogSink sink);
