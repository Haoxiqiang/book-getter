package org.bookgetter.core;

import com.jayway.jsonpath.JsonPath;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import org.bookgetter.core.Models.Source;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.w3c.dom.NodeList;

/** Supported subset: CSS, XPath, JSONPath, regex and JS, with a single extraction action. */
public final class RuleEvaluator {
    private static final Pattern PUT = Pattern.compile("(?i)@put:(\\{[^}]+\\})");
    private static final Pattern GET = Pattern.compile("(?i)@get:\\{([^}]+)\\}");
    private static final Pattern JSON_INNER = Pattern.compile("\\{(\\$\\.[^{}]+)\\}");
    private static final Pattern BRACKET_INDEX = Pattern.compile("^(.*?)(!)?\\[([\\d\\s,:-]+)\\]$");
    private final ScriptRuntime script;
    private final Map<String, Map<String, String>> variables = new ConcurrentHashMap<>();
    private final ThreadLocal<String> scope = new ThreadLocal<>();
    private final ThreadLocal<Map<String, String>> searchItemVariables = new ThreadLocal<>();
    public RuleEvaluator(ScriptRuntime script) { this.script = script; }

    public void enterBook(String bookUrl) { enterBook(bookUrl, Map.of()); }
    public void enterBook(String bookUrl, Map<String, String> inherited) {
        scope.set(bookUrl);
        if (inherited != null && !inherited.isEmpty())
            variables.computeIfAbsent(bookUrl, ignored -> new ConcurrentHashMap<>()).putAll(inherited);
    }
    public void leaveBook() { scope.remove(); }
    public Map<String, String> bookVariables(String bookUrl) {
        Map<String, String> current = variables.get(bookUrl);
        return current == null ? Map.of() : Map.copyOf(current);
    }
    public void enterSearchItem() { searchItemVariables.set(new java.util.LinkedHashMap<>()); }
    public Map<String, String> searchItemVariables() {
        Map<String, String> current = searchItemVariables.get();
        return current == null ? Map.of() : Map.copyOf(current);
    }
    public void leaveSearchItem() { searchItemVariables.remove(); }

    /** Detail init follows Legado getElement: keep a JSON object or DOM node structured. */
    public Object element(String rule, Object input, String baseUrl, Source source) {
        if (rule == null || rule.isBlank()) return input;
        String value = rule.trim();
        if (value.startsWith("@json:") || value.startsWith("$.") || value.startsWith("$[")) {
            String path = value.startsWith("@json:") ? value.substring(6) : value;
            try { return JsonPath.read(input instanceof String ? (String) input : new com.google.gson.Gson().toJson(input), path); }
            catch (com.jayway.jsonpath.PathNotFoundException ignored) { return null; }
        }
        if (value.startsWith("@js:") || value.startsWith("<js>"))
            return one(value, input, baseUrl, source, false);
        List<Object> found = many(value, input, baseUrl, source);
        return found.size() == 1 ? found.get(0) : found;
    }

    private Map<String, String> variables(Source source) {
        Map<String, String> item = searchItemVariables.get();
        if (item != null) return item;
        return variables.computeIfAbsent(scope.get() == null ? source.id() : scope.get(),
                ignored -> new ConcurrentHashMap<>());
    }

