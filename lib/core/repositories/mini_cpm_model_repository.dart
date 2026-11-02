import 'dart:convert';
import 'dart:io';
import 'package:crypto/crypto.dart';
import 'package:path/path.dart' as p;
import 'package:path_provider/path_provider.dart';
import 'package:servllama/core/database/app_database.dart';
import 'package:servllama/core/database/model_selection_cleanup.dart';
import 'package:servllama/core/models/mini_cpm_bundle.dart';
import 'package:servllama/core/utils/new_id.dart';

/// LES assets share the application's model_assets database. The directory is
/// owned as one unit; individual experts are never published as GGUF models.
class MiniCpmModelRepository {
  MiniCpmModelRepository({
    AppDatabase? database,
    Directory? appSupportDirectory,
    Future<MiniCpmBundle> Function()? bundleLoader,
  }) : _database = database,
       _directory = appSupportDirectory,
       _bundleLoader = bundleLoader ?? MiniCpmBundle.load;
  final AppDatabase? _database;
  final Directory? _directory;
  final Future<MiniCpmBundle> Function() _bundleLoader;
  Future<AppDatabase> get _db async => _database ?? await AppDatabase.shared();
  Future<Directory> get _root async => Directory(
    p.join(
      (_directory ?? await getApplicationSupportDirectory()).path,
      'minicpm_models',
    ),
  );

  Future<List<MiniCpmModelDescriptor>> listModels() async {
    final db = await _db;
    final rows = await db.query(
      "SELECT * FROM model_assets WHERE kind='llm' AND engine='mini_cpm' AND state='ready'",
    );
    if (rows.isEmpty) return [];
    final b = await _bundleLoader();
    final models = <MiniCpmModelDescriptor>[];
    for (final r in rows) {
      final root = r.read<String>('path');
      Map<String, dynamic> meta;
      try {
        meta = jsonDecode(r.read<String>('manifest')) as Map<String, dynamic>;
      } catch (_) {
        meta = {};
      }
      if (meta['bundleId'] != b.id ||
          meta['importedAt'] is! int ||
          !await _filesPresent(root, b)) {
        await db.execute("UPDATE model_assets SET state='missing' WHERE id=?", [
          r.read<String>('id'),
        ]);
        continue;
      }
      models.add(
        MiniCpmModelDescriptor(
          id: r.read<String>('id'),
          modelName: r.read<String>('name'),
          storedDirectoryPath: root,
          storedFilePath: p.join(root, b.mainFile),
          mmprojFilePath: p.join(root, b.mmprojFile),
          expertsDirectoryPath: p.join(root, b.expertsDirectory),
          sizeBytes: b.sizeBytes,
          importedAt: DateTime.fromMillisecondsSinceEpoch(
            meta['importedAt'] as int,
          ),
          visionEnabled: meta['images'] != false,
        ),
      );
    }
    await clearMissingLocalChatTargets(db);
    return models;
  }

  Future<MiniCpmModelDescriptor?> findByRuntimeId(String id) async {
    for (final m in await listModels()) {
      if (m.id == id) return m;
    }
    return null;
  }

  /// Metadata checks during library listing are cheap; every model load rehashes
  /// the complete immutable bundle, so external mutation cannot bypass readiness.
  Future<void> validateForLoad(
    MiniCpmModelDescriptor model, {
    bool Function()? shouldContinue,
  }) async => verifyDirectory(
    Directory(model.storedDirectoryPath),
    await _bundleLoader(),
    shouldContinue: shouldContinue,
  );

  static Future<bool> _filesPresent(String root, MiniCpmBundle b) async {
    root = p.normalize(p.absolute(root));
    if (await FileSystemEntity.type(root, followLinks: false) !=
        FileSystemEntityType.directory) {
      return false;
    }
    for (final f in b.files) {
      final path = p.join(root, f.path);
      var parent = p.dirname(path);
      while (parent != root) {
        if (await FileSystemEntity.type(parent, followLinks: false) !=
            FileSystemEntityType.directory) {
          return false;
        }
        parent = p.dirname(parent);
      }
      if (await FileSystemEntity.type(path, followLinks: false) !=
              FileSystemEntityType.file ||
          await File(path).length() != f.sizeBytes) {
        return false;
      }
    }
    return true;
  }

  static Future<void> verifyDirectory(
    Directory directory,
    MiniCpmBundle b, {
    bool Function()? shouldContinue,
  }) async {
    if (!await _filesPresent(directory.path, b)) {
      throw const FormatException(
        'LES model is incomplete or has incorrect file sizes',
      );
    }
    final canonicalRoot = await directory.resolveSymbolicLinks();
    for (final f in b.files) {
      final file = File(p.join(directory.path, f.path));
      if (!p.isWithin(canonicalRoot, await file.resolveSymbolicLinks())) {
        throw const FormatException('LES file escapes the model directory');
      }
      final checkedStream = file.openRead().map((chunk) {
        if (shouldContinue != null && !shouldContinue()) {
          throw StateError('LES verification cancelled');
        }
        return chunk;
      });
      if ((await sha256.bind(checkedStream).first).toString() != f.sha256) {
        throw FormatException('LES checksum mismatch: ${f.path}');
      }
    }
  }

