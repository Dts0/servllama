/// The inference backends the app can run. Exactly one is active at a time
/// (design decision D1) and the active one owns the configured port.
enum InferenceEngine {
  llamaCpp,
  mnn,
  miniCpm;

  static InferenceEngine fromStorageValue(String? value) {
    for (final engine in InferenceEngine.values) {
      if (engine.storageValue == value) {
        return engine;
      }
    }
    return InferenceEngine.llamaCpp;
  }

  String get storageValue {
    switch (this) {
      case InferenceEngine.llamaCpp:
        return 'llama_cpp';
      case InferenceEngine.mnn:
        return 'mnn';
      case InferenceEngine.miniCpm:
        return 'mini_cpm';
    }
  }

  /// Engine name as shown to users. These are product names, so they are the
  /// same in every locale and intentionally live outside the ARB files.
  String get displayName {
    switch (this) {
      case InferenceEngine.llamaCpp:
        return 'llama.cpp';
      case InferenceEngine.mnn:
        return 'MNN';
      case InferenceEngine.miniCpm:
        return 'MiniCPM / LES';
    }
  }

  /// Model container shape: GGUF ships as a single file, MNN and LES as directories.
  bool get usesDirectoryModels => this != InferenceEngine.llamaCpp;

  bool get isLlamaFamily => this != InferenceEngine.mnn;
}
