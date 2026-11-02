import 'package:flutter_test/flutter_test.dart';
import 'package:mnn_engine/mnn_engine_method_channel.dart';
import 'package:mnn_engine/mnn_engine_platform_interface.dart';

void main() {
  test('MethodChannelMnnEngine is the default platform implementation', () {
    expect(MnnEnginePlatform.instance, isA<MethodChannelMnnEngine>());
  });
}