  Future<MiniCpmModelDescriptor> adoptDownloadedBundle({
    required Directory directory,
    required String modelName,
    bool Function()? shouldContinue,
  }) async =>
      _import(directory, modelName, move: true, shouldContinue: shouldContinue);

  Future<MiniCpmModelDescriptor> importDirectory({
    required Directory directory,
    required String modelName,
  }) async => _import(directory, modelName, move: false);

  Future<MiniCpmModelDescriptor> _import(
    Directory directory,
    String name, {
    required bool move,
    bool Function()? shouldContinue,
  }) async {
    _validateName(name);
    final b = await _bundleLoader();
    final root = await _root;
    if (move) {
      final support = p.dirname(root.path);
      final source = p.normalize(directory.absolute.path);
      if (!p.isWithin(p.join(support, 'downloads'), source) &&
          p.dirname(source) != p.join(support, 'minicpm_import')) {
        throw StateError('Refusing to move unowned LES staging directory');
      }
    }
    await verifyDirectory(directory, b, shouldContinue: shouldContinue);
    await root.create(recursive: true);
    final id = newId();
    final destination = Directory(p.join(root.path, id));
    final now = DateTime.now();
    try {
      if (shouldContinue != null && !shouldContinue()) {
        throw StateError("LES import cancelled");
      }
      if (move) {
        final allowed = b.files
            .map((f) => p.normalize(p.join(directory.path, f.path)))
            .toSet();
        await for (final entity in directory.list(
          recursive: true,
          followLinks: false,
        )) {
          if (entity is Link ||
              (entity is File && !allowed.contains(p.normalize(entity.path)))) {
            throw const FormatException(
              'Unexpected file in LES staging directory',
            );
          }
        }
        if (shouldContinue != null && !shouldContinue()) {
          throw StateError('LES import cancelled');
        }
        await directory.rename(destination.path);
      } else {
        await destination.create();
        for (final f in b.files) {
          final target = File(p.join(destination.path, f.path));
          await target.parent.create(recursive: true);
          await File(p.join(directory.path, f.path)).copy(target.path);
        }
        await verifyDirectory(destination, b);
      }
      if (shouldContinue != null && !shouldContinue()) {
        throw StateError("LES import cancelled");
      }
      final db = await _db;
      if (shouldContinue != null && !shouldContinue()) {
        throw StateError('LES import cancelled');
      }
      // Publication is the commit boundary; a later cancel cannot undo success.
      await db.execute(
        'INSERT INTO model_assets(id,kind,engine,runtime_id,storage_owner,path,name,revision,state,manifest) VALUES(?,?,?,?,?,?,?,?,?,?)',
        [
          id,
          'llm',
          'mini_cpm',
          id,
          'app',
          destination.path,
          name,
          b.revision,
          'ready',
          jsonEncode({
            'bundleId': b.id,
            'libraryId': 'minicpm:$id',
            'images': true,
            'tools': false,
            'importedAt': now.millisecondsSinceEpoch,
            'quantization': b.quantization,
          }),
        ],
      );
    } catch (_) {
      if (await destination.exists()) {
        if (move && !await directory.exists()) {
          await destination.rename(directory.path);
        } else {
          await destination.delete(recursive: true);
        }
      }
      rethrow;
    }
    return MiniCpmModelDescriptor(
      id: id,
      modelName: name,
      storedDirectoryPath: destination.path,
      storedFilePath: p.join(destination.path, b.mainFile),
      mmprojFilePath: p.join(destination.path, b.mmprojFile),
      expertsDirectoryPath: p.join(destination.path, b.expertsDirectory),
      sizeBytes: b.sizeBytes,
      importedAt: now,
    );
  }

  Future<void> deleteModel(String id) async {
    final db = await _db;
    final rows = await db.query(
      "SELECT path FROM model_assets WHERE id=? AND engine='mini_cpm'",
      [id],
    );
    if (rows.isEmpty) return;
    final path = rows.single.read<String>('path');
    final root = await _root;
    if (p.normalize(path) != p.join(root.path, id)) {
      throw StateError('Refusing to delete unowned LES directory');
    }
    await db.transaction(() async {
      await db.execute(
        "UPDATE model_assets SET state='missing' WHERE id=? AND engine='mini_cpm'",
        [id],
      );
      await clearMissingLocalChatTargets(db);
    });
    final dir = Directory(path);
    if (await dir.exists()) await dir.delete(recursive: true);
  }

  Future<MiniCpmModelDescriptor> renameModel(String id, String name) async {
    _validateName(name);
    final db = await _db;
    await db.execute(
      "UPDATE model_assets SET name=? WHERE id=? AND engine='mini_cpm'",
      [name, id],
    );
    final m = await findByRuntimeId(id);
    if (m == null) throw StateError('LES model not found');
    return m;
  }

  static void _validateName(String name) {
    if (name.trim().isEmpty ||
        name == '.' ||
        name == '..' ||
        RegExp(r'[\\/\x00-\x1f]').hasMatch(name)) {
      throw const FormatException('Invalid LES model name');
    }
  }
}
