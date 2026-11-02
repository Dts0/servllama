import 'dart:async';

import 'package:servllama/core/models/engine_runtime_state.dart';
import 'package:servllama/core/models/inference_engine.dart';
import 'package:servllama/core/models/mini_cpm_bundle.dart';
import 'package:servllama/core/repositories/mini_cpm_model_repository.dart';
import 'package:servllama/core/services/engines/inference_engine_adapter.dart';
import 'package:servllama/core/services/llama_server_control_client.dart';
import 'package:servllama/core/services/llama_server_service.dart';
import 'package:servllama/core/services/mini_cpm_launch_args_builder.dart';
import 'package:servllama/core/services/server_launch_settings_loader.dart';

/// Runs one isolated MiniCPM / LES CPU HTTP server process. Since Android builds do not
/// support llama.cpp's subprocess router, changing models means replacing the
/// process with one launched using the new model path.
class MiniCpmEngineAdapter implements InferenceEngineAdapter {
  MiniCpmEngineAdapter({
    LlamaServerProcessService? serverService,
    ServerLaunchSettingsLoader? settingsLoader,
    MiniCpmLaunchArgsBuilder? launchArgsBuilder,
    MiniCpmModelRepository? modelRepository,
    LlamaServerControlClient? controlClient,
  }) : _serverService = serverService ?? LlamaServerService.miniCpm(),
       _settingsLoader = settingsLoader ?? ServerLaunchSettingsLoader(),
       _launchArgsBuilder =
           launchArgsBuilder ?? const MiniCpmLaunchArgsBuilder(),
       _modelRepository = modelRepository ?? MiniCpmModelRepository(),
       _controlClient = controlClient ?? LlamaServerControlClient();

  final LlamaServerProcessService _serverService;
  final ServerLaunchSettingsLoader _settingsLoader;
  final MiniCpmLaunchArgsBuilder _launchArgsBuilder;
  final MiniCpmModelRepository _modelRepository;
  final LlamaServerControlClient _controlClient;
  int _generation = 0;
  Future<EngineStartResult>? _pendingStart;

  @override
  InferenceEngine get engine => InferenceEngine.miniCpm;

  @override
  bool get isRunning => _serverService.isRunning;

  @override
  bool get hasResources => _pendingStart != null || _serverService.isRunning;

  @override
  Stream<bool> get runningStateStream => _serverService.runningStateStream;

  @override
  Future<void> prepare() async {
    _serverService.initForegroundTask();
  }

  @override
  Future<EngineStartResult> start({
    required String modelId,
    required RuntimePhaseCallback onPhase,
  }) async {
    if (_pendingStart != null) {
      throw StateError('A MiniCPM start is already in progress');
    }
    final generation = ++_generation;
    final pending = _startInternal(
      modelId: modelId,
      onPhase: onPhase,
      generation: generation,
    );
    _pendingStart = pending;
    try {
      return await pending;
    } finally {
      if (identical(_pendingStart, pending)) _pendingStart = null;
    }
  }

  Future<EngineStartResult> _startInternal({
    required int generation,
    required String modelId,
    required RuntimePhaseCallback onPhase,
  }) async {
    try {
      await prepare();
      final model = await _findModel(modelId);
      _throwIfCancelled(generation);
      final settings = await _settingsLoader.loadForRuntime();
      _throwIfCancelled(generation);
      onPhase(RuntimePhase.verifying);
      await _modelRepository.validateForLoad(
        model,
        shouldContinue: () => generation == _generation,
      );
      _throwIfCancelled(generation);

      onPhase(RuntimePhase.startingServer);
      final started = await _serverService.startServer(
        args: _launchArgsBuilder.build(
          settings,
          modelPath: model.storedFilePath,
          modelAlias: modelId,
          mmprojPath: model.activeMmprojFilePath,
          expertsPath: model.expertsDirectoryPath,
        ),
      );
      _throwIfCancelled(generation);
      if (!started) {
        throw const EngineAdapterException(
          EngineRuntimeErrorKind.serverStartFailed,
        );
      }

      _controlClient.updateBaseUrl('http://127.0.0.1:${settings.port}');

      onPhase(RuntimePhase.loadingModel);
      final ready = await _controlClient.waitUntilReady(
        shouldContinue: () =>
            generation == _generation && _serverService.isRunning,
      );
      _throwIfCancelled(generation);
      if (!ready) {
        throw EngineAdapterException(
          _serverService.isRunning
              ? EngineRuntimeErrorKind.modelLoadFailed
              : EngineRuntimeErrorKind.serverStartFailed,
          detail: modelId,
        );
      }

      return EngineStartResult(
        host: settings.host,
        port: settings.port,
        activeModelId: modelId,
        activeModelName: model.modelName,
      );
    } catch (_) {
      if (_serverService.isRunning) {
        await _serverService.stopServer();
      }
      _throwIfCancelled(generation);
      rethrow;
    }
  }

  @override
  Future<void> stop({required RuntimePhaseCallback onPhase}) async {
    if (!_serverService.isRunning) {
      return;
    }
    onPhase(RuntimePhase.stoppingServer);
    final stopped = await _serverService.stopServer();
    if (!stopped) {
      throw const EngineAdapterException(
        EngineRuntimeErrorKind.serverStopFailed,
      );
    }
  }

  @override
  Future<EngineStartResult> activateModel(
    String modelId, {
    required RuntimePhaseCallback onPhase,
  }) async {
    if (_pendingStart != null) {
      throw StateError('A MiniCPM start is already in progress');
    }
    final generation = ++_generation;
    if (_serverService.isRunning) {
      onPhase(RuntimePhase.stoppingServer);
      final stopped = await _serverService.stopServer();
      if (!stopped) {
        throw const EngineAdapterException(
          EngineRuntimeErrorKind.serverStopFailed,
        );
      }
      _throwIfCancelled(generation);
    }
    return start(modelId: modelId, onPhase: onPhase);
  }

  @override
  Future<void> cancel({required RuntimePhaseCallback onPhase}) async {
    _generation += 1;
    // Hold the runtime lease until verification / process launch has settled.
    // A process may become alive after cancellation raced with startServer.
    final pending = _pendingStart;
    if (pending != null) {
      try {
        await pending;
      } catch (_) {
        /* The caller observes the start error. */
      }
    }
    if (_serverService.isRunning) {
      onPhase(RuntimePhase.stoppingServer);
      await _serverService.stopServer();
    }
  }

  void _throwIfCancelled(int generation) {
    if (generation != _generation) {
      throw const EngineOperationCancelledException();
    }
  }

  Future<MiniCpmModelDescriptor> _findModel(String modelId) async {
    final model = await _modelRepository.findByRuntimeId(modelId);
    if (model != null) return model;
    throw EngineAdapterException(
      EngineRuntimeErrorKind.modelLoadFailed,
      detail: modelId,
    );
  }

  @override
  void dispose() {}
}
