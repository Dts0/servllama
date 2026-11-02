import 'package:servllama/core/models/mini_cpm_bundle.dart';
import 'package:servllama/core/repositories/mini_cpm_model_repository.dart';

/// Isolates unrelated engine tests from real filesystem/platform storage.
class EmptyMiniCpmRepository extends MiniCpmModelRepository {
  @override
  Future<List<MiniCpmModelDescriptor>> listModels() async => [];
}
