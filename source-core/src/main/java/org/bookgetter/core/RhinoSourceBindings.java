package org.bookgetter.core;

import com.google.gson.Gson;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.bookgetter.core.Models.Source;
import org.mozilla.javascript.*;
import static org.bookgetter.core.RhinoRuntime.arg;
import static org.bookgetter.core.RhinoRuntime.requestHeaders;
import static org.bookgetter.core.RhinoRuntime.stringMethod;

/** Source-state and cookie facades; native stores never enter a script scope. */
final class RhinoSourceBindings {
    private static final Gson JSON = new Gson();
    private RhinoSourceBindings() {}
    static void installSourceState(Context context, Scriptable scope, Scriptable sourceObject, Source source, SourceStateStore states, CookieAccess cookies) {
        String identity = SourcePolicy.of(source).stateKey();
        ScriptableObject.putProperty(sourceObject, "getKey", stringMethod(source::id));
        ScriptableObject.putProperty(sourceObject, "getTag", stringMethod(source::name));
        ScriptableObject.putProperty(sourceObject, "getLoginHeader", new BaseFunction() {
            public Object call(Context cx, Scriptable sc, Scriptable self, Object[] args) {
                String value = states.get(identity, "loginHeader");
                return value.isEmpty() ? null : value;
            }
        });
        ScriptableObject.putProperty(sourceObject, "putLoginHeader", new BaseFunction() {
            public Object call(Context cx, Scriptable sc, Scriptable self, Object[] args) {
                String value = arg(args, 0);
                Map<String, String> headers = requestHeaders(value);
                headers.forEach((name, content) -> { if (name.equalsIgnoreCase("Cookie")) cookies.replace(source.id(), content); });
                states.put(identity, "loginHeader", JSON.toJson(headers));
                return Undefined.instance;
            }
        });
        ScriptableObject.putProperty(sourceObject, "removeLoginHeader", new BaseFunction() {
            public Object call(Context cx, Scriptable sc, Scriptable self, Object[] args) {
                cookies.remove(source.id()); states.put(identity, "loginHeader", null); return Undefined.instance;
            }
        });
        ScriptableObject.putProperty(sourceObject, "getVariable", stringMethod(() -> states.get(identity, "variable")));
        BaseFunction setVariable = new BaseFunction() {
            public Object call(Context cx, Scriptable sc, Scriptable self, Object[] args) {
                states.put(identity, "variable", args.length == 0 || args[0] == null || args[0] == Undefined.instance ? null : arg(args, 0));
                return Undefined.instance;
            }
        };
        ScriptableObject.putProperty(sourceObject, "putVariable", setVariable);
        ScriptableObject.putProperty(sourceObject, "setVariable", setVariable);
        ScriptableObject.putProperty(sourceObject, "put", new BaseFunction() {
            public Object call(Context cx, Scriptable sc, Scriptable self, Object[] args) {
                String value = arg(args, 1); states.put(identity, "value:" + arg(args, 0), value); return value;
            }
        });
        ScriptableObject.putProperty(sourceObject, "get", new BaseFunction() {
            public Object call(Context cx, Scriptable sc, Scriptable self, Object[] args) { return states.get(identity, "value:" + arg(args, 0)); }
        });
    }

    static void installCookies(Context context, Scriptable scope, Scriptable java, CookieAccess cookies) {
        BaseFunction get = new BaseFunction() {
            public Object call(Context cx, Scriptable sc, Scriptable self, Object[] args) {
                String value = cookies.get(arg(args, 0));
                if (args.length < 2 || args[1] == null || args[1] == Undefined.instance) return value;
                String name = arg(args, 1);
                for (String item : value.split(";")) {
                    int at = item.indexOf('=');
                    if (at >= 0 && item.substring(0, at).trim().equals(name)) return item.substring(at + 1).trim();
                }
                return "";
            }
        };
        ScriptableObject.putProperty(java, "getCookie", get);
        Scriptable cookie = context.newObject(scope);
        ScriptableObject.putProperty(cookie, "getCookie", get);
        ScriptableObject.putProperty(cookie, "getKey", get);
        for (String name : List.of("setCookie", "replaceCookie", "removeCookie")) {
            ScriptableObject.putProperty(cookie, name, new BaseFunction() {
                public Object call(Context cx, Scriptable sc, Scriptable self, Object[] args) {
                    String url = arg(args, 0);
                    if (name.equals("removeCookie")) cookies.remove(url);
                    else if (name.equals("replaceCookie")) cookies.replace(url, arg(args, 1));
                    else cookies.set(url, arg(args, 1));
                    return Undefined.instance;
                }
            });
        }
        ScriptableObject.putProperty(cookie, "cookieToMap", new BaseFunction() {
            public Object call(Context cx, Scriptable sc, Scriptable self, Object[] args) {
                Scriptable result = cx.newObject(sc);
                for (String entry : arg(args, 0).split(";")) {
                    int eq = entry.indexOf('=');
                    if (eq > 0) ScriptableObject.putProperty(result, entry.substring(0, eq).trim(), entry.substring(eq + 1).trim());
                }
                return result;
            }
        });
        ScriptableObject.putProperty(cookie, "mapToCookie", new BaseFunction() {
            public Object call(Context cx, Scriptable sc, Scriptable self, Object[] args) {
                return requestHeaders(args.length == 0 ? null : args[0]).entrySet().stream()
                        .map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.joining("; "));
            }
        });
        ScriptableObject.putProperty(scope, "cookie", cookie);
    }

}
