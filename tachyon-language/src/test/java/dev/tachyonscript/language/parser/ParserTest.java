package dev.tachyonscript.language.parser;

import dev.tachyonscript.language.diagnostic.Diagnostic;
import dev.tachyonscript.language.diagnostic.DiagnosticCode;
import dev.tachyonscript.language.diagnostic.DiagnosticCollector;
import dev.tachyonscript.language.diagnostic.DiagnosticRenderer;
import dev.tachyonscript.language.lexer.Lexer;
import dev.tachyonscript.language.source.SourceFile;
import dev.tachyonscript.language.syntax.Declaration;
import dev.tachyonscript.language.syntax.SourceUnit;
import dev.tachyonscript.language.syntax.SyntaxPrinter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParserTest {

    private record Parsed(SourceUnit unit, DiagnosticCollector diagnostics) {
        List<DiagnosticCode> codes() {
            return diagnostics.diagnostics().stream().map(Diagnostic::code).toList();
        }

        String tree() {
            return SyntaxPrinter.print(unit).strip();
        }
    }

    private static Parsed parse(String source) {
        DiagnosticCollector diagnostics = new DiagnosticCollector();
        SourceFile file = new SourceFile("test.tys", source);
        SourceUnit unit = Parser.parse(Lexer.lex(file, diagnostics), diagnostics);
        return new Parsed(unit, diagnostics);
    }

    private static String tree(String source) {
        Parsed parsed = parse(source);
        assertFalse(parsed.diagnostics.hasErrors(), () -> render(parsed));
        return parsed.tree();
    }

    /** Parses {@code expression} as the value of a statement inside a function body. */
    private static String expression(String expression) {
        String printed = tree("function f() {\n    " + expression + "\n}");
        return printed.substring("(function f () { ".length(), printed.length() - " })".length());
    }

    private static String render(Parsed parsed) {
        StringBuilder out = new StringBuilder();
        for (Diagnostic diagnostic : parsed.diagnostics.diagnostics()) {
            out.append(DiagnosticRenderer.plain().render(diagnostic)).append('\n');
        }
        return out.toString();
    }

    // ------------------------------------------------------------ declarations

    @Test
    void parsesFirstScript() {
        assertEquals("(event player.join { (call (. player send) (template \"Hello \" (. player name) \"!\")) })",
                tree("event player.join {\n    player.send(\"Hello {player.name}!\")\n}\n"));
    }

    @Test
    void parsesFunctionsWithParametersAndReturnTypes() {
        assertEquals("(function double (value:int) : int { (return (* value 2)) })",
                tree("function double(value: int): int {\n    return value * 2\n}"));
        assertEquals("(function reward (player:Player amount:int) : void { (call give player amount) })",
                tree("function reward(player: Player, amount: int): void { give(player, amount) }"));
        assertEquals("(function f (a:List<string> b:Map<string,Player?>) {})",
                tree("function f(a: List<string>, b: Map<string, Player?>) {}"));
    }

    @Test
    void parsesConstantsModulesAndImports() {
        Parsed parsed = parse("""
                module economy.currency
                import utilities.messages
                import { formatMoney, parseMoney } from economy
                import a.b as c
                const MAX_PLAYERS: int = 100
                const PREFIX = "<gold>[Server]</gold>"
                """);
        assertFalse(parsed.diagnostics.hasErrors(), () -> render(parsed));
        assertEquals("""
                (module economy.currency)
                (import utilities.messages)
                (import economy {formatMoney parseMoney})
                (import a.b as c)
                (const MAX_PLAYERS:int 100)
                (const PREFIX "<gold>[Server]</gold>")""", parsed.tree());
    }

    @Test
    void attachesDocumentationComments() {
        Parsed parsed = parse("""
                // unrelated
                /// Gives a player their reward.
                /// Second line.
                function reward() {}
                /// Not attached: separated by a declaration.
                const A = 1
                function other() {}
                """);
        Declaration.Function reward = (Declaration.Function) parsed.unit.declarations().get(0);
        assertEquals("Gives a player their reward.\nSecond line.", reward.documentation());
        Declaration.Function other = (Declaration.Function) parsed.unit.declarations().get(2);
        assertEquals("", other.documentation());
    }

    // ------------------------------------------------------------ statements

    @Test
    void parsesVariablesAndAssignments() {
        assertEquals("(let amount:int 5) (var kills 0) (+= kills 1) (= (. player health) (. player maxHealth))"
                        + " (-= x 2) (*= x 3) (/= x 4) (%= x 5)",
                expression("let amount: int = 5\n var kills = 0\n kills += 1\n player.health = player.maxHealth\n"
                        + " x -= 2; x *= 3; x /= 4; x %= 5"));
    }

    @Test
    void parsesControlFlow() {
        assertEquals("(if (< (. player health) 10) { (call (. player send) \"low\") } else { (call (. player send) \"ok\") })",
                expression("if player.health < 10 {\n player.send(\"low\")\n } else {\n player.send(\"ok\")\n }"));
        assertEquals("(if a { } else (if b { } else { }))".replace("{ }", "{}"),
                expression("if a {\n}\nelse if b {\n} else {}"));
        assertEquals("(while (< count 10) { (+= count 1) })", expression("while count < 10 { count += 1 }"));
        assertEquals("(for i (.. 1 10) { (call log i) })", expression("for i in 1..10 { log(i) }"));
        assertEquals("(for p (. server players) { (break) (continue) (return) })",
                expression("for p in server.players { break; continue\n return }"));
    }

    // ------------------------------------------------------------ expressions

    @Test
    void respectsPrecedence() {
        assertEquals("(+ a (* b c))", expression("a + b * c"));
        assertEquals("(- (- a b) c)", expression("a - b - c"));
        assertEquals("(|| a (&& b c))", expression("a || b && c"));
        assertEquals("(&& (! a) b)", expression("!a && b"));
        assertEquals("(* (- a) b)", expression("-a * b"));
        assertEquals("(&& (< (. player health) 10) (call (. player hasPermission) \"vip\"))",
                expression("player.health < 10 && player.hasPermission(\"vip\")"));
        assertEquals("(?? a (?? b c))", expression("a ?? b ?? c"));
        assertEquals("(== (?? a b) c)", expression("a ?? b == c"));
        assertEquals("(?? (?. target name) \"Unknown\")", expression("target?.name ?? \"Unknown\""));
        assertEquals("(&& (is entity Player) x)", expression("entity is Player && x"));
        assertEquals("(+ (as x double) 1)", expression("x as double + 1"));
        assertEquals("(as? x Player)", expression("x as? Player"));
        assertEquals("(.. 1 (+ n 1))", expression("1..n + 1"));
        assertEquals("(..< 0 n)", expression("0..<n"));
        assertEquals("(* (paren (+ a b)) c)", expression("(a + b) * c"));
    }

    @Test
    void parsesPostfixChainsAndLiterals() {
        assertEquals("(call (. (index (. server players) 0) send) (template \"\" (+ a b) \"\"))",
                expression("server.players[0].send(\"{a + b}\")"));
        assertEquals("[1 2L 1.5 2.5f true null \"s\"]", expression("[1, 2L, 1.5, 2.5f, true, null, \"s\",]"));
        assertEquals("(let d (duration 5 seconds))", expression("let d = 5 seconds"));
        assertEquals("(- 5)", expression("-5"));
    }

    @Test
    void handlesNewlinesInsideExpressions() {
        assertEquals("(call (. player send) \"<gradient>Hi</gradient>\")",
                expression("player.send(\n    \"<gradient>Hi</gradient>\"\n)"));
        assertEquals("(call (. (call (. builder a) ) b) )".replace(" )", ")"), expression("builder.a()\n    .b()"));
        assertEquals("(let ok (&& a b))", expression("let ok = a\n    && b"));
        assertEquals("(let total (+ a b))", expression("let total = a +\n    b"));
        assertEquals("(let x a) (- b)", expression("let x = a\n-b"), "a leading '-' starts a new statement");
    }

    // ------------------------------------------------------------ diagnostics

    @Test
    void rejectsChainedComparisons() {
        assertEquals(List.of(DiagnosticCode.CHAINED_COMPARISON), parse("function f() { let x = a < b < c }").codes());
        assertEquals(List.of(DiagnosticCode.CHAINED_COMPARISON), parse("function f() { let x = a == b == c }").codes());
    }

    @Test
    void reportsBlockNeverClosedAtEndOfFile() {
        Parsed parsed = parse("event player.join {\n    player.send(\"hi\")\n");
        assertEquals(List.of(DiagnosticCode.UNCLOSED_BLOCK), parsed.codes());
        String rendered = render(parsed);
        assertTrue(rendered.contains("The block beginning at line 1 was never closed."), rendered);
        assertTrue(rendered.contains("this block was never closed"), rendered);
    }

    @Test
    void reportsBlockNeverClosedBeforeNextDeclarationOnce() {
        Parsed parsed = parse("""
                event player.join {
                    if x {
                        player.send("hi")
                }

                event player.quit {
                    player.send("bye")
                }
                """);
        assertEquals(List.of(DiagnosticCode.UNCLOSED_BLOCK), parsed.codes(), () -> render(parsed));
        assertEquals(2, parsed.unit.declarations().size(), "the following declaration is still parsed");
    }

    @Test
    void recoversAndReportsOneErrorPerBrokenStatement() {
        Parsed parsed = parse("""
                function f() {
                    let a = (1 +
                    let b = 2
                    let c = * 3
                    let d = 4
                }
                function g() {}
                """);
        assertEquals(2, parsed.codes().size(), () -> render(parsed));
        assertEquals(2, parsed.unit.declarations().size());
    }

    @Test
    void pointsAtUnclosedParenthesis() {
        Parsed parsed = parse("function f() {\n    let a = (1 +\n    let b = 2\n}");
        assertEquals(List.of(DiagnosticCode.EXPECTED_EXPRESSION), parsed.codes(), () -> render(parsed));
        assertTrue(render(parsed).contains("this '(' is never closed"), () -> render(parsed));
    }

    @Test
    void explainsCommonMistakes() {
        assertMessage("function f() { if x = 5 { } }", DiagnosticCode.UNEXPECTED_TOKEN, "==");
        assertMessage("return 5", DiagnosticCode.MISPLACED_DECLARATION, "event player.join");
        assertMessage("player.send(\"hi\")", DiagnosticCode.MISPLACED_DECLARATION, "event player.join");
        assertMessage("evnet player.join {}", DiagnosticCode.EXPECTED_DECLARATION, "event");
        assertMessage("fn greet(p: Player) {}", DiagnosticCode.EXPECTED_DECLARATION, "function");
        assertMessage("def greet(p: Player) {}", DiagnosticCode.EXPECTED_DECLARATION, "function");
        assertMessage("on player.join {}", DiagnosticCode.EXPECTED_DECLARATION, "event");
        assertMessage("val MAX = 3", DiagnosticCode.EXPECTED_DECLARATION, "const");
        assertMessage("function f() { for (x in list) {} }", DiagnosticCode.UNEXPECTED_TOKEN, "for item in list");
        assertMessage("function f() { let x = 5 let y = 6 }", DiagnosticCode.EXPECTED_STATEMENT_END, "own line");
        assertMessage("function f() { f(1, 2 }", DiagnosticCode.EXPECTED_TOKEN, "opened here");
        assertMessage("function f() { else {} }", DiagnosticCode.UNEXPECTED_TOKEN, "'if'");
        assertMessage("function f() { let s = \"a {} b\" }", DiagnosticCode.EMPTY_INTERPOLATION, "\\{");
        assertMessage("function f() { let let = 1 }", DiagnosticCode.EXPECTED_TOKEN, "reserved keyword");
        assertMessage("function f() { x?.y = 1 }", DiagnosticCode.INVALID_ASSIGNMENT_TARGET, "null");
        assertMessage("function f() { f() = 1 }", DiagnosticCode.INVALID_ASSIGNMENT_TARGET, "Only variables");
    }

    @Test
    void reportsUnsupportedFeaturesWithAlternatives() {
        assertMessage("gui shop {\n}\nfunction ok() {}", DiagnosticCode.UNSUPPORTED_FEATURE, "Menu(");
        assertMessage("function f() {\n    wait 5 seconds\n}", DiagnosticCode.UNSUPPORTED_FEATURE, "after 5 seconds");
        assertMessage("function f() { try { } }", DiagnosticCode.EXPECTED_TOKEN, "catch");
    }

    // ------------------------------------------------------------ version 0.2 syntax

    @Test
    void parsesTopLevelVariables() {
        assertEquals("(let limit 10)\n(var count:int 0)\n(persistent var total:long 0)\n(playerdata var coins:int 100)",
                tree("let limit = 10\nvar count: int = 0\npersistent var total: long = 0\nplayerdata var coins: int = 100"));
        assertMessage("persistent let x = 1", DiagnosticCode.MISPLACED_DECLARATION, "'var'");
        assertMessage("var x: int", DiagnosticCode.MISSING_INITIALIZER, "var x");
    }

    @Test
    void parsesRecords() {
        assertEquals("(record Warp (name:string cost:int=10))",
                tree("record Warp(name: string, cost: int = 10)"));
        assertEquals("(record Warp (name:string) (function label () : string { (return name) }))",
                tree("record Warp(name: string) {\n    function label(): string {\n        return name\n    }\n}"));
    }

    @Test
    void parsesCommandsWithAnnotations() {
        assertEquals("(@permission \"x.heal\") (@cooldown (duration 5 seconds)) (command heal (target:Player?=null) {})",
                tree("@permission(\"x.heal\")\n@cooldown(5 seconds)\ncommand heal(target: Player? = null) {\n}"));
        assertEquals("(command warp.set (name:string) {})", tree("command warp.set(name: string) {}"));
        assertEquals("(command msg (target:Player message:string...) {})",
                tree("command msg(target: Player, message: string...) {}"));
        assertEquals("(command spawn () {})", tree("command spawn {}"));
    }

    @Test
    void parsesLifecycleTasksAndPlaceholders() {
        assertEquals("(on load {})\n(on unload {})", tree("on load {}\non unload {}"));
        assertEquals("(every (duration 5 minutes) {})\n(at \"20:00\" {})", tree("every 5 minutes {}\nat \"20:00\" {}"));
        assertEquals("(placeholder coins { (return \"1\") })", tree("placeholder coins { return \"1\" }"));
        assertEquals("(@priority HIGH) (@ignoreCancelled) (event block.break {})",
                tree("@priority(HIGH)\n@ignoreCancelled\nevent block.break {}"));
    }

    @Test
    void parsesImports() {
        assertEquals(2, parse("import economy\nimport {pay, balance} from economy.bank").unit().imports().size());
        assertEquals("(import economy as eco)", tree("import economy as eco"));
    }

    @Test
    void parsesLambdasMapsAndConditionals() {
        assertEquals("(let f (lambda (x) (* x 2)))", expression("let f = x => x * 2"));
        assertEquals("(let f (lambda (a b:int) (+ a b)))", expression("let f = (a, b: int) => a + b"));
        assertEquals("(let f (lambda () { (return 1) }))", expression("let f = () => {\n        return 1\n    }"));
        assertEquals("(let m {\"a\":1 \"b\":2})", expression("let m = {\"a\": 1, \"b\": 2}"));
        assertEquals("(let e {})", expression("let e: Map<string, int> = {}").replace("e:Map<string,int>", "e"));
        assertEquals("(let x (? (> a 1) \"big\" \"small\"))", expression("let x = a > 1 ? \"big\" : \"small\""));
        assertEquals("(let y (? a (? b 1 2) 3))", expression("let y = a ? b ? 1 : 2 : 3"));
    }

    @Test
    void parsesNewOperators() {
        assertEquals("(let x (| (& a b) (^ c d)))", expression("let x = a & b | c ^ d"));
        assertEquals("(let x (<< a 2))", expression("let x = a << 2"));
        assertEquals("(let x (>> a 2))", expression("let x = a >> 2"));
        assertEquals("(let x (>>> a 2))", expression("let x = a >>> 2"));
        assertEquals("(let x (~ a))", expression("let x = ~a"));
        assertEquals("(let x (in a list))", expression("let x = a in list"));
        assertEquals("(let x (!in a list))", expression("let x = a !in list"));
        assertEquals("(let x (!is a Player))", expression("let x = a !is Player"));
        assertEquals("(+= count 1)", expression("count++"));
        assertEquals("(-= count 1)", expression("count--"));
        assertEquals("(>>= bits 1)", expression("bits >>= 1"));
        assertEquals("(let n (. (. a b) c))", expression("let n = a.b.c"));
        assertEquals("(let l List<List<int>>)", expression("let l: List<List<int>> = x").replaceAll(" x\\)$", ")")
                .replace("l:List<List<int>>", "l List<List<int>>").replace("(let l List<List<int>>", "(let l List<List<int>>"));
    }

    @Test
    void parsesSwitchTryAndThrow() {
        assertEquals("(switch x (case 1 2 -> (call a)) (default -> (call b)))",
                expression("switch x {\n        case 1, 2 -> a()\n        default -> b()\n    }"));
        assertEquals("(let y (switch-expr x (case 1 -> \"one\") (default -> \"many\")))",
                expression("let y = switch x {\n        case 1 -> \"one\"\n        default -> \"many\"\n    }"));
        assertEquals("(try { (call a) } (catch e { (call b) }) (finally { (call c) }))",
                expression("try {\n        a()\n    } catch e {\n        b()\n    } finally {\n        c()\n    }"));
        assertEquals("(throw \"no\")", expression("throw \"no\""));
    }

    @Test
    void parsesSchedulingBlocks() {
        assertEquals("(after (duration 5 seconds) { (call a) })", expression("after 5 seconds {\n        a()\n    }"));
        assertEquals("(every (duration 1 seconds) for player { (call a) })", expression("every 1 second for player {\n        a()\n    }"));
        assertEquals("(async { (call a) })", expression("async {\n        a()\n    }"));
        assertEquals("(sync {})", expression("sync {}"));
        // A parenthesized delay is still a delay...
        assertEquals("(after (paren (duration 2 seconds)) { (call a) })", expression("after (2 seconds) {\n        a()\n    }"));
        assertEquals("(every (* (paren (duration 1 seconds)) 2) for player {})",
                expression("every (1 second) * 2 for player {}"));
        // ...while a call of a function named 'after' stays a call.
        assertEquals("(call after 1)", expression("after(1)"));
        // The words stay usable as names.
        assertEquals("(= after 1)", expression("after = 1"));
    }

    @Test
    void parsesFunctionTypesAndDefaults() {
        assertEquals("(function apply (f:function(int):bool x:int=1) : bool { (return (call f x)) })",
                tree("function apply(f: function(int): bool, x: int = 1): bool {\n    return f(x)\n}"));
        assertEquals("(for k,v m {})", expression("for k, v in m {}"));
    }

    @Test
    void boundsNestingDepth() {
        String deepParens = "function f() { let x = " + "(".repeat(300) + "1" + ")".repeat(300) + " }";
        assertEquals(List.of(DiagnosticCode.NESTING_TOO_DEEP), parse(deepParens).codes());

        String longChain = "function f() { let x = 1" + " + 1".repeat(1000) + " }";
        assertEquals(List.of(DiagnosticCode.NESTING_TOO_DEEP), parse(longChain).codes());

        StringBuilder deepBlocks = new StringBuilder("function f() {");
        deepBlocks.append("if x {".repeat(200)).append("}".repeat(200)).append("}");
        Parsed blocks = parse(deepBlocks.toString());
        assertTrue(blocks.codes().contains(DiagnosticCode.NESTING_TOO_DEEP), () -> blocks.codes().toString());
    }

    @Test
    void survivesGarbage() {
        for (String source : List.of("}}}}", "{{{{", "event", "event {", "function (", "const", "\"", "a..b..c",
                "function f() { ((((", "function f() { \"{\" }", "event a.b { let }", "import {", "module")) {
            Parsed parsed = parse(source);
            assertTrue(parsed.diagnostics.hasErrors(), source);
        }
    }

    private static void assertMessage(String source, DiagnosticCode code, String fragment) {
        Parsed parsed = parse(source);
        assertEquals(List.of(code), parsed.codes(), () -> render(parsed));
        String rendered = render(parsed);
        assertTrue(rendered.contains(fragment), () -> "expected '" + fragment + "' in:\n" + rendered);
    }
}
