import 'dart:convert';
import 'package:flutter/services.dart';
import 'package:servllama/features/downloads/models/model_hub.dart';

/// Pinned, immutable LES bundle. Remote manifests cannot add arbitrary paths.
class MiniCpmBundle {
  MiniCpmBundle.fromJson(Map<String, dynamic> json)
    : id = json['id'] as String,
      displayName = json['displayName'] as String,
      repoId = json['repoId'] as String,
      revision = json['revision'] as String,
      remoteDirectory = json['remoteDirectory'] as String,
      quantization = json['quantization'] as String,
      mainFile = json['mainFile'] as String,
      mmprojFile = json['mmprojFile'] as String,
      expertsDirectory = json['expertsDirectory'] as String,
      files = (json['files'] as List)
          .map(
            (v) => HubRepoFile(
              path: v['path'] as String,
              sizeBytes: v['sizeBytes'] as int,
              sha256: v['sha256'] as String,
            ),
          )
          .toList(growable: false) {
    final paths = <String>{};
    for (final f in files) {
      validateRelativePath(f.path);
      if (!paths.add(f.path) ||
          f.sizeBytes <= 0 ||
          !RegExp(r'^[0-9a-f]{64}$').hasMatch(f.sha256 ?? '')) {
        throw const FormatException('Invalid LES bundle entry');
      }
    }
    validateRelativePath(remoteDirectory);
    validateRelativePath(expertsDirectory);
    if (!paths.contains(mainFile) ||
        !paths.contains(mmprojFile) ||
        !paths.contains('$expertsDirectory/manifest.json')) {
      throw const FormatException('Missing LES bundle components');
    }
  }
  final String id, displayName, repoId, revision, remoteDirectory, quantization;
  final String mainFile, mmprojFile, expertsDirectory;
  final List<HubRepoFile> files;
  int get sizeBytes => files.fold(0, (sum, f) => sum + f.sizeBytes);
  List<HubRepoFile> get downloadFiles => files
      .map(
        (f) => HubRepoFile(
          path: '$remoteDirectory/${f.path}',
          sizeBytes: f.sizeBytes,
          sha256: f.sha256,
        ),
      )
      .toList(growable: false);
  void validateDownload({
    required ModelHubSource source,
    required String repoId,
    required String revision,
    required List<HubRepoFile> files,
  }) {
    final expected = {for (final f in downloadFiles) f.path: f};
    if (source != ModelHubSource.huggingFace ||
        repoId != this.repoId ||
        revision != this.revision ||
        files.length != expected.length ||
        files.map((f) => f.path).toSet().length != files.length ||
        files.any(
          (f) =>
              expected[f.path]?.sizeBytes != f.sizeBytes ||
              expected[f.path]?.sha256 != f.sha256,
        )) {
      throw const FormatException(
        'MiniCPM requires the complete pinned LES bundle',
      );
    }
  }

  static Future<MiniCpmBundle> load({AssetBundle? bundle}) async =>
      MiniCpmBundle.fromJson(
        jsonDecode(
              await (bundle ?? rootBundle).loadString(
                'assets/catalog/minicpm_les.json',
              ),
            )
            as Map<String, dynamic>,
      );
  static void validateRelativePath(String path) {
    if (path.isEmpty ||
        path.startsWith('/') ||
        path.contains('\\') ||
        path.contains(':') ||
        path.split('/').any((p) => p.isEmpty || p == '.' || p == '..') ||
        path.contains(RegExp(r'[\x00-\x1f]'))) {
      throw const FormatException('Unsafe LES relative path');
    }
  }
}

class MiniCpmModelDescriptor {
  const MiniCpmModelDescriptor({
    required this.id,
    required this.modelName,
    required this.storedDirectoryPath,
    required this.storedFilePath,
    required this.mmprojFilePath,
    required this.expertsDirectoryPath,
    required this.sizeBytes,
    required this.importedAt,
    this.visionEnabled = true,
  });
  final String id, modelName, storedDirectoryPath, storedFilePath;
  final String mmprojFilePath, expertsDirectoryPath;
  final int sizeBytes;
  final DateTime importedAt;
  final bool visionEnabled;
  String? get activeMmprojFilePath => visionEnabled ? mmprojFilePath : null;
}
