package org.bookgetter.core;

import com.google.gson.Gson;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bookgetter.core.Models.*;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.ClassShutter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/** A narrow JS host. Unknown Legado java.* methods raise normal JS errors. */
public final class RhinoRuntime implements ScriptRuntime {
    private final Transport transport;
    private final Consumer<String> notification;
    private final Gson gson = new Gson();
    private final Map<String, Map<String, String>> sourceVariables = new ConcurrentHashMap<>();
    public RhinoRuntime(Transport transport) { this(transport, ignored -> {}); }
    public RhinoRuntime(Transport transport, Consumer<String> notification) {
        this.transport = transport;
        this.notification = notification;
    }

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
            BaseFunction notify = new BaseFunction() {
                @Override public Object call(Context context, Scriptable scope, Scriptable thisObj, Object[] args) {
                    notification.accept(args.length == 0 ? "null" : Context.toString(args[0]));
                    return Undefined.instance;
                }
            };
            ScriptableObject.putProperty(java, "toast", notify);
            ScriptableObject.putProperty(java, "longToast", notify);
            ScriptableObject.putProperty(java, "startBrowserAwait", new BaseFunction() {
                @Override public Object call(Context context, Scriptable scope, Scriptable thisObj, Object[] args) {
                    throw Context.reportRuntimeError("Interactive browser verification is unavailable in this runtime");
                }
            });
            Object[] ruleContent = {bindings.getOrDefault("result", "")};
            String ruleBaseUrl = String.valueOf(bindings.getOrDefault("baseUrl", source == null ? "" : source.id()));
            RuleEvaluator hostRules = new RuleEvaluator(this);
            ScriptableObject.putProperty(java, "setContent", new BaseFunction() {
                @Override public Object call(Context context, Scriptable scope, Scriptable thisObj, Object[] args) {
                    if (args.length == 0 || args[0] == null || args[0] == Undefined.instance)
                        throw Context.reportRuntimeError("java.setContent requires non-null content");
                    ruleContent[0] = Context.toString(args[0]);
                    return thisObj;
                }
            });
            BaseFunction getElements = new BaseFunction() {
                @Override public Object call(Context context, Scriptable scope, Scriptable thisObj, Object[] args) {
                    String rule = args.length == 0 ? "" : Context.toString(args[0]);
                    Object[] elements = hostRules.many(rule, ruleContent[0], ruleBaseUrl, source).stream()
                            .map(Object::toString).toArray();
                    return context.newArray(scope, elements);
                }
            };
            ScriptableObject.putProperty(java, "getElement", getElements);
            ScriptableObject.putProperty(java, "getElements", getElements);
            ScriptableObject.putProperty(java, "getString", new BaseFunction() {
                @Override public Object call(Context context, Scriptable scope, Scriptable thisObj, Object[] args) {
                    String rule = args.length == 0 ? "" : Context.toString(args[0]);
                    Object input = args.length > 1 && args[1] != Undefined.instance
                            ? Context.toString(args[1]) : ruleContent[0];
                    return hostRules.one(rule, input, ruleBaseUrl, source, false);
                }
            });
            ScriptableObject.putProperty(java, "base64Encode", new BaseFunction() {
                @Override public Object call(Context context, Scriptable scope, Scriptable thisObj, Object[] args) {
                    String value = args.length == 0 ? "" : Context.toString(args[0]);
                    return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
                }
            });
            ScriptableObject.putProperty(java, "base64Decode", new BaseFunction() {
                @Override public Object call(Context context, Scriptable scope, Scriptable thisObj, Object[] args) {
                    if (args.length == 0 || args[0] == null || args[0] == Undefined.instance) return "";
                    if (args.length > 1 && args[1] instanceof Number)
                        throw Context.reportRuntimeError("java.base64Decode flags overload is unavailable");
                    String value = Context.toString(args[0]);
                    String encoding = args.length > 1 && args[1] != Undefined.instance
                            ? Context.toString(args[1]) : "UTF-8";
                    try { return new String(Base64.getMimeDecoder().decode(value), Charset.forName(encoding)); }
                    catch (Exception error) { throw Context.throwAsScriptRuntimeEx(error); }
                }
            });
            ScriptableObject.putProperty(java, "encodeURI", new BaseFunction() {
                @Override public Object call(Context context, Scriptable scope, Scriptable thisObj, Object[] args) {
                    String value = args.length == 0 || args[0] == null || args[0] == Undefined.instance
                            ? "" : Context.toString(args[0]);
                    String encoding = args.length > 1 && args[1] != Undefined.instance
                            ? Context.toString(args[1]) : "UTF-8";
                    try { return URLEncoder.encode(value, encoding); }
                    catch (Exception ignored) { return ""; }
                }
            });
            ScriptableObject.putProperty(java, "md5Encode", new BaseFunction() {
                @Override public Object call(Context context, Scriptable scope, Scriptable thisObj, Object[] args) {
                    String value = args.length == 0 ? "" : Context.toString(args[0]);
                    try {
                        byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
                        char[] hex = "0123456789abcdef".toCharArray();
                        char[] result = new char[digest.length * 2];
                        for (int i = 0; i < digest.length; i++) {
                            result[i * 2] = hex[(digest[i] >>> 4) & 15];
                            result[i * 2 + 1] = hex[digest[i] & 15];
                        }
                        return new String(result);
                    } catch (Exception error) { throw Context.throwAsScriptRuntimeEx(error); }
                }
            });
            ScriptableObject.putProperty(java, "ajax", new BaseFunction() {
                @Override public Object call(Context context, Scriptable scope, Scriptable thisObj, Object[] args) {
                    String url = args.length == 0 ? "" : Context.toString(args[0]);
                    try { return transport.fetch(nestedRequest(url, bindings, source)).body(); }
                    catch (Exception e) { throw Context.throwAsScriptRuntimeEx(e); }
                }
            });
            ScriptableObject.putProperty(java, "post", new BaseFunction() {
                @Override public Object call(Context context, Scriptable scope, Scriptable thisObj, Object[] args) {
                    if (args.length < 2) throw Context.reportRuntimeError("java.post requires URL and body");
                    if (args.length > 3) throw Context.reportRuntimeError("java.post timeout overload is unavailable");
                    String url = Context.toString(args[0]);
                    String body = Context.toString(args[1]);
                    Map<String, String> headers = requestHeaders(args.length > 2 ? args[2] : null);
                    if (headers.keySet().stream().noneMatch(name -> name.equalsIgnoreCase("Content-Type")))
                        headers.put("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                    try {
                        FetchResponse response = transport.fetch(new FetchRequest(url, "POST", headers,
                                body, null, false, 0, false));
                        return responseView(context, scope, response);
                    } catch (Exception e) { throw Context.throwAsScriptRuntimeEx(e); }
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
                        FetchResponse response = transport.fetch(nestedRequest(url, bindings, source));
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

    private static Map<String, String> requestHeaders(Object input) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (input == null || input == Undefined.instance) return headers;
        if (input instanceof CharSequence text) {
            com.google.gson.JsonObject object = com.google.gson.JsonParser.parseString(text.toString()).getAsJsonObject();
            object.entrySet().forEach(entry -> headers.put(entry.getKey(), entry.getValue().getAsString()));
            return headers;
        }
        if (input instanceof Scriptable object) {
            for (Object id : object.getIds()) {
                if (!(id instanceof String name)) throw Context.reportRuntimeError("java.post header names must be strings");
                Object value = ScriptableObject.getProperty(object, name);
                if (value == null || value == Undefined.instance)
                    throw Context.reportRuntimeError("java.post header values cannot be null");
                headers.put(name, Context.toString(value));
            }
            return headers;
        }
        throw Context.reportRuntimeError("java.post headers must be an object or JSON string");
    }

    private static Scriptable responseView(Context context, Scriptable scope, FetchResponse response) {
        Scriptable view = context.newObject(scope);
        ScriptableObject.putProperty(view, "header", new BaseFunction() {
            @Override public Object call(Context context, Scriptable scope, Scriptable thisObj, Object[] args) {
                String name = args.length == 0 ? "" : Context.toString(args[0]);
                return response.headers().entrySet().stream()
                        .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                        .map(Map.Entry::getValue).findFirst().orElse(null);
            }
        });
        ScriptableObject.putProperty(view, "body", stringMethod(response::body));
        ScriptableObject.putProperty(view, "statusCode", new BaseFunction() {
            @Override public Object call(Context context, Scriptable scope, Scriptable thisObj, Object[] args) {
                return response.status();
            }
        });
        ScriptableObject.putProperty(view, "url", stringMethod(response::url));
        return view;
    }

    private FetchRequest nestedRequest(String url, Map<String, Object> bindings, Source source) {
        if (source == null) return new FetchRequest(url);
        String key = String.valueOf(bindings.getOrDefault("key", ""));
        Object pageValue = bindings.getOrDefault("page", 1);
        int page = pageValue instanceof Number number ? number.intValue() : Integer.parseInt(pageValue.toString());
        return new RequestPlanner(this).plan(url, source, key, page, source.id());
    }

    private void bind(Context cx, Scriptable scope, String name, Object value) {
        if (value instanceof Element element) {
            ScriptableObject.putProperty(scope, name, elementView(cx, scope, element));
            return;
        }
        Object jsValue = cx.evaluateString(scope, "(" + gson.toJson(value) + ")", "binding:" + name, 1, null);
        if (value instanceof Map<?, ?> && jsValue instanceof ScriptableObject object) {
            object.defineProperty("toString", new BaseFunction() {
                @Override public Object call(Context context, Scriptable callScope, Scriptable thisObj, Object[] args) {
                    return value.toString();
                }
            }, ScriptableObject.DONTENUM);
        }
        ScriptableObject.putProperty(scope, name, jsValue);
    }

    private static Scriptable elementView(Context context, Scriptable scope, Element element) {
        Scriptable view = context.newObject(scope);
        ScriptableObject.putProperty(view, "attr", new BaseFunction() {
            @Override public Object call(Context context, Scriptable callScope, Scriptable thisObj, Object[] args) {
                return element.attr(args.length == 0 ? "" : Context.toString(args[0]));
            }
        });
        ScriptableObject.putProperty(view, "hasAttr", new BaseFunction() {
            @Override public Object call(Context context, Scriptable callScope, Scriptable thisObj, Object[] args) {
                return element.hasAttr(args.length == 0 ? "" : Context.toString(args[0]));
            }
        });
        ScriptableObject.putProperty(view, "hasClass", new BaseFunction() {
            @Override public Object call(Context context, Scriptable callScope, Scriptable thisObj, Object[] args) {
                return element.hasClass(args.length == 0 ? "" : Context.toString(args[0]));
            }
        });
        ScriptableObject.putProperty(view, "select", new BaseFunction() {
            @Override public Object call(Context context, Scriptable callScope, Scriptable thisObj, Object[] args) {
                return elementsView(context, callScope,
                        element.select(args.length == 0 ? "" : Context.toString(args[0])));
            }
        });
        ScriptableObject.putProperty(view, "text", stringMethod(element::text));
        ScriptableObject.putProperty(view, "ownText", stringMethod(element::ownText));
        ScriptableObject.putProperty(view, "html", stringMethod(element::html));
        ScriptableObject.putProperty(view, "outerHtml", stringMethod(element::outerHtml));
        ScriptableObject.putProperty(view, "toString", stringMethod(element::outerHtml));
        return view;
    }

    private static Scriptable elementsView(Context context, Scriptable scope, Elements elements) {
        Scriptable view = context.newObject(scope);
        ((ScriptableObject) view).defineProperty("length", elements.size(),
                ScriptableObject.READONLY | ScriptableObject.PERMANENT);
        ScriptableObject.putProperty(view, "size", new BaseFunction() {
            @Override public Object call(Context context, Scriptable callScope, Scriptable thisObj, Object[] args) {
                return elements.size();
            }
        });
        ScriptableObject.putProperty(view, "hasClass", new BaseFunction() {
            @Override public Object call(Context context, Scriptable callScope, Scriptable thisObj, Object[] args) {
                return elements.hasClass(args.length == 0 ? "" : Context.toString(args[0]));
            }
        });
        ScriptableObject.putProperty(view, "select", new BaseFunction() {
            @Override public Object call(Context context, Scriptable callScope, Scriptable thisObj, Object[] args) {
                return elementsView(context, callScope,
                        elements.select(args.length == 0 ? "" : Context.toString(args[0])));
            }
        });
        ScriptableObject.putProperty(view, "first", new BaseFunction() {
            @Override public Object call(Context context, Scriptable callScope, Scriptable thisObj, Object[] args) {
                Element first = elements.first();
                return first == null ? null : elementView(context, callScope, first);
            }
        });
        ScriptableObject.putProperty(view, "attr", new BaseFunction() {
            @Override public Object call(Context context, Scriptable callScope, Scriptable thisObj, Object[] args) {
                return elements.attr(args.length == 0 ? "" : Context.toString(args[0]));
            }
        });
        ScriptableObject.putProperty(view, "text", stringMethod(elements::text));
        ScriptableObject.putProperty(view, "toString", stringMethod(elements::toString));
        return view;
    }

    private static BaseFunction stringMethod(java.util.function.Supplier<String> action) {
        return new BaseFunction() {
            @Override public Object call(Context context, Scriptable scope, Scriptable thisObj, Object[] args) {
                return action.get();
            }
        };
    }
}
