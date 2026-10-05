package dev.tachyonscript.security;

import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Masks exact configured secrets and common credential forms before text leaves the analyzer. */
public final class SecretRedactor {
    public SecretRedactor withSource(dev.tachyonscript.language.source.SourceFile source) {
        java.util.List<String> values = new java.util.ArrayList<>(configured);
        var tokens = dev.tachyonscript.language.lexer.Lexer.lex(source, new dev.tachyonscript.language.diagnostic.DiagnosticCollector()).tokens();
        for (int i = 0; i < tokens.size(); i++) {
            if (!tokens.get(i).is(dev.tachyonscript.language.lexer.TokenKind.IDENTIFIER)
                    || !tokens.get(i).text().toLowerCase(java.util.Locale.ROOT).matches(".*(password|passwd|api.?key|secret|token|webhook|credential).*")) continue;
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
        for (String secret : configured) result = result.replace(secret, mask(secret));
        Matcher matcher = SECRETS.matcher(result);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) matcher.appendReplacement(out, Matcher.quoteReplacement(mask(matcher.group())));
        matcher.appendTail(out);
        for (int i = 0; i < out.length(); i++) {
            char character = out.charAt(i);
            if (character < 32 && character != '\n' && character != '\r' && character != '\t'
                    || character == 127 || character >= '\u202a' && character <= '\u202e'
                    || character >= '\u2066' && character <= '\u2069') out.setCharAt(i, '?');
        }
        return out.toString();
    }

    public boolean containsSecret(String text) {
        return text != null && (SECRETS.matcher(text).find() || configured.stream().anyMatch(text::contains));
    }

    private static String mask(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            out.append(c == '\n' || c == '\r' ? c : '*');
        }
        return out.toString();
    }
}
