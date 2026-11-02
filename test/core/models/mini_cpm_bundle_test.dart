import 'dart:convert';
import 'dart:io';
import 'package:crypto/crypto.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:servllama/core/models/mini_cpm_bundle.dart';
import 'package:servllama/features/downloads/models/model_hub.dart';
import 'package:servllama/core/database/app_database.dart';
import 'package:servllama/core/repositories/mini_cpm_model_repository.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  test('pinned catalog contains 43 verified files at immutable revision', () {
    final b = MiniCpmBundle.fromJson(
      jsonDecode(File('assets/catalog/minicpm_les.json').readAsStringSync()),
    );
    expect(b.files.length, 43);
    expect(b.sizeBytes, 16623585508);
    expect(b.revision, '7a7ab2ac8f045d53574ead3324b97e709d0e318b');
    b.validateDownload(
      source: ModelHubSource.huggingFace,
      repoId: b.repoId,
      revision: b.revision,
      files: b.downloadFiles,
    );
    expect(
      () => b.validateDownload(
        source: ModelHubSource.huggingFace,
        repoId: b.repoId,
        revision: 'main',
        files: b.downloadFiles,
      ),
      throwsFormatException,
    );
    expect(
      () => b.validateDownload(
        source: ModelHubSource.huggingFace,
        repoId: b.repoId,
        revision: b.revision,
        files: b.downloadFiles.sublist(1),
      ),
      throwsFormatException,
    );
  });
  test('rejects traversal and duplicate manifest paths', () {
    for (final path in [
      '../model',
      '/model',
      'experts/../x',
      'experts//x',
      'C:x',
      r'experts\x',
    ]) {
      expect(
        () => MiniCpmBundle.validateRelativePath(path),
        throwsFormatException,
      );
    }
    final j = fixture();
    (j['files'] as List).add((j['files'] as List).first);
    expect(() => MiniCpmBundle.fromJson(j), throwsFormatException);
  });
  test(
    'complete import stable rename deletion; corruption and cancellation rejected',
    () async {
      final temp = await Directory.systemTemp.createTemp('les-test-');
      final db = AppDatabase.memory();
      addTearDown(() async {
        await db.close();
        await temp.delete(recursive: true);
      });
      final b = MiniCpmBundle.fromJson(fixture());
      final source = Directory('${temp.path}/source');
      for (final f in b.files) {
        final file = File('${source.path}/${f.path}');
        await file.parent.create(recursive: true);
        await file.writeAsString('test');
      }
      final repo = MiniCpmModelRepository(
        database: db,
        appSupportDirectory: temp,
        bundleLoader: () async => b,
      );
      final model = await repo.importDirectory(
        directory: source,
        modelName: 'First',
      );
      expect((await repo.listModels()).single.id, model.id);
      expect((await repo.renameModel(model.id, 'Second')).id, model.id);
      await expectLater(
        repo.validateForLoad(model, shouldContinue: () => false),
        throwsStateError,
      );
      await File(model.storedFilePath).writeAsString('fail');
      await expectLater(repo.validateForLoad(model), throwsFormatException);
      await db.execute(
        "UPDATE model_assets SET manifest='invalid' WHERE id=?",
        [model.id],
      );
      expect(await repo.listModels(), isEmpty);
      await repo.deleteModel(model.id);
      expect(await repo.listModels(), isEmpty);
      expect(await source.exists(), isTrue);
    },
  );
  test('cancelled owned staging adoption leaves no ready asset', () async {
    final temp = await Directory.systemTemp.createTemp('les-cancel-');
    final db = AppDatabase.memory();
    addTearDown(() async {
      await db.close();
      await temp.delete(recursive: true);
    });
    final b = MiniCpmBundle.fromJson(fixture());
    final staging = Directory('${temp.path}/minicpm_import/staging');
    for (final f in b.files) {
      final file = File('${staging.path}/${f.path}');
      await file.parent.create(recursive: true);
      await file.writeAsString('test');
    }
    final repo = MiniCpmModelRepository(
      database: db,
      appSupportDirectory: temp,
      bundleLoader: () async => b,
    );
    await expectLater(
      repo.adoptDownloadedBundle(
        directory: staging,
        modelName: 'Cancelled',
        shouldContinue: () => false,
      ),
      throwsStateError,
    );
    expect(await repo.listModels(), isEmpty);
    expect(await staging.exists(), isTrue);
    await expectLater(
      repo.adoptDownloadedBundle(
        directory: staging,
        modelName: 'Cancelled at commit',
        shouldContinue: () => staging.existsSync(),
      ),
      throwsStateError,
    );
    expect(await staging.exists(), isTrue);
    expect(await repo.listModels(), isEmpty);
    final m = await repo.adoptDownloadedBundle(
      directory: staging,
      modelName: 'Ready',
    );
    expect(await staging.exists(), isFalse);
    expect((await repo.listModels()).single.id, m.id);
  });
  test('missing shard and intermediate symlink are rejected', () async {
    final temp = await Directory.systemTemp.createTemp('les-links-');
    addTearDown(() => temp.delete(recursive: true));
    final b = MiniCpmBundle.fromJson(fixture());
    final root = Directory('${temp.path}/model');
    await root.create();
    await expectLater(
      MiniCpmModelRepository.verifyDirectory(root, b),
      throwsFormatException,
    );
    for (final f in b.files) {
      final file = File('${root.path}/${f.path}');
      await file.parent.create(recursive: true);
      await file.writeAsString('test');
    }
    await Directory('${root.path}/experts').rename('${temp.path}/experts');
    await Link('${root.path}/experts').create('${temp.path}/experts');
    await expectLater(
      MiniCpmModelRepository.verifyDirectory(root, b),
      throwsFormatException,
    );
  });
}

Map<String, dynamic> fixture() => {
  'id': 'test',
  'displayName': 'Test',
  'repoId': 'test/repo',
  'revision': 'abc',
  'remoteDirectory': 'bundle',
  'quantization': 'test',
  'mainFile': 'main.gguf',
  'mmprojFile': 'mmproj.gguf',
  'expertsDirectory': 'experts',
  'files': [
    for (final path in [
      'main.gguf',
      'mmproj.gguf',
      'experts/manifest.json',
      'experts/layer_000.bin',
    ])
      {
        'path': path,
        'sizeBytes': 4,
        'sha256': sha256.convert(utf8.encode('test')).toString(),
      },
  ],
};
