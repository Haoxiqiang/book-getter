package org.bookgetter.core;

/** Shared lexical boundaries; selector and script contents retain their original case. */
final class RuleSyntax {
    private static final String[] MODE_PREFIXES = {"@css:", "@json:", "@xpath:"};
    private RuleSyntax() {}

    static String normalizePrefix(String rule) {
        String value = rule.trim();
        for (String prefix : MODE_PREFIXES) {
            if (value.regionMatches(true, 0, prefix, 0, prefix.length()))
                return prefix + value.substring(prefix.length());
        }
        return value;
    }

    static int replacementStart(String rule) {
        int square = 0, round = 0, curly = 0;
        char quote = 0;
        for (int i = 0; i + 1 < rule.length(); i++) {
            char c = rule.charAt(i);
            if (c == '\\') { i++; continue; }
            if (quote != 0) {
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"') { quote = c; continue; }
            if (c == '[') square++;
            else if (c == ']') square--;
            else if (c == '(') round++;
            else if (c == ')') round--;
            else if (c == '{') curly++;
            else if (c == '}') curly--;
            if (square == 0 && round == 0 && curly == 0 && rule.startsWith("##", i)) return i;
        }
        return -1;
    }

    /** Legado permits whitespace around the comma separating URL options. */
    static int urlOptionsStart(String text) {
        int embedded = 0;
        for (int i = 0; i < text.length() - 1; i++) {
            if (text.startsWith("{{", i)) { embedded++; i++; continue; }
            if (embedded > 0 && text.startsWith("}}", i)) { embedded--; i++; continue; }
            if (embedded != 0 || text.charAt(i) != ',') continue;
            int next = i + 1;
            while (next < text.length() && Character.isWhitespace(text.charAt(next))) next++;
            if (next < text.length() && text.charAt(next) == '{') return i;
        }
        return -1;
    }
}
