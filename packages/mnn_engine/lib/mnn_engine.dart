library;

export 'src/mnn_engine_exception.dart';
export 'src/mnn_models.dart';

import 'mnn_engine_platform_interface.dart';
import 'src/mnn_models.dart';

class MnnEngine {
  MnnEngine._();

  static final MnnEngine instance = MnnEngine._();

  Future<MnnEngineInfo> initialize() => MnnEnginePlatform.instance.initialize();

  Future<MnnRuntimeSnapshot> getSnapshot() =>
      MnnEnginePlatform.instance.getSnapshot();

  Future<List<MnnBackendCapability>> getBackendCapabilities() =>
      MnnEnginePlatform.instance.getBackendCapabilities();

  Future<String> getTestRootPath() =>
      MnnEnginePlatform.instance.getTestRootPath();

  Future<List<MnnModelInfo>> listImportedModels() =>
      MnnEnginePlatform.instance.listImportedModels();

  Future<MnnModelInfo> importModelDirectory({bool replaceExisting = true}) =>
      MnnEnginePlatform.instance.importModelDirectory(
        replaceExisting: replaceExisting,
      );

  Future<MnnModelImportResult> importModelDirectoryWithResult({
    bool replaceExisting = false,
    bool autoRename = true,
    List<String> unavailableNames = const <String>[],
  }) => MnnEnginePlatform.instance.importModelDirectoryWithResult(
    replaceExisting: replaceExisting,
    autoRename: autoRename,
    unavailableNames: unavailableNames,
  );

  /// Imports a model directory the app already owns (e.g. a finished
  /// download in private storage) without going through the SAF picker.
  Future<MnnModelInfo> importModelFromPath(
    String directoryPath, {
    bool replaceExisting = true,
  }) => MnnEnginePlatform.instance.importModelFromPath(
    directoryPath,
    replaceExisting: replaceExisting,
  );

  Future<MnnModelImportResult> importModelFromPathWithResult(
    String directoryPath, {
    bool replaceExisting = false,
    bool autoRename = true,
    List<String> unavailableNames = const <String>[],
  }) => MnnEnginePlatform.instance.importModelFromPathWithResult(
    directoryPath,
    replaceExisting: replaceExisting,
    autoRename: autoRename,
    unavailableNames: unavailableNames,
  );

  Future<void> deleteImportedModel(String modelId) =>
      MnnEnginePlatform.instance.deleteImportedModel(modelId);

  /// Renames the model directory and therefore changes its runtime model ID.
  /// The model and API server must be stopped before this operation.
  Future<MnnModelInfo> renameImportedModel(String modelId, String newName) =>
      MnnEnginePlatform.instance.renameImportedModel(modelId, newName);

  Future<MnnModelInfo> loadModel(
    String modelId, {
    MnnLoadOptions options = const MnnLoadOptions(),
  }) => MnnEnginePlatform.instance.loadModel(modelId, options: options);

  Future<void> unloadModel() => MnnEnginePlatform.instance.unloadModel();

  /// Reports the size of generated mmap and GPU runtime caches.
  /// Pass [modelId] to inspect one imported model; omit it for the total.
  Future<MnnMmapCacheInfo> getMmapCache({String? modelId}) =>
      MnnEnginePlatform.instance.getMmapCache(modelId: modelId);

  /// Deletes generated mmap and GPU runtime caches.
  /// The model must not be loaded. Pass [modelId] to clear one imported model;
  /// omit it to clear every model's cache.
  Future<MnnMmapCacheInfo> clearMmapCache({String? modelId}) =>
      MnnEnginePlatform.instance.clearMmapCache(modelId: modelId);

  /// Performs an advisory port probe. The authoritative result is the bind
  /// performed by [startServer], whose `port_in_use` error should be handled
  /// by callers.
  Future<MnnPortCheckResult> checkPort({
    MnnServerBindMode bindMode = MnnServerBindMode.loopback,
    required int port,
  }) => MnnEnginePlatform.instance.checkPort(bindMode: bindMode, port: port);

  Future<MnnServerInfo> startServer({
    MnnServerBindMode bindMode = MnnServerBindMode.loopback,
    int port = 8081,
    String? apiKey,
  }) => MnnEnginePlatform.instance.startServer(
    bindMode: bindMode,
    port: port,
    apiKey: apiKey,
  );

  /// Stops HTTP admission and waits for admitted native work to return.
  /// On failure, retry stopping before unloading or starting another server.
  /// The resident model is released separately by [unloadModel].
  Future<void> stopServer() => MnnEnginePlatform.instance.stopServer();

  /// Requests cancellation of the current native generation only.
  /// HTTP clients cancel by closing their request; they do not call this API.
  /// Completion acknowledges the signal, not the end of native execution.
  Future<void> cancelGeneration() =>
      MnnEnginePlatform.instance.cancelGeneration();

  Future<List<MnnLogEntry>> getLogSnapshot() =>
      MnnEnginePlatform.instance.getLogSnapshot();

  Future<void> clearLogs() => MnnEnginePlatform.instance.clearLogs();

  Stream<MnnRuntimeEvent> get events => MnnEnginePlatform.instance.events;

  Stream<MnnLogEntry> get logs => MnnEnginePlatform.instance.logs;
}
