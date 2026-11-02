import 'package:servllama/l10n/l10n.dart';
import 'package:servllama/core/logging/app_logger.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:servllama/core/models/mini_cpm_bundle.dart';
import 'package:servllama/core/models/inference_engine.dart';
import 'package:servllama/features/downloads/models/model_hub.dart';
import 'package:servllama/features/downloads/providers/download_provider.dart';
import 'package:servllama/features/downloads/widgets/download_wifi_only_gate.dart';

/// A LES bundle cannot be downloaded as an isolated GGUF quantization.
class MiniCpmBundlePage extends StatefulWidget {
  const MiniCpmBundlePage({super.key});
  @override
  State<MiniCpmBundlePage> createState() => _MiniCpmBundlePageState();
}

class _MiniCpmBundlePageState extends State<MiniCpmBundlePage> {
  final _bundle = MiniCpmBundle.load();
  bool _busy = false;
  String? _status;
  Future<void> _download(MiniCpmBundle b) async {
    if (!await confirmDownloadOnMeteredNetwork(context) || !mounted) return;
    setState(() {
      _busy = true;
      _status = null;
    });
    try {
      await context.read<DownloadProvider>().enqueue(
        engine: InferenceEngine.miniCpm,
        source: ModelHubSource.huggingFace,
        repoId: b.repoId,
        revision: b.revision,
        modelName: b.displayName,
        files: b.downloadFiles,
        quantLabel: b.quantization,
      );
      if (mounted) setState(() => _status = context.l10n.miniCpmDownloadQueued);
    } catch (_) {
      AppLogger.instance.warning(
        'MiniCPM bundle enqueue failed',
        channel: LogChannel.download,
      );
      if (mounted) {
        setState(() => _status = context.l10n.miniCpmDownloadFailed);
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('MiniCPM LES')),
    body: FutureBuilder<MiniCpmBundle>(
      future: _bundle,
      builder: (context, snapshot) {
        if (snapshot.hasError) {
          return Center(child: Text(context.l10n.miniCpmCatalogUnavailable));
        }
        final b = snapshot.data;
        if (b == null) return const Center(child: CircularProgressIndicator());
        return ListView(
          padding: const EdgeInsets.all(24),
          children: [
            Text(
              b.displayName,
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            const SizedBox(height: 16),
            Text(context.l10n.miniCpmBundleWarning),
            const SizedBox(height: 16),
            Text(
              '${b.files.length} files · ${(b.sizeBytes / 1e9).toStringAsFixed(2)} GB\n${b.quantization}\nRevision: ${b.revision}',
            ),
            Text(context.l10n.miniCpmBundleVerification),
            const SizedBox(height: 24),
            FilledButton.icon(
              onPressed: _busy ? null : () => _download(b),
              icon: const Icon(Icons.download),
              label: Text(context.l10n.miniCpmDownloadBundle),
            ),
            if (_status != null)
              Padding(
                padding: const EdgeInsets.only(top: 16),
                child: Text(_status!),
              ),
          ],
        );
      },
    ),
  );
}