    public List<Object> many(String rule, Object input, String baseUrl, Source source) {
        if (rule == null || rule.isBlank()) return List.of();
        String value = rule.trim();
        if (value.startsWith("@@")) value = value.substring(2);
        int embeddedScript = value.indexOf("<js>");
        if (embeddedScript > 0) {
            int embeddedEnd = value.indexOf("</js>", embeddedScript + 4);
            if (embeddedEnd >= 0) {
                List<Object> initial = many(value.substring(0, embeddedScript).trim(), input, baseUrl, source);
                Object evaluated = script.evaluate(value.substring(embeddedScript + 4, embeddedEnd),
                        Map.of("result", initial, "baseUrl", baseUrl), source);
                String trailing = value.substring(embeddedEnd + 5).trim();
                return trailing.isEmpty() ? normalizeScript(evaluated)
                        : many(trailing, evaluated == null ? "" : evaluated, baseUrl, source);
            }
        }
        int scriptEnd = value.startsWith("<js>") ? value.indexOf("</js>") : -1;
        if (scriptEnd >= 0) {
            Object evaluated = script.evaluate(value.substring(4, scriptEnd),
                    Map.of("result", scriptInput(input), "baseUrl", baseUrl), source);
            String trailing = value.substring(scriptEnd + 5).trim();
            return trailing.isEmpty() ? normalizeScript(evaluated)
                    : many(trailing, evaluated == null ? "" : evaluated, baseUrl, source);
        }
        int chainedJs = value.indexOf("\n@js:");
        if (chainedJs > 0) {
            List<Object> initial = many(value.substring(0, chainedJs), input, baseUrl, source);
            return normalize(script.evaluate(value.substring(chainedJs + 5), Map.of("result", initial.stream().map(Object::toString).toList(), "baseUrl", baseUrl), source));
        }
        if (value.startsWith("@js:")) return normalizeScript(script.evaluate(value.substring(4), Map.of("result", scriptInput(input), "baseUrl", baseUrl), source));
        Combination combination = combination(value);
        if (combination != null) {
            List<List<Object>> branches = new ArrayList<>();
            for (String part : combination.parts()) {
                List<Object> found = many(part.trim(), input, baseUrl, source);
                if (!found.isEmpty() && found.stream().anyMatch(item -> item != null && !item.toString().isBlank())) {
                    branches.add(found);
                    if (combination.operator().equals("||")) break;
                }
            }
            return merge(branches, combination.operator());
        }
        if (value.startsWith("@json:") || value.startsWith("$.") || value.startsWith("$[")) {
            String path = value.startsWith("@json:") ? value.substring(6) : value;
            try {
                return normalize(JsonPath.read(input instanceof String ? (String) input : new com.google.gson.Gson().toJson(input), path));
            } catch (com.jayway.jsonpath.PathNotFoundException ignored) {
                return List.of();
            }
        }
        if (value.regionMatches(true, 0, "@xpath:", 0, 7) || value.startsWith("/")) {
            try {
                // Most book-source XPath rules use unprefixed HTML names (e.g. //a).
                // jsoup otherwise places HTML elements in the XHTML namespace, where //a matches nothing.
                Element root = input instanceof Element element ? element.clone() : Jsoup.parse(input.toString(), baseUrl);
                // Android's Harmony DocumentType has no owner document; jsoup's W3CDom
                // cannot attach its source-node marker to that node on Android.
                root.childNodes().stream().filter(node -> node instanceof org.jsoup.nodes.DocumentType)
                        .toList().forEach(Node::remove);
                org.w3c.dom.Document document = new W3CDom().namespaceAware(false).fromJsoup(root);
                String path = value.regionMatches(true, 0, "@xpath:", 0, 7) ? value.substring(7) : value;
                NodeList nodes = (NodeList) XPathFactory.newInstance().newXPath().evaluate(path, document, XPathConstants.NODESET);
                List<Object> result = new ArrayList<>();
                for (int i = 0; i < nodes.getLength(); i++) {
                    org.w3c.dom.Node node = nodes.item(i);
                    Object original;
                    try { original = node.getUserData(W3CDom.SourceProperty); }
                    catch (NullPointerException ignored) {
                        // Android Harmony DOM may have no owner document for a selected text node.
                        original = null;
                    }
                    result.add(original instanceof Element ? original : node.getTextContent());
                }
                return result;
            } catch (Exception e) { throw new IllegalArgumentException("Invalid XPath rule: " + value, e); }
        }
        if (value.startsWith("@regex:")) {
            Matcher matcher = Pattern.compile(value.substring(7), Pattern.DOTALL).matcher(input.toString());
            List<Object> result = new ArrayList<>();
            while (matcher.find()) result.add(matcher.group());
            return result;
        }
        if (!value.startsWith("@css:") && value.contains("@")) {
            List<Object> current = List.of(input);
            for (String part : value.split("@")) {
                if (part.isBlank()) continue;
                List<Object> next = new ArrayList<>();
                for (Object item : current) next.addAll(select(item, part, baseUrl));
                current = next;
            }
            return current;
        }
        return select(input, value.replaceFirst("^@css:", ""), baseUrl);
    }

