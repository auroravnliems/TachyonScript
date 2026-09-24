package dev.tachyonscript.language.parser;

import dev.tachyonscript.language.diagnostic.Diagnostic;
import dev.tachyonscript.language.diagnostic.DiagnosticCode;
import dev.tachyonscript.language.diagnostic.DiagnosticCollector;
import dev.tachyonscript.language.lexer.Comment;
import dev.tachyonscript.language.lexer.LexResult;
import dev.tachyonscript.language.lexer.Token;
import dev.tachyonscript.language.lexer.TokenKind;
import dev.tachyonscript.language.source.SourceFile;
import dev.tachyonscript.language.source.Span;
import dev.tachyonscript.language.syntax.AssignmentOperator;
import dev.tachyonscript.language.syntax.BinaryOperator;
import dev.tachyonscript.language.syntax.BinaryOperator.Precedence;
import dev.tachyonscript.language.syntax.Declaration;
import dev.tachyonscript.language.syntax.DurationUnit;
import dev.tachyonscript.language.syntax.Expression;
import dev.tachyonscript.language.syntax.Identifier;
import dev.tachyonscript.language.syntax.QualifiedName;
import dev.tachyonscript.language.syntax.SourceUnit;
import dev.tachyonscript.language.syntax.Statement;
import dev.tachyonscript.language.syntax.TypeRef;
import dev.tachyonscript.language.syntax.UnaryOperator;
import dev.tachyonscript.language.util.Suggestions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Hand-written recursive-descent parser for declarations and statements, with a Pratt
 * (precedence-climbing) parser for expressions.
 *
 * <p><b>Error recovery.</b> When a construct cannot be parsed, a diagnostic is reported and
 * a private {@link SyntaxError} unwinds to the nearest recovery point: the enclosing
 * statement list or the top level. Recovery skips to a synchronization point (end of line,
 * {@code ;}, a closing brace, or a declaration keyword at the start of a line), so one
 * mistake produces one diagnostic. A declaration keyword at column 1 inside a block is
 * taken as evidence of a missing {@code }}, which gives a precise "block never closed"
 * message instead of a cascade.
 *
 * <p><b>Newlines.</b> Statements end at a newline or {@code ;}. An expression continues on
 * the next line only after a binary operator, or when the next line starts with {@code .},
 * {@code ?.}, {@code &&}, {@code ||} or {@code ??}. Inside parentheses and brackets the lexer
 * does not emit newlines at all.
 *
 * <p><b>Limits.</b> Nesting depth is bounded ({@link #MAX_NESTING}, {@link
 * #MAX_EXPRESSION_DEPTH}) so pathological input cannot overflow the stack of this or any
 * later compiler stage.
 */
public final class Parser {

    /** Maximum recursion depth of nested blocks and parenthesized expressions. */
    public static final int MAX_NESTING = 100;
    /** Maximum depth of an expression tree (long operator chains included). */
    public static final int MAX_EXPRESSION_DEPTH = 256;

    /** Top-level declaration keywords that are recognised but not implemented yet. */
    private static final Set<String> PLANNED_DECLARATIONS =
            Set.of("command", "playerdata", "persistent", "gui", "eventtype", "script", "cooldown");
    /** Identifiers that start a top-level declaration when followed by a name. */
    private static final Set<String> CONTEXTUAL_DECLARATIONS =
            Set.of("event", "module", "import", "command", "playerdata", "persistent", "gui", "eventtype", "script", "cooldown");

    /** Declaration keywords, for "did you mean" suggestions on misspellings. */
    private static final List<String> DECLARATION_KEYWORDS =
            List.of("event", "function", "const", "module", "import", "command", "playerdata", "persistent");
    /** Declaration keywords of other languages, mapped to the TachyonScript keyword. */
    private static final Map<String, String> DECLARATION_ALIASES = Map.ofEntries(
            Map.entry("fn", "function"), Map.entry("fun", "function"), Map.entry("func", "function"),
            Map.entry("def", "function"), Map.entry("sub", "function"), Map.entry("proc", "function"),
            Map.entry("on", "event"), Map.entry("when", "event"), Map.entry("val", "const"));

    private final SourceFile file;
    private final List<Token> tokens;
    private final List<Comment> comments;
    private final DiagnosticCollector diagnostics;
    private int index;
    private int nesting;
    /** Open '(' and '[' tokens of the expression being parsed, innermost last. */
    private final java.util.ArrayDeque<Token> openGroups = new java.util.ArrayDeque<>();

    /** Unwinds to the nearest recovery point after a diagnostic has been reported. */
    private static final class SyntaxError extends RuntimeException {
        private static final SyntaxError INSTANCE = new SyntaxError();

        private SyntaxError() {
            super(null, null, false, false);
        }
    }

    private Parser(LexResult lexed, DiagnosticCollector diagnostics) {
        this.file = lexed.file();
        this.tokens = lexed.tokens();
        this.comments = lexed.comments();
        this.diagnostics = diagnostics;
    }

    /** Parses a lexed file. Always returns a tree; problems are reported to {@code diagnostics}. */
    public static SourceUnit parse(LexResult lexed, DiagnosticCollector diagnostics) {
        return new Parser(lexed, diagnostics).parseSourceUnit();
    }

    // ============================================================== top level

    private SourceUnit parseSourceUnit() {
        Declaration.Module module = null;
        List<Declaration.Import> imports = new ArrayList<>();
        List<Declaration> declarations = new ArrayList<>();
        skipSeparators();
        while (!at(TokenKind.EOF)) {
            int before = index;
            try {
                if (atIdentifier("module") && peek(1).is(TokenKind.IDENTIFIER)) {
                    Declaration.Module parsed = parseModule();
                    if (module != null || !imports.isEmpty() || !declarations.isEmpty()) {
                        error(DiagnosticCode.MISPLACED_DECLARATION, parsed.span(),
                                "The 'module' declaration must be the first declaration of the file.");
                    } else {
                        module = parsed;
                    }
                } else if (atIdentifier("import") && (peek(1).is(TokenKind.IDENTIFIER) || peek(1).is(TokenKind.LBRACE))) {
                    Declaration.Import parsed = parseImport();
                    if (!declarations.isEmpty()) {
                        error(DiagnosticCode.MISPLACED_DECLARATION, parsed.span(),
                                "Imports must appear before other declarations.");
                    } else {
                        imports.add(parsed);
                    }
                } else {
                    Declaration declaration = parseDeclaration();
                    if (declaration != null) {
                        declarations.add(declaration);
                    }
                }
                expectDeclarationEnd();
            } catch (SyntaxError e) {
                synchronizeTopLevel(before);
            }
            skipSeparators();
        }
        return new SourceUnit(file, module, imports, declarations, new Span(0, file.length()));
    }

    private Declaration.Module parseModule() {
        Token keyword = advance();
        QualifiedName name = parseQualifiedName("module name");
        return new Declaration.Module(name, span(keyword, name.span()));
    }

    private Declaration.Import parseImport() {
        Token keyword = advance();
        if (at(TokenKind.LBRACE)) {
            Token open = advance();
            List<Identifier> names = new ArrayList<>();
            while (!at(TokenKind.RBRACE)) {
                names.add(expectIdentifier("imported name"));
                if (!accept(TokenKind.COMMA)) {
                    break;
                }
            }
            expectClosing(TokenKind.RBRACE, open, "import list");
            if (!atIdentifier("from")) {
                throw fail(DiagnosticCode.EXPECTED_TOKEN, current().span(),
                        "Expected 'from' after the import list, found " + describe(current()) + ".",
                        "Example: import { formatMoney } from economy");
            }
            advance();
            QualifiedName module = parseQualifiedName("module name");
            return new Declaration.Import(module, names, null, span(keyword, module.span()));
        }
        QualifiedName module = parseQualifiedName("module name");
        Identifier alias = null;
        Span end = module.span();
        if (at(TokenKind.AS)) {
            advance();
            alias = expectIdentifier("import alias");
            end = alias.span();
        }
        return new Declaration.Import(module, List.of(), alias, span(keyword, end));
    }

    /** Parses one top-level declaration, or returns {@code null} after reporting an unsupported one. */
    private Declaration parseDeclaration() {
        String documentation = documentationBefore(index);
        Token token = current();
        if (token.is(TokenKind.FUNCTION)) {
            return parseFunction(documentation);
        }
        if (token.is(TokenKind.CONST)) {
            return parseConst(documentation);
        }
        if (token.isIdentifier("event") && peek(1).is(TokenKind.IDENTIFIER)) {
            return parseEvent(documentation);
        }
        if (token.is(TokenKind.IDENTIFIER) && PLANNED_DECLARATIONS.contains(token.text())
                && peek(1).is(TokenKind.IDENTIFIER)) {
            diagnostics.report(Diagnostic.builder(DiagnosticCode.UNSUPPORTED_FEATURE, file, token.span(),
                            "'" + token.text() + "' declarations are not supported yet.")
                    .note("This part of the language is planned; see docs/status.md for the roadmap.").build());
            throw SyntaxError.INSTANCE;
        }
        if (token.isIdentifier("on") && (peek(1).isIdentifier("load") || peek(1).isIdentifier("unload"))) {
            diagnostics.report(Diagnostic.builder(DiagnosticCode.UNSUPPORTED_FEATURE, file, token.span().to(peek(1).span()),
                            "'on " + peek(1).text() + "' lifecycle hooks are not supported yet.")
                    .note("This part of the language is planned; see docs/status.md for the roadmap.").build());
            throw SyntaxError.INSTANCE;
        }
        if (token.is(TokenKind.IDENTIFIER) && peek(1).is(TokenKind.IDENTIFIER)) {
            String alias = DECLARATION_ALIASES.get(token.text());
            List<String> close = alias != null ? List.of(alias) : Suggestions.closest(token.text(), DECLARATION_KEYWORDS, 1);
            if (!close.isEmpty()) {
                diagnostics.report(Diagnostic.builder(DiagnosticCode.EXPECTED_DECLARATION, file, token.span(),
                        "Unknown declaration '" + token.text() + "'.").suggestions(close).build());
                throw SyntaxError.INSTANCE;
            }
        }
        if (token.is(TokenKind.LET) || token.is(TokenKind.VAR)) {
            throw fail(DiagnosticCode.MISPLACED_DECLARATION, token.span(),
                    "Variables can only be declared inside an event handler or function.",
                    "Use 'const' for a value shared by the whole script: const NAME = value");
        }
        if (startsStatement(token)) {
            throw fail(DiagnosticCode.MISPLACED_DECLARATION, token.span(),
                    "Statements must be inside an event handler or function.",
                    "Example:\n    event player.join {\n        player.send(\"Hello!\")\n    }");
        }
        throw fail(DiagnosticCode.EXPECTED_DECLARATION, token.span(),
                "Expected a declaration ('event', 'function' or 'const'), found " + describe(token) + ".", null);
    }

    private Declaration.Event parseEvent(String documentation) {
        Token keyword = advance();
        QualifiedName name = parseQualifiedName("event name");
        Statement.Block body = parseBlock("event handler '" + name.text() + "'");
        return new Declaration.Event(name, body, documentation, span(keyword, body.span()));
    }

    private Declaration.Function parseFunction(String documentation) {
        Token keyword = advance();
        Identifier name = expectIdentifier("function name");
        Token open = expect(TokenKind.LPAREN, "Expected '(' after the function name '" + name.name() + "'");
        List<Declaration.Parameter> parameters = new ArrayList<>();
        while (!at(TokenKind.RPAREN) && !at(TokenKind.EOF)) {
            Identifier parameterName = expectIdentifier("parameter name");
            if (!at(TokenKind.COLON)) {
                throw fail(DiagnosticCode.EXPECTED_TOKEN, parameterName.span(),
                        "Parameter '" + parameterName.name() + "' needs a type.",
                        "Example: " + parameterName.name() + ": int");
            }
            advance();
            TypeRef type = parseType();
            parameters.add(new Declaration.Parameter(parameterName, type, parameterName.span().to(type.span())));
            if (!accept(TokenKind.COMMA)) {
                break;
            }
        }
        expectClosing(TokenKind.RPAREN, open, "parameter list");
        TypeRef returnType = null;
        if (accept(TokenKind.COLON)) {
            returnType = parseType();
        }
        Statement.Block body = parseBlock("function '" + name.name() + "'");
        return new Declaration.Function(name, parameters, returnType, body, documentation, span(keyword, body.span()));
    }

    private Declaration.Const parseConst(String documentation) {
        Token keyword = advance();
        Identifier name = expectIdentifier("constant name");
        TypeRef type = null;
        if (accept(TokenKind.COLON)) {
            type = parseType();
        }
        if (!at(TokenKind.EQ)) {
            throw fail(DiagnosticCode.EXPECTED_TOKEN, current().span(),
                    "Expected '=' and a value for constant '" + name.name() + "', found " + describe(current()) + ".",
                    "Constants must be initialized: const " + name.name() + " = value");
        }
        advance();
        skipNewlines();
        Expression value = parseRootExpression();
        return new Declaration.Const(name, type, value, documentation, span(keyword, value.span()));
    }

    // ============================================================== statements

    private Statement.Block parseBlock(String owner) {
        skipNewlines();
        if (!at(TokenKind.LBRACE)) {
            throw fail(DiagnosticCode.EXPECTED_TOKEN, current().span(),
                    "Expected '{' to start the body of " + owner + ", found " + describe(current()) + ".", null);
        }
        enterNesting(current().span());
        Token open = advance();
        try {
            List<Statement> statements = new ArrayList<>();
            skipSeparators();
            while (!at(TokenKind.RBRACE)) {
                if (at(TokenKind.EOF)) {
                    reportUnclosed(open, current(), "Expected '}' at the end of the file.");
                    return new Statement.Block(statements, new Span(open.start(), current().start()));
                }
                if (atTopLevelDeclarationStart()) {
                    reportUnclosed(open, current(), "Expected '}' before this declaration.");
                    return new Statement.Block(statements, new Span(open.start(), current().start()));
                }
                int before = index;
                try {
                    Statement statement = parseStatement();
                    if (statement != null) {
                        statements.add(statement);
                    }
                    expectStatementEnd();
                } catch (SyntaxError e) {
                    synchronizeStatement(before);
                }
                skipSeparators();
            }
            Token close = advance();
            return new Statement.Block(statements, span(open, close.span()));
        } finally {
            nesting--;
        }
    }

    private void reportUnclosed(Token open, Token at, String message) {
        int line = file.lineOf(open.start());
        diagnostics.report(Diagnostic.builder(DiagnosticCode.UNCLOSED_BLOCK, file, Span.at(at.start()), message)
                .label(open.span(), "this block was never closed")
                .note("The block beginning at line " + line + " was never closed.")
                .build());
    }

    private Statement parseStatement() {
        Token token = current();
        return switch (token.kind()) {
            case LET, VAR -> parseLocalVariable();
            case IF -> parseIf();
            case WHILE -> parseWhile();
            case FOR -> parseFor();
            case RETURN -> parseReturn();
            case BREAK -> new Statement.Break(advance().span());
            case CONTINUE -> new Statement.Continue(advance().span());
            case LBRACE -> parseBlock("block");
            case FUNCTION, CONST -> throw fail(DiagnosticCode.MISPLACED_DECLARATION, token.span(),
                    (token.is(TokenKind.FUNCTION) ? "Functions" : "Constants")
                            + " can only be declared at the top level of a script.", null);
            case TRY, CATCH, THROW -> throw fail(DiagnosticCode.UNSUPPORTED_FEATURE, token.span(),
                    "Error handling ('" + token.text() + "') is not supported yet.",
                    "This part of the language is planned; see docs/status.md for the roadmap.");
            case ELSE -> throw fail(DiagnosticCode.UNEXPECTED_TOKEN, token.span(),
                    "'else' without a matching 'if'.", "'else' must follow the closing '}' of an 'if' block.");
            default -> {
                if (atSchedulingStatement()) {
                    throw fail(DiagnosticCode.UNSUPPORTED_FEATURE, token.span(),
                            "'" + token.text() + "' blocks are not supported yet.",
                            "Scheduling (after, every, async, sync) is planned; see docs/status.md.");
                }
                yield parseExpressionOrAssignment();
            }
        };
    }

    private boolean atSchedulingStatement() {
        Token token = current();
        Token next = peek(1);
        if (token.isIdentifier("after") || token.isIdentifier("every")) {
            return next.kind().isLiteral() && next.kind() != TokenKind.STRING_LITERAL;
        }
        if (token.isIdentifier("async")) {
            return next.is(TokenKind.LBRACE);
        }
        return false;
    }

    private Statement parseExpressionOrAssignment() {
        Expression target = parseRootExpression();
        AssignmentOperator operator = assignmentOperator(current().kind());
        if (operator == null) {
            return new Statement.ExpressionStatement(target, target.span());
        }
        Token operatorToken = advance();
        validateAssignmentTarget(target, operatorToken);
        skipNewlines();
        Expression value = parseRootExpression();
        return new Statement.Assignment(target, operator, value, target.span().to(value.span()));
    }

    private void validateAssignmentTarget(Expression target, Token operator) {
        switch (target) {
            case Expression.Name ignored -> {
            }
            case Expression.Index ignored -> {
            }
            case Expression.Member member when !member.nullSafe() -> {
            }
            case Expression.Member member -> throw fail(DiagnosticCode.INVALID_ASSIGNMENT_TARGET, member.span(),
                    "Cannot assign through '?.'.", "Check for null first: if x != null { x.value = ... }");
            default -> throw fail(DiagnosticCode.INVALID_ASSIGNMENT_TARGET, target.span(),
                    "Cannot assign to this expression with '" + operator.text() + "'.",
                    "Only variables, properties and list elements can be assigned."
                            + (operator.is(TokenKind.EQ) ? " Use '==' to compare values." : ""));
        }
    }

    private static AssignmentOperator assignmentOperator(TokenKind kind) {
        return switch (kind) {
            case EQ -> AssignmentOperator.ASSIGN;
            case PLUS_EQ -> AssignmentOperator.ADD;
            case MINUS_EQ -> AssignmentOperator.SUBTRACT;
            case STAR_EQ -> AssignmentOperator.MULTIPLY;
            case SLASH_EQ -> AssignmentOperator.DIVIDE;
            case PERCENT_EQ -> AssignmentOperator.REMAINDER;
            default -> null;
        };
    }

    private Statement.LocalVariable parseLocalVariable() {
        Token keyword = advance();
        boolean mutable = keyword.is(TokenKind.VAR);
        Identifier name = expectIdentifier("variable name");
        TypeRef type = null;
        if (accept(TokenKind.COLON)) {
            type = parseType();
        }
        Expression initializer = null;
        Span end = type != null ? type.span() : name.span();
        if (at(TokenKind.EQ)) {
            advance();
            skipNewlines();
            initializer = parseRootExpression();
            end = initializer.span();
        } else if (at(TokenKind.EQ_EQ)) {
            throw fail(DiagnosticCode.EXPECTED_TOKEN, current().span(),
                    "Use '=' to initialize a variable, not '=='.", null);
        }
        return new Statement.LocalVariable(name, type, initializer, mutable, span(keyword, end));
    }

    private Statement.If parseIf() {
        Token keyword = advance();
        Expression condition = parseRootExpression();
        if (at(TokenKind.EQ)) {
            throw fail(DiagnosticCode.UNEXPECTED_TOKEN, current().span(),
                    "Unexpected '=' in 'if' condition.", "Use '==' to compare values.");
        }
        Statement.Block thenBlock = parseBlock("'if'");
        Statement elseBranch = null;
        int save = index;
        skipNewlines();
        if (at(TokenKind.ELSE)) {
            advance();
            skipNewlines();
            elseBranch = at(TokenKind.IF) ? parseIf() : parseBlock("'else'");
        } else {
            index = save;
        }
        Span end = elseBranch != null ? elseBranch.span() : thenBlock.span();
        return new Statement.If(condition, thenBlock, elseBranch, span(keyword, end));
    }

    private Statement.While parseWhile() {
        Token keyword = advance();
        Expression condition = parseRootExpression();
        if (at(TokenKind.EQ)) {
            throw fail(DiagnosticCode.UNEXPECTED_TOKEN, current().span(),
                    "Unexpected '=' in 'while' condition.", "Use '==' to compare values.");
        }
        Statement.Block body = parseBlock("'while' loop");
        return new Statement.While(condition, body, span(keyword, body.span()));
    }

    private Statement.For parseFor() {
        Token keyword = advance();
        if (at(TokenKind.LPAREN)) {
            throw fail(DiagnosticCode.UNEXPECTED_TOKEN, current().span(),
                    "Parentheses are not used around 'for' loops.", "Write: for item in list { ... }");
        }
        Identifier variable = expectIdentifier("loop variable name");
        if (!at(TokenKind.IN)) {
            throw fail(DiagnosticCode.EXPECTED_TOKEN, current().span(),
                    "Expected 'in' after the loop variable '" + variable.name() + "', found " + describe(current()) + ".",
                    "Write: for " + variable.name() + " in list { ... }");
        }
        advance();
        Expression iterable = parseRootExpression();
        Statement.Block body = parseBlock("'for' loop");
        return new Statement.For(variable, iterable, body, span(keyword, body.span()));
    }

    private Statement.Return parseReturn() {
        Token keyword = advance();
        if (at(TokenKind.NEWLINE) || at(TokenKind.SEMICOLON) || at(TokenKind.RBRACE) || at(TokenKind.EOF)) {
            return new Statement.Return(null, keyword.span());
        }
        Expression value = parseRootExpression();
        return new Statement.Return(value, span(keyword, value.span()));
    }

    // ============================================================== expressions

    /** Parses an expression that is not nested in another one and bounds its depth. */
    private Expression parseRootExpression() {
        Expression expression = parseExpression();
        if (depthExceeds(expression, MAX_EXPRESSION_DEPTH)) {
            throw fail(DiagnosticCode.NESTING_TOO_DEEP, expression.span(),
                    "Expression is too deeply nested (more than " + MAX_EXPRESSION_DEPTH + " levels).",
                    "Split it into several statements using local variables.");
        }
        return expression;
    }

    private Expression parseExpression() {
        return parseBinary(Precedence.OR);
    }

    private Expression parseBinary(int minPrecedence) {
        enterNesting(current().span());
        try {
            Expression left = parseUnary();
            while (true) {
                OperatorToken operator = peekOperator();
                if (operator == null || operator.precedence() < minPrecedence) {
                    return left;
                }
                if (operator.afterNewline()) {
                    skipNewlines();
                }
                Token token = advance();
                switch (operator.kind()) {
                    case IS -> {
                        TypeRef type = parseType();
                        left = new Expression.Is(left, type, left.span().to(type.span()));
                    }
                    case CAST -> {
                        boolean safe = at(TokenKind.QUESTION) && current().start() == token.end();
                        if (safe) {
                            advance();
                        }
                        TypeRef type = parseType();
                        left = new Expression.Cast(left, type, safe, left.span().to(type.span()));
                    }
                    case RANGE -> {
                        skipNewlines();
                        Expression right = parseBinary(Precedence.RANGE + 1);
                        left = new Expression.Range(left, right, token.is(TokenKind.DOT_DOT), left.span().to(right.span()));
                    }
                    case BINARY -> {
                        skipNewlines();
                        BinaryOperator binary = operator.binary();
                        int next = binary.associativity() == BinaryOperator.Associativity.RIGHT
                                ? binary.precedence() : binary.precedence() + 1;
                        Expression right = parseBinary(next);
                        left = new Expression.Binary(binary, left, right, left.span().to(right.span()));
                    }
                }
                if (operator.nonAssociative()) {
                    OperatorToken following = peekOperator();
                    if (following != null && following.precedence() == operator.precedence()) {
                        throw fail(DiagnosticCode.CHAINED_COMPARISON, left.span().to(current().span()),
                                "Comparison operators cannot be chained.",
                                "Combine separate comparisons with '&&', e.g. 'a < b && b < c'.");
                    }
                }
            }
        } finally {
            nesting--;
        }
    }

    private enum OperatorKind {
        BINARY,
        IS,
        CAST,
        RANGE
    }

    private record OperatorToken(OperatorKind kind, BinaryOperator binary, int precedence, boolean afterNewline) {
        boolean nonAssociative() {
            return kind == OperatorKind.IS || kind == OperatorKind.RANGE
                    || (binary != null && binary.associativity() == BinaryOperator.Associativity.NONE);
        }
    }

    /** The binary operator at the current position, looking past a newline for leading '&&', '||' and '??'. */
    private OperatorToken peekOperator() {
        Token token = current();
        boolean afterNewline = false;
        if (token.is(TokenKind.NEWLINE)) {
            Token next = peek(1);
            if (!(next.is(TokenKind.AMP_AMP) || next.is(TokenKind.PIPE_PIPE) || next.is(TokenKind.QUESTION_QUESTION))) {
                return null;
            }
            token = next;
            afterNewline = true;
        }
        return switch (token.kind()) {
            case PIPE_PIPE -> binary(BinaryOperator.OR, afterNewline);
            case AMP_AMP -> binary(BinaryOperator.AND, afterNewline);
            case EQ_EQ -> binary(BinaryOperator.EQUAL, false);
            case BANG_EQ -> binary(BinaryOperator.NOT_EQUAL, false);
            case LT -> binary(BinaryOperator.LESS, false);
            case LT_EQ -> binary(BinaryOperator.LESS_EQUAL, false);
            case GT -> binary(BinaryOperator.GREATER, false);
            case GT_EQ -> binary(BinaryOperator.GREATER_EQUAL, false);
            case QUESTION_QUESTION -> binary(BinaryOperator.COALESCE, afterNewline);
            case PLUS -> binary(BinaryOperator.ADD, false);
            case MINUS -> binary(BinaryOperator.SUBTRACT, false);
            case STAR -> binary(BinaryOperator.MULTIPLY, false);
            case SLASH -> binary(BinaryOperator.DIVIDE, false);
            case PERCENT -> binary(BinaryOperator.REMAINDER, false);
            case IS -> new OperatorToken(OperatorKind.IS, null, Precedence.RELATIONAL, false);
            case AS -> new OperatorToken(OperatorKind.CAST, null, Precedence.CAST, false);
            case DOT_DOT, DOT_DOT_LT -> new OperatorToken(OperatorKind.RANGE, null, Precedence.RANGE, false);
            default -> null;
        };
    }

    private static OperatorToken binary(BinaryOperator operator, boolean afterNewline) {
        return new OperatorToken(OperatorKind.BINARY, operator, operator.precedence(), afterNewline);
    }

    private Expression parseUnary() {
        if (at(TokenKind.MINUS) || at(TokenKind.BANG)) {
            Token operator = advance();
            enterNesting(operator.span());
            try {
                Expression operand = parseUnary();
                UnaryOperator unary = operator.is(TokenKind.MINUS) ? UnaryOperator.NEGATE : UnaryOperator.NOT;
                return new Expression.Unary(unary, operand, span(operator, operand.span()));
            } finally {
                nesting--;
            }
        }
        return parsePostfix(parsePrimary());
    }

    private Expression parsePostfix(Expression expression) {
        while (true) {
            if (at(TokenKind.DOT) || at(TokenKind.QUESTION_DOT)
                    || (at(TokenKind.NEWLINE) && (peek(1).is(TokenKind.DOT) || peek(1).is(TokenKind.QUESTION_DOT)))) {
                skipNewlines();
                boolean nullSafe = advance().is(TokenKind.QUESTION_DOT);
                skipNewlines();
                Identifier member = expectMemberName("member name");
                expression = new Expression.Member(expression, member, nullSafe, expression.span().to(member.span()));
            } else if (at(TokenKind.LPAREN)) {
                Token open = advance();
                openGroups.addLast(open);
                try {
                    List<Expression> arguments = new ArrayList<>();
                    while (!at(TokenKind.RPAREN) && !at(TokenKind.EOF)) {
                        arguments.add(parseExpression());
                        if (!accept(TokenKind.COMMA)) {
                            break;
                        }
                    }
                    Token close = expectClosing(TokenKind.RPAREN, open, "argument list");
                    expression = new Expression.Call(expression, arguments, expression.span().to(close.span()));
                } finally {
                    openGroups.removeLast();
                }
            } else if (at(TokenKind.LBRACKET)) {
                Token open = advance();
                openGroups.addLast(open);
                try {
                    Expression indexExpression = parseExpression();
                    Token close = expectClosing(TokenKind.RBRACKET, open, "index");
                    expression = new Expression.Index(expression, indexExpression, expression.span().to(close.span()));
                } finally {
                    openGroups.removeLast();
                }
            } else {
                return expression;
            }
        }
    }

    private Expression parsePrimary() {
        Token token = current();
        switch (token.kind()) {
            case INT_LITERAL, LONG_LITERAL, FLOAT_LITERAL, DOUBLE_LITERAL -> {
                advance();
                Expression.Literal literal = new Expression.Literal(literalKind(token.kind()), token.value(), token.span());
                if (at(TokenKind.IDENTIFIER)) {
                    DurationUnit unit = DurationUnit.fromWord(current().text());
                    if (unit != null) {
                        Token unitToken = advance();
                        return new Expression.Duration(literal, unit, span(token, unitToken.span()));
                    }
                }
                return literal;
            }
            case STRING_LITERAL -> {
                advance();
                return new Expression.Literal(Expression.LiteralKind.STRING, token.value(), token.span());
            }
            case TEMPLATE_START -> {
                return parseTemplate();
            }
            case TRUE, FALSE -> {
                advance();
                return new Expression.Literal(Expression.LiteralKind.BOOL, token.is(TokenKind.TRUE), token.span());
            }
            case NULL -> {
                advance();
                return new Expression.Literal(Expression.LiteralKind.NULL, null, token.span());
            }
            case IDENTIFIER -> {
                advance();
                return new Expression.Name(new Identifier(token.text(), token.span()));
            }
            case LPAREN -> {
                Token open = advance();
                openGroups.addLast(open);
                try {
                    Expression inner = parseExpression();
                    Token close = expectClosing(TokenKind.RPAREN, open, "parenthesized expression");
                    return new Expression.Parenthesized(inner, span(open, close.span()));
                } finally {
                    openGroups.removeLast();
                }
            }
            case LBRACKET -> {
                return parseListLiteral();
            }
            case LBRACE -> throw fail(DiagnosticCode.UNSUPPORTED_FEATURE, token.span(),
                    "Map literals are not supported yet.", "See docs/status.md for the roadmap.");
            default -> {
                Diagnostic.Builder builder = Diagnostic.builder(DiagnosticCode.EXPECTED_EXPRESSION, file, token.span(),
                        "Expected an expression, found " + describe(token) + ".");
                Token unclosed = openGroups.peekLast();
                if (unclosed != null && isStatementKeyword(token) && firstOnLine(index)) {
                    builder.label(unclosed.span(), "this '" + unclosed.text() + "' is never closed");
                } else if (token.kind().isKeyword()) {
                    builder.note("'" + token.text() + "' is a reserved keyword and cannot be used as a value or name.");
                }
                diagnostics.report(builder.build());
                throw SyntaxError.INSTANCE;
            }
        }
    }

    private Expression parseTemplate() {
        Token start = advance();
        List<String> segments = new ArrayList<>();
        List<Expression> parts = new ArrayList<>();
        segments.add((String) start.value());
        while (true) {
            if (at(TokenKind.TEMPLATE_MIDDLE) || at(TokenKind.TEMPLATE_END)) {
                Token previous = tokens.get(index - 1);
                Span empty = new Span(previous.end() - 1, current().start() + 1);
                diagnostics.report(Diagnostic.builder(DiagnosticCode.EMPTY_INTERPOLATION, file, empty,
                        "Empty interpolation '{}' in string.").note("Write '\\{' for a literal '{'.").build());
                parts.add(new Expression.Error(empty));
            } else {
                parts.add(parseExpression());
            }
            Token part = current();
            if (part.is(TokenKind.TEMPLATE_MIDDLE)) {
                advance();
                segments.add((String) part.value());
            } else if (part.is(TokenKind.TEMPLATE_END)) {
                advance();
                segments.add((String) part.value());
                return new Expression.Template(segments, parts, span(start, part.span()));
            } else {
                diagnostics.report(Diagnostic.builder(DiagnosticCode.EXPECTED_TOKEN, file, part.span(),
                                "Expected '}' to end the interpolation, found " + describe(part) + ".")
                        .label(start.span(), "string starts here")
                        .note("Write '\\{' for a literal '{'.").build());
                throw SyntaxError.INSTANCE;
            }
        }
    }

    private Expression parseListLiteral() {
        Token open = advance();
        openGroups.addLast(open);
        try {
            List<Expression> elements = new ArrayList<>();
            while (!at(TokenKind.RBRACKET) && !at(TokenKind.EOF)) {
                elements.add(parseExpression());
                if (!accept(TokenKind.COMMA)) {
                    break;
                }
            }
            Token close = expectClosing(TokenKind.RBRACKET, open, "list");
            return new Expression.ListLiteral(elements, span(open, close.span()));
        } finally {
            openGroups.removeLast();
        }
    }

    private static Expression.LiteralKind literalKind(TokenKind kind) {
        return switch (kind) {
            case INT_LITERAL -> Expression.LiteralKind.INT;
            case LONG_LITERAL -> Expression.LiteralKind.LONG;
            case FLOAT_LITERAL -> Expression.LiteralKind.FLOAT;
            default -> Expression.LiteralKind.DOUBLE;
        };
    }

    // ============================================================== types and names

    private TypeRef parseType() {
        if (!at(TokenKind.IDENTIFIER)) {
            throw fail(DiagnosticCode.EXPECTED_TOKEN, current().span(),
                    "Expected a type, found " + describe(current()) + ".", "Examples: int, string, Player, List<string>");
        }
        QualifiedName name = parseQualifiedName("type name");
        List<TypeRef> arguments = new ArrayList<>();
        Span end = name.span();
        if (at(TokenKind.LT)) {
            Token open = advance();
            do {
                arguments.add(parseType());
            } while (accept(TokenKind.COMMA));
            end = expectClosing(TokenKind.GT, open, "type arguments").span();
        }
        TypeRef type = new TypeRef.Named(name, arguments, name.span().to(end));
        if (at(TokenKind.QUESTION)) {
            Token question = advance();
            type = new TypeRef.Nullable(type, type.span().to(question.span()));
            if (at(TokenKind.QUESTION) || at(TokenKind.QUESTION_QUESTION)) {
                throw fail(DiagnosticCode.UNEXPECTED_TOKEN, current().span(), "A type can only be made nullable once.", null);
            }
        }
        return type;
    }

    private QualifiedName parseQualifiedName(String what) {
        List<Identifier> parts = new ArrayList<>();
        parts.add(expectIdentifier(what));
        while (at(TokenKind.DOT) && isNameAfterDot(peek(1))) {
            advance();
            parts.add(expectMemberName(what));
        }
        return new QualifiedName(parts, parts.getFirst().span().to(parts.getLast().span()));
    }

    // ============================================================== recovery

    private void expectStatementEnd() {
        if (at(TokenKind.NEWLINE) || at(TokenKind.SEMICOLON)) {
            advance();
            return;
        }
        if (at(TokenKind.RBRACE) || at(TokenKind.EOF)) {
            return;
        }
        if (firstOnLine(index) && diagnostics.hasErrors(file)) {
            // Newlines are only missing here after an unclosed '(' or '[' (already reported).
            return;
        }
        throw fail(DiagnosticCode.EXPECTED_STATEMENT_END, current().span(),
                "Expected the end of the statement before " + describe(current()) + ".",
                "Put each statement on its own line, or separate statements with ';'.");
    }

    private void expectDeclarationEnd() {
        if (at(TokenKind.NEWLINE) || at(TokenKind.SEMICOLON) || at(TokenKind.EOF)) {
            return;
        }
        if (startsLine(index)) {
            return;
        }
        throw fail(DiagnosticCode.EXPECTED_STATEMENT_END, current().span(),
                "Expected the end of the declaration before " + describe(current()) + ".", null);
    }

    /** Skips the rest of a broken statement: up to the end of line or ';', or before the closing '}'. */
    private void synchronizeStatement(int start) {
        if (index == start && !at(TokenKind.EOF) && !at(TokenKind.RBRACE)) {
            advance();
        }
        int depth = 0;
        while (!at(TokenKind.EOF)) {
            Token token = current();
            if (depth == 0 && (token.is(TokenKind.NEWLINE) || token.is(TokenKind.SEMICOLON))) {
                advance();
                return;
            }
            if (token.is(TokenKind.LBRACE)) {
                depth++;
            } else if (token.is(TokenKind.RBRACE)) {
                if (depth == 0) {
                    return;
                }
                depth--;
            } else if (depth == 0 && atTopLevelDeclarationStart()) {
                return;
            } else if (depth == 0 && index != start && isStatementKeyword(token) && firstOnLine(index)) {
                return;
            }
            advance();
        }
    }

    private static boolean isStatementKeyword(Token token) {
        return switch (token.kind()) {
            case LET, VAR, IF, WHILE, FOR, RETURN, BREAK, CONTINUE -> true;
            default -> false;
        };
    }

    /** Whether the token at {@code at} is the first token on its source line. */
    private boolean firstOnLine(int at) {
        if (at == 0) {
            return true;
        }
        Token previous = tokens.get(at - 1);
        return previous.is(TokenKind.NEWLINE) || file.lineOf(previous.end()) < file.lineOf(tokens.get(at).start());
    }

    /** Skips to the next declaration that starts a line, skipping balanced braces. */
    private void synchronizeTopLevel(int start) {
        if (index == start && !at(TokenKind.EOF)) {
            advance();
        }
        int depth = 0;
        while (!at(TokenKind.EOF)) {
            Token token = current();
            if (token.is(TokenKind.LBRACE)) {
                depth++;
            } else if (token.is(TokenKind.RBRACE)) {
                depth = Math.max(0, depth - 1);
                if (depth == 0) {
                    advance();
                    return;
                }
            } else if (depth == 0 && startsLine(index) && startsDeclaration(index)) {
                return;
            }
            advance();
        }
    }

    private boolean atTopLevelDeclarationStart() {
        return file.columnOf(current().start()) == 1 && startsDeclaration(index);
    }

    private boolean startsDeclaration(int at) {
        Token token = tokens.get(at);
        Token next = at + 1 < tokens.size() ? tokens.get(at + 1) : token;
        if (token.is(TokenKind.FUNCTION) || token.is(TokenKind.CONST)) {
            return next.is(TokenKind.IDENTIFIER);
        }
        if (token.is(TokenKind.IDENTIFIER) && CONTEXTUAL_DECLARATIONS.contains(token.text())) {
            return next.is(TokenKind.IDENTIFIER) || (token.text().equals("import") && next.is(TokenKind.LBRACE));
        }
        return false;
    }

    private boolean startsLine(int at) {
        return at == 0 || tokens.get(at - 1).is(TokenKind.NEWLINE) || tokens.get(at - 1).is(TokenKind.RBRACE);
    }

    private static boolean startsStatement(Token token) {
        return switch (token.kind()) {
            case IDENTIFIER, IF, WHILE, FOR, RETURN, BREAK, CONTINUE, INT_LITERAL, STRING_LITERAL, TEMPLATE_START, LPAREN -> true;
            default -> false;
        };
    }

    private void enterNesting(Span at) {
        if (++nesting > MAX_NESTING) {
            nesting--;
            throw fail(DiagnosticCode.NESTING_TOO_DEEP, at,
                    "Code is nested too deeply (more than " + MAX_NESTING + " levels).",
                    "Move parts of it into functions.");
        }
    }

    private static boolean depthExceeds(Expression expression, int remaining) {
        if (remaining <= 0) {
            return true;
        }
        return switch (expression) {
            case Expression.Binary binary -> depthExceeds(binary.left(), remaining - 1)
                    || depthExceeds(binary.right(), remaining - 1);
            case Expression.Unary unary -> depthExceeds(unary.operand(), remaining - 1);
            case Expression.Member member -> depthExceeds(member.target(), remaining - 1);
            case Expression.Call call -> depthExceeds(call.callee(), remaining - 1)
                    || call.arguments().stream().anyMatch(argument -> depthExceeds(argument, remaining - 1));
            case Expression.Index indexed -> depthExceeds(indexed.target(), remaining - 1)
                    || depthExceeds(indexed.index(), remaining - 1);
            case Expression.Is is -> depthExceeds(is.operand(), remaining - 1);
            case Expression.Cast cast -> depthExceeds(cast.operand(), remaining - 1);
            case Expression.Range range -> depthExceeds(range.start(), remaining - 1)
                    || depthExceeds(range.end(), remaining - 1);
            case Expression.Parenthesized parenthesized -> depthExceeds(parenthesized.inner(), remaining - 1);
            case Expression.ListLiteral list -> list.elements().stream().anyMatch(e -> depthExceeds(e, remaining - 1));
            case Expression.Template template -> template.parts().stream().anyMatch(e -> depthExceeds(e, remaining - 1));
            case Expression.Literal ignored -> false;
            case Expression.Duration ignored -> false;
            case Expression.Name ignored -> false;
            case Expression.Error ignored -> false;
        };
    }

    // ============================================================== doc comments

    /** Text of the {@code ///} comments directly preceding the token at {@code tokenIndex}. */
    private String documentationBefore(int tokenIndex) {
        if (comments.isEmpty()) {
            return "";
        }
        int tokenStart = tokens.get(tokenIndex).start();
        int previousEnd = 0;
        for (int i = tokenIndex - 1; i >= 0; i--) {
            if (!tokens.get(i).is(TokenKind.NEWLINE)) {
                previousEnd = tokens.get(i).end();
                break;
            }
        }
        List<String> lines = new ArrayList<>();
        int boundary = tokenStart;
        for (int i = lastCommentBefore(tokenStart); i >= 0; i--) {
            Comment comment = comments.get(i);
            if (comment.span().start() < previousEnd || comment.kind() != Comment.Kind.DOC
                    || !file.content().substring(comment.span().end(), boundary).isBlank()) {
                break;
            }
            String text = comment.text();
            lines.addFirst(text.startsWith(" ") ? text.substring(1) : text);
            boundary = comment.span().start();
        }
        return String.join("\n", lines);
    }

    private int lastCommentBefore(int offset) {
        int low = 0;
        int high = comments.size() - 1;
        int result = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (comments.get(mid).span().end() <= offset) {
                result = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return result;
    }

    // ============================================================== token helpers

    private Token current() {
        return tokens.get(index);
    }

    private Token peek(int distance) {
        return tokens.get(Math.min(index + distance, tokens.size() - 1));
    }

    private boolean at(TokenKind kind) {
        return current().kind() == kind;
    }

    private boolean atIdentifier(String text) {
        return current().isIdentifier(text);
    }

    private Token advance() {
        Token token = current();
        if (!token.is(TokenKind.EOF)) {
            index++;
        }
        return token;
    }

    private boolean accept(TokenKind kind) {
        if (at(kind)) {
            advance();
            return true;
        }
        return false;
    }

    private void skipNewlines() {
        while (at(TokenKind.NEWLINE)) {
            advance();
        }
    }

    private void skipSeparators() {
        while (at(TokenKind.NEWLINE) || at(TokenKind.SEMICOLON)) {
            advance();
        }
    }

    /** Consumes {@code kind} or reports "{@code message}, found X." */
    private Token expect(TokenKind kind, String message) {
        if (at(kind)) {
            return advance();
        }
        throw fail(DiagnosticCode.EXPECTED_TOKEN, current().span(), message + ", found " + describe(current()) + ".", null);
    }

    private Token expectClosing(TokenKind kind, Token open, String what) {
        if (at(kind)) {
            return advance();
        }
        diagnostics.report(Diagnostic.builder(DiagnosticCode.EXPECTED_TOKEN, file, current().span(),
                        "Expected " + kind.description() + " to close the " + what + ", found " + describe(current()) + ".")
                .label(open.span(), "opened here").build());
        throw SyntaxError.INSTANCE;
    }

    private Identifier expectIdentifier(String what) {
        Token token = current();
        if (token.is(TokenKind.IDENTIFIER)) {
            advance();
            return new Identifier(token.text(), token.span());
        }
        String hint = token.kind().isKeyword()
                ? "'" + token.text() + "' is a reserved keyword and cannot be used as a name." : null;
        String article = "aeiou".indexOf(what.charAt(0)) >= 0 ? "an " : "a ";
        throw fail(DiagnosticCode.EXPECTED_TOKEN, token.span(), "Expected " + article + what + ", found " + describe(token) + ".", hint);
    }

    /**
     * A name after '.', where reserved words are allowed because the position is
     * unambiguous (for example the event {@code block.break}).
     */
    private Identifier expectMemberName(String what) {
        Token token = current();
        if (isNameAfterDot(token)) {
            advance();
            return new Identifier(token.text(), token.span());
        }
        return expectIdentifier(what);
    }

    private static boolean isNameAfterDot(Token token) {
        return token.is(TokenKind.IDENTIFIER) || (token.kind().isKeyword() && Character.isLetter(token.text().charAt(0)));
    }

    private SyntaxError fail(DiagnosticCode code, Span span, String message, String note) {
        Diagnostic.Builder builder = Diagnostic.builder(code, file, span, message);
        if (note != null) {
            builder.note(note);
        }
        diagnostics.report(builder.build());
        return SyntaxError.INSTANCE;
    }

    private void error(DiagnosticCode code, Span span, String message) {
        diagnostics.report(Diagnostic.builder(code, file, span, message).build());
    }

    private static Span span(Token start, Span end) {
        return new Span(start.start(), Math.max(start.end(), end.end()));
    }

    /** Describes a token for "found ..." messages. */
    static String describe(Token token) {
        return switch (token.kind()) {
            case IDENTIFIER -> "'" + token.text() + "'";
            case INT_LITERAL, LONG_LITERAL, FLOAT_LITERAL, DOUBLE_LITERAL -> "number " + token.text();
            case STRING_LITERAL, TEMPLATE_START -> "a string";
            case TEMPLATE_MIDDLE, TEMPLATE_END -> "the end of an interpolation";
            case NEWLINE -> "the end of the line";
            case EOF -> "the end of the file";
            default -> token.kind().description();
        };
    }
}
