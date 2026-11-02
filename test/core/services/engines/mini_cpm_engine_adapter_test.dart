import 'dart:async';

import 'package:flutter_test/flutter_test.dart';
import 'package:servllama/core/models/engine_runtime_state.dart';
import 'package:servllama/core/models/mini_cpm_bundle.dart';
import 'package:servllama/core/models/server_launch_settings.dart';
import 'package:servllama/core/repositories/mini_cpm_model_repository.dart';
import 'package:servllama/core/services/engines/inference_engine_adapter.dart';
import 'package:servllama/core/services/engines/mini_cpm_engine_adapter.dart';
import 'package:servllama/core/services/llama_server_control_client.dart';
import 'package:servllama/core/services/llama_server_service.dart';
import 'package:servllama/core/services/server_launch_settings_loader.dart';

void main() {
  group('MiniCpmEngineAdapter', () {
    test('starts one model-specific process with alias and mmproj', () async {
      final process = _FakeProcessService();
      final control = _FakeControlClient();
      final adapter = _adapter(process: process, control: control);
      final phases = <RuntimePhase>[];

      final result = await adapter.start(modelId: 'alpha', onPhase: phases.add);

      expect(process.startArguments, hasLength(1));
      expect(
        process.startArguments.single,
        containsAllInOrder(<String>[
          '--model',
          '/models/alpha/model.gguf',
          '--alias',
          'alpha',
          '--mmproj',
          '/models/alpha/mmproj.gguf',
        ]),
      );
      expect(control.baseUrl, 'http://127.0.0.1:9000');
      expect(phases, <RuntimePhase>[
        RuntimePhase.verifying,
        RuntimePhase.startingServer,
        RuntimePhase.loadingModel,
      ]);
      expect(result.activeModelId, 'alpha');
      expect(result.activeModelName, 'alpha');
    });

    test(
      'does not start a process when the selected model is missing',
      () async {
        final process = _FakeProcessService();
        final adapter = _adapter(
          process: process,
          control: _FakeControlClient(),
          models: const <MiniCpmModelDescriptor>[],
        );

        await expectLater(
          adapter.start(modelId: 'missing', onPhase: (_) {}),
          throwsA(
            isA<EngineAdapterException>()
                .having(
                  (error) => error.kind,
                  'kind',
                  EngineRuntimeErrorKind.modelLoadFailed,
                )
                .having((error) => error.detail, 'detail', 'missing'),
          ),
        );
        expect(process.startArguments, isEmpty);
      },
    );

    test('stops the process when model readiness fails', () async {
      final process = _FakeProcessService();
      final adapter = _adapter(
        process: process,
        control: _FakeControlClient(ready: false),
      );

      await expectLater(
        adapter.start(modelId: 'alpha', onPhase: (_) {}),
        throwsA(
          isA<EngineAdapterException>().having(
            (error) => error.kind,
            'kind',
            EngineRuntimeErrorKind.modelLoadFailed,
          ),
        ),
      );
      expect(process.stopCount, 1);
      expect(process.isRunning, isFalse);
    });

    test('replaces the process when activating another model', () async {
      final process = _FakeProcessService();
      final adapter = _adapter(process: process, control: _FakeControlClient());
      await adapter.start(modelId: 'alpha', onPhase: (_) {});
      final phases = <RuntimePhase>[];

      final result = await adapter.activateModel('beta', onPhase: phases.add);

      expect(process.stopCount, 1);
      expect(process.startArguments, hasLength(2));
      expect(
        process.startArguments.last,
        containsAllInOrder(<String>[
          '--model',
          '/models/beta/model.gguf',
          '--alias',
          'beta',
        ]),
      );
      expect(phases, <RuntimePhase>[
        RuntimePhase.stoppingServer,
        RuntimePhase.verifying,
        RuntimePhase.startingServer,
        RuntimePhase.loadingModel,
      ]);
      expect(result.activeModelId, 'beta');
    });

    test('cancelling verification settles before retry can launch', () async {
      final process = _FakeProcessService();
      final repository = _BlockingRepository();
      final adapter = MiniCpmEngineAdapter(
        serverService: process,
        settingsLoader: _FixedSettingsLoader(),
        modelRepository: repository,
        controlClient: _FakeControlClient(),
      );
      final first = adapter.start(modelId: 'alpha', onPhase: (_) {});
      final failed = expectLater(
        first,
        throwsA(isA<EngineOperationCancelledException>()),
      );
      await repository.entered.future;
      final cancellation = adapter.cancel(onPhase: (_) {});
      await expectLater(
        adapter.start(modelId: 'beta', onPhase: (_) {}),
        throwsStateError,
      );
      repository.release.complete();
      await cancellation;
      await failed;
      expect(process.startArguments, isEmpty);
      final next = await adapter.start(modelId: 'beta', onPhase: (_) {});
      expect(next.activeModelId, 'beta');
      expect(process.startArguments, hasLength(1));
    });

    test('cancellation stops a partially started process', () async {
      final process = _FakeProcessService();
      final readiness = Completer<bool>();
      final adapter = _adapter(
        process: process,
        control: _FakeControlClient(readiness: readiness),
      );
      final startFuture = adapter.start(modelId: 'alpha', onPhase: (_) {});
      await Future<void>.delayed(Duration.zero);

      final cancelling = adapter.cancel(onPhase: (_) {});
      readiness.complete(false);

      await expectLater(
        startFuture,
        throwsA(isA<EngineOperationCancelledException>()),
      );
      await cancelling;
      expect(process.stopCount, 1);
      expect(process.isRunning, isFalse);
    });
  });
}

