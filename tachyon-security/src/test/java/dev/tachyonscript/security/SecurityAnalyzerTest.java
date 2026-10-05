package dev.tachyonscript.security;

import dev.tachyonscript.compiler.CompilationResult;
import dev.tachyonscript.compiler.Compiler;
import dev.tachyonscript.language.diagnostic.DiagnosticRenderer;
import dev.tachyonscript.language.semantic.BoundModule;
import dev.tachyonscript.language.source.SourceFile;
import dev.tachyonscript.stdlib.StandardLibrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SecurityAnalyzerTest {
    private static final Compiler COMPILER = new Compiler(StandardLibrary.registry());
    static List<BoundModule> compile(SourceFile... sources) {
        CompilationResult result = COMPILER.compile(List.of(sources));
        assertTrue(result.succeeded(), () -> result.diagnostics().diagnostics().stream().map(DiagnosticRenderer.plain()::render).reduce("", String::concat));
        return result.modules().stream().map(m -> m.bound()).toList();
    }
    static SecurityManifest analyze(SourceFile file) {
        return new SecurityAnalyzer(SecurityOptions.defaults()).analyze(compile(file)).get(file.path());
    }
    @ParameterizedTest
    @ValueSource(strings = {"\n", "\r\n", "\r"})
    void followsExactLocationsAcrossLineEndingsUnicodeCommentsAndBlankLines(String newline) {
        String text = String.join(newline, "// Unicode 😀 Tiếng Việt", "", "event player.chat {",
                "    let cmd = \"say \" + message", "    server.dispatch(cmd)", "}", "");
        SourceFile file = new SourceFile("scripts/shop.tys", text);
        SecurityManifest manifest = analyze(file);
        SecurityFinding finding = manifest.findings().stream().filter(f -> f.category() == SecurityCategory.COMMAND_INJECTION).findFirst().orElseThrow();
        assertEquals("scripts/shop.tys", finding.location().file());
        assertEquals(5, finding.location().startLine()); assertEquals(5, finding.location().startColumn());
        assertEquals(5, finding.location().endLine()); assertEquals(25, finding.location().endColumn());
        assertEquals("server.dispatch(cmd)", text.substring(finding.location().startOffset(), finding.location().endOffset()));
        assertEquals("message", finding.source()); assertEquals("server.dispatch", finding.sink());
        assertEquals("event player.chat", finding.handlerName());
        assertEquals(4, finding.flow().getFirst().location().startLine());
        assertEquals(24, finding.flow().getFirst().location().startColumn());
        assertTrue(finding.flow().stream().anyMatch(step -> step.kind().equals("CONCAT")));
        assertTrue(finding.flow().stream().anyMatch(step -> step.expression().equals("cmd")));
        assertEquals("SINK", finding.flow().getLast().kind());
        assertNotNull(manifest.node(finding.nodeId()));
        assertTrue(finding.codeSnippet().contains("5 |     server.dispatch(cmd)"));
        assertEquals(SecurityDecision.QUARANTINE, SecurityPolicyEngine.decide(manifest.findings(), SecurityOptions.defaults()));
    }
    @Test void multilineNestedCallsRetainTheEntireNativeCall() {
        String source = "event player.chat {\n    server.dispatch(\n        \"say \" + message.trim()\n    )\n}\n";
        SecurityFinding finding = analyze(new SourceFile("multi.tys", source)).findings().stream()
                .filter(f -> f.category() == SecurityCategory.COMMAND_INJECTION).findFirst().orElseThrow();
        assertEquals(2, finding.location().startLine()); assertEquals(5, finding.location().startColumn());
        assertEquals(4, finding.location().endLine()); assertEquals(6, finding.location().endColumn());
        assertEquals("server.dispatch(\n        \"say \" + message.trim()\n    )", source.substring(finding.location().startOffset(), finding.location().endOffset()));
    }
    @Test void importedFunctionFlowUsesCalleeFileAndCallerSource() {
        SourceFile library = new SourceFile("lib.tys", "module lib\nfunction run(value: string) {\n    server.dispatch(value)\n}\n");
        SourceFile caller = new SourceFile("shop.tys", "import lib\nevent player.chat {\n    lib.run(message)\n}\n");
        var manifests = new SecurityAnalyzer(SecurityOptions.defaults()).analyze(compile(library, caller));
        var finding = manifests.get("lib.tys").findings().stream().filter(f -> f.category() == SecurityCategory.COMMAND_INJECTION).findFirst().orElseThrow();
        assertEquals("lib.tys", finding.location().file()); assertEquals(3, finding.location().startLine());
        assertEquals("shop.tys", finding.flow().getFirst().location().file());
        assertEquals(3, finding.flow().getFirst().location().startLine());
        assertEquals(library.hash(), manifests.get("shop.tys").dependencies().get("lib.tys"));
        assertEquals(1, SecurityAnalyzer.importLocation(compile(library, caller).stream().filter(m -> m.name().equals("shop")).findFirst().orElseThrow(), "lib").startLine());
    }
    @Test void numericCommandParametersAndConstantCommandsAreNotInjection() {
        var manifest = analyze(new SourceFile("safe.tys", "command amount(value: int) {\n    server.dispatch(\"say {value}\")\n}\non load { server.dispatch(\"say loaded\") }\n"));
        assertFalse(manifest.findings().stream().anyMatch(f -> f.category() == SecurityCategory.COMMAND_INJECTION));
        assertFalse(SecurityPolicyEngine.decide(manifest.findings(), SecurityOptions.defaults()).deniesExecution());
    }
    @Test void globalTaintReachesAFixedPointAcrossHandlers() {
        var manifest = analyze(new SourceFile("global.tys", "var saved = \"\"\nvar count = 0\nevent player.chat { saved = message }\nevent player.join {\n    count += 1\n    server.dispatch(saved)\n}\n"));
        assertTrue(manifest.findings().stream().anyMatch(f -> f.category() == SecurityCategory.COMMAND_INJECTION && f.location().startLine() == 6));
    }
    @ParameterizedTest @ValueSource(strings = {"http://127.0.0.1/admin", "http://169.254.169.254/latest/meta-data", "http://[::1]/", "http://10.0.0.1/", "http://2130706433/"})
    void detectsSsrfAtActualUrlSink(String url) {
        var finding = analyze(new SourceFile("ssrf.tys", "on load {\n    web.get(\"" + url + "\", (status, body) => {})\n}\n")).findings().stream()
                .filter(f -> f.category() == SecurityCategory.SSRF).findFirst().orElseThrow();
        assertEquals(2, finding.location().startLine()); assertEquals(5, finding.location().startColumn());
        assertEquals("web.get", finding.sink());
    }
    @Test void pathTraversalAndLoopFindingsHaveCompilerLocations() {
        var manifest = analyze(new SourceFile("bad.tys", "on load {\n    files.read(\"../server.properties\")\n    while true { log(\"busy\") }\n}\n"));
        assertTrue(manifest.findings().stream().anyMatch(f -> f.category() == SecurityCategory.PATH_TRAVERSAL && f.location().startLine() == 2));
        assertTrue(manifest.findings().stream().anyMatch(f -> f.category() == SecurityCategory.UNBOUNDED_LOOP && f.location().startLine() == 3));
    }
    @Test void manifestAndSnippetNeverMixSourceRevisions() {
        SourceFile a = new SourceFile("edit.tys", "event player.chat { server.dispatch(message) }\n");
        SourceFile b = new SourceFile("edit.tys", "\n\nevent player.chat { server.dispatch(message) }\n");
        var first = analyze(a); var second = analyze(b);
        assertNotEquals(first.sha256(), second.sha256()); assertNotEquals(first.nodes().getFirst().id(), second.nodes().getFirst().id());
        assertEquals(3, second.findings().getFirst().location().startLine());
        try (var provider = new QwenSecurityProvider(SecurityOptions.defaults())) {
            assertThrows(IllegalArgumentException.class, () -> provider.validate("{}", first, b));
        }
    }
    @Test void strictJsonRejectsDuplicatesTrailingActionsAndNonfiniteNumbers() {
        for (String invalid : List.of("{\"a\":1,\"a\":2}", "{} delete()", "{\"a\":NaN}", "[1,]", "01", "1e9999"))
            assertThrows(IllegalArgumentException.class, () -> SecurityJson.parse(invalid));
        assertEquals(Map.of("line", 47L), SecurityJson.parse("{\"line\":47}"));
    }
}
