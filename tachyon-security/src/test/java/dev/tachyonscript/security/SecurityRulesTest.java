package dev.tachyonscript.security;

import dev.tachyonscript.compiler.Compiler;
import dev.tachyonscript.language.source.SourceFile;
import dev.tachyonscript.stdlib.StandardLibrary;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SecurityRulesTest {
    @Test void ordinaryEventAndDeferredMenuTasksAreNotSchedulingStorms() {
        var manifest = SecurityAnalyzerTest.analyze(new SourceFile("menu.tys", """
                event player.join {
                    after 1 tick for player { player.send("welcome") }
                    let menu = Menu(1, "Menu")
                    for slot in 0..<9 {
                        menu.set(slot, ItemStack(Material.STONE), click => {
                            after 1 tick for click.player { click.player.send("clicked") }
                        })
                    }
                    menu.open(player)
                }
                """));
        assertTrue(manifest.findings().stream().noneMatch(f -> Set.of(SecurityCategory.EVENT_SPAM,
                SecurityCategory.TASK_EXPLOSION, SecurityCategory.RECURSIVE_SCHEDULING).contains(f.category())),
                manifest.findings()::toString);
    }
    @Test void permissionGuardDoesNotRemoveTaintEvidenceButPreventsStaticAutomaticRevocation() {
        var manifest = SecurityAnalyzerTest.analyze(new SourceFile("guard.tys", "@permission(\"admin.run\")\ncommand run(value: string) {\n    server.dispatch(value)\n}\n"));
        var finding = manifest.findings().stream().filter(f -> f.category() == SecurityCategory.COMMAND_INJECTION).findFirst().orElseThrow();
        assertEquals(SecuritySeverity.HIGH, finding.severity()); assertEquals(3, finding.location().startLine());
        assertEquals(SecurityDecision.WARN, SecurityPolicyEngine.decide(manifest.findings(), SecurityOptions.defaults()));
    }
    @Test void eventResponseFlowsThroughCallbackAndPrivilegedPermissionSinkHasAnExactSpan() {
        var manifest = SecurityAnalyzerTest.analyze(new SourceFile("response.tys", "on load {\n    web.get(\"https://api.example.com\", (status, body) => {\n        server.dispatch(body)\n    })\n}\n"));
        var finding = manifest.findings().stream().filter(f -> f.category() == SecurityCategory.COMMAND_INJECTION).findFirst().orElseThrow();
        assertEquals(3, finding.location().startLine()); assertEquals(9, finding.location().startColumn());
        assertTrue(finding.source().contains("callback body")); assertEquals(2, finding.flow().getFirst().location().startLine());
        var privilege = SecurityAnalyzerTest.analyze(new SourceFile("priv.tys", "event player.join {\n    player.addPermission(\"*\")\n}\n"));
        assertTrue(privilege.findings().stream().anyMatch(f -> f.category() == SecurityCategory.PRIVILEGE_ESCALATION && f.location().startLine() == 2));
    }
    @Test void reconstructedPrivilegedCommandsAndSchedulingLoopsAreReportedAtTheirSinks() {
        var hidden = SecurityAnalyzerTest.analyze(new SourceFile("hidden.tys", "on load {\n    server.dispatch(\"o\" + \"p Somebody\")\n}\n"));
        assertTrue(hidden.findings().stream().anyMatch(f -> f.category() == SecurityCategory.OBFUSCATION && f.location().startLine() == 2));
        var tasks = SecurityAnalyzerTest.analyze(new SourceFile("tasks.tys", "on load {\n    for i in 1..3 {\n        after 1 second { log(\"work\") }\n    }\n}\n"));
        var finding = tasks.findings().stream().filter(f -> f.category() == SecurityCategory.TASK_EXPLOSION).findFirst().orElseThrow();
        assertEquals(3, finding.location().startLine()); assertEquals(9, finding.location().startColumn());
        assertFalse(SecurityPolicyEngine.decide(tasks.findings(), SecurityOptions.defaults()).deniesExecution());
    }
    @Test void databaseValuesAreTaintedButPreparedValuesDoNotBecomeQuerySyntax() {
        var manifest = SecurityAnalyzerTest.analyze(new SourceFile("sql.tys", "let db = Database.sqlite(\"test.db\")\nevent player.chat {\n    db.execute(\"INSERT INTO messages(text) VALUES (?)\", [message])\n}\n"));
        assertFalse(manifest.findings().stream().anyMatch(f -> f.category() == SecurityCategory.SQL_INJECTION));
        SourceFile rejected = new SourceFile("reject.tys", "let db = Database.sqlite(\"test.db\")\nevent player.chat {\n    db.execute(\"DELETE FROM users WHERE name = '\" + message + \"'\", [])\n}\n");
        var result = new Compiler(StandardLibrary.registry()).compile(List.of(rejected));
        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().diagnostics().stream().anyMatch(d -> d.code().name().equals("CONSTANT_TEXT_REQUIRED") && d.location().line() == 3));
        var findings = new SecurityAnalyzer(SecurityOptions.defaults()).analyze(result.modules().stream().map(m -> m.bound()).filter(java.util.Objects::nonNull).toList());
        assertTrue(findings.get(rejected.path()).findings().stream().anyMatch(f -> f.category() == SecurityCategory.DYNAMIC_SQL && f.location().startLine() == 3));
    }
    @Test void taskQuotaRejectsDeclarationsBeforeAnyTaskStarts() {
        SourceFile source = new SourceFile("quota.tys", "every 1 second { log(\"one\") }\nevery 2 seconds { log(\"two\") }\n");
        var defaults = SecurityOptions.defaults();
        var options = new SecurityOptions(true, defaults.ai(), defaults.discord(), java.util.Set.of(), java.util.Map.of(), 25_000, 500, 1);
        var manifest = new SecurityAnalyzer(options).analyze(SecurityAnalyzerTest.compile(source)).get(source.path());
        assertEquals(SecurityDecision.QUARANTINE, SecurityPolicyEngine.decide(manifest.findings(), options));
        assertTrue(manifest.findings().stream().allMatch(f -> f.location().known() && f.category() == SecurityCategory.TASK_EXPLOSION));
    }
    @Test void sourceSnippetWindowsDoNotPutTheCaretAtAFabricatedColumn() {
        String text = "event player.chat {" + " ".repeat(600) + "server.dispatch(message) }\n";
        var finding = SecurityAnalyzerTest.analyze(new SourceFile("wide.tys", text)).findings().stream().filter(f -> f.category() == SecurityCategory.COMMAND_INJECTION).findFirst().orElseThrow();
        assertEquals(text.indexOf("server.dispatch") + 1, finding.location().startColumn());
        assertTrue(finding.codeSnippet().contains("[from column")); assertTrue(finding.codeSnippet().contains("server.dispatch(message)"));
    }
}
