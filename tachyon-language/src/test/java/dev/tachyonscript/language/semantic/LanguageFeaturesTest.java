package dev.tachyonscript.language.semantic;

import dev.tachyonscript.api.declaration.EventDeclaration;
import dev.tachyonscript.api.declaration.FunctionDeclaration;
import dev.tachyonscript.api.declaration.PropertyDeclaration;
import dev.tachyonscript.api.registry.KeyTable;
import dev.tachyonscript.api.registry.SymbolRegistry;
import dev.tachyonscript.api.type.ClassType;
import dev.tachyonscript.api.type.Types;
import dev.tachyonscript.language.diagnostic.Diagnostic;
import dev.tachyonscript.language.diagnostic.DiagnosticCode;
import dev.tachyonscript.language.diagnostic.DiagnosticCollector;
import dev.tachyonscript.language.diagnostic.DiagnosticRenderer;
import dev.tachyonscript.language.lexer.Lexer;
import dev.tachyonscript.language.parser.Parser;
import dev.tachyonscript.language.source.SourceFile;
import dev.tachyonscript.language.syntax.SourceUnit;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static dev.tachyonscript.api.type.Types.COMPONENT;
import static dev.tachyonscript.api.type.Types.INT;
import static dev.tachyonscript.api.type.Types.STRING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Binder tests for the language features of version 0.2. */
class LanguageFeaturesTest {

    static final ClassType MATERIAL = ClassType.builder("Material").keyed().build();
    static final ClassType SQL = ClassType.builder("Sql").constantText().build();
    static final ClassType TASK = ClassType.builder("Task").build();
    static final ClassType ITEM = ClassType.builder("ItemStack").storable().build();

    static final SymbolRegistry REGISTRY = registry();

    private static SymbolRegistry registry() {
        SymbolRegistry.Builder builder = SymbolRegistry.builder()
                .type(TestRegistry.SENDER).type(TestRegistry.ENTITY).type(TestRegistry.LIVING).type(TestRegistry.PLAYER)
                .type(TestRegistry.LOCATION).type(TestRegistry.CANCELLABLE).type(TestRegistry.JOIN_EVENT)
                .type(TestRegistry.BREAK_EVENT).type(MATERIAL).type(SQL).type(TASK).type(ITEM)
                .property(PropertyDeclaration.member(TestRegistry.ENTITY, "name", STRING).build())
                .property(PropertyDeclaration.member(TestRegistry.PLAYER, "level", INT).mutable().build())
                .function(FunctionDeclaration.method(TestRegistry.SENDER, "send").parameter("message", COMPONENT).build())
                .function(FunctionDeclaration.method(TestRegistry.CANCELLABLE, "cancel").build())
                .function(FunctionDeclaration.method(TASK, "cancel").build())
                .function(FunctionDeclaration.global("log").parameter("message", STRING).build())
                .function(FunctionDeclaration.global("give").parameter("player", TestRegistry.PLAYER)
                        .parameter("material", MATERIAL).build())
                .function(FunctionDeclaration.global("db.run").parameter("sql", SQL)
                        .parameter("arguments", Types.list(Types.nullable(Types.ANY))).build())
                .property(PropertyDeclaration.global("server.players", Types.list(TestRegistry.PLAYER)).build())
                .event(EventDeclaration.builder("player.join", TestRegistry.JOIN_EVENT)
                        .variable("player", TestRegistry.PLAYER, "The player.").build())
                .event(EventDeclaration.builder("block.break", TestRegistry.BREAK_EVENT)
                        .variable("player", TestRegistry.PLAYER, "Breaker.").cancellable().build());
        Map<String, String> materials = new LinkedHashMap<>();
        materials.put("DIAMOND", "minecraft:diamond");
        materials.put("STONE", "minecraft:stone");
        builder.keys(MATERIAL, KeyTable.of(materials));
        return builder.build();
    }

    private record Bound(BoundModule module, DiagnosticCollector diagnostics) {
        List<DiagnosticCode> codes() {
            return diagnostics.diagnostics().stream().map(Diagnostic::code).toList();
        }

        List<DiagnosticCode> errors() {
            return diagnostics.diagnostics().stream().filter(Diagnostic::isError).map(Diagnostic::code).toList();
        }

