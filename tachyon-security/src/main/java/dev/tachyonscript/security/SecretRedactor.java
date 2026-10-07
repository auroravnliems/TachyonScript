package dev.tachyonscript.security;

import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Masks exact configured secrets and common credential forms before text leaves the analyzer. */
public final class SecretRedactor {
    /** Identifier names that announce a credential ({@code apiKey = "..."}). */
    static final Pattern SECRET_NAME = Pattern.compile(".*(password|passwd|api.?key|secret|token|webhook|credential).*");

    public SecretRedactor withSource(dev.tachyonscript.language.source.SourceFile source) {
        java.util.List<String> values = new java.util.ArrayList<>(configured);
        var tokens = dev.tachyonscript.language.lexer.Lexer.lex(source, new dev.tachyonscript.language.diagnostic.DiagnosticCollector()).tokens();
        for (int i = 0; i < tokens.size(); i++) {
            if (!tokens.get(i).is(dev.tachyonscript.language.lexer.TokenKind.IDENTIFIER)
                    || !SECRET_NAME.matcher(tokens.get(i).text().toLowerCase(java.util.Locale.ROOT)).matches()) continue;
            for (int j = i + 1; j < Math.min(tokens.size() - 1, i + 8); j++) {
                if (tokens.get(j).is(dev.tachyonscript.language.lexer.TokenKind.NEWLINE)) break;
                if (tokens.get(j).is(dev.tachyonscript.language.lexer.TokenKind.EQ) && tokens.get(j + 1).value() instanceof String value && value.length() >= 4) {
                    values.add(value); values.add(tokens.get(j + 1).text()); break;
                }
            }
        }
        return new SecretRedactor(values);
    }
    private static final Pattern SECRETS = Pattern.compile(
            "https://(?:canary\\.|ptb\\.)?discord(?:app)?\\.com/api/webhooks/[^\\s\\\"'<>]+"
            + "|\\b(?:sk-|AKIA)[A-Za-z0-9_-]{12,}"
            + "|(?i:Bearer)\\s+[A-Za-z0-9_.-]{12,}"
            + "|(?i:(?:api[_-]?key|password|passwd|secret|token|webhook)[\\w-]*)\\s*[:=]\\s*[\\\"']?[^\\r\\n\\\"',;]{4,}");
    private final List<String> configured;

    public SecretRedactor(Collection<String> configured) {
        this.configured = configured.stream().filter(s -> s != null && !s.isBlank()).distinct().toList();
    }

    public static SecretRedactor defaults() { return new SecretRedactor(List.of()); }

    /** Preserves UTF-16 length and newlines so source-column caret positions stay correct. */
    public String redact(String text) {
        if (text == null) return "";
        String result = text;
        for (String secret : configured) if (result.contains(secret)) result = result.replace(secret, mask(secret));
        if (mayMatch(result)) {
            Matcher matcher = SECRETS.matcher(result);
            StringBuilder out = new StringBuilder(result.length());
            while (matcher.find()) matcher.appendReplacement(out, Matcher.quoteReplacement(mask(matcher.group())));
            matcher.appendTail(out);
            result = out.toString();
        }
        StringBuilder safe = null;
        for (int i = 0; i < result.length(); i++) {
            char character = result.charAt(i);
            if (character < 32 && character != '\n' && character != '\r' && character != '\t'
                    || character == 127 || character >= '\u202a' && character <= '\u202e'
                    || character >= '\u2066' && character <= '\u2069') {
                if (safe == null) safe = new StringBuilder(result);
                safe.setCharAt(i, '?');
            }
        }
        return safe == null ? result : safe.toString();
    }

    public boolean containsSecret(String text) {
        return text != null && (mayMatch(text) && SECRETS.matcher(text).find() || configured.stream().anyMatch(text::contains));
    }

    /**
     * Whether {@link #SECRETS} can match at all: every alternative needs one of these words
     * (compared ignoring case, a superset of the pattern's own case rules). Most source snippets
     * contain none, and skipping the regex then is what keeps the analyzer fast.
     */
    private static boolean mayMatch(String text) {
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        for (String word : PREFILTER) if (lower.contains(word)) return true;
        return false;
    }
    private static final String[] PREFILTER = {"discord", "sk-", "akia", "bearer", "api", "password", "passwd", "secret", "token", "webhook"};

    private static String mask(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(c == '\n' || c == '\r' ? c : '*');
        }
        return out.toString();
    }
}
