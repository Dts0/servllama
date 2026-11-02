import 'dart:convert';
import 'package:flutter_test/flutter_test.dart';
import 'package:servllama/core/security/mini_cpm_log_sanitizer.dart';

void main() {
  test(
    'split lifecycle paths and credentials are hidden without losing timings',
    () async {
      final sanitizer = MiniCpmLogSanitizer(
        binaryPath: '/private/native/server',
        arguments: [
          '--model',
          '/private/models/a.gguf',
          '--api-key',
          'private-key',
        ],
      );
      final chunks = [
        'load_model: /private/mod',
        'els/a.gguf\nother /data/user/0/model/expert.bin\n',
        'prompt eval time = 120 ms / 32 tokens (266.67 tokens per second)\nprivate-key\n',
      ];
      final lines = await sanitizer
          .decode(Stream.fromIterable(chunks.map(utf8.encode)))
          .toList();
      expect(lines.join(), isNot(contains('/private')));
      expect(lines.join(), isNot(contains('/data/user')));
      expect(lines.join(), isNot(contains('private-key')));
      expect(
        lines[2],
        contains('120 ms / 32 tokens (266.67 tokens per second)'),
      );
    },
  );
}
