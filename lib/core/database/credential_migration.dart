import 'dart:convert';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:servllama/core/database/app_database.dart';
import 'package:servllama/core/security/log_redactor.dart';
import 'package:servllama/core/storage/download_prefs_keys.dart';
import 'package:servllama/core/storage/kv_storage.dart';
import 'package:servllama/core/storage/server_prefs_keys.dart';
import 'package:servllama/features/agent/models/agent_records.dart';

/// Upgrade-only bridge. Business code reads credentials with their settings.
class CredentialMigration {
  CredentialMigration(
    this.db, {
    FlutterSecureStorage? legacyStorage,
    KvStorage? preferences,
  }) : _legacyStorage =
           legacyStorage ??
           const FlutterSecureStorage(
             aOptions: AndroidOptions(encryptedSharedPreferences: true),
           ),
       _preferences = preferences ?? KvStorage.instance;

  static const marker = 'inlineCredentialsComplete';
  static const _headersPrefix = 'servllama-mcp-headers:v1:';
  final AppDatabase db;
  final FlutterSecureStorage _legacyStorage;
  final KvStorage _preferences;

  Future<void> run() async {
    if (await db.metadata(marker) == '1') return;
    final legacy = await _legacyStorage.readAll();
    for (final value in legacy.values) {
      LogRedactor.remember(value);
    }
    final updates = <(String, String, Map<String, dynamic>)>[];
    for (final table in const ['ai_connections', 'mcp_servers']) {
      final field = table == 'ai_connections' ? 'apiKey' : 'headers';
      for (final row in await db.query('SELECT id,config FROM $table')) {
        final config =
            jsonDecode(row.read<String>('config')) as Map<String, dynamic>;
        final ref = config['secretRef'] as String?;
        if (!config.containsKey(field)) {
          final value = ref == null ? '' : legacy[ref];
          if (value == null) {
            throw StateError('Saved credential unavailable; retry migration');
          }
          if (field == 'apiKey') {
            config['apiKey'] = value;
            config['credentialRevision'] = ref == null ? 0 : 1;
          } else {
            if (value.isNotEmpty && !value.startsWith(_headersPrefix)) {
              throw const FormatException('Invalid saved MCP headers');
            }
            final headers = value.isEmpty
                ? <String, String>{}
                : Map<String, String>.from(
                    jsonDecode(value.substring(_headersPrefix.length)) as Map,
                  );
            McpCredentials(headers: headers).validate();
            config['headers'] = headers;
          }
        }
        config.remove('secretRef');
        updates.add((table, row.read<String>('id'), config));
      }
    }

    for (final entry in const {
      ServerPrefsKeys.apiKey: ServerPrefsKeys.apiKey,
      DownloadPrefsKeys.huggingFaceToken: 'servllama.download.hf_token',
      DownloadPrefsKeys.modelScopeToken: 'servllama.download.ms_token',
    }.entries) {
      if (await _preferences.getString(entry.key) == null &&
          legacy[entry.value] != null) {
        await _preferences.setString(entry.key, legacy[entry.value]!);
      }
    }
    await db.transaction(() async {
      for (final (table, id, config) in updates) {
        await db.execute('UPDATE $table SET config=? WHERE id=?', [
          jsonEncode(config),
          id,
        ]);
      }
    });

    // New values are durable before cleanup. A retry preserves inline values,
    // including after cleanup succeeded but publishing the marker failed.
    await _legacyStorage.deleteAll();
    await db.setMetadata(marker, '1');
  }
}