    public String one(String rule, Object input, String baseUrl, Source source, boolean url) {
        if (rule == null || rule.isBlank()) return "";
        String value = rule.trim();
        if (!value.startsWith("@js:") && !value.startsWith("<js>")) {
            value = expandGets(value, source);
            Matcher put = PUT.matcher(value);
            if (put.find()) {
                String assignmentsRule = put.group(1);
                String baseRule = put.replaceAll("").trim();
                JsonObject assignments = JsonParser.parseString(assignmentsRule).getAsJsonObject();
                String result = one(baseRule, input, baseUrl, source, url);
                for (Map.Entry<String, com.google.gson.JsonElement> assignment : assignments.entrySet())
                    variables(source).put(assignment.getKey(), one(assignment.getValue().getAsString(),
                            input, baseUrl, source, false));
                return result;
            }
        }
        int scriptEnd = value.startsWith("<js>") ? value.indexOf("</js>") : -1;
        if (scriptEnd >= 0) {
            Object evaluated = script.evaluate(value.substring(4, scriptEnd),
                    Map.of("result", scriptInput(input), "baseUrl", baseUrl), source);
            String text = evaluated == null ? "" : evaluated.toString().trim();
            String trailing = value.substring(scriptEnd + 5).trim();
            if (!trailing.isEmpty()) {
                if (!trailing.startsWith("##")) return one(trailing, text, baseUrl, source, url);
                text = replaceRule(text, trailing.split("##", -1)).trim();
            }
            return url && !text.isBlank() ? absolute(baseUrl, text) : text;
        }
        int embeddedScript = value.indexOf("<js>");
        if (embeddedScript > 0 && value.endsWith("</js>")) {
            String initial = one(value.substring(0, embeddedScript).trim(), input, baseUrl, source, false);
            Object evaluated = script.evaluate(value.substring(embeddedScript + 4, value.length() - 5),
                    Map.of("result", initial, "baseUrl", baseUrl), source);
            String text = evaluated == null ? "" : evaluated.toString().trim();
            return url && !text.isBlank() ? absolute(baseUrl, text) : text;
        }
        int chainedJs = value.indexOf("@js:");
        if (chainedJs > 0) {
            String initial = one(value.substring(0, chainedJs).trim(), input, baseUrl, source, false);
            Object changed = script.evaluate(value.substring(chainedJs + 4), Map.of("result", initial, "baseUrl", baseUrl), source);
            String text = changed == null ? "" : changed.toString();
            return url && !text.isBlank() ? absolute(baseUrl, text) : text;
        }
        if (value.startsWith("@js:")) {
            String code = expandScriptTemplate(value.substring(4), input, baseUrl, source);
            Object evaluated = script.evaluate(code, Map.of("result", scriptInput(input), "baseUrl", baseUrl), source);
            String text = evaluated == null ? "" : evaluated.toString();
            return url && !text.isBlank() ? absolute(baseUrl, text) : text;
        }
        if (value.contains("{{")) {
            String text = expandTemplate(value, input, baseUrl, source).trim();
            return url && !text.isBlank() ? absolute(baseUrl, text) : text;
        }
        if (input instanceof Map<?, ?> object && object.containsKey(value)) {
            Object selected = object.get(value);
            String text = selected == null ? "" : String.valueOf(selected).trim();
            return url && !text.isBlank() ? absolute(baseUrl, text) : text;
        }
        Combination combination = combination(value);
        if (combination != null) {
            List<List<String>> branches = new ArrayList<>();
            for (String part : combination.parts()) {
                String found = one(part.trim(), input, baseUrl, source, url);
                if (!found.isBlank()) {
                    branches.add(List.of(found.split("\\n", -1)));
                    if (combination.operator().equals("||")) break;
                }
            }
            String joined = String.join("\n", merge(branches, combination.operator()));
            return combination.suffix().isEmpty() ? joined : replaceRule(joined, combination.suffix().split("##", -1)).trim();
        }
        if (value.contains("{$.")) {
            Matcher inner = JSON_INNER.matcher(value);
            StringBuffer interpolated = new StringBuffer();
            boolean resolved = false;
            while (inner.find()) {
                String selected = one(inner.group(1), input, baseUrl, source, false);
                if (!selected.isEmpty()) resolved = true;
                inner.appendReplacement(interpolated, Matcher.quoteReplacement(
                        selected.isEmpty() ? inner.group() : selected));
            }
            inner.appendTail(interpolated);
            String text = resolved ? interpolated.toString().trim() : "";
            return url && !text.isBlank() ? absolute(baseUrl, text) : text;
        }
        String[] regex = value.split("##", -1);
        value = regex[0];
        // Legado field rules may address the current list item directly, without a selector.
        if (value.equals("text") || value.equals("@text") || value.equals("html") || value.equals("@html")
                || value.equals("ownText") || value.equals("@ownText") || value.equals("href")
                || value.equals("@href") || value.equals("src") || value.equals("@src")
                || value.equals("textNodes") || value.equals("@textNodes")
                || value.equals("all") || value.equals("@all")) {
            String action = value.startsWith("@") ? value.substring(1) : value;
            String result;
            if (input instanceof Element element) result = elementValue(element, action);
            else if (input instanceof Map<?, ?> object) {
                Object selected = object.get(action);
                result = selected == null ? "" : String.valueOf(selected);
            } else result = input.toString();
            if (regex.length > 1) result = replaceRule(result, regex);
            result = result.trim();
            return url && !result.isBlank() ? absolute(baseUrl, result) : result;
        }
        if (value.matches("@[A-Za-z_][A-Za-z0-9_:-]*") && input instanceof Element element) {
            String result = element.attr(value.substring(1)).trim();
            if (regex.length > 1) result = replaceRule(result, regex).trim();
            return url && !result.isBlank() ? absolute(baseUrl, result) : result;
        }
        int actionAt = value.lastIndexOf('@');
        boolean hasAction = actionAt > 0 && !value.startsWith("@json:")
                && !value.regionMatches(true, 0, "@xpath:", 0, 7) && !value.startsWith("/");
        String action = hasAction ? value.substring(actionAt + 1) : "text";
        String selector = hasAction ? value.substring(0, actionAt) : value;
        List<Object> items = selector.isBlank() ? List.of(input) : many(selector, input, baseUrl, source);
        List<String> strings = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Element element) {
                strings.add(elementValue(element, action));
            } else strings.add(String.valueOf(item));
        }
        // AnalyzeRule uses AnalyzeByJSoup.getString0 for URL fields: only the first match is a URL.
        String result = url ? (strings.isEmpty() ? "" : strings.get(0))
                : String.join(action.equals("all") ? "" : "\n", strings);
        if (regex.length > 1) result = replaceRule(result, regex);
        result = result.trim();
        return url && !result.isBlank() ? absolute(baseUrl, result) : result;
    }

    private static String elementValue(Element element, String action) {
        return switch (action) {
            case "text" -> element.text();
            case "html" -> {
                Element clean = element.clone();
                clean.select("script,style").remove();
                yield clean.outerHtml();
            }
            case "ownText" -> element.ownText();
            case "textNodes" -> element.textNodes().stream().map(node -> node.text().trim())
                    .filter(value -> !value.isEmpty()).collect(java.util.stream.Collectors.joining("\n"));
            case "all" -> element.outerHtml();
            default -> element.attr(action);
        };
    }

    private static String replaceRule(String input, String[] ruleParts) {
        if (ruleParts[1].isEmpty()) return input;
        Matcher matcher = Pattern.compile(ruleParts[1]).matcher(input);
        String replacement = ruleParts.length > 2 ? ruleParts[2] : "";
        if (ruleParts.length > 3) {
            return matcher.find() ? Pattern.compile(ruleParts[1])
                    .matcher(matcher.group()).replaceFirst(replacement) : "";
        }
        return matcher.replaceAll(replacement);
    }

    private String expandGets(String rule, Source source) {
        Matcher matcher = GET.matcher(rule);
        StringBuffer expanded = new StringBuffer();
        while (matcher.find()) matcher.appendReplacement(expanded,
                Matcher.quoteReplacement(variables(source).getOrDefault(matcher.group(1), "")));
        matcher.appendTail(expanded);
        return expanded.toString();
    }

    private record Combination(String operator, List<String> parts, String suffix) {}

    /** Match Legado's top-level combination operators without splitting JSONPath filters or quoted selectors. */
    private static Combination combination(String rule) {
        List<Integer> operators = new ArrayList<>();
        String operator = null;
        int suffixAt = rule.length();
        int square = 0, round = 0, curly = 0;
        char quote = 0;
        for (int i = 0; i + 1 < rule.length(); i++) {
            char c = rule.charAt(i);
            if (quote != 0) {
                if (c == '\\') { i++; continue; }
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"') { quote = c; continue; }
            if (c == '\\') { i++; continue; }
            if (c == '[') square++;
            else if (c == ']') square--;
            else if (c == '(') round++;
            else if (c == ')') round--;
            else if (c == '{') curly++;
            else if (c == '}') curly--;
            if (square != 0 || round != 0 || curly != 0) continue;
            String pair = rule.substring(i, i + 2);
            if (pair.equals("##")) { suffixAt = i; break; }
            if (pair.equals("&&") || pair.equals("||") || pair.equals("%%")) {
                if (operator == null) operator = pair;
                if (pair.equals(operator)) operators.add(i);
                i++;
            }
        }
        if (operator == null) return null;
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int at : operators) {
            parts.add(rule.substring(start, at));
            start = at + 2;
        }
        parts.add(rule.substring(start, suffixAt));
        return new Combination(operator, parts, rule.substring(suffixAt));
    }

    private static <T> List<T> merge(List<List<T>> branches, String operator) {
        List<T> merged = new ArrayList<>();
        if (operator.equals("%%") && !branches.isEmpty()) {
            for (int i = 0; i < branches.get(0).size(); i++)
                for (List<T> branch : branches) if (i < branch.size()) merged.add(branch.get(i));
        } else for (List<T> branch : branches) merged.addAll(branch);
        return merged;
    }

    private static List<Object> normalize(Object value) {
        if (value == null) return List.of();
        if (value instanceof List<?> list) return new ArrayList<>(list);
        return List.of(value);
    }

    private static List<Object> normalizeScript(Object value) {
        if (value instanceof String string && string.startsWith("[") && string.endsWith("]")) {
            try {
                List<Object> result = new ArrayList<>();
                JsonParser.parseString(string).getAsJsonArray().forEach(item -> result.add(item.isJsonPrimitive()
                        ? item.getAsString() : new com.google.gson.Gson().fromJson(item, Object.class)));
                return result;
            } catch (Exception ignored) { /* Not a JSON array; keep literal text. */ }
        }
        return normalize(value);
    }

    private static Object scriptInput(Object input) {
        return input instanceof Map<?, ?> || input instanceof Element ? input : input.toString();
    }

    private String expandTemplate(String template, Object input, String baseUrl, Source source) {
        Matcher matcher = Pattern.compile("\\{\\{([^{}]+)\\}\\}").matcher(template);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String expression = matcher.group(1).trim();
            String value = expression.startsWith("$") || expression.startsWith("@")
                    ? one(expression, input, baseUrl, source, false)
                    : String.valueOf(script.evaluate(expression, Map.of("result", scriptInput(input), "baseUrl", baseUrl), source));
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    private String expandScriptTemplate(String template, Object input, String baseUrl, Source source) {
        Matcher gets = GET.matcher(template);
        StringBuffer withGets = new StringBuffer();
        while (gets.find()) {
            String stored = variables(source).getOrDefault(gets.group(1), "");
            char quote = scriptQuoteAt(template, gets.start());
            gets.appendReplacement(withGets, Matcher.quoteReplacement(quote == 0 ? stored : escapeJsString(stored, quote)));
        }
        gets.appendTail(withGets);
        String script = withGets.toString();
        Matcher matcher = Pattern.compile("\\{\\{([^{}]+)\\}\\}").matcher(script);
        StringBuffer result = new StringBuffer();
        boolean hasDeferredPath = false;
        while (matcher.find()) {
            String expression = matcher.group(1).trim();
            String replacement;
            if (expression.matches("\\$\\.[A-Za-z_$][A-Za-z0-9_$.]*")) {
                char quote = scriptQuoteAt(script, matcher.start());
                if (quote == '\'' || quote == '"') {
                    replacement = escapeJsString(one(expression, input, baseUrl, source, false), quote);
                } else {
                    String accessor = "__bgGet(" + new com.google.gson.Gson().toJson(expression.substring(2)) + ")";
                    replacement = quote == '`' ? "${" + accessor + "}" : accessor;
                    hasDeferredPath = true;
                }
            } else {
                replacement = expression.equals("baseUrl") ? baseUrl
                        : expandTemplate("{{" + expression + "}}", input, baseUrl, source);
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        if (!hasDeferredPath) return result.toString();
        return "function __bgGet(path) { var value = result; "
                + "if (typeof value === 'string') { try { value = JSON.parse(value); } catch (ignored) {} } "
                + "var keys = path.split('.'); "
                + "for (var i = 0; i < keys.length; i++) { if (value == null) return ''; value = value[keys[i]]; } "
                + "return value == null ? '' : value; }\n" + result;
    }

    private static char scriptQuoteAt(String text, int end) {
        char quote = 0;
        for (int i = 0; i < end; i++) {
            char current = text.charAt(i);
            if (current == '\\') { i++; continue; }
            if (quote == 0 && (current == '\'' || current == '"' || current == '`')) quote = current;
            else if (current == quote) quote = 0;
        }
        return quote;
    }

    private static String escapeJsString(String value, char quote) {
        String escaped = value.replace("\\", "\\\\").replace(String.valueOf(quote), "\\" + quote);
        return quote == '`' ? escaped.replace("${", "\\${") : escaped;
    }

    private static String normalizeSelector(String selector) {
        if (selector.startsWith("class.")) return "." + selector.substring(6);
        if (selector.startsWith("id.")) return "#" + selector.substring(3);
        if (selector.startsWith("tag.")) return selector.substring(4);
        return selector;
    }

    private static List<Object> select(Object input, String selector, String baseUrl) {
        Element element = input instanceof Element e ? e : Jsoup.parse(input.toString(), baseUrl);
        Matcher bracket = BRACKET_INDEX.matcher(selector);
        Matcher index = Pattern.compile("^(.*?)([.!])(-?\\d+)$").matcher(selector);
        String baseSelector = bracket.matches() ? bracket.group(1) : index.matches() ? index.group(1) : selector;
        List<Object> found;
        if (baseSelector.isBlank() && bracket.matches()) found = new ArrayList<>(element.children());
        else if (baseSelector.startsWith("class.") && baseSelector.substring(6).contains(" "))
            found = new ArrayList<>(element.getElementsByClass(baseSelector.substring(6)));
        else {
            String normalized = normalizeSelector(baseSelector);
            found = normalized.isBlank() ? List.of() : new ArrayList<>(element.select(normalized));
        }
        if (bracket.matches()) return indexedElements(found, bracket.group(3), bracket.group(2) != null);
        if (!index.matches()) return found;
        int position = Integer.parseInt(index.group(3));
        if (position < 0) position += found.size();
        if (position < 0 || position >= found.size()) return index.group(2).equals("!") ? found : List.of();
        if (index.group(2).equals(".")) return List.of(found.get(position));
        List<Object> remaining = new ArrayList<>(found);
        remaining.remove(position);
        return remaining;
    }

    private static List<Object> indexedElements(List<Object> elements, String spec, boolean exclude) {
        int size = elements.size();
        if (size == 0) return List.of();
        Set<Integer> selected = new LinkedHashSet<>();
        for (String part : spec.split(",", -1)) {
            String[] range = part.trim().split(":", -1);
            if (range.length == 1) {
                if (range[0].isBlank()) continue;
                int position = normalizeIndex(Integer.parseInt(range[0].trim()), size);
                if (position >= 0 && position < size) selected.add(position);
            } else if (range.length == 2 || range.length == 3) {
                int start = range[0].isBlank() ? 0 : normalizeIndex(Integer.parseInt(range[0].trim()), size);
                int end = range[1].isBlank() ? size - 1 : normalizeIndex(Integer.parseInt(range[1].trim()), size);
                if ((start < 0 && end < 0) || (start >= size && end >= size)) continue;
                start = Math.max(0, Math.min(size - 1, start));
                end = Math.max(0, Math.min(size - 1, end));
                int step = range.length == 3 && !range[2].isBlank() ? Integer.parseInt(range[2].trim()) : 1;
                if (step <= 0) step = -step < size ? step + size : 1;
                if (step >= size) { selected.add(start); continue; }
                if (start <= end) for (int position = start; position <= end; position += step) selected.add(position);
                else for (int position = start; position >= end; position -= step) selected.add(position);
            }
        }
        List<Object> result = new ArrayList<>();
        if (exclude) {
            for (int i = 0; i < size; i++) if (!selected.contains(i)) result.add(elements.get(i));
        } else {
            for (int position : selected) result.add(elements.get(position));
        }
        return result;
    }

    private static int normalizeIndex(int position, int size) {
        return position < 0 ? position + size : position;
    }

    public static String absolute(String base, String path) {
        int optionAt = path.indexOf(",{");
        if (optionAt >= 0) {
            try { JsonParser.parseString(path.substring(optionAt + 1).trim()).getAsJsonObject(); }
            catch (Exception ignored) { optionAt = -1; }
        }
        String raw = optionAt < 0 ? path : path.substring(0, optionAt);
        try {
            String resolved = URI.create(base).resolve(raw).toString();
            return optionAt < 0 ? resolved : resolved + path.substring(optionAt);
        }
        catch (Exception ignored) { return path; }
    }
}
