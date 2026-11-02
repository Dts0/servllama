import 'package:flutter_test/flutter_test.dart';
import 'package:servllama/core/models/inference_engine.dart';
import 'package:servllama/core/models/llama_cpp_backend.dart';
import 'package:servllama/core/models/server_launch_settings.dart';
import 'package:servllama/core/services/mini_cpm_launch_args_builder.dart';

void main() {
  const builder = MiniCpmLaunchArgsBuilder();
  List<String> build(ServerLaunchSettings settings) => builder.build(
    settings,
    modelPath: '/bundle/model.gguf',
    modelAlias: 'runtime-uuid',
    expertsPath: '/bundle/experts',
    mmprojPath: '/bundle/mmproj.gguf',
  );
  test('third engine preserves stored official engine identities', () {
    expect(InferenceEngine.llamaCpp.storageValue, 'llama_cpp');
    expect(InferenceEngine.mnn.storageValue, 'mnn');
    expect(InferenceEngine.miniCpm.storageValue, 'mini_cpm');
    expect(InferenceEngine.miniCpm.isLlamaFamily, isTrue);
    expect(InferenceEngine.miniCpm.usesDirectoryModels, isTrue);
  });
  test('256K, batching and parallelism are not capped for LES', () {
    expect(ServerLaunchSettings.maxContextSize, 262144);
    final args = build(
      const ServerLaunchSettings(
        contextSize: 262144,
        batchSize: 2048,
        parallelSlots: 8,
        miniCpmMicroBatch: 128,
        miniCpmSlotBank: 512,
        miniCpmCacheWindow: 4,
        llamaCppBackend: LlamaCppBackend.hexagon,
      ),
    );
    for (final pair in <List<String>>[
      ['--ctx-size', '262144'],
      ['--batch-size', '2048'],
      ['--parallel', '8'],
      ['--ubatch-size', '128'],
      ['--moe-slot-bank', '512'],
      ['--moe-cache-window', '4'],
      ['--gpu-layers', '0'],
      ['--device', 'none'],
      ['--mmproj', '/bundle/mmproj.gguf'],
      ['--alias', 'runtime-uuid'],
    ]) {
      final index = args.indexOf(pair.first);
      expect(index, greaterThanOrEqualTo(0));
      expect(args[index + 1], pair.last);
    }
    expect(args, isNot(contains('HTP0')));
  });
  test('reasoning can be disabled and invalid allocations fail explicitly', () {
    expect(
      build(const ServerLaunchSettings(miniCpmReasoning: false)),
      containsAllInOrder(['--reasoning', 'off']),
    );
    for (final settings in [
      const ServerLaunchSettings(miniCpmMicroBatch: 0),
      const ServerLaunchSettings(miniCpmSlotBank: -1),
      const ServerLaunchSettings(miniCpmCacheWindow: -1),
    ]) {
      expect(() => build(settings), throwsFormatException);
    }
  });
}
