import 'package:servllama/core/security/log_redactor.dart';
import 'package:servllama/core/storage/download_prefs_keys.dart';
import 'package:servllama/core/storage/kv_storage.dart';
import 'package:servllama/features/downloads/models/model_hub.dart';

/// Which Hugging Face origin to fetch from. The mirror exists because the
/// official host is frequently unreachable from mainland China.
enum HuggingFaceRoute {
  auto,
  official,
  mirror;

  static HuggingFaceRoute fromStorageValue(String? value) {
    for (final route in HuggingFaceRoute.values) {
      if (route.name == value) {
        return route;
      }
    }
    return HuggingFaceRoute.auto;
  }
}

class DownloadSettings {
  const DownloadSettings({
    this.huggingFaceRoute = HuggingFaceRoute.auto,
    this.huggingFaceToken = '',
    this.modelScopeToken = '',
    this.wifiOnly = true,
    this.maxConcurrentTasks = defaultMaxConcurrentTasks,
    this.preferredSource = ModelHubSource.huggingFace,
  });

  static const int defaultMaxConcurrentTasks = 1;
  static const int minConcurrentTasksValue = 1;
  static const int maxConcurrentTasksValue = 2;

  final HuggingFaceRoute huggingFaceRoute;
  final String huggingFaceToken;
  final String modelScopeToken;
  final bool wifiOnly;
  final int maxConcurrentTasks;
  final ModelHubSource preferredSource;

  String? tokenFor(ModelHubSource source) {
    switch (source) {
      case ModelHubSource.huggingFace:
        return huggingFaceToken.isEmpty ? null : huggingFaceToken;
      case ModelHubSource.modelScope:
        return modelScopeToken.isEmpty ? null : modelScopeToken;
    }
  }

  DownloadSettings copyWith({
    HuggingFaceRoute? huggingFaceRoute,
    String? huggingFaceToken,
    String? modelScopeToken,
    bool? wifiOnly,
    int? maxConcurrentTasks,
    ModelHubSource? preferredSource,
  }) {
    return DownloadSettings(
      huggingFaceRoute: huggingFaceRoute ?? this.huggingFaceRoute,
      huggingFaceToken: huggingFaceToken ?? this.huggingFaceToken,
      modelScopeToken: modelScopeToken ?? this.modelScopeToken,
      wifiOnly: wifiOnly ?? this.wifiOnly,
      maxConcurrentTasks: maxConcurrentTasks ?? this.maxConcurrentTasks,
      preferredSource: preferredSource ?? this.preferredSource,
    );
  }
}

/// Reads and writes download preferences. Tokens are credentials: they are
/// never logged and never included in exported log files.
class DownloadSettingsStore {
  DownloadSettingsStore({KvStorage? kvStorage})
    : _kvStorage = kvStorage ?? KvStorage.instance;

  final KvStorage _kvStorage;

  Future<DownloadSettings> load() async {
    return DownloadSettings(
      huggingFaceRoute: await loadHuggingFaceRoute(),
      huggingFaceToken: await _loadToken(DownloadPrefsKeys.huggingFaceToken),
      modelScopeToken: await _loadToken(DownloadPrefsKeys.modelScopeToken),
      wifiOnly: await _kvStorage.getBool(DownloadPrefsKeys.wifiOnly) ?? true,
      maxConcurrentTasks:
          await _kvStorage.getInt(DownloadPrefsKeys.maxConcurrentTasks) ??
          DownloadSettings.defaultMaxConcurrentTasks,
      preferredSource: ModelHubSource.fromStorageValue(
        await _kvStorage.getString(DownloadPrefsKeys.preferredSource),
      ),
    );
  }

  Future<HuggingFaceRoute> loadHuggingFaceRoute() async =>
      HuggingFaceRoute.fromStorageValue(
        await _kvStorage.getString(DownloadPrefsKeys.huggingFaceRoute),
      );

  Future<void> saveHuggingFaceRoute(HuggingFaceRoute route) =>
      _kvStorage.setString(DownloadPrefsKeys.huggingFaceRoute, route.name);

  Future<void> saveHuggingFaceToken(String token) =>
      _saveToken(DownloadPrefsKeys.huggingFaceToken, token);

  Future<void> saveModelScopeToken(String token) =>
      _saveToken(DownloadPrefsKeys.modelScopeToken, token);

  Future<void> saveWifiOnly(bool value) =>
      _kvStorage.setBool(DownloadPrefsKeys.wifiOnly, value);

  Future<void> saveMaxConcurrentTasks(int value) =>
      _kvStorage.setInt(DownloadPrefsKeys.maxConcurrentTasks, value);

  Future<void> savePreferredSource(ModelHubSource source) => _kvStorage
      .setString(DownloadPrefsKeys.preferredSource, source.storageValue);

  Future<String> _loadToken(String key) async {
    final value = await _kvStorage.getString(key) ?? '';
    LogRedactor.remember(value);
    return value;
  }

  Future<void> _saveToken(String key, String value) async {
    LogRedactor.remember(value);
    await _kvStorage.setString(key, value);
  }
}
