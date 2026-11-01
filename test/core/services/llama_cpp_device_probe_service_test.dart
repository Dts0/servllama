import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import 'package:servllama/core/logging/app_logger.dart';
import 'package:servllama/core/services/llama_cpp_device_probe_service.dart';
import 'package:servllama/core/services/native_library_dir_service.dart';

void main() {
  for (final exitCode in [0, 1]) {
    test('logs raw probe output at debug level for exit $exitCode', () async {
      final logger = AppLogger();
      addTearDown(logger.dispose);
      final stdout = exitCode == 0
          ? 'Available devices:\n  HTP0: Hexagon (0 MiB, 0 MiB free)\n'
          : 'Available devices:\n  (none)\n';
      final stderr = exitCode == 0
          ? 'ggml-hex: Loading driver libcdsprpc.so'
          : 'ggml-hex: failed to load libcdsprpc.so: driver unavailable';
      final output = '$stdout\n$stderr';
      var runs = 0;
      final service = LlamaCppDeviceProbeService(
        nativeLibraryDirService: _FakeNativeLibraryDirService(),
        logger: logger,
        runner: (executable, arguments, environment) async {
          expect(executable, '/native/libllama-server.so');
          expect(arguments, ['--list-devices']);
          runs++;
          return ProcessResult(1, exitCode, stdout, stderr);
        },
      );

      final result = await service.probe();

      final debugEntries = logger
          .entriesFor(LogChannel.engine)
          .where((entry) => entry.level == LogLevel.debug);
      expect(debugEntries, hasLength(1));
      expect(
        debugEntries.single.message,
        'llama-server --list-devices (exit $exitCode):\n$output',
      );
      expect(result.rawOutput, output);
      expect(result.hexagonAvailable, exitCode == 0);
      expect(result.error, exitCode == 0 ? isNull : 'exit $exitCode');

      expect(await service.probe(), same(result));
      expect(runs, 1);
      expect(
        logger
            .entriesFor(LogChannel.engine)
            .where((entry) => entry.level == LogLevel.debug),
        hasLength(1),
      );

      await service.probe(force: true);
      expect(runs, 2);
      expect(
        logger
            .entriesFor(LogChannel.engine)
            .where((entry) => entry.level == LogLevel.debug),
        hasLength(2),
      );
    });
  }
}

class _FakeNativeLibraryDirService extends NativeLibraryDirService {
  @override
  Future<String> getNativeLibraryDir() async => '/native';
}
