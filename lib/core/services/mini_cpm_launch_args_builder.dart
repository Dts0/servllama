import 'package:servllama/core/models/server_launch_settings.dart';
import 'package:servllama/core/services/server_launch_args_builder.dart';

/// Shared llama.cpp parameters are forwarded unchanged; only acceleration is
/// CPU-only. LES allocations remain explicit user settings, not hidden caps.
class MiniCpmLaunchArgsBuilder {
  const MiniCpmLaunchArgsBuilder();

  List<String> build(
    ServerLaunchSettings settings, {
    required String modelPath,
    required String modelAlias,
    required String expertsPath,
    String? mmprojPath,
  }) {
    if (settings.miniCpmMicroBatch <= 0 ||
        settings.miniCpmSlotBank <= 0 ||
        settings.miniCpmCacheWindow < 0) {
      throw const FormatException('Invalid MiniCPM allocation parameters');
    }
    return [
      ...const ServerLaunchArgsBuilder().build(
        settings,
        modelPath: modelPath,
        modelAlias: modelAlias,
        mmprojPath: mmprojPath,
      ),
      '--gpu-layers',
      '0',
      '--ubatch-size',
      '${settings.miniCpmMicroBatch}',
      '--moe-experts',
      expertsPath,
      '--moe-slot-bank',
      '${settings.miniCpmSlotBank}',
      '--moe-cache-window',
      '${settings.miniCpmCacheWindow}',
      '--reasoning',
      settings.miniCpmReasoning ? 'auto' : 'off',
      '--jinja',
    ];
  }
}
