import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:mnn_engine/mnn_engine.dart'
    show
        MnnEngineException,
        MnnServerBindMode,
        MnnBackend,
        MnnBackendStatus,
        MnnLoadOptions,
        MnnPrecision;
import 'package:mnn_engine/mnn_engine_method_channel.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  final platform = MethodChannelMnnEngine();
  const channel = MethodChannel('com.arkanefans.mnn_engine/methods');

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('initialize decodes native engine information', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'initialize');
          return <String, Object?>{
            'pluginVersion': '0.0.1',
            'mnnVersion': '3.6.0',
            'mnnCommit': 'cc20f672',
            'abi': 'arm64-v8a',
            'androidApiLevel': 35,
            'ndkVersion': '27.3.13750724',
            'nativeLibraryLoaded': true,
            'testRootPath': '/data/user/0/app/files/mnn_test',
          };
        });

    final info = await platform.initialize();

    expect(info.mnnVersion, '3.6.0');
    expect(info.mnnCommit, 'cc20f672');
    expect(info.nativeLibraryLoaded, isTrue);
  });

  test('checkPort sends host and port', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'checkPort');
          expect(call.arguments, <String, Object?>{
            'bindMode': 'loopback',
            'port': 8081,
          });
          return <String, Object?>{'available': true, 'ownedByMnn': false};
        });

    final result = await platform.checkPort(
      bindMode: MnnServerBindMode.loopback,
      port: 8081,
    );

    expect(result.available, isTrue);
    expect(result.ownedByMnn, isFalse);
  });

  test('allows allInterfaces start without an API key', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'startServer');
          expect(call.arguments, <String, Object?>{
            'bindMode': 'allInterfaces',
            'port': 8081,
            'apiKey': null,
          });
          return <String, Object?>{
            'running': true,
            'host': '0.0.0.0',
            'bindMode': 'allInterfaces',
            'bindAddress': '0.0.0.0',
            'port': 8081,
            'baseUrl': 'http://127.0.0.1:8081',
            'localBaseUrl': 'http://127.0.0.1:8081',
            'advertisedUrls': <String>['http://192.168.1.2:8081'],
            'requiresApiKey': false,
          };
        });

    final server = await platform.startServer(
      bindMode: MnnServerBindMode.allInterfaces,
      port: 8081,
    );

    expect(server.bindMode, MnnServerBindMode.allInterfaces);
    expect(server.bindAddress, '0.0.0.0');
    expect(server.requiresApiKey, isFalse);
  });

  test('loadModel decodes native load duration', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'loadModel');
          expect(call.arguments, <String, Object?>{
            'modelId': 'qwen',
            'options': {
              'backend': 'cpu',
              'useMmap': false,
              'precision': 'low',
              'threadNum': 4,
            },
          });
          return <String, Object?>{
            'modelId': 'qwen',
            'modelKey': 'qwen',
            'displayName': 'Qwen',
            'modelDirPath': '/models/qwen',
            'configPath': '/models/qwen/config.json',
            'sizeBytes': 100,
            'importedAt': 1,
            'isActive': true,
            'loadDurationMs': 1234,
          };
        });

    final model = await platform.loadModel('qwen');

    expect(model.loadDurationMs, 1234);
  });

  test(
    'forwards each explicit backend and decodes the resident backend',
    () async {
      for (final backend in MnnBackend.values) {
        TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(channel, (call) async {
              expect(call.method, 'loadModel');
              expect(call.arguments, {
                'modelId': 'qwen',
                'options': {
                  'backend': backend.name,
                  'useMmap': false,
                  'precision': 'low',
                  'threadNum': 4,
                },
              });
              return {
                'modelId': 'qwen',
                'backend': backend.name,
                'isActive': true,
              };
            });
        final model = await platform.loadModel(
          'qwen',
          options: MnnLoadOptions(backend: backend),
        );
        expect(model.backend, backend);
      }
    },
  );

  test('distinguishes compiled Hexagon from a deployable runtime', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'getBackendCapabilities');
          return [
            {
              'backend': 'opencl',
              'compiled': true,
              'available': true,
              'reason': 'available',
            },
            {
              'backend': 'hexagon',
              'compiled': true,
              'available': false,
              'reason': 'runtimeLibrariesMissing',
              'detail': 'DSP skeleton missing',
            },
          ];
        });
    final capabilities = await platform.getBackendCapabilities();
    expect(capabilities.first.available, isTrue);
    expect(capabilities.first.dspArchitecture, isNull);
    expect(capabilities.last.backend, MnnBackend.hexagon);
    expect(capabilities.last.compiled, isTrue);
    expect(capabilities.last.available, isFalse);
    expect(capabilities.last.status, MnnBackendStatus.runtimeLibrariesMissing);
    expect(capabilities.last.detail, 'DSP skeleton missing');
  });

  test(
    'decodes the DSP architecture selected automatically by Android',
    () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            expect(call.method, 'getBackendCapabilities');
            return [
              {
                'backend': 'hexagon',
                'compiled': true,
                'available': true,
                'reason': 'available',
                'dspArchitecture': 'v79',
              },
            ];
          });
      final capability = (await platform.getBackendCapabilities()).single;
      expect(capability.backend, MnnBackend.hexagon);
      expect(capability.available, isTrue);
      expect(capability.dspArchitecture, 'v79');
    },
  );

  test('backend failures retain actionable native details', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          throw PlatformException(
            code: 'backend_unavailable',
            message: 'OpenCL unavailable',
            details: {'backend': 'opencl', 'reason': 'driverUnavailable'},
          );
        });
    await expectLater(
      platform.loadModel(
        'qwen',
        options: const MnnLoadOptions(backend: MnnBackend.opencl),
      ),
      throwsA(
        isA<MnnEngineException>()
            .having((error) => error.code, 'code', 'backend_unavailable')
            .having((error) => error.details, 'details', {
              'backend': 'opencl',
              'reason': 'driverUnavailable',
            }),
      ),
    );
  });

  test('importModelFromPath forwards the private directory', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'importModelFromPath');
          expect(call.arguments, <String, Object?>{
            'directoryPath': '/data/user/0/app/files/downloads/model',
            'replaceExisting': false,
          });
          return <String, Object?>{
            'modelId': 'qwen',
            'modelKey': 'qwen',
            'displayName': 'Qwen',
            'modelDirPath': '/data/user/0/app/files/mnn/models/qwen',
            'configPath': '/data/user/0/app/files/mnn/models/qwen/config.json',
            'sizeBytes': 100,
            'importedAt': 1,
            'isActive': false,
          };
        });

    final model = await platform.importModelFromPath(
      '/data/user/0/app/files/downloads/model',
      replaceExisting: false,
    );

    expect(model.modelId, 'qwen');
    expect(model.modelDirPath, contains('/mnn/models/qwen'));
  });

  test('directory import result forwards automatic naming options', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'importModelDirectoryWithResult');
          expect(call.arguments, <String, Object?>{
            'replaceExisting': false,
            'autoRename': true,
            'unavailableNames': <String>['Qwen'],
          });
          return <String, Object?>{
            'requestedModelName': 'Qwen',
            'model': <String, Object?>{
              'modelId': 'Qwen (2)',
              'modelKey': 'Qwen (2)',
              'displayName': 'Qwen (2)',
              'modelDirPath': '/models/Qwen (2)',
              'configPath': '/models/Qwen (2)/config.json',
              'sizeBytes': 100,
              'importedAt': 1,
              'isActive': false,
            },
          };
        });

    final result = await platform.importModelDirectoryWithResult(
      replaceExisting: false,
      autoRename: true,
      unavailableNames: const <String>['Qwen'],
    );

    expect(result.requestedModelName, 'Qwen');
    expect(result.model.modelKey, 'Qwen (2)');
    expect(result.wasRenamed, isTrue);
  });

  test('path import result forwards automatic naming options', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'importModelFromPathWithResult');
          expect(call.arguments, <String, Object?>{
            'directoryPath': '/downloads/qwen',
            'replaceExisting': false,
            'autoRename': true,
            'unavailableNames': <String>['Qwen'],
          });
          return <String, Object?>{
            'requestedModelName': 'Qwen',
            'model': <String, Object?>{
              'modelId': 'Qwen (2)',
              'modelKey': 'Qwen (2)',
              'displayName': 'Qwen (2)',
              'modelDirPath': '/models/Qwen (2)',
              'configPath': '/models/Qwen (2)/config.json',
              'sizeBytes': 100,
              'importedAt': 1,
              'isActive': false,
            },
          };
        });

    final result = await platform.importModelFromPathWithResult(
      '/downloads/qwen',
      replaceExisting: false,
      autoRename: true,
      unavailableNames: const <String>['Qwen'],
    );

    expect(result.model.modelId, 'Qwen (2)');
  });

  test('forwards mmap, precision and thread options', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'loadModel');
          expect(call.arguments, <String, Object?>{
            'modelId': 'qwen',
            'options': {
              'backend': 'cpu',
              'useMmap': true,
              'precision': 'high',
              'threadNum': 6,
            },
          });
          return <String, Object?>{
            'modelId': 'qwen',
            'backend': 'cpu',
            'useMmap': true,
            'precision': 'high',
            'threadNum': 6,
            'isActive': true,
          };
        });

    final model = await platform.loadModel(
      'qwen',
      options: const MnnLoadOptions(
        useMmap: true,
        precision: MnnPrecision.high,
        threadNum: 6,
      ),
    );
    expect(model.useMmap, isTrue);
    expect(model.precision, MnnPrecision.high);
    expect(model.threadNum, 6);
  });

  test(
    'getMmapCache and clearMmapCache forward an optional model id',
    () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            expect(call.method, 'getMmapCache');
            expect(call.arguments, <String, Object?>{'modelId': 'qwen'});
            return <String, Object?>{
              'sizeBytes': 2048,
              'cleared': false,
              'modelId': 'qwen',
            };
          });
      final info = await platform.getMmapCache(modelId: 'qwen');
      expect(info.sizeBytes, 2048);
      expect(info.cleared, isFalse);

      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(channel, (call) async {
            expect(call.method, 'clearMmapCache');
            expect(call.arguments, <String, Object?>{'modelId': null});
            return <String, Object?>{
              'sizeBytes': 2048,
              'cleared': true,
              'modelId': null,
            };
          });
      final cleared = await platform.clearMmapCache();
      expect(cleared.cleared, isTrue);
      expect(cleared.sizeBytes, 2048);
    },
  );

  test('renameImportedModel forwards the current id and new name', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          expect(call.method, 'renameImportedModel');
          expect(call.arguments, <String, Object?>{
            'modelId': 'qwen',
            'newName': 'Qwen 3',
          });
          return <String, Object?>{
            'modelId': 'Qwen 3',
            'modelKey': 'Qwen 3',
            'displayName': 'Qwen 3',
            'modelDirPath': '/models/Qwen 3',
            'configPath': '/models/Qwen 3/config.json',
            'sizeBytes': 100,
            'importedAt': 1,
            'isActive': false,
          };
        });

    final model = await platform.renameImportedModel('qwen', 'Qwen 3');

    expect(model.modelId, 'Qwen 3');
    expect(model.displayName, 'Qwen 3');
  });

  test('preserves stable native error codes and details', () async {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          throw PlatformException(
            code: 'port_in_use',
            message: 'Port is unavailable.',
            details: <String, Object?>{'port': 8081},
          );
        });

    await expectLater(
      platform.startServer(bindMode: MnnServerBindMode.loopback, port: 8081),
      throwsA(
        isA<MnnEngineException>()
            .having((error) => error.code, 'code', 'port_in_use')
            .having((error) => error.details, 'details', <String, Object?>{
              'port': 8081,
            }),
      ),
    );
  });
}
