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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.bookgetter.core.Models.*;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.ClassShutter;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.NativeArray;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.Undefined;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

/** A narrow JS host. Unknown Legado java.* methods raise normal JS errors. */
public final class RhinoRuntime implements ScriptRuntime {
    private static final long SCRIPT_TIMEOUT_NANOS = java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
    private static final ThreadLocal<Long> DEADLINE = new ThreadLocal<>();
    private static final ContextFactory INTERRUPTIBLE_CONTEXTS = new ContextFactory() {
        @Override protected void observeInstructionCount(Context context, int instructionCount) {
            // Rhino's interpreter only checks interruption when instruction counting is enabled.
            if (Thread.currentThread().isInterrupted())
                throw new IllegalStateException("JavaScript execution interrupted");
            Long deadline = DEADLINE.get();
            if (deadline != null && System.nanoTime() - deadline >= 0)
                throw new IllegalStateException("JavaScript execution exceeded 60 seconds");
        }
    };
    private final Transport transport;
    private final Consumer<String> notification;
    private final Gson gson = new Gson();
    private final Map<String, Map<String, String>> sourceVariables = new ConcurrentHashMap<>();
    private final ThreadLocal<Map<String, Object>> currentBook = new ThreadLocal<>();
    public RhinoRuntime(Transport transport) { this(transport, ignored -> {}); }
    public RhinoRuntime(Transport transport, Consumer<String> notification) {
        this.transport = transport;
        this.notification = notification;
    }

    @Override public void enterBook(Map<String, Object> book) {
        Map<String, Object> state = new LinkedHashMap<>(book);
        Map<String, String> variables = new LinkedHashMap<>();
        if (book.get("variables") instanceof Map<?, ?> existing)
            existing.forEach((key, value) -> variables.put(String.valueOf(key), String.valueOf(value)));
        state.put("variables", variables);
        state.put("reverseToc", "true".equals(variables.get("__bookgetter.reverseToc")));
        currentBook.set(state);
    }

