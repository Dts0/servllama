import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';
import 'package:servllama/app/app_theme.dart';
import 'package:servllama/features/agent/models/agent_records.dart';
import 'package:servllama/features/agent/pages/mcp_servers_page.dart';
import 'package:servllama/features/agent/services/agent_tool_service.dart';
import 'package:servllama/l10n/generated/app_localizations.dart';

// Opening an editor must not invoke a service method or credential read.
class _Tools extends Fake with ChangeNotifier implements AgentToolService {}

void main() {
  late _Tools tools;
  setUp(() => tools = _Tools());
  tearDown(() => tools.dispose());

  Widget app(McpServer? server) =>
      ChangeNotifierProvider<AgentToolService>.value(
        value: tools,
        child: MaterialApp(
          theme: AppTheme.light(),
          locale: const Locale('en'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          home: McpEditor(server: server),
        ),
      );

  for (final existing in [false, true]) {
    testWidgets(
      '${existing ? 'stored' : 'new'} MCP buttons have final colors on the first frame and reentry',
      (tester) async {
        await tester.binding.setSurfaceSize(const Size(420, 1400));
        addTearDown(() => tester.binding.setSurfaceSize(null));
        final server = existing
            ? const McpServer(
                id: 'fixture',
                name: 'Fixture',
                url: 'https://example.invalid/mcp',
                headers: {'X-API-Key': 'fixture-header'},
              )
            : null;
        final save = find.byKey(const Key('mcp_save_button'));
        final discover = find.ancestor(
          of: find.text('Connect and discover tools'),
          matching: find.byType(OutlinedButton),
        );
        Color? color(Finder button) => DefaultTextStyle.of(
          tester.element(
            find.descendant(of: button, matching: find.byType(Text)).first,
          ),
        ).style.color;

        for (var entry = 0; entry < 2; entry++) {
          await tester.pumpWidget(app(server));
          expect(find.byKey(const Key('mcp_add_header')), findsOneWidget);
          expect(tester.widget<FilledButton>(save).onPressed, isNotNull);
          expect(tester.widget<OutlinedButton>(discover).onPressed, isNotNull);
          expect(color(save), AppTheme.light().colorScheme.onPrimary);
          expect(color(discover), AppTheme.light().colorScheme.onSurface);
          expect(find.byType(LinearProgressIndicator), findsNothing);
          if (existing) {
            expect(
              tester
                  .widget<TextField>(
                    find.byKey(const Key('mcp_header_value_0')),
                  )
                  .controller!
                  .text,
              'fixture-header',
            );
          }
          await tester.pump(const Duration(milliseconds: 16));
          expect(color(save), AppTheme.light().colorScheme.onPrimary);
          expect(color(discover), AppTheme.light().colorScheme.onSurface);
          await tester.pumpWidget(const SizedBox());
        }
      },
    );
  }
}