MiniCpmEngineAdapter _adapter({
  required _FakeProcessService process,
  required _FakeControlClient control,
  List<MiniCpmModelDescriptor>? models,
}) {
  return MiniCpmEngineAdapter(
    serverService: process,
    settingsLoader: _FixedSettingsLoader(),
    modelRepository: _FakeModelRepository(
      models ??
          <MiniCpmModelDescriptor>[
            _model('alpha', vision: true),
            _model('beta'),
          ],
    ),
    controlClient: control,
  );
}

MiniCpmModelDescriptor _model(String name, {bool vision = false}) =>
    MiniCpmModelDescriptor(
      id: name,
      modelName: name,
      sizeBytes: 1,
      storedDirectoryPath: '/models/$name',
      storedFilePath: '/models/$name/model.gguf',
      importedAt: DateTime(2026),
      mmprojFilePath: '/models/$name/mmproj.gguf',
      expertsDirectoryPath: '/models/$name/experts',
      visionEnabled: vision,
    );

class _FakeModelRepository extends MiniCpmModelRepository {
  _FakeModelRepository(this.models);

  final List<MiniCpmModelDescriptor> models;

  @override
  Future<List<MiniCpmModelDescriptor>> listModels() async => models;

  @override
  Future<void> validateForLoad(
    MiniCpmModelDescriptor model, {
    bool Function()? shouldContinue,
  }) async {}
}

class _FixedSettingsLoader extends ServerLaunchSettingsLoader {
  @override
  Future<ServerLaunchSettings> load() async =>
      const ServerLaunchSettings(port: 9000);
}

class _FakeProcessService implements LlamaServerProcessService {
  final StreamController<bool> _running = StreamController<bool>.broadcast();
  final List<List<String>> startArguments = <List<String>>[];
  bool _isRunning = false;
  int stopCount = 0;

  @override
  bool get isRunning => _isRunning;

  @override
  Stream<bool> get runningStateStream => _running.stream;

  @override
  void initForegroundTask() {}

  @override
  Future<bool> startServer({List<String>? args}) async {
    startArguments.add(List<String>.of(args ?? const <String>[]));
    _isRunning = true;
    _running.add(true);
    return true;
  }

  @override
  Future<bool> stopServer() async {
    stopCount += 1;
    _isRunning = false;
    _running.add(false);
    return true;
  }
}

class _FakeControlClient implements LlamaServerControlClient {
  _FakeControlClient({this.ready = true, this.readiness});

  final bool ready;
  final Completer<bool>? readiness;
  String? baseUrl;

  @override
  void updateBaseUrl(String value) {
    baseUrl = value;
  }

  @override
  Future<bool> waitUntilReady({
    Duration timeout = LlamaServerControlClient.serverReadyTimeout,
    bool Function()? shouldContinue,
  }) async {
    final result = await (readiness?.future ?? Future<bool>.value(ready));
    return result && (shouldContinue?.call() ?? true);
  }
}

class _BlockingRepository extends _FakeModelRepository {
  _BlockingRepository() : super([_model('alpha'), _model('beta')]);
  final entered = Completer<void>();
  final release = Completer<void>();
  @override
  Future<void> validateForLoad(
    MiniCpmModelDescriptor model, {
    bool Function()? shouldContinue,
  }) async {
    if (!entered.isCompleted) {
      entered.complete();
      await release.future;
    }
    if (!(shouldContinue?.call() ?? true)) {
      throw const EngineOperationCancelledException();
    }
  }
}
