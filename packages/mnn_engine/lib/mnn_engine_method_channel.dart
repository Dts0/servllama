import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import 'mnn_engine_platform_interface.dart';
import 'src/mnn_engine_exception.dart';
import 'src/mnn_models.dart';

class MethodChannelMnnEngine extends MnnEnginePlatform {
  @visibleForTesting
  final methodChannel = const MethodChannel(
    'com.arkanefans.mnn_engine/methods',
  );

  @visibleForTesting
  final eventChannel = const EventChannel('com.arkanefans.mnn_engine/events');

  @visibleForTesting
  final logChannel = const EventChannel('com.arkanefans.mnn_engine/logs');

  Future<T> _invoke<T>(String method, [Map<String, Object?>? arguments]) async {
    try {
      final value = await methodChannel.invokeMethod<Object?>(
        method,
        arguments,
      );
      return value as T;
    } on PlatformException catch (error) {
      throw MnnEngineException.fromPlatformException(error);
    }
  }

  Future<void> _invokeVoid(
    String method, [
    Map<String, Object?>? arguments,
  ]) async {
    await _invoke<Object?>(method, arguments);
  }

  @override
  Future<MnnEngineInfo> initialize() async =>
      MnnEngineInfo.fromMap(await _invoke<Object?>('initialize'));

  @override
  Future<MnnRuntimeSnapshot> getSnapshot() async =>
      MnnRuntimeSnapshot.fromMap(await _invoke<Object?>('getSnapshot'));

  @override
  Future<List<MnnBackendCapability>> getBackendCapabilities() async {
    final values = await _invoke<List<Object?>>('getBackendCapabilities');
    return values.map(MnnBackendCapability.fromMap).toList(growable: false);
  }

  @override
  Future<String> getTestRootPath() => _invoke<String>('getTestRootPath');

  @override
  Future<List<MnnModelInfo>> listImportedModels() async {
    final values = await _invoke<List<Object?>>('listImportedModels');
    return values.map(MnnModelInfo.fromMap).toList(growable: false);
  }

  @override
  Future<MnnModelInfo> importModelDirectory({
    required bool replaceExisting,
  }) async => MnnModelInfo.fromMap(
    await _invoke<Object?>('importModelDirectory', {
      'replaceExisting': replaceExisting,
    }),
  );

  @override
  Future<MnnModelImportResult> importModelDirectoryWithResult({
    required bool replaceExisting,
    required bool autoRename,
    required List<String> unavailableNames,
  }) async => MnnModelImportResult.fromMap(
    await _invoke<Object?>('importModelDirectoryWithResult', {
      'replaceExisting': replaceExisting,
      'autoRename': autoRename,
      'unavailableNames': unavailableNames,
    }),
  );

  @override
  Future<MnnModelInfo> importModelFromPath(
    String directoryPath, {
    required bool replaceExisting,
  }) async => MnnModelInfo.fromMap(
    await _invoke<Object?>('importModelFromPath', {
      'directoryPath': directoryPath,
      'replaceExisting': replaceExisting,
    }),
  );

  @override
  Future<MnnModelImportResult> importModelFromPathWithResult(
    String directoryPath, {
    required bool replaceExisting,
    required bool autoRename,
    required List<String> unavailableNames,
  }) async => MnnModelImportResult.fromMap(
    await _invoke<Object?>('importModelFromPathWithResult', {
      'directoryPath': directoryPath,
      'replaceExisting': replaceExisting,
      'autoRename': autoRename,
      'unavailableNames': unavailableNames,
    }),
  );

  @override
  Future<void> deleteImportedModel(String modelId) =>
      _invokeVoid('deleteImportedModel', {'modelId': modelId});

  @override
  Future<MnnModelInfo> renameImportedModel(
    String modelId,
    String newName,
  ) async => MnnModelInfo.fromMap(
    await _invoke<Object?>('renameImportedModel', {
      'modelId': modelId,
      'newName': newName,
    }),
  );

  @override
  Future<MnnModelInfo> loadModel(
    String modelId, {
    MnnLoadOptions options = const MnnLoadOptions(),
  }) async => MnnModelInfo.fromMap(
    await _invoke<Object?>('loadModel', {
      'modelId': modelId,
      'options': options.toMap(),
    }),
  );

  @override
  Future<void> unloadModel() => _invokeVoid('unloadModel');

  @override
  Future<MnnMmapCacheInfo> getMmapCache({String? modelId}) async =>
      MnnMmapCacheInfo.fromMap(
        await _invoke<Object?>('getMmapCache', {'modelId': modelId}),
      );

  @override
  Future<MnnMmapCacheInfo> clearMmapCache({String? modelId}) async =>
      MnnMmapCacheInfo.fromMap(
        await _invoke<Object?>('clearMmapCache', {'modelId': modelId}),
      );

  @override
  Future<MnnPortCheckResult> checkPort({
    required MnnServerBindMode bindMode,
    required int port,
  }) async => MnnPortCheckResult.fromMap(
    await _invoke<Object?>('checkPort', {
      'bindMode': bindMode.name,
      'port': port,
    }),
  );

  @override
  Future<MnnServerInfo> startServer({
    required MnnServerBindMode bindMode,
    required int port,
    String? apiKey,
  }) async => MnnServerInfo.fromMap(
    await _invoke<Object?>('startServer', {
      'bindMode': bindMode.name,
      'port': port,
      'apiKey': apiKey,
    }),
  );

  @override
  Future<void> stopServer() => _invokeVoid('stopServer');

  @override
  Future<void> cancelGeneration() => _invokeVoid('cancelGeneration');

  @override
  Future<List<MnnLogEntry>> getLogSnapshot() async {
    final values = await _invoke<List<Object?>>('getLogSnapshot');
    return values.map(MnnLogEntry.fromMap).toList(growable: false);
  }

  @override
  Future<void> clearLogs() => _invokeVoid('clearLogs');

  late final Stream<MnnRuntimeEvent> _events = eventChannel
      .receiveBroadcastStream()
      .map(MnnRuntimeEvent.fromMap);

  @override
  Stream<MnnRuntimeEvent> get events => _events;

  late final Stream<MnnLogEntry> _logs = logChannel
      .receiveBroadcastStream()
      .map(MnnLogEntry.fromMap);

  @override
  Stream<MnnLogEntry> get logs => _logs;
}