        String rendered() {
            StringBuilder out = new StringBuilder();
            diagnostics.diagnostics().forEach(d -> out.append(DiagnosticRenderer.plain().render(d)).append('\n'));
            return out.toString();
        }

        String tree() {
            return BoundPrinter.print(module).strip();
        }
    }

    private static Bound bind(String source) {
        return bind("test.tys", source, Map.of());
    }

    private static Bound bind(String path, String source, Map<String, BoundModule> modules) {
        DiagnosticCollector diagnostics = new DiagnosticCollector();
        SourceFile file = new SourceFile(path, source);
        SourceUnit unit = Parser.parse(Lexer.lex(file, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "syntax errors: " + diagnostics.diagnostics());
        return new Bound(Binder.bind(unit, REGISTRY, diagnostics, modules), diagnostics);
    }

    private static String clean(String source) {
        Bound bound = bind(source);
        assertTrue(bound.diagnostics.diagnostics().isEmpty(), bound::rendered);
        return bound.tree();
    }

    private static void assertErrors(String source, List<DiagnosticCode> codes, String... fragments) {
        Bound bound = bind(source);
        assertEquals(codes, bound.errors(), bound::rendered);
        String rendered = bound.rendered();
        for (String fragment : fragments) {
            assertTrue(rendered.contains(fragment), () -> "expected '" + fragment + "' in:\n" + rendered);
        }
    }

    private static void assertCodes(String source, List<DiagnosticCode> codes, String... fragments) {
        Bound bound = bind(source);
        assertEquals(codes, bound.codes(), bound::rendered);
        String rendered = bound.rendered();
        for (String fragment : fragments) {
            assertTrue(rendered.contains(fragment), () -> "expected '" + fragment + "' in:\n" + rendered);
        }
    }

    // ---------------------------------------------------------------- top-level variables

    @Test
    void bindsTopLevelVariablesInOrder() {
        String tree = clean("""
                let limit = 10
                var count = limit * 2
                function add() {
                    count += 1
                    count = count * 2
                    log("{count}")
                }
                """);
        assertTrue(tree.contains("(global let limit: int)"), tree);
        assertTrue(tree.contains("(global var count: int)"), tree);
        assertTrue(tree.contains("(global-set count (multiply:int @limit 2:int))"), tree);
        assertTrue(tree.contains("(global-add count 1:int)"), tree);
        assertTrue(tree.contains("(global-set count (multiply:int @count 2:int))"), tree);
    }

    @Test
    void checksTopLevelVariables() {
        assertErrors("let a = 1\nfunction f() {\n    a = 2\n}", List.of(DiagnosticCode.ASSIGN_TO_READONLY), "'let'");
        assertErrors("var a = b + 1\nvar b = 2", List.of(DiagnosticCode.USED_BEFORE_DECLARATION), "before its declaration");
        assertErrors("var a = 1\nvar a = 2", List.of(DiagnosticCode.DUPLICATE_DECLARATION));
        assertErrors("persistent var owner: Player? = null", List.of(DiagnosticCode.NOT_STORABLE), "cannot be saved");
        assertErrors("var x = null", List.of(DiagnosticCode.TYPE_MISMATCH), "Player? = null");
        assertEquals(List.of(), bind("persistent var best: Map<string, int> = {}\npersistent var item: ItemStack? = null").errors());
    }

    @Test
    void bindsPlayerData() {
        String tree = clean("""
                playerdata var coins: int = 100
                event player.join {
                    player.coins += 5
                    log("{player.coins}")
                }
                """);
        assertTrue(tree.contains("(global playerdata var coins: int)"), tree);
        assertTrue(tree.contains("(default coins { (return 100:int) })"), tree);
        assertTrue(tree.contains("(playerdata-add coins player 5:int)"), tree);
        assertErrors("playerdata var coins = 0\nfunction f() {\n    coins = 1\n}", List.of(DiagnosticCode.INVALID_ASSIGNMENT_TARGET),
                "player.coins");
        assertErrors("playerdata var name = \"x\"", List.of(DiagnosticCode.DUPLICATE_DECLARATION), "member of Player");
    }

    // ---------------------------------------------------------------- lambdas

    @Test
    void bindsLambdasAgainstExpectedTypes() {
        String tree = clean("""
                function f() {
                    let high = server.players.filter(p => p.level > 10)
                    let names = high.map(p => p.name)
                    let add: function(int, int): int = (a, b) => a + b
                    log(names.join(", ") + "{add(1, 2)}")
                }
                """);
        assertTrue(tree.contains("(let high:List<Player>"), tree);
        assertTrue(tree.contains("(let names:List<string>"), tree);
        assertTrue(tree.contains("(call-value add 1:int 2:int)"), tree);
    }

    @Test
    void capturesOnlyVariablesThatNeverChange() {
        String tree = clean("""
                function f(limit: int) {
                    var bonus = 2
                    let above = server.players.filter(p => p.level > limit + bonus)
                    log("{above.size}")
                }
                """);
        assertTrue(tree.contains("[limit=limit bonus=bonus]"), tree);
        assertErrors("""
                function f() {
                    var total = 0
                    server.players.forEach(p => log("{total}"))
                    total = 1
                }
                """, List.of(DiagnosticCode.CAPTURED_VARIABLE_CHANGES), "let totalNow = total");
        // The variable counts as used: no 'never used' warning next to the error.
        assertCodes("""
                function f() {
                    var total = 0
                    server.players.forEach(p => log("{total}"))
                    total = 1
                }
                """, List.of(DiagnosticCode.CAPTURED_VARIABLE_CHANGES));
        assertErrors("""
                function f(x: int) {
                    let g = () => {
                        x = 2
                    }
                }
                """, List.of(DiagnosticCode.ASSIGN_TO_READONLY));
    }

    @Test
    void reportsLambdaMistakes() {
        assertErrors("function f() {\n    let g = x => x\n}", List.of(DiagnosticCode.TYPE_MISMATCH), "(x: Player) => ...");
        assertErrors("function f() {\n    server.players.filter((a, b) => true)\n}", List.of(DiagnosticCode.WRONG_ARGUMENT_COUNT));
        assertErrors("function f() {\n    let g: function(int): int = x => \"a\"\n}", List.of(DiagnosticCode.TYPE_MISMATCH));
    }

    @Test
    void usesFunctionsAsValues() {
        String tree = clean("""
                function levelOf(p: Player): int {
                    return p.level
                }
                function f() {
                    let sorted = server.players.sortedBy(levelOf)
                    log("{sorted.size}")
                }
                """);
        assertTrue(tree.contains("(function-ref levelOf(Player))"), tree);
    }

    // ---------------------------------------------------------------- maps and operators

    @Test
    void bindsMaps() {
        String tree = clean("""
                function f(): int {
                    let prices = {"diamond": 100, "gold": 20}
                    var total = 0
                    for name, price in prices {
                        total += price
                    }
                    prices["iron"] = 5
                    prices["iron"] += 1
                    if "gold" in prices {
                        return prices["gold"] ?? 0
                    }
                    return total + prices.size
                }
                """);
        assertTrue(tree.contains("(let prices:Map<string, int> (map:Map<string, int> \"diamond\":100:int \"gold\":20:int))"), tree);
        assertTrue(tree.contains("(call $map.addInt prices \"iron\" 1:int)"), tree);
        assertTrue(tree.contains("(call $map.containsKey prices \"gold\")"), tree);
        assertErrors("function f() {\n    let m = {}\n}", List.of(DiagnosticCode.TYPE_MISMATCH), "Map<string, int>");
    }

    @Test
    void bindsNewOperators() {
        String tree = clean("""
                function f(a: int, b: long): long {
                    let flags = a & 3 | 4
                    let shifted = b << 2
                    let big = a > 5 ? "big" : "small"
                    var n = 1
                    n++
                    if a in 1..10 && big !in ["x"] {
                        return shifted + flags + n
                    }
                    return ~b
                }
                """);
        assertTrue(tree.contains("(bit_or:int (bit_and:int a 3:int) 4:int)"), tree);
        assertTrue(tree.contains("(shift_left:long b 2:long)"), tree);
        assertTrue(tree.contains("(? (greater:int a 5:int) \"big\" \"small\")"), tree);
        assertErrors("function f(a: bool) {\n    let x = a | true\n}", List.of(DiagnosticCode.INVALID_OPERATOR), "'||'");
    }

    // ---------------------------------------------------------------- switch, try, throw

    @Test
    void bindsSwitches() {
        clean("""
                function f(a: int): string {
                    switch a {
                        case 1, 2 -> log("small")
                        case 3 -> {
                            log("three")
                        }
                        default -> log("big")
                    }
                    return switch a {
                        case 1 -> "one"
                        default -> "many"
                    }
                }
                """);
        assertErrors("function f(a: int): string {\n    return switch a {\n        case 1 -> \"one\"\n    }\n}",
                List.of(DiagnosticCode.SWITCH_NOT_EXHAUSTIVE), "default");
        assertCodes("function f(a: int) {\n    switch a {\n        case 1 -> log(\"a\")\n        case 1 -> log(\"b\")\n    }\n}",
                List.of(DiagnosticCode.DUPLICATE_CASE));
        assertErrors("function f(a: int) {\n    for i in 0..3 {\n        switch a {\n            case 1 -> break\n        }\n    }\n}",
                List.of(DiagnosticCode.JUMP_OUTSIDE_LOOP), "falls through");
    }

    @Test
    void bindsTryCatchAndThrow() {
        String tree = clean("""
                function f(): string {
                    try {
                        throw "boom"
                    } catch e {
                        return e.message + e.kind
                    } finally {
                        log("done")
                    }
                }
                """);
        assertTrue(tree.contains("(throw \"boom\")"), tree);
        assertTrue(tree.contains("(call $error.message e)"), tree);
        assertErrors("function f() {\n    throw 5\n}", List.of(DiagnosticCode.TYPE_MISMATCH), "throw \"Not enough money\"");
        assertErrors("function f() {\n    for i in 0..2 {\n        try {\n        } finally {\n            break\n        }\n    }\n}",
                List.of(DiagnosticCode.JUMP_OUT_OF_FINALLY));
    }

    // ---------------------------------------------------------------- records

    @Test
    void bindsRecords() {
        String tree = clean("""
                record Warp(name: string, cost: int = 10) {
                    function label(): string {
                        return "{name} ({cost})"
                    }
                }
                function f(): string {
                    let warp = Warp("spawn")
                    let other = Warp("shop", 5)
                    return warp.label() + other.name
                }
                """);
        assertTrue(tree.contains("(record Warp (name:string cost:int))"), tree);
        assertTrue(tree.contains("(new Warp \"spawn\" 10:int)"), tree);
        assertTrue(tree.contains("(call Warp.label() warp)"), tree);
        assertErrors("record Warp(name: string)\nfunction f(w: Warp) {\n    w.name = \"x\"\n}",
                List.of(DiagnosticCode.ASSIGN_TO_READONLY), "records cannot be changed");
        assertErrors("record Warp(name: string)\nfunction f() {\n    let w = Warp()\n}", List.of(DiagnosticCode.WRONG_ARGUMENT_COUNT),
                "Missing a value for field 'name'");
        assertErrors("record warp(name: string)", List.of(DiagnosticCode.DUPLICATE_DECLARATION), "capital");
    }

    // ---------------------------------------------------------------- commands, tasks, hooks

    @Test
    void bindsCommands() {
        String tree = clean("""
                @permission("x.give")
                @cooldown(5 seconds)
                command give(target: Player, material: Material = Material.STONE, amount: int = 1) {
                    give(target, material)
                    sender.send("Given {amount}")
                }
                command warp.set(name: string...) {
                    if player != null {
                        player.send("Set {name}")
                    }
                }
                """);
        assertTrue(tree.contains("(command /give target:Player material:Material? amount:int?"), tree);
        assertTrue(tree.contains("(command /warp set name:string..."), tree);
        assertErrors("command bad(values: List<int>) {\n}", List.of(DiagnosticCode.INVALID_COMMAND), "cannot take a parameter");
        assertErrors("@permision(\"x\")\ncommand bad {\n}", List.of(DiagnosticCode.INVALID_ANNOTATION), "@permission");
        assertErrors("command Bad {\n}", List.of(DiagnosticCode.INVALID_COMMAND), "bad");
        // An invalid parameter is still known in the body: one error, no follow-up 'unknown name'.
        assertErrors("command bad(amount: int = 1, name: string) {\n    sender.send(\"{name} {amount}\")\n}",
                List.of(DiagnosticCode.INVALID_COMMAND), "Required parameters must come before optional ones");
        assertErrors("command bad(text: string..., count: int) {\n    sender.send(\"{text} {count}\")\n}",
                List.of(DiagnosticCode.INVALID_COMMAND), "Only the last parameter");
        assertErrors("function one(): int {\n    return 1\n}\ncommand bad(count: int = one()) {\n    sender.send(\"{count}\")\n}",
                List.of(DiagnosticCode.NOT_CONSTANT), "must be a constant");
        assertErrors("command bad(values: List<int>) {\n    sender.send(\"{values}\")\n}",
                List.of(DiagnosticCode.INVALID_COMMAND), "cannot take a parameter");
    }

    @Test
    void bindsTasksHooksAndSchedules() {
        String tree = clean("""
                every 5 minutes {
                    log("tick")
                }
                at "20:30" {
                    log("evening")
                }
                on load {
                    log("loaded")
                }
                event player.join {
                    let name = player.name
                    after 5 seconds {
                        player.send("Welcome {name}")
                    }
                    every 1 second {
                        task.cancel()
                    }
                }
                """);
        assertTrue(tree.contains("(every 300000ms"), tree);
        assertTrue(tree.contains("(at 1230min"), tree);
        assertTrue(tree.contains("(call $sched.after (reinterpret:long 5000ms) (lambda"), tree);
        assertCodes("event block.break {\n    after 1 second {\n        event.cancel()\n    }\n}",
                List.of(DiagnosticCode.EVENT_USED_LATER));
        assertErrors("at \"25:00\" {\n}", List.of(DiagnosticCode.NOT_CONSTANT), "HH:mm");
    }

    @Test
    void bindsEventAnnotations() {
        String tree = clean("@priority(HIGH)\n@ignoreCancelled\nevent block.break {\n    event.cancel()\n}");
        assertTrue(tree.contains("(event block.break @HIGH @ignoreCancelled"), tree);
        assertErrors("@priority(HUGE)\nevent block.break {\n}", List.of(DiagnosticCode.INVALID_ANNOTATION), "MONITOR");
        assertErrors("@ignoreCancelled\nevent player.join {\n}", List.of(DiagnosticCode.INVALID_ANNOTATION), "cannot be cancelled");
    }

    // ---------------------------------------------------------------- keyed constants and constant text

    @Test
    void checksKeyedConstants() {
        String tree = clean("event player.join {\n    give(player, Material.DIAMOND)\n}");
        assertTrue(tree.contains("Material.DIAMOND"), tree);
        assertErrors("event player.join {\n    give(player, Material.DIAMOD)\n}", List.of(DiagnosticCode.UNKNOWN_CONSTANT),
                "DIAMOND");
        assertErrors("event player.join {\n    give(player, Material.diamond)\n}", List.of(DiagnosticCode.UNKNOWN_CONSTANT),
                "capitals");
    }

    @Test
    void requiresConstantSqlText() {
        clean("event player.join {\n    db.run(\"UPDATE bank SET coins = coins + 1 WHERE name = ?\", [player.name])\n}");
        assertErrors("event player.join {\n    db.run(\"DELETE FROM bank WHERE name = '\" + player.name + \"'\", [])\n}",
                List.of(DiagnosticCode.CONSTANT_TEXT_REQUIRED), "Write '?' where a value goes");
    }

    // ---------------------------------------------------------------- imports

    @Test
    void importsOtherModules() {
        Bound economy = bind("economy.tys", """
                module economy
                const START = 100
                persistent var total: long = 0
                record Account(owner: string, coins: int)
                function pay(p: Player, amount: int) {
                    total += amount
                }
                """, Map.of());
        assertTrue(economy.diagnostics.diagnostics().isEmpty(), economy::rendered);
        Bound shop = bind("shop.tys", """
                import economy
                import {pay, Account} from economy
                event player.join {
                    pay(player, economy.START)
                    economy.pay(player, 1)
                    let account = Account(player.name, 5)
                    economy.total += account.coins
                }
                """, Map.of("economy", economy.module));
        assertTrue(shop.diagnostics.diagnostics().isEmpty(), shop::rendered);
        Bound broken = bind("shop.tys", "import econmy\n", Map.of("economy", economy.module));
        assertEquals(List.of(DiagnosticCode.UNKNOWN_MODULE), broken.errors(), broken::rendered);
    }
}
