import 'package:flutter/services.dart';

class MnnEngineException implements Exception {
  const MnnEngineException(this.code, this.message, {this.details});

  final String code;
  final String message;
  final Object? details;

  factory MnnEngineException.fromPlatformException(PlatformException error) {
    return MnnEngineException(
      error.code,
      error.message ?? 'MNN engine operation failed.',
      details: error.details,
    );
  }

  @override
  String toString() => 'MnnEngineException($code): $message';
}
