package org.bookgetter.runtime;

import com.google.re2j.Pattern;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Linear RE2/J subset for browser URL rules on every platform, including Android's native ICU JVM.
 * Backreferences and look-around are explicitly unsupported. Never fall back to java.util.regex.
 */
public final class LinearRegex {
    public static final int MAX_PATTERN_CHARS = 4_096;
    public static final int MAX_INPUT_CHARS = 65_536;
    public static final long DEFAULT_TIMEOUT_MILLIS = 250;
    private final Pattern pattern;

    public LinearRegex(String expression) {
        if (expression == null || expression.length() > MAX_PATTERN_CHARS)
            throw new IllegalArgumentException("Browser regex exceeds the 4096-character pattern limit");
        checkCompilationBudget(expression);
        try { pattern = Pattern.compile(expression); }
        catch (com.google.re2j.PatternSyntaxException unsupported) {
            throw new UnsupportedPatternException("Browser URL regex requires the RE2/J linear subset; backreferences, look-around, "
                    + "and other unsupported syntax cannot execute", unsupported);
        } catch (StackOverflowError exhausted) {
            throw new IllegalArgumentException("Browser regex compilation exceeded stack budget", exhausted);
        }
    }
    /** Conservative upper bound: counted repeats can otherwise expand a tiny pattern into a huge program. */
    private static void checkCompilationBudget(String expression) {
        long expansion = 1;
        int depth = 0;
        int groups = 0;
        boolean characterClass = false;
        boolean quoted = false;
        for (int i = 0; i < expression.length(); i++) {
            char value = expression.charAt(i);
            if (quoted) {
                if (value == '\\' && i + 1 < expression.length() && expression.charAt(i + 1) == 'E') {
                    quoted = false; i++;
                }
                continue;
            }
            if (value == '\\' && i + 1 < expression.length()) {
                char escaped = expression.charAt(++i);
                if (escaped == 'Q' && !characterClass) quoted = true;
                continue;
            }
            if (value == '[') characterClass = true;
            if (value == ']') { characterClass = false; continue; }
            if (characterClass) continue;
            if (value == '(' && (++depth > 64 || ++groups > 128))
                throw new IllegalArgumentException("Browser regex exceeds the group complexity limit");
            if (value == ')') depth--;
            if (value != '{') continue;
            int end = expression.indexOf('}', i + 1);
            if (end < 0) continue;
            String repeat = expression.substring(i + 1, end);
            int comma = repeat.indexOf(',');
            String bound = comma < 0 ? repeat : comma == repeat.length() - 1
                    ? repeat.substring(0, comma) : repeat.substring(comma + 1);
            if (bound.isEmpty() || bound.length() > 4) continue;
            int count = 0;
            boolean numeric = true;
            for (int at = 0; at < bound.length(); at++) {
                char digit = bound.charAt(at);
                if (digit < '0' || digit > '9') { numeric = false; break; }
                count = count * 10 + digit - '0';
            }
            if (!numeric) continue;
            expansion *= Math.max(1, count);
            if (expansion * (expression.length() + 1L) > 262_144)
                throw new IllegalArgumentException("Browser regex counted repetitions exceed the compilation budget");
            i = end;
        }
    }

    public boolean matches(String value) { return matches(value, DEFAULT_TIMEOUT_MILLIS, () -> false); }
    public boolean matches(String value, long timeoutMillis, BooleanSupplier cancelled) {
        if (value == null) return false;
        if (value.length() > MAX_INPUT_CHARS) throw new IllegalArgumentException("Browser regex input exceeds the length limit");
        if (timeoutMillis <= 0) throw new IllegalArgumentException("A positive matching timeout is required");
        Budget budget = new Budget(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.min(timeoutMillis, 60_000)), cancelled);
        budget.check();
        // RE2/J retains this CharSequence; unlike Android ICU it does not stringify it before matching.
        boolean result = pattern.matcher(new CheckedText(value, 0, value.length(), budget)).matches();
        budget.check();
        return result;
    }
    public static final class UnsupportedPatternException extends IllegalArgumentException {
        UnsupportedPatternException(String message, Throwable cause) { super(message, cause); }
    }
    private static final class Budget {
        private final long deadline;
        private final BooleanSupplier cancelled;
        private int accesses;
        Budget(long deadline, BooleanSupplier cancelled) {
            this.deadline = deadline; this.cancelled = cancelled == null ? () -> false : cancelled;
        }
        void check() {
            if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean())
                throw new IllegalArgumentException("Browser regex matching cancelled");
            if (System.nanoTime() - deadline >= 0)
                throw new IllegalArgumentException("Browser regex exceeded matching time budget");
        }
        void access() { if ((++accesses & 63) == 0) check(); }
    }
    private static final class CheckedText implements CharSequence {
        private final String value;
        private final int start;
        private final int end;
        private final Budget budget;
        CheckedText(String value, int start, int end, Budget budget) {
            this.value = value; this.start = start; this.end = end; this.budget = budget;
        }
        @Override public int length() { budget.access(); return end - start; }
        @Override public char charAt(int index) {
            budget.access();
            if (index < 0 || index >= end - start) throw new IndexOutOfBoundsException();
            return value.charAt(start + index);
        }
        @Override public CharSequence subSequence(int from, int to) {
            budget.check();
            if (from < 0 || to < from || to > end - start) throw new IndexOutOfBoundsException();
            return new CheckedText(value, start + from, start + to, budget);
        }
        @Override public String toString() { budget.check(); return value.substring(start, end); }
    }
}
