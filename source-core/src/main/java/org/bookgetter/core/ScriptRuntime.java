package org.bookgetter.core;

import java.util.Map;
import org.bookgetter.core.Models.Source;

public interface ScriptRuntime {
    Object evaluate(String script, Map<String, Object> bindings, Source source);
    Object call(String mainJs, String function, Map<String, Object> args, Source source);
}
