package dev.tachyonscript.language.semantic;

import dev.tachyonscript.language.diagnostic.Diagnostic;
import dev.tachyonscript.language.diagnostic.DiagnosticCode;
import dev.tachyonscript.language.diagnostic.DiagnosticCollector;
import dev.tachyonscript.language.diagnostic.DiagnosticRenderer;
import dev.tachyonscript.language.diagnostic.Severity;
import dev.tachyonscript.language.lexer.Lexer;
import dev.tachyonscript.language.parser.Parser;
import dev.tachyonscript.language.source.SourceFile;
import dev.tachyonscript.language.syntax.SourceUnit;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinderTest {

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
        DiagnosticCollector diagnostics = new DiagnosticCollector();
        SourceFile file = new SourceFile("test.tys", source);
        SourceUnit unit = Parser.parse(Lexer.lex(file, diagnostics), diagnostics);
        assertFalse(diagnostics.hasErrors(), () -> "syntax errors: " + diagnostics.diagnostics());
        return new Bound(Binder.bind(unit, TestRegistry.REGISTRY, diagnostics), diagnostics);
    }

    private static String clean(String source) {
        Bound bound = bind(source);
        assertTrue(bound.diagnostics.diagnostics().isEmpty(), bound::rendered);
        return bound.tree();
    }

    /** Binds statements inside a function with a Player parameter and returns the body. */
    private static String body(String statements) {
        String tree = clean("function f(player: Player) {\n" + statements + "\n}");
        String prefix = "(function f(Player): void { ";
        assertTrue(tree.startsWith(prefix), tree);
        return tree.substring(prefix.length(), tree.length() - " })".length());
    }

    private static Bound bindBody(String statements) {
        return bind("function f(player: Player) {\n" + statements + "\n}");
    }

    private static void assertError(String statements, DiagnosticCode code, String... fragments) {
        Bound bound = bindBody(statements);
        assertEquals(List.of(code), bound.errors(), bound::rendered);
        String rendered = bound.rendered();
        for (String fragment : fragments) {
            assertTrue(rendered.contains(fragment), () -> "expected '" + fragment + "' in:\n" + rendered);
        }
    }

    // ---------------------------------------------------------------- first script

    @Test
    void bindsFirstScript() {
        assertEquals("(event player.join [player] { (call CommandSender.send(Component) player "
                        + "(template \"Hello \" (call Entity.name:get player) \"!\")) })",
                clean("event player.join {\n    player.send(\"Hello {player.name}!\")\n}"));
    }

    @Test
    void onlyUsedEventVariablesAreInitialized() {
        assertEquals("(event player.death [killer] { (if (non-null killer) { (call CommandSender.send(Component) "
                        + "killer!Player (template \"Kill!\")) }) })",
                clean("event player.death {\n    if killer != null {\n        killer.send(\"Kill!\")\n    }\n}"));
    }

    // ---------------------------------------------------------------- type errors from the spec

    @Test
    void reportsTypeMismatchWithExpectedAndReceived() {
        assertError("let kills: int = \"hello\"", DiagnosticCode.TYPE_MISMATCH,
                "Type mismatch for variable 'kills'.", "Expected:\n    int", "Received:\n    string");
    }

    @Test
    void reportsNoMatchingOverload() {
        assertError("player.teleport(123)", DiagnosticCode.NO_MATCHING_OVERLOAD,
                "No matching overload for Player.teleport.", "teleport(destination: Location)", "Received:\n    (int)");
    }

    @Test
    void reportsArgumentMismatchForUserFunctions() {
        Bound bound = bind("""
                function reward(player: Player, amount: int) {
                }
                event player.join {
                    reward(player, "100")
                }
                """);
        assertEquals(List.of(DiagnosticCode.TYPE_MISMATCH), bound.errors(), bound::rendered);
        assertTrue(bound.rendered().contains("Argument #2 of 'reward' has the wrong type."), bound::rendered);
        assertTrue(bound.rendered().contains("4:20"), bound::rendered);
    }

    @Test
    void suggestsMembersNamesEventsAndTypes() {
        assertError("player.sned(\"x\")", DiagnosticCode.UNKNOWN_MEMBER, "Unknown member 'sned' on Player.", "send");
        assertError("plyer.send(\"x\")", DiagnosticCode.UNKNOWN_NAME, "Did you mean:\n    player");
        assertError("let s: String = \"x\"", DiagnosticCode.UNKNOWN_TYPE, "Did you mean:\n    string");
        assertError("math.abss(1)", DiagnosticCode.UNKNOWN_MEMBER, "abs");
        Bound bound = bind("event player.jion {\n}");
        assertEquals(List.of(DiagnosticCode.UNKNOWN_EVENT), bound.codes());
        assertTrue(bound.rendered().contains("player.join"), bound::rendered);
    }

    // ---------------------------------------------------------------- null safety

    @Test
    void requiresNullChecks() {
        Bound bound = bind("event player.death {\n    killer.send(\"x\")\n}");
        assertEquals(List.of(DiagnosticCode.NULLABLE_ACCESS), bound.errors(), bound::rendered);
        assertTrue(bound.rendered().contains("if killer != null"), bound::rendered);
    }

    @Test
    void narrowsAfterEarlyReturnAndLogicalOperators() {
        clean("event player.death {\n    if killer == null {\n        return\n    }\n    killer.send(\"x\")\n}");
        clean("event player.death {\n    if killer != null && killer.health < 5 {\n        killer.send(\"x\")\n    }\n}");
        clean("event player.death {\n    if killer == null || killer.health > 5 {\n        return\n    }\n"
                + "    killer.send(\"x\")\n}");
        Bound reassigned = bind("function f(p: Player?, q: Player?) {\n    var x = p\n    if x != null {\n"
                + "        x = q\n        x.send(\"x\")\n    }\n}");
        assertEquals(List.of(DiagnosticCode.NULLABLE_ACCESS), reassigned.errors(), reassigned::rendered);
    }

    @Test
    void loopsForgetFactsAboutVariablesAssignedInside() {
        Bound bound = bind("function f(p: Player?) {\n    var x = p\n    if x != null {\n        while true {\n"
                + "            x.send(\"a\")\n            x = null\n        }\n    }\n}");
        assertEquals(List.of(DiagnosticCode.NULLABLE_ACCESS), bound.errors(), bound::rendered);
    }

    @Test
    void supportsSafeCallsAndCoalescing() {
        assertEquals("(let name:string (?? (?. (call server.player(string) \"Aurora\") -> $t0 (call Entity.name:get $t0!Player)) "
                        + "-> $t1 $t1!string \"Unknown\")) (call log(string) name)",
                body("let name = server.player(\"Aurora\")?.name ?? \"Unknown\"\nlog(name)"));
        assertEquals("(call log(string) (concat \"health \" (to-string:string (?? (?. (call server.player(string) \"x\") "
                        + "-> $t0 (box:double? (call LivingEntity.health:get $t0!Player))) -> $t1 (unbox:double $t1) 0.0:double))))",
                body("log(\"health {server.player(\"x\")?.health ?? 0}\")"));
    }

    @Test
    void narrowsWithIs() {
        assertEquals("(function f(Entity): void { (if (is e Player) { (call CommandSender.send(Component) e!Player "
                        + "(template \"hi\")) }) })",
                clean("function f(e: Entity) {\n    if e is Player {\n        e.send(\"hi\")\n    }\n}"));
    }

    // ---------------------------------------------------------------- subtyping and conversions

    @Test
    void respectsSubtyping() {
        assertEquals("(let entity:Entity player) (call log(string) (call Entity.name:get entity))",
                body("let entity: Entity = player\nlog(entity.name)"));
        assertError("let entity: Entity = player\nlet p: Player = entity\nlog(p.name)", DiagnosticCode.TYPE_MISMATCH,
                "Expected:\n    Player", "Received:\n    Entity");
    }

    @Test
    void typesNumericLiteralsByContext() {
        assertEquals("(let x:double 5.0:double) (let y:double (add:double 1.0:double 2.5:double))"
                        + " (let z:long 3000000000:long) (let m:int -2147483648:int) (call log(string) (concat (to-string:string x)"
                        + " (to-string:string y) (to-string:string z) (to-string:string m)))",
                body("let x: double = 5\nlet y = 1 + 2.5\nlet z = 3000000000L\nlet m = -2147483648\nlog(\"{x}{y}{z}{m}\")"));
        assertError("let big = 3000000000\nlog(\"{big}\")", DiagnosticCode.LITERAL_OUT_OF_RANGE, "3000000000L");
    }

    @Test
    void resolvesOverloadsAtCompileTime() {
        assertEquals("(call log(string) (concat (to-string:string (call math.abs(int) -5:int)) \" \" "
                        + "(to-string:string (call math.abs(double) 2.5:double)) \" \" "
                        + "(to-string:string (call math.max(double, double) 1.0:double 2.5:double))))",
                body("log(\"{math.abs(-5)} {math.abs(2.5)} {math.max(1, 2.5)}\")"));
    }

    @Test
    void supportsDurations() {
        assertEquals("(let d:Duration 5000ms) (let e:Duration (multiply:long d 2:long)) "
                        + "(call log(string) (to-string:string (add:long d e)))",
                body("let d = 5 seconds\nlet e = d * 2\nlog(\"{d + e}\")"));
    }

    // ---------------------------------------------------------------- templates and constants

    @Test
    void mergesConstantsIntoMessageTemplates() {
        assertEquals("(const PREFIX:string \"<gold>[S]</gold> \")\n(const MAX:int 100)\n"
                        + "(event player.join [player] { (call CommandSender.send(Component) player "
                        + "(template \"<gold>[S]</gold> Hi \" (call Entity.name:get player) \", max 100\")) })",
                clean("const PREFIX = \"<gold>[S]</gold> \"\nconst MAX: int = 10 * 10\n"
                        + "event player.join {\n    player.send(\"{PREFIX}Hi {player.name}, max {MAX}\")\n}"));
    }

    @Test
    void usesToStringMembersForInterpolation() {
        assertEquals("(call log(string) (concat \"p=\" (call Player.toString() player)))", body("log(\"p={player}\")"));
    }

    @Test
    void refusesToFormatRuntimeTextImplicitly() {
        Bound variable = bindBody("let text = \"<red>hi \" + player.name\nplayer.send(text)");
        assertEquals(List.of(DiagnosticCode.UNSAFE_TEXT_FORMATTING), variable.errors(), variable::rendered);
        assertTrue(variable.rendered().contains("\"{text}\"") && variable.rendered().contains("text.mini(text)"),
                variable::rendered);
        for (String unsafe : List.of("player.send(\"<red>\" + player.name)",
                "let maybe: string? = null\nplayer.send(maybe ?? \"none\")")) {
            Bound bound = bindBody(unsafe);
            assertEquals(List.of(DiagnosticCode.UNSAFE_TEXT_FORMATTING), bound.errors(), bound::rendered);
        }
        // Constant text is formatted once; values in templates are inserted as plain text.
        Bound safe = bindBody("player.send(\"<red>\" + \"hi\")\nplayer.send(\"<red>{player.name}\")");
        assertEquals(List.of(), safe.errors(), safe::rendered);
    }

    @Test
    void hintsWhenInterpolatedTextContainsTags() {
        Bound bound = bindBody("let prefix = \"<red>[VIP]\"\nplayer.send(\"{prefix} hi\")");
        assertEquals(List.of(DiagnosticCode.INTERPOLATION_NOT_FORMATTED), bound.codes(), bound::rendered);
        assertEquals(Severity.HINT, bound.diagnostics.diagnostics().getFirst().severity());
    }

    @Test
    void checksConstants() {
        assertEquals(List.of(DiagnosticCode.CONSTANT_CYCLE), bind("const A = B + 1\nconst B = A + 1").errors());
        assertEquals(List.of(DiagnosticCode.NOT_CONSTANT), bind("const P = server.players").errors());
        assertEquals(List.of(DiagnosticCode.LITERAL_OUT_OF_RANGE), bind("const O = 2147483647 + 1").errors());
        assertEquals(List.of(DiagnosticCode.DIVISION_BY_ZERO), bind("const Z = 1 / 0").errors());
        assertEquals(List.of(DiagnosticCode.ASSIGN_TO_READONLY),
                bind("const K = 1\nfunction f() {\n    K = 2\n}").errors());
    }

    // ---------------------------------------------------------------- statements and control flow

    @Test
    void checksReturns() {
        assertEquals(List.of(DiagnosticCode.MISSING_RETURN),
                bind("function f(x: bool): int {\n    if x {\n        return 1\n    }\n}").errors());
        clean("function f(x: bool): int {\n    if x {\n        return 1\n    } else {\n        return 2\n    }\n}");
        clean("function f(): int {\n    while true {\n    }\n}");
        assertEquals(List.of(DiagnosticCode.MISSING_RETURN_VALUE), bind("function f(): int {\n    return\n}").errors());
        assertEquals(List.of(DiagnosticCode.UNEXPECTED_RETURN_VALUE),
                bind("event player.join {\n    return 5\n}").errors());
        assertEquals("(function double(int): int { (return (multiply:int value 2:int)) })",
                clean("function double(value: int): int {\n    return value * 2\n}"));
    }

    @Test
    void warnsAboutUnreachableAndUnusedCode() {
        Bound bound = bindBody("return\nplayer.send(\"never\")");
        assertEquals(List.of(DiagnosticCode.UNREACHABLE_CODE), bound.codes());
        Bound unused = bindBody("let x = 5");
        assertEquals(List.of(DiagnosticCode.UNUSED_VARIABLE), unused.codes());
        Bound expression = bindBody("player.health + 1");
        assertEquals(List.of(DiagnosticCode.UNUSED_EXPRESSION), expression.codes());
    }

    @Test
    void checksAssignments() {
        assertEquals("(let kills:int 0:int) (set kills (add:int kills 1:int)) (call log(string) (to-string:string kills))",
                body("var kills = 0\nkills += 1\nlog(\"{kills}\")"));
        assertEquals("(call LivingEntity.health:set player (call LivingEntity.maxHealth:get player)) "
                        + "(call Player.food:set player 20:int)",
                body("player.health = player.maxHealth\nplayer.food = 20"));
        assertError("let x = 1\nx = 2\nlog(\"{x}\")", DiagnosticCode.ASSIGN_TO_READONLY, "'let'", "'var'");
        assertError("player.maxHealth = 5.0", DiagnosticCode.ASSIGN_TO_READONLY, "read-only");
        assertEquals(List.of(DiagnosticCode.ASSIGN_TO_READONLY), bind("event player.join {\n    player = player\n}").errors());
        assertError("var x: int = 1\nx += 1.5\nlog(\"{x}\")", DiagnosticCode.TYPE_MISMATCH, "Expected:\n    int");
    }

    @Test
    void hoistsReceiversOfCompoundPropertyAssignments() {
        assertEquals("{ (let $t0:Player (list-get (call server.players:get) 0:int)) (call LivingEntity.health:set $t0 "
                        + "(add:double (call LivingEntity.health:get $t0) 1.0:double)) }",
                body("server.players[0].health += 1"));
        assertEquals("(call LivingEntity.health:set player (add:double (call LivingEntity.health:get player) 1.0:double))",
                body("player.health += 1"), "a local receiver is not copied");
    }

    @Test
    void checksLoops() {
        assertEquals("(for p:Player (call server.players:get) { (call CommandSender.send(Component) p (template \"hi\")) })",
                body("for p in server.players {\n    p.send(\"hi\")\n}"));
        assertEquals("(for i:int 1:int .. 10:int { (call log(string) (to-string:string i)) })",
                body("for i in 1..10 {\n    log(\"{i}\")\n}"));
        assertEquals(List.of(DiagnosticCode.JUMP_OUTSIDE_LOOP), bindBody("break").errors());
        assertEquals(List.of(DiagnosticCode.NOT_ITERABLE), bindBody("for x in 5 {\n}").errors());
    }

    @Test
    void supportsLists() {
        assertEquals("(let names:List<string> (list:List<string>)) (list-add names \"Steve\") "
                        + "(call log(string) (concat (to-string:string (list-size names)) (list-get names 0:int)))",
                body("let names: List<string> = []\nnames.add(\"Steve\")\nlog(\"{names.size}{names[0]}\")"));
        assertEquals(List.of(DiagnosticCode.TYPE_MISMATCH), bindBody("let bad = []").errors());
        assertEquals("(let values:List<double> (list:List<double> 1.0:double 2.5:double)) (call log(string) "
                        + "(to-string:string values))",
                body("let values = [1, 2.5]\nlog(\"{values}\")"));
    }

    // ---------------------------------------------------------------- events and namespaces

    @Test
    void checksEventCancellation() {
        Bound bound = bind("event player.join {\n    event.cancel()\n}");
        assertEquals(List.of(DiagnosticCode.EVENT_NOT_CANCELLABLE), bound.errors(), bound::rendered);
        assertEquals("(event block.break [player] { (if (not (call CommandSender.hasPermission(string) player "
                        + "\"build.bypass\")) { (call Cancellable.cancel() event) }) })",
                clean("event block.break {\n    if !player.hasPermission(\"build.bypass\") {\n        event.cancel()\n    }\n}"));
    }

    @Test
    void distinguishesNamespacesFunctionsAndValues() {
        assertEquals("(call log(string) \"a\") (call log.info(string) \"b\")", body("log(\"a\")\nlog.info(\"b\")"));
        assertError("let x = server\nlog(\"{x}\")", DiagnosticCode.NOT_A_VALUE, "namespace");
        assertError("let x = player.send\nlog(\"{x}\")", DiagnosticCode.NOT_A_VALUE, "method");
        assertError("let x = player.send(\"a\")", DiagnosticCode.VOID_VALUE);
        assertError("player.health()", DiagnosticCode.NOT_CALLABLE, "property");
    }

    @Test
    void warnsAboutDeprecatedApi() {
        Bound bound = bindBody("player.message(\"x\")");
        assertEquals(List.of(DiagnosticCode.DEPRECATED), bound.codes());
        assertTrue(bound.rendered().contains("Use:\n    player.send(...)"), bound::rendered);
    }

    @Test
    void checksOperators() {
        assertError("let x = player + 1", DiagnosticCode.INVALID_OPERATOR, "Operator '+' cannot be applied to Player and int");
        assertError("let b = player == \"x\"", DiagnosticCode.INVALID_OPERATOR, "cannot be compared");
        assertError("let z = 1 / 0", DiagnosticCode.DIVISION_BY_ZERO);
        assertEquals("(let s:string \"a1true\") (call log(string) s)", body("let s = \"a\" + 1 + true\nlog(s)"));
        Bound check = bindBody("if player == null {\n}");
        assertEquals(List.of(DiagnosticCode.UNNECESSARY_NULL_CHECK), check.codes(), check::rendered);
    }

    @Test
    void checksCasts() {
        assertEquals("(let e:Entity player) (let p:Player (as e Player)) (let q:Player? (as? e Player)) "
                        + "(let i:int (numeric:int 2.7:double)) (call log(string) (concat (call Player.toString() p) "
                        + "(?? (?. q -> $t0 (call Player.toString() $t0!Player)) -> $t1 $t1!string \"null\") (to-string:string i)))",
                body("let e: Entity = player\nlet p = e as Player\nlet q = e as? Player\nlet d = 2.7\nlet i = d as int\n"
                        + "log(\"{p}{q}{i}\")").replace("(let d:double 2.7:double) ", "").replace("(numeric:int d)",
                        "(numeric:int 2.7:double)"));
    }
}
