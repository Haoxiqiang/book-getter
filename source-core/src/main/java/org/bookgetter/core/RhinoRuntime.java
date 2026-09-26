package org.bookgetter.core;

import com.google.gson.Gson;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.bookgetter.core.Models.*;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.ClassShutter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;

/** A narrow JS host. Unknown Legado java.* methods raise normal JS errors. */
public final class RhinoRuntime implements ScriptRuntime {
    private final Transport transport;
    private final Gson gson = new Gson();
    private final Map<String, Map<String, String>> sourceVariables = new ConcurrentHashMap<>();
    public RhinoRuntime(Transport transport) { this.transport = transport; }

    @Override public Object evaluate(String script, Map<String, Object> bindings, Source source) {
        return run(script, bindings, source);
    }

    @Override public Object call(String mainJs, String function, Map<String, Object> args, Source source) {
        if (!function.matches("[A-Za-z_$][A-Za-z0-9_$]*")) throw new IllegalArgumentException("Invalid function name");
        String parameters = switch (function) {
            case "search" -> "key,page";
            case "getBookInfo", "getChapters" -> "book";
            case "getContent" -> "chapter,book,nextChapterUrl";
            default -> String.join(",", args.keySet());
        };
        String invocation = function.equals("getBookInfo")
                ? "typeof getBookInfo === 'function' ? getBookInfo(" + parameters + ") : null"
                : "(function(){ if (typeof " + function + " !== 'function') throw new Error('Missing " + function
                        + "'); return " + function + "(" + parameters + "); })()";
        return run(mainJs + "\n" + invocation, args, source);
    }

    private Object run(String code, Map<String, Object> bindings, Source source) {
        Context cx = Context.enter();
        try {
            cx.setOptimizationLevel(-1);
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setClassShutter(fullName -> false);
            ScriptableObject scope = cx.initSafeStandardObjects();
            Scriptable java = cx.newObject(scope);
            ScriptableObject.putProperty(java, "ajax", new BaseFunction() {
                @Override public Object call(Context context, Scriptable scope, Scriptable thisObj, Object[] args) {
                    String url = args.length == 0 ? "" : Context.toString(args[0]);
                    try { return transport.fetch(new FetchRequest(RuleEvaluator.absolute(source == null ? "" : source.id(), url))).body(); }
                    catch (Exception e) { throw Context.throwAsScriptRuntimeEx(e); }
                }
            });
            ScriptableObject.putProperty(java, "put", new BaseFunction() {
                @Override public Object call(Context context, Scriptable scope, Scriptable thisObj, Object[] args) {
                    String key = args.length > 0 ? Context.toString(args[0]) : "";
                    String value = args.length > 1 ? Context.toString(args[1]) : "";
                    sourceVariables.computeIfAbsent(source == null ? "" : source.id(), unused -> new ConcurrentHashMap<>())
                            .put(key, value);
                    return value;
                }
            });
            ScriptableObject.putProperty(java, "get", new BaseFunction() {
                @Override public Object call(Context context, Scriptable scope, Scriptable thisObj, Object[] args) {
                    String url = args.length == 0 ? "" : Context.toString(args[0]);
                    if (args.length < 2 && !url.startsWith("http://") && !url.startsWith("https://"))
                        return sourceVariables.getOrDefault(source == null ? "" : source.id(), Map.of()).getOrDefault(url, "");
                    try {
                        FetchResponse response = transport.fetch(new FetchRequest(RuleEvaluator.absolute(source == null ? "" : source.id(), url)));
                        Scriptable result = context.newObject(scope);
                        ScriptableObject.putProperty(result, "body", new BaseFunction() {
                            @Override public Object call(Context context, Scriptable scope, Scriptable thisObj, Object[] args) {
                                return response.body();
                            }
                        });
                        return result;
                    } catch (Exception e) { throw Context.throwAsScriptRuntimeEx(e); }
                }
            });
            ScriptableObject.putProperty(java, "t2s", new BaseFunction() {
                @Override public Object call(Context context, Scriptable scope, Scriptable thisObj, Object[] args) {
                    return args.length == 0 ? "" : Context.toString(args[0]);
                }
            });
            ScriptableObject.putProperty(java, "timeFormatUTC", new BaseFunction() {
                @Override public Object call(Context context, Scriptable scope, Scriptable thisObj, Object[] args) {
                    long epochMillis = (long) Context.toNumber(args[0]);
                    String pattern = args.length > 1 ? Context.toString(args[1]) : "yyyy-MM-dd HH:mm:ss";
                    return DateTimeFormatter.ofPattern(pattern)
                            .withZone(ZoneOffset.UTC).format(Instant.ofEpochMilli(epochMillis));
                }
            });
            ScriptableObject.putProperty(scope, "java", java);
            if (source != null) {
                bind(cx, scope, "source", source.json());
                Scriptable sourceObject = (Scriptable) ScriptableObject.getProperty(scope, "source");
                ScriptableObject.putProperty(sourceObject, "getLoginHeader", new BaseFunction() {
                    @Override public Object call(Context context, Scriptable scope, Scriptable thisObj, Object[] args) {
                        return null;
                    }
                });
                ScriptableObject.putProperty(sourceObject, "getVariable", emptyStringFunction());
                ScriptableObject.putProperty(sourceObject, "key", source.id());
            }
            for (Map.Entry<String, Object> binding : bindings.entrySet()) bind(cx, scope, binding.getKey(), binding.getValue());
            if (!bindings.containsKey("book")) {
                ScriptableObject.putProperty(scope, "book", cx.newObject(scope));
            }
            Object book = ScriptableObject.getProperty(scope, "book");
            if (book instanceof Scriptable bookObject)
                ScriptableObject.putProperty(bookObject, "getVariable", emptyStringFunction());
            String library = source == null ? null : source.string("jsLib");
            if (library != null && !library.isBlank()) cx.evaluateString(scope, library, "book-source-library", 1, null);
            Object result = cx.evaluateString(scope, code, "book-source", 1, null);
            if (result == null || result == Undefined.instance) return null;
            if (result instanceof String || result instanceof Number || result instanceof Boolean) return result;
            ScriptableObject.putProperty(scope, "__bookGetterResult", result);
            Object json = cx.evaluateString(scope, "JSON.stringify(__bookGetterResult)", "result", 1, null);
            return json == Undefined.instance ? null : Context.toString(json);
        } finally { Context.exit(); }
    }

    private static BaseFunction emptyStringFunction() {
        return new BaseFunction() {
            @Override public Object call(Context context, Scriptable scope, Scriptable thisObj, Object[] args) {
                return "";
            }
        };
    }

    private void bind(Context cx, Scriptable scope, String name, Object value) {
        Object jsValue = cx.evaluateString(scope, "(" + gson.toJson(value) + ")", "binding:" + name, 1, null);
        ScriptableObject.putProperty(scope, name, jsValue);
    }
}
