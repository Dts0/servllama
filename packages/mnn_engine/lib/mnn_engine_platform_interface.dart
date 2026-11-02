import 'package:plugin_platform_interface/plugin_platform_interface.dart';

import 'mnn_engine_method_channel.dart';
import 'src/mnn_models.dart';

abstract class MnnEnginePlatform extends PlatformInterface {
  MnnEnginePlatform() : super(token: _token);

  static final Object _token = Object();
  static MnnEnginePlatform _instance = MethodChannelMnnEngine();

  static MnnEnginePlatform get instance => _instance;

  static set instance(MnnEnginePlatform instance) {
    PlatformInterface.verifyToken(instance, _token);
    _instance = instance;
  }

  Future<MnnEngineInfo> initialize();
  Future<MnnRuntimeSnapshot> getSnapshot();
  Future<List<MnnBackendCapability>> getBackendCapabilities();
  Future<String> getTestRootPath();
  Future<List<MnnModelInfo>> listImportedModels();
  Future<MnnModelInfo> importModelDirectory({required bool replaceExisting});
  Future<MnnModelImportResult> importModelDirectoryWithResult({
    required bool replaceExisting,
    required bool autoRename,
    required List<String> unavailableNames,
  });

  /// Imports a model directory the app already owns (e.g. a finished
  /// download in private storage) without going through the SAF picker.
  Future<MnnModelInfo> importModelFromPath(
    String directoryPath, {
    required bool replaceExisting,
  });
  Future<MnnModelImportResult> importModelFromPathWithResult(
    String directoryPath, {
    required bool replaceExisting,
    required bool autoRename,
    required List<String> unavailableNames,
  });
  Future<void> deleteImportedModel(String modelId);
  Future<MnnModelInfo> renameImportedModel(String modelId, String newName);
  Future<MnnModelInfo> loadModel(
    String modelId, {
    MnnLoadOptions options = const MnnLoadOptions(),
  });
  Future<void> unloadModel();
  Future<MnnMmapCacheInfo> getMmapCache({String? modelId});
  Future<MnnMmapCacheInfo> clearMmapCache({String? modelId});

  /// Performs an advisory bind probe without starting the server. It is useful
  /// for diagnostics only; callers should still handle `port_in_use` from
  /// [startServer], because a probe and a later bind are not atomic.
  Future<MnnPortCheckResult> checkPort({
    required MnnServerBindMode bindMode,
    required int port,
  });
  Future<MnnServerInfo> startServer({
    required MnnServerBindMode bindMode,
    required int port,
    String? apiKey,
  });
  Future<void> stopServer();
  Future<void> cancelGeneration();
  Future<List<MnnLogEntry>> getLogSnapshot();
  Future<void> clearLogs();
  Stream<MnnRuntimeEvent> get events;
  Stream<MnnLogEntry> get logs;
}
