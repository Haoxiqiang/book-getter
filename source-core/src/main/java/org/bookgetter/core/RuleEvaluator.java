package org.bookgetter.core;

import com.jayway.jsonpath.JsonPath;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    private final ScriptRuntime script;
    private final Map<String, Map<String, String>> variables = new ConcurrentHashMap<>();
    private final ThreadLocal<String> scope = new ThreadLocal<>();
    public RuleEvaluator(ScriptRuntime script) { this.script = script; }

    public void enterBook(String bookUrl) { scope.set(bookUrl); }
    public void leaveBook() { scope.remove(); }

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
        return variables.computeIfAbsent(scope.get() == null ? source.id() : scope.get(),
                ignored -> new ConcurrentHashMap<>());
    }

    public List<Object> many(String rule, Object input, String baseUrl, Source source) {
        if (rule == null || rule.isBlank()) return List.of();
        String value = rule.trim();
        if (value.startsWith("@@")) value = value.substring(2);
        if (value.startsWith("<js>") && value.endsWith("</js>"))
            return normalizeScript(script.evaluate(value.substring(4, value.length() - 5),
                    Map.of("result", scriptInput(input), "baseUrl", baseUrl), source));
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
        if (value.startsWith("@xpath:") || value.startsWith("//")) {
            try {
                // Most book-source XPath rules use unprefixed HTML names (e.g. //a).
                // jsoup otherwise places HTML elements in the XHTML namespace, where //a matches nothing.
                org.w3c.dom.Document document = new W3CDom().namespaceAware(false).fromJsoup(Jsoup.parse(input.toString(), baseUrl));
                NodeList nodes = (NodeList) XPathFactory.newInstance().newXPath().evaluate(value.replaceFirst("^@xpath:", ""), document, XPathConstants.NODESET);
                List<Object> result = new ArrayList<>();
                for (int i = 0; i < nodes.getLength(); i++) result.add(nodes.item(i).getTextContent());
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
                if (!trailing.startsWith("##")) throw new IllegalArgumentException("Unsupported rule after </js>: " + trailing);
                text = replaceRule(text, trailing.split("##", -1)).trim();
            }
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
        String[] regex = value.split("##", -1);
        value = regex[0];
        // Legado field rules may address the current list item directly, without a selector.
        if (value.equals("text") || value.equals("@text") || value.equals("html") || value.equals("@html")
                || value.equals("ownText") || value.equals("@ownText") || value.equals("href")
                || value.equals("@href") || value.equals("src") || value.equals("@src")) {
            String action = value.startsWith("@") ? value.substring(1) : value;
            String result = input instanceof Element element ? elementValue(element, action) : input.toString();
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
        boolean hasAction = actionAt > 0 && !value.startsWith("@json:") && !value.startsWith("@xpath:");
        String action = hasAction ? value.substring(actionAt + 1) : "text";
        String selector = hasAction ? value.substring(0, actionAt) : value;
        List<Object> items = selector.isBlank() ? List.of(input) : many(selector, input, baseUrl, source);
        List<String> strings = new ArrayList<>();
        for (Object item : items) {
            if (item instanceof Element element) {
                strings.add(elementValue(element, action));
            } else strings.add(String.valueOf(item));
        }
        String result = String.join("\n", strings);
        if (regex.length > 1) result = replaceRule(result, regex);
        result = result.trim();
        return url && !result.isBlank() ? absolute(baseUrl, result) : result;
    }

    private static String elementValue(Element element, String action) {
        return switch (action) {
            case "text" -> element.text();
            case "html" -> element.html();
            case "ownText" -> element.ownText();
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
        return input instanceof Map<?, ?> ? input : input.toString();
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
        Matcher matcher = Pattern.compile("\\{\\{([^{}]+)\\}\\}").matcher(template);
        StringBuffer result = new StringBuffer();
        boolean hasDeferredPath = false;
        while (matcher.find()) {
            String expression = matcher.group(1).trim();
            String replacement;
            if (expression.matches("\\$\\.[A-Za-z_$][A-Za-z0-9_$.]*")) {
                String accessor = "__bgGet(" + new com.google.gson.Gson().toJson(expression.substring(2)) + ")";
                replacement = inTemplateLiteral(template, matcher.start()) ? "${" + accessor + "}" : accessor;
                hasDeferredPath = true;
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

    private static boolean inTemplateLiteral(String text, int end) {
        boolean inside = false;
        for (int i = 0; i < end; i++) if (text.charAt(i) == '`' && (i == 0 || text.charAt(i - 1) != '\\')) inside = !inside;
        return inside;
    }

    private static String normalizeSelector(String selector) {
        if (selector.startsWith("class.")) return "." + selector.substring(6);
        if (selector.startsWith("id.")) return "#" + selector.substring(3);
        if (selector.startsWith("tag.")) return selector.substring(4);
        return selector;
    }

    private static List<Object> select(Object input, String selector, String baseUrl) {
        Element element = input instanceof Element e ? e : Jsoup.parse(input.toString(), baseUrl);
        if (selector.startsWith("class.") && selector.substring(6).contains(" "))
            return new ArrayList<>(element.getElementsByClass(selector.substring(6)));
        String normalized = normalizeSelector(selector);
        return normalized.isBlank() ? List.of() : new ArrayList<>(element.select(normalized));
    }

    public static String absolute(String base, String path) {
        try { return URI.create(base).resolve(path).toString(); }
        catch (Exception ignored) { return path; }
    }
}