    @Override public void leaveBook() { currentBook.remove(); }
    @Override public Map<String, String> bookVariables() {
        Map<String, Object> state = currentBook.get();
        if (state == null || !(state.get("variables") instanceof Map<?, ?> variables)) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        variables.forEach((key, value) -> result.put(String.valueOf(key), String.valueOf(value)));
        return result;
    }
    @Override public boolean reverseToc() {
        Map<String, Object> state = currentBook.get();
        return state != null && Boolean.TRUE.equals(state.get("reverseToc"));
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
        boolean outermost = DEADLINE.get() == null;
        if (outermost) DEADLINE.set(System.nanoTime() + SCRIPT_TIMEOUT_NANOS);
        Context cx = INTERRUPTIBLE_CONTEXTS.enterContext();
        try {
            cx.setOptimizationLevel(-1);
            cx.setInstructionObserverThreshold(10_000);
            cx.setLanguageVersion(Context.VERSION_ES6);
            cx.setClassShutter(fullName -> false);
            ScriptableObject scope = cx.initSafeStandardObjects();
            Scriptable java = cx.newObject(scope);
            IdentityHashMap<Scriptable, Object> nativeViews = new IdentityHashMap<>();
            installJsoupFacade(cx, scope, nativeViews);
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
            ScriptableObject.putProperty(java, "getElement", new BaseFunction() {
                @Override public Object call(Context context, Scriptable scope, Scriptable thisObj, Object[] args) {
                    String rule = args.length == 0 ? "" : Context.toString(args[0]);
                    Object found = hostRules.element(rule, ruleContent[0], ruleBaseUrl, source);
                    if (found instanceof Element element) return elementsView(context, scope,
                            new Elements(element), nativeViews);
                    return ruleView(context, scope, found, nativeViews);
                }
            });
            ScriptableObject.putProperty(java, "getElements", new BaseFunction() {
                @Override public Object call(Context context, Scriptable scope, Scriptable thisObj, Object[] args) {
                    String rule = args.length == 0 ? "" : Context.toString(args[0]);
                    List<Object> found = hostRules.many(rule, ruleContent[0], ruleBaseUrl, source);
                    Object[] views = found.stream().map(item -> ruleView(context, scope, item, nativeViews)).toArray();
                    Scriptable array = context.newArray(scope, views);
                    ScriptableObject.putProperty(array, "toArray", new BaseFunction() {
                        @Override public Object call(Context context, Scriptable callScope,
                                                     Scriptable thisObj, Object[] args) {
                            return thisObj;
                        }
                    });
                    return array;
                }
            });
            ScriptableObject.putProperty(java, "getString", new BaseFunction() {
                @Override public Object call(Context context, Scriptable scope, Scriptable thisObj, Object[] args) {
                    String rule = args.length == 0 ? "" : Context.toString(args[0]);
                    Object input = args.length > 1 && args[1] != Undefined.instance
                            ? Context.toString(args[1]) : ruleContent[0];
                    return hostRules.one(rule, input, ruleBaseUrl, source, false);
                }
            });
            ScriptableObject.putProperty(java, "getStringList", new BaseFunction() {
                @Override public Object call(Context context, Scriptable scope, Scriptable thisObj, Object[] args) {
                    String rule = args.length == 0 ? "" : Context.toString(args[0]);
                    Object input = args.length > 1 && args[1] != Undefined.instance
                            ? Context.toString(args[1]) : ruleContent[0];
                    // RuleEvaluator.one applies Legado's terminal @text/@href action and
                    // fallback branches; many() alone returns selector nodes.
                    String selected = hostRules.one(rule, input, ruleBaseUrl, source, false);
                    List<String> values = selected.isEmpty() ? List.of() : List.of(selected.split("\\n", -1));
                    return stringListView(context, scope, values);
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
                    try {
                        FetchResponse response = transport.fetch(nestedRequest(url, bindings, source));
                        String quality = ResponseSignals.quality(response);
                        if (quality.equals("CHALLENGE_PAGE"))
                            throw new IllegalStateException("Nested request returned CHALLENGE_PAGE");
                        return response.body();
                    }
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
                    String value = args.length == 0 || args[0] == null || args[0] == Undefined.instance
                            ? "" : Context.toString(args[0]);
                    return ChineseText.t2s(value);
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
                bind(cx, scope, "source", source.json(), nativeViews);
                Scriptable sourceObject = (Scriptable) ScriptableObject.getProperty(scope, "source");
                ScriptableObject.putProperty(sourceObject, "getBookSourceUrl", new BaseFunction() {
                    @Override public Object call(Context context, Scriptable callScope, Scriptable thisObj, Object[] args) {
                        return source.id();
                    }
                });
                ScriptableObject.putProperty(sourceObject, "getLoginHeader", new BaseFunction() {
                    @Override public Object call(Context context, Scriptable scope, Scriptable thisObj, Object[] args) {
                        return null;
                    }
                });
                ScriptableObject.putProperty(sourceObject, "getVariable", emptyStringFunction());
                ScriptableObject.putProperty(sourceObject, "key", source.id());
            }
            // Explicit mainJs arguments take precedence over the enclosing ordinary-rule context.
            if (!bindings.containsKey("book") && currentBook.get() != null)
                bind(cx, scope, "book", currentBook.get(), nativeViews);
            for (Map.Entry<String, Object> binding : bindings.entrySet())
                bind(cx, scope, binding.getKey(), binding.getValue(), nativeViews);
            if (!bindings.containsKey("src"))
                bind(cx, scope, "src", bindings.getOrDefault("result", ""), nativeViews);
            if (!bindings.containsKey("book") && currentBook.get() == null) {
                ScriptableObject.putProperty(scope, "book", cx.newObject(scope));
            }
            Object book = ScriptableObject.getProperty(scope, "book");
            if (book instanceof Scriptable bookObject) {
                ScriptableObject.putProperty(bookObject, "getVariable", new BaseFunction() {
                    @Override public Object call(Context context, Scriptable scope, Scriptable thisObj, Object[] args) {
                        String key = args.length == 0 ? "" : Context.toString(args[0]);
                        return bookVariables().getOrDefault(key, "");
                    }
                });
                ScriptableObject.putProperty(bookObject, "putVariable", new BaseFunction() {
                    @Override public Object call(Context context, Scriptable scope, Scriptable thisObj, Object[] args) {
                        if (args.length < 2) throw Context.reportRuntimeError("book.putVariable requires key and value");
                        Map<String, Object> state = currentBook.get();
                        if (state == null) throw Context.reportRuntimeError("book.putVariable requires a book");
                        @SuppressWarnings("unchecked") Map<String, String> variables =
                                (Map<String, String>) state.get("variables");
                        String key = Context.toString(args[0]);
                        if (args[1] == null || args[1] == Undefined.instance) return variables.remove(key) != null;
                        variables.put(key, Context.toString(args[1]));
                        return true;
                    }
                });
                ScriptableObject.putProperty(bookObject, "setReverseToc", new BaseFunction() {
                    @Override public Object call(Context context, Scriptable scope, Scriptable thisObj, Object[] args) {
                        Map<String, Object> state = currentBook.get();
                        if (state == null) throw Context.reportRuntimeError("book.setReverseToc requires a book");
                        state.put("reverseToc", args.length > 0 && Context.toBoolean(args[0]));
                        return Undefined.instance;
                    }
                });
                ScriptableObject.putProperty(bookObject, "getReverseToc", new BaseFunction() {
                    @Override public Object call(Context context, Scriptable scope, Scriptable thisObj, Object[] args) {
                        return reverseToc();
                    }
                });
            }
            String library = source == null ? null : source.string("jsLib");
            if (library != null && !library.isBlank()) cx.evaluateString(scope, library, "book-source-library", 1, null);
            Object result = cx.evaluateString(scope, code, "book-source", 1, null);
            if (result == null || result == Undefined.instance) return null;
            if (result instanceof String || result instanceof Number || result instanceof Boolean) return result;
            Object preserved = nativeResult(result, nativeViews);
            if (preserved != null) return preserved;
            ScriptableObject.putProperty(scope, "__bookGetterResult", result);
            Object json = cx.evaluateString(scope, "JSON.stringify(__bookGetterResult)", "result", 1, null);
            return json == Undefined.instance ? null : Context.toString(json);
        } finally {
            Context.exit();
            if (outermost) DEADLINE.remove();
        }
    }

    private static void installJsoupFacade(Context context, ScriptableObject scope,
                                            IdentityHashMap<Scriptable, Object> nativeViews) {
        Scriptable jsoup = context.newObject(scope);
        ScriptableObject.putProperty(jsoup, "parse", new BaseFunction() {
            @Override public Object call(Context cx, Scriptable callScope, Scriptable thisObj, Object[] args) {
                String html = args.length == 0 ? "" : Context.toString(args[0]);
                return elementView(cx, callScope, org.jsoup.Jsoup.parse(html), nativeViews);
            }
        });
        Scriptable jsoupNamespace = context.newObject(scope);
        ScriptableObject.putProperty(jsoupNamespace, "Jsoup", jsoup);
        Scriptable orgNamespace = context.newObject(scope);
        ScriptableObject.putProperty(orgNamespace, "jsoup", jsoupNamespace);
        ScriptableObject.putProperty(scope, "org", orgNamespace);
        ScriptableObject.putProperty(scope, "importClass", new BaseFunction() {
            @Override public Object call(Context cx, Scriptable callScope, Scriptable thisObj, Object[] args) {
                if (args.length != 1 || args[0] != jsoup)
                    throw Context.reportRuntimeError("Only the safe org.jsoup.Jsoup facade can be imported");
                ScriptableObject.putProperty(scope, "Jsoup", jsoup);
                return Undefined.instance;
            }
        });
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

    private void bind(Context cx, Scriptable scope, String name, Object value,
                      IdentityHashMap<Scriptable, Object> nativeViews) {
        if (value instanceof Element element) {
            ScriptableObject.putProperty(scope, name, elementView(cx, scope, element, nativeViews));
            return;
        }
        if (value instanceof List<?> list && list.stream().allMatch(Element.class::isInstance)) {
            Elements elements = new Elements();
            list.forEach(item -> elements.add((Element) item));
            ScriptableObject.putProperty(scope, name, elementsView(cx, scope, elements, nativeViews));
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

    private static Object ruleView(Context context, Scriptable scope, Object value,
                                   IdentityHashMap<Scriptable, Object> nativeViews) {
        if (value instanceof Element element) return elementView(context, scope, element, nativeViews);
        if (value instanceof List<?> list && list.stream().allMatch(Element.class::isInstance)) {
            Elements elements = new Elements();
            list.forEach(item -> elements.add((Element) item));
            return elementsView(context, scope, elements, nativeViews);
        }
        if (value instanceof List<?> list)
            return context.newArray(scope, list.stream().map(item -> ruleView(context, scope, item, nativeViews)).toArray());
        if (value == null) return null;
        if (value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        return value.toString();
    }

    private static Object nativeResult(Object value, IdentityHashMap<Scriptable, Object> nativeViews) {
        if (!(value instanceof Scriptable scriptable)) return null;
        if (nativeViews.containsKey(scriptable)) return nativeViews.get(scriptable);
        if (scriptable instanceof NativeArray array) {
            List<Object> found = new java.util.ArrayList<>();
            for (int index = 0; index < array.getLength(); index++) {
                Object item = array.get(index, array);
                if (!(item instanceof Scriptable node) || !nativeViews.containsKey(node)) return null;
                found.add(nativeViews.get(node));
            }
            return found;
        }
        return null;
    }

    /** Minimal Java List view used by Legado rules that call size() and get(index). */
    private static Scriptable stringListView(Context context, Scriptable scope, List<String> values) {
        Scriptable view = context.newArray(scope, values.toArray());
        ScriptableObject.putProperty(view, "size", new BaseFunction() {
            @Override public Object call(Context context, Scriptable callScope, Scriptable thisObj, Object[] args) {
                return values.size();
            }
        });
        ScriptableObject.putProperty(view, "get", new BaseFunction() {
            @Override public Object call(Context context, Scriptable callScope, Scriptable thisObj, Object[] args) {
                int index = args.length == 0 ? -1 : (int) Context.toNumber(args[0]);
                return index < 0 || index >= values.size() ? null : values.get(index);
            }
        });
        return view;
    }

    private static Scriptable elementView(Context context, Scriptable scope, Element element,
                                          IdentityHashMap<Scriptable, Object> nativeViews) {
        Scriptable view = context.newObject(scope);
        nativeViews.put(view, element);
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
                        element.select(args.length == 0 ? "" : Context.toString(args[0])), nativeViews);
            }
        });
        ScriptableObject.putProperty(view, "remove", new BaseFunction() {
            @Override public Object call(Context context, Scriptable callScope, Scriptable thisObj, Object[] args) {
                element.remove();
                return view;
            }
        });
        ScriptableObject.putProperty(view, "text", stringMethod(element::text));
        ScriptableObject.putProperty(view, "ownText", stringMethod(element::ownText));
        ScriptableObject.putProperty(view, "html", stringMethod(element::html));
        ScriptableObject.putProperty(view, "outerHtml", stringMethod(element::outerHtml));
        ScriptableObject.putProperty(view, "toString", stringMethod(element::outerHtml));
        return view;
    }

    private static Scriptable elementsView(Context context, Scriptable scope, Elements elements,
                                           IdentityHashMap<Scriptable, Object> nativeViews) {
        Scriptable view = context.newObject(scope);
        nativeViews.put(view, elements);
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
                        elements.select(args.length == 0 ? "" : Context.toString(args[0])), nativeViews);
            }
        });
        ScriptableObject.putProperty(view, "remove", new BaseFunction() {
            @Override public Object call(Context context, Scriptable callScope, Scriptable thisObj, Object[] args) {
                // Jsoup's collection operation detaches the selected nodes from their shared DOM.
                elements.remove();
                return view;
            }
        });
        ScriptableObject.putProperty(view, "eachText", new BaseFunction() {
            @Override public Object call(Context context, Scriptable callScope, Scriptable thisObj, Object[] args) {
                return stringListView(context, callScope, elements.eachText());
            }
        });
        ScriptableObject.putProperty(view, "first", new BaseFunction() {
            @Override public Object call(Context context, Scriptable callScope, Scriptable thisObj, Object[] args) {
                Element first = elements.first();
                return first == null ? null : elementView(context, callScope, first, nativeViews);
            }
        });
        ScriptableObject.putProperty(view, "toArray", new BaseFunction() {
            @Override public Object call(Context context, Scriptable callScope, Scriptable thisObj, Object[] args) {
                return context.newArray(callScope, elements.stream()
                        .map(element -> elementView(context, callScope, element, nativeViews)).toArray());
            }
        });
        ScriptableObject.putProperty(view, "attr", new BaseFunction() {
            @Override public Object call(Context context, Scriptable callScope, Scriptable thisObj, Object[] args) {
                return elements.attr(args.length == 0 ? "" : Context.toString(args[0]));
            }
        });
        ScriptableObject.putProperty(view, "text", stringMethod(elements::text));
        ScriptableObject.putProperty(view, "html", stringMethod(elements::html));
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
