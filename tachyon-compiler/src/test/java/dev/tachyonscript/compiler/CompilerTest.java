package dev.tachyonscript.compiler;

import dev.tachyonscript.ir.IrPrinter;
import dev.tachyonscript.language.diagnostic.Diagnostic;
import dev.tachyonscript.language.diagnostic.DiagnosticCode;
import dev.tachyonscript.language.diagnostic.DiagnosticRenderer;
import dev.tachyonscript.language.source.SourceFile;
import dev.tachyonscript.stdlib.StandardLibrary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompilerTest {

    private static final Compiler COMPILER = new Compiler(StandardLibrary.registry());

    private static CompilationResult compile(SourceFile... files) {
        return COMPILER.compile(List.of(files));
    }

    private static String render(CompilationResult result) {
        StringBuilder out = new StringBuilder();
        for (Diagnostic diagnostic : result.diagnostics().diagnostics()) {
            out.append(DiagnosticRenderer.plain().render(diagnostic));
        }
        return out.toString();
    }

    private static String ir(String source) {
        CompilationResult result = compile(new SourceFile("test.tys", source));
        assertTrue(result.succeeded(), () -> render(result));
        return IrPrinter.print(result.modules().getFirst().ir());
    }

    @Test
    void compilesFirstScriptThroughEveryStage() {
        String ir = ir("event player.join {\n    player.send(\"Hello {player.name}!\")\n}\n");
        assertEquals("""
                module test
                  on player.join -> event player.join #0

                function event player.join #0: void  [event player.join]
                  params: r0:PlayerJoinEvent
                  B0:
                    r1:Player = call event player.join:player(r0)
                    r2:string = call Entity.name:get(r1)
                    r3:Component = template "Hello " r2 "!"
                    call CommandSender.send(Component)(r1, r3)
                    return
                """, ir);
    }

    @Test
    void lowersControlFlowAndLoops() {
        String ir = ir("""
                function sum(n: int): int {
                    var total = 0
                    for i in 1..n {
                        if i % 2 == 0 && i != 4 {
                            continue
                        }
                        total += i
                    }
                    return total
                }
                """);
        assertTrue(ir.contains("loop B"), ir);
        assertTrue(ir.contains("eq_i32"), "inclusive range checks the last value before incrementing:\n" + ir);
        assertTrue(ir.contains("rem_i32"), ir);
    }

    @Test
    void lowersNullSafetyConstructs() {
        String ir = ir("""
                event player.death {
                    let name = killer?.name ?? "nobody"
                    victim.send("Killed by {name}")
                }
                """);
        assertTrue(ir.contains("is_not_null"), ir);
        assertTrue(ir.contains("template \"Killed by \""), ir);
    }

    @Test
    void reportsErrorsPerFileAndKeepsGoodModules() {
        CompilationResult result = compile(
                new SourceFile("good.tys", "event player.join {\n    player.send(\"hi\")\n}"),
                new SourceFile("bad.tys", "event player.join {\n    player.sned(\"hi\")\n}"));
        assertFalse(result.succeeded());
        assertEquals(1, result.successfulModules().size());
        assertEquals("good.tys", result.successfulModules().getFirst().file().path());
        CompiledModule bad = result.modules().getFirst();
        assertEquals("bad.tys", bad.file().path(), "modules are ordered by path");
        assertNull(bad.ir());
        assertNotNull(bad.bound());
    }

    @Test
    void rejectsDuplicateModuleNamesAndHugeFiles() {
        CompilationResult duplicate = compile(new SourceFile("a.tys", "module shared"), new SourceFile("b.tys", "module shared"));
        assertEquals(List.of(DiagnosticCode.DUPLICATE_DECLARATION),
                duplicate.diagnostics().diagnostics().stream().map(Diagnostic::code).toList());

        Compiler small = new Compiler(StandardLibrary.registry(), new CompilerOptions(10, 50, true), InternalErrorHandler.IGNORE);
        CompilationResult huge = small.compile(List.of(new SourceFile("big.tys", "const A = 1000000000")));
        assertEquals(DiagnosticCode.SOURCE_TOO_LARGE, huge.diagnostics().diagnostics().getFirst().code());
    }

    @Test
    void compilesEverySampleOfTheLanguage() {
        String source = """
                const PREFIX = "<gold>[Server]</gold> "
                const MAX_HEALTH_BONUS: double = 4.0

                /// Heals a player completely.
                function heal(target: Player) {
                    target.health = target.maxHealth
                    target.food = 20
                    target.send("{PREFIX}<green>You have been healed.")
                }

                function double(value: int): int {
                    return value * 2
                }

                event player.join {
                    player.send("<gradient:#ffffff:#a855f7>Welcome {player.name}!</gradient>")
                    if player.hasPermission("server.vip") {
                        heal(player)
                    }
                    for other in server.players {
                        if other != player {
                            other.send("{player.name} joined ({server.onlineCount} online)")
                        }
                    }
                }

                event player.death {
                    if killer != null {
                        killer.send("<yellow>You killed {victim.name}.")
                    }
                    event.keepInventory = victim.hasPermission("keep.inventory")
                }

                event block.break {
                    if !player.hasPermission("build.bypass") {
                        event.cancel()
                    }
                }

                event entity.damage {
                    if entity is Player && cause == "fall" {
                        event.damage = math.max(0.0, event.damage - MAX_HEALTH_BONUS)
                    }
                }

                event player.move {
                    if player.health < 10 {
                        player.send("<red>Low health: {math.round(player.health)}")
                    }
                }
                """;
        CompilationResult result = compile(new SourceFile("samples.tys", source));
        assertTrue(result.succeeded(), () -> render(result));
        assertTrue(result.timings().irInstructions() > 0);
    }
}
