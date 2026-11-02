import 'dart:convert';

/// MiniCPM native lifecycle diagnostics can contain private model/storage paths.
/// Frame complete lines before redaction so split pipe chunks cannot leak them.
class MiniCpmLogSanitizer {
  MiniCpmLogSanitizer({
    required List<String> arguments,
    required String binaryPath,
  }) : _privateValues = [
         binaryPath,
         for (var i = 0; i + 1 < arguments.length; i++)
           if (const {
             '--model',
             '--mmproj',
             '--moe-experts',
             '--api-key',
           }.contains(arguments[i]))
             arguments[i + 1],
       ]..sort((a, b) => b.length.compareTo(a.length));
  final List<String> _privateValues;
  static final _path = RegExp(
    r'''(?<![\w])(?:/[\w.~-]|[A-Za-z]:[\\/])[^\s"'<>]*''',
  );
  String redact(String line) {
    var result = line;
    for (final value in _privateValues) {
      if (value.isNotEmpty) result = result.replaceAll(value, '[REDACTED]');
    }
    return result.replaceAll(_path, '[PATH]');
  }

  Stream<String> decode(Stream<List<int>> bytes) => bytes
      .transform(const Utf8Decoder(allowMalformed: true))
      .transform(const LineSplitter())
      .map(redact);
}
