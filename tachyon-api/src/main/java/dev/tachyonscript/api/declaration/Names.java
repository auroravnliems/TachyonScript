package dev.tachyonscript.api.declaration;

/** Validation of declared names. */
final class Names {

    private Names() {
    }

    /** {@code [A-Za-z_][A-Za-z0-9_]*} */
    static boolean isIdentifier(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean letter = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_';
            boolean digit = c >= '0' && c <= '9';
            if (!(letter || (i > 0 && digit))) {
                return false;
            }
        }
        return true;
    }

    /** One or more identifiers separated by dots. */
    static boolean isQualifiedName(String text) {
        if (text == null || text.isEmpty() || text.startsWith(".") || text.endsWith(".")) {
            return false;
        }
        for (String part : text.split("\\.", -1)) {
            if (!isIdentifier(part)) {
                return false;
            }
        }
        return true;
    }

    static String requireIdentifier(String text, String what) {
        if (!isIdentifier(text)) {
            throw new IllegalArgumentException("Invalid " + what + " '" + text + "'");
        }
        return text;
    }

    static String requireQualifiedName(String text, String what) {
        if (!isQualifiedName(text)) {
            throw new IllegalArgumentException("Invalid " + what + " '" + text + "'");
        }
        return text;
    }
}
