package org.bookgetter.core;

import java.util.Map;
import org.bookgetter.core.Models.Source;

public interface ScriptRuntime {
    /** Scope the current book to one worker thread; runtimes without host state may ignore it. */
    default void enterBook(Map<String, Object> book) {}
    default void leaveBook() {}
    default Map<String, String> bookVariables() { return Map.of(); }
    default boolean reverseToc() { return false; }
    Object evaluate(String script, Map<String, Object> bindings, Source source);
    Object call(String mainJs, String function, Map<String, Object> args, Source source);
}
