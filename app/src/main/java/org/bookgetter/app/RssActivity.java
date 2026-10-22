package org.bookgetter.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebView;
import android.widget.*;
import java.io.File;
import java.util.*;
import java.util.concurrent.*;
import org.bookgetter.core.*;
import org.bookgetter.core.Models.*;
import org.bookgetter.core.RssModels.*;
import org.bookgetter.runtime.*;

/** RSS source management and reading are separate from book/chapter capture. */
public final class RssActivity extends Activity {
    private final ActivityTasks tasks = new ActivityTasks();
    private final ExecutorService preloads = Executors.newFixedThreadPool(2);
    private final List<Future<?>> pendingPreloads = new ArrayList<>();
    private RssStore store;
    private RssSelectionStore selections;
    private SourceStateStore states;
    private RhinoRuntime scripts;
    private AndroidTransport transport;
    private Spinner picker;
    private EditText keyword;
    private TextView status;
    private ListView list;
    private LinearLayout reader;
    private RssWebViewController browser;
    private List<RssSource> sources = List.of();
    private List<Article> articles = List.of();
    private RssReadCache readCache;
    private final Set<String> visitedPages = new HashSet<>();
    private RssSource activeSource;
    private Category activeCategory;
    private Page activePage;
    private String activeKey = "";
    private String articleToken;
    private String restoredSource;
    private final Queue<String> diagnostics = new ConcurrentLinkedQueue<>();
    private volatile boolean destroyed;
    private volatile long browserOwner;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        store = new RssStore(this);
        selections = new RssSelectionStore(getFilesDir());
        states = new FileSourceStateStore(new File(getFilesDir(), "source-state"));
        transport = new AndroidTransport(this);
        readCache = new RssReadCache(new AndroidCookieAccess());
        scripts = new RhinoRuntime(transport, this::report, ignored -> {}, states, new AndroidCookieAccess());
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(12, 12, 12, 12);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(12 + insets.getSystemWindowInsetLeft(), 12 + insets.getSystemWindowInsetTop(),
                    12 + insets.getSystemWindowInsetRight(), 12 + insets.getSystemWindowInsetBottom()); return insets;
        });
        picker = new Spinner(this); root.addView(picker);
        keyword = new EditText(this); keyword.setSingleLine(true); keyword.setHint("RSS 搜索关键词"); root.addView(keyword);
        LinearLayout controls = new LinearLayout(this); root.addView(controls);
        add(controls, "分类", this::chooseCategory); add(controls, "搜索", this::search);
        add(controls, "下一页", this::nextPage); add(controls, "返回列表", this::showList);
        LinearLayout secondary = new LinearLayout(this); root.addView(secondary);
        add(secondary, "刷新", this::refresh); add(secondary, "登录", this::login);
        add(secondary, "取消", () -> { tasks.cancel(); cancelPreloads(); closeBrowser(); report("已取消"); });
        status = new TextView(this); status.setPadding(0, 12, 0, 12); status.setMaxLines(3); root.addView(status);
        status.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle("RSS 诊断")
                .setMessage(String.join("\n\n", diagnostics)).setPositiveButton("关闭", null).show());
        list = new ListView(this); root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        reader = new LinearLayout(this); reader.setOrientation(LinearLayout.VERTICAL); reader.setVisibility(View.GONE);
        root.addView(reader, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root); root.requestApplyInsets();
        root.setFocusableInTouchMode(true); root.requestFocus();
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (state != null) { restoredSource = state.getString("sourceId"); articleToken = state.getString("articleToken"); }
        work(() -> {
            List<RssSource> loaded = store.all().stream().filter(source -> source.flag("enabled", true)).toList();
            Article restored = selections.restore(articleToken);
            ui(() -> {
                sources = List.copyOf(loaded);
                picker.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
                        sources.stream().map(RssSource::name).toList()));
                if (restoredSource != null) for (int i = 0; i < sources.size(); i++)
                    if (sources.get(i).id().equals(restoredSource)) picker.setSelection(i);
                if (restored != null) {
                    RssSource source = sources.stream().filter(item -> item.id().equals(restored.origin())).findFirst().orElse(null);
                    if (source != null) open(source, restored, false); else report("原 RSS 源已移除");
                } else report(sources.isEmpty() ? "请在首页导入 RSS 源 JSON" : "选择分类或搜索文章");
            });
        });
    }

    private void add(LinearLayout row, String label, Runnable action) {
        Button button = new Button(this); button.setText(label); button.setOnClickListener(view -> action.run());
        row.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
    }
    private void work(ThrowingTask action) {
        tasks.submit(() -> {
            try { action.run(); }
            catch (Exception failure) { diagnostics.add(RunDiagnostics.formatError(failure)); report(RunDiagnostics.formatError(failure)); }
        });
    }
    @FunctionalInterface private interface ThrowingTask { void run() throws Exception; }
    private void ui(Runnable callback) { runOnUiThread(tasks.currentCallback(callback)); }
    private void report(String text) { ui(() -> status.setText(text)); }
    private RssSource selected() {
        int index = picker.getSelectedItemPosition();
        return index >= 0 && index < sources.size() ? sources.get(index) : null;
    }
    private RssEngine engine() {
        return new RssEngine(transport, scripts, diagnostic -> diagnostics.add(diagnostic.field() + " / "
                + diagnostic.code() + ": " + diagnostic.message()), ignored -> {});
    }
    private void chooseCategory() {
        RssSource source = selected(); if (source == null) { report("请先导入 RSS 源"); return; }
        cancelPreloads(); showList();
        if (source.flag("singleUrl", false) || nonblank(source.string("startHtml"))) { startPage(source); return; }
        work(() -> {
            List<Category> categories = engine().categories(source);
            ui(() -> new AlertDialog.Builder(this).setTitle("RSS 分类").setItems(categories.stream()
                    .map(category -> category.name().isBlank() ? "默认" : category.name()).toArray(String[]::new),
                    (dialog, index) -> load(source, categories.get(index), 1, "", null, true)).show());
        });
    }
    private void search() {
        RssSource source = selected(); String key = keyword.getText().toString().trim();
        if (source == null || key.isEmpty()) { report("选择源并输入关键词"); return; }
        String url = source.string("searchUrl");
        if (!nonblank(url)) { report("该 RSS 源未配置搜索"); return; }
        load(source, new Category("搜索", url), 1, key, null, true);
    }
    private void nextPage() {
        if (activePage == null || !nonblank(activePage.nextPageUrl())) { report("没有下一页"); return; }
        if (selected() == null || !selected().id().equals(activeSource.id())) { report("请先加载当前所选源"); return; }
        load(activeSource, activeCategory, activePage.page() + 1, activeKey, activePage.nextPageUrl(), false);
    }
    private void refresh() {
        readCache.clear();
        if (activeSource != null && activeCategory != null) load(activeSource, activeCategory, 1, activeKey, null, true);
        else chooseCategory();
    }
    private void load(RssSource source, Category category, int page, String key, String pageUrl, boolean reset) {
        cancelPreloads(); showList();
        if (reset) visitedPages.clear();
        report("获取 RSS 第 " + page + " 页…");
        work(() -> {
            Page found = engine().articles(source, category, page, key, pageUrl == null ? category.url() : pageUrl);
            boolean pageTemplate = "PAGE".equalsIgnoreCase(source.string("ruleNextPage"));
            String identity = found.responseUrl() + (pageTemplate ? "\u0000" + page : "");
            ui(() -> {
                if (!visitedPages.add(identity)) { report("RSS 分页返回已访问页面"); return; }
                activeSource = source; activeCategory = category; activePage = found; activeKey = key;
                articles = found.articles(); renderList();
                report("第 " + page + " 页，" + articles.size() + " 篇文章");
                preload(source, articles);
            });
        });
    }
    private void renderList() {
        list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1,
                articles.stream().map(article -> article.title() + (nonblank(article.pubDate()) ? "\n" + article.pubDate() : "")).toList()));
        list.setOnItemClickListener((parent, view, index, id) -> open(activeSource, articles.get(index), true));
    }
    private void open(RssSource source, Article article, boolean save) {
        cancelPreloads(); closeBrowser();
        work(() -> {
            if (save) {
                String token = selections.save(article);
                if (!tasks.isCurrent()) { selections.remove(token); return; }
                runOnUiThread(tasks.currentCallback(() -> {
                    String previous = articleToken;
                    articleToken = token;
                    selections.remove(previous);
                }, () -> selections.remove(token)));
            }
            RssReadCache.Ticket ticket = readCache.ticket(source, article, states);
            ReadTarget target = readCache.get(ticket);
            if (target == null) {
                target = engine().open(source, article);
                if (tasks.isCurrent()) readCache.putIfUnchanged(ticket, source, article, states, target);
            }
            RssWebViewController.Config config = config(source);
            ReadTarget ready = target;
            ui(() -> { activeSource = source; present(source, ready, config); });
        });
    }
    private void startPage(RssSource source) {
        closeBrowser();
        work(() -> {
            String html = source.string("startHtml");
            if (html != null && html.startsWith("@js:")) html = Objects.toString(scripts.evaluate(html.substring(4), Map.of(), source.executionSource()), "");
            else if (html != null && html.startsWith("<js>") && html.endsWith("</js>"))
                html = Objects.toString(scripts.evaluate(html.substring(4, html.length() - 5), Map.of(), source.executionSource()), "");
            RssWebViewController.Config config = config(source);
            String start = html;
            ui(() -> {
                activeSource = source; prepareBrowser(source, config);
                if (nonblank(start)) browser.loadHtml(start, source.id(), true); else browser.loadUrl(source.id());
            });
        });
    }
    private RssWebViewController.Config config(RssSource source) {
        FetchRequest request = new RequestPlanner(scripts).plan(source.id(), source.executionSource(), "", 1, source.id());
        return new RssWebViewController.Config(source.id(), source.id(), request.headers(), source.flag("enableJs", true),
                source.flag("cacheFirst", false), source.string("preloadJs"), source.string("injectJs"), source.string("startHtml"),
                source.string("startJs"), source.string("style"), source.string("startStyle"), source.string("contentWhitelist"),
                source.string("contentBlacklist"), states.snapshot(SourcePolicy.of(source.executionSource()).stateKey()));
    }
    private void present(RssSource source, ReadTarget target, RssWebViewController.Config config) {
        prepareBrowser(source, config);
        if (target instanceof HtmlContent html) browser.loadHtml(html.html(), source.flag("loadWithBaseUrl", true) ? html.baseUrl() : null, false);
        else if (target instanceof BrowserPage page) browser.loadUrl(page.url());
        else if (target instanceof Images images) {
            StringBuilder html = new StringBuilder("<html><body>");
            for (Resource resource : images.resources()) html.append("<img style=\"max-width:100%\" src=\"")
                    .append(android.text.TextUtils.htmlEncode(resource.url())).append("\">");
            browser.loadHtml(html.append("</body></html>").toString(), source.id(), false);
        } else if (target instanceof Video video) browser.loadUrl(video.url());
        report("RSS 阅读：" + source.name());
    }
    private void prepareBrowser(RssSource source, RssWebViewController.Config config) {
        closeBrowser(); list.setVisibility(View.GONE); reader.setVisibility(View.VISIBLE);
        WebView view = new WebView(this); reader.addView(view, new LinearLayout.LayoutParams(-1, -1));
        long owner = browserOwner;
        browser = new RssWebViewController(view, config, new RssWebViewController.Host() {
            @Override public String dispatch(String method, List<String> arguments) {
                return bridge(source, method, arguments, owner);
            }
            @Override public FetchRequest prepare(FetchRequest request) throws Exception {
                return SourceRequests.prepare(RssRequestPolicy.forSource(source, request), states);
            }
            @Override public boolean shouldOverride(String url) {
                String code = source.string("shouldOverrideUrlLoading");
                if (!nonblank(code)) return false;
                Map<String, RhinoRuntime.HostFunction> functions = new LinkedHashMap<>();
                for (String method : List.of("searchBook", "open", "showPhoto"))
                    functions.put(method, args -> bridge(source, method, args, owner));
                Object result = scripts.evaluateWithHost(code, Map.of("url", url), source.executionSource(), functions);
                return Boolean.TRUE.equals(result) || "true".equalsIgnoreCase(String.valueOf(result));
            }
            @Override public void onDiagnostic(String field, String code, String message) {
                diagnostics.add(field + " / " + code + ": " + message);
                report(code.equals("PARTIAL_WEB_BRIDGE")
                        ? "当前 WebView 无法安全启用网页原生功能；文章仍可阅读。点击查看详情。"
                        : "网页功能受限，点击查看详情：" + code);
            }
        }, request -> scripts.fetch(RssRequestPolicy.forSource(source, request)));
    }
    private String bridge(RssSource source, String method, List<String> arguments, long owner) {
        if (destroyed || (owner != browserOwner && !method.equals("put"))) throw new IllegalStateException("RSS reader closed");
        String first = arguments.isEmpty() ? "" : arguments.get(0);
        String identity = SourcePolicy.of(source.executionSource()).stateKey();
        if (method.equals("put")) { states.put(identity, "value:" + first, arguments.get(1)); return arguments.get(1); }
        if (method.equals("get")) return states.get(identity, "value:" + first);
        if (method.equals("searchBook")) {
            runOnUiThread(() -> { if (!destroyed && owner == browserOwner) startActivity(new Intent(this, MainActivity.class).putExtra("rssSearch", first)); });
            return "";
        }
        if (method.equals("showPhoto")) {
            if (!ResourceUrls.isHttp(first)) throw new IllegalArgumentException("Invalid photo URL");
            runOnUiThread(() -> { if (!destroyed && owner == browserOwner && browser != null) browser.loadHtml("<img style=\"max-width:100%\" src=\""
                    + android.text.TextUtils.htmlEncode(first) + "\">", source.id(), false); }); return "";
        }
        if (method.equals("open")) {
            String name = first;
            String url = arguments.size() > 1 ? arguments.get(1) : "";
            String title = arguments.size() > 2 ? arguments.get(2) : "";
            String origin = arguments.size() > 3 ? arguments.get(3) : "";
            if (name.equals("search")) {
                if (title.isBlank()) return "";
                runOnUiThread(() -> { if (!destroyed && owner == browserOwner) startActivity(new Intent(this, MainActivity.class)
                        .putExtra("rssSearch", title).putExtra("rssSearchSource", origin)); }); return "";
            }
            if (!origin.isEmpty() && !origin.equals(source.id())) throw new UnsupportedOperationException("Cross-source RSS open is unavailable");
            if (name.equals("login")) {
                String login = source.string("loginUrl");
                if (!ResourceUrls.isHttp(login)) throw new UnsupportedOperationException("RSS source has no HTTP(S) login URL");
                runOnUiThread(() -> { if (!destroyed && owner == browserOwner) {
                    readCache.clear(); startActivity(new Intent(this, LoginActivity.class).putExtra("url", login));
                } }); return "";
            }
            if (name.equals("sort")) {
                runOnUiThread(() -> { if (!destroyed && owner == browserOwner) {
                    if (url.isBlank()) chooseCategory(); else load(source, new Category(title, url), 1, "", null, true);
                } }); return "";
            }
            if (!name.equals("rss")) throw new UnsupportedOperationException("Unsupported RSS open target: " + name);
            if (url.isEmpty()) { runOnUiThread(() -> { if (!destroyed && owner == browserOwner) startPage(source); }); return ""; }
            String absolute = RuleEvaluator.absolute(source.id(), url);
            if (!ResourceUrls.isHttp(absolute)) throw new UnsupportedOperationException("RSS page requires HTTP(S)");
            runOnUiThread(() -> { if (!destroyed && owner == browserOwner && browser != null) browser.loadUrl(absolute); }); return "";
        }
        throw new UnsupportedOperationException("Unsupported RSS bridge action");
    }
    private void preload(RssSource source, List<Article> found) {
        if (!source.flag("preload", false)) return;
        for (Article article : found.stream().limit(2).toList()) {
            pendingPreloads.add(preloads.submit(() -> {
                try {
                    RssReadCache.Ticket ticket = readCache.ticket(source, article, states);
                    ReadTarget target = engine().open(source, article);
                    if (!Thread.currentThread().isInterrupted()) readCache.putIfUnchanged(ticket, source, article, states, target);
                } catch (RuntimeException ignored) { /* Foreground reading reports its own failures. */ }
            }));
        }
    }
    private void cancelPreloads() { pendingPreloads.forEach(task -> task.cancel(true)); pendingPreloads.clear(); }
    private void showList() {
        tasks.cancel(); cancelPreloads(); closeBrowser();
        reader.setVisibility(View.GONE); list.setVisibility(View.VISIBLE);
    }
    private void closeBrowser() { browserOwner++; if (browser != null) { browser.close(); browser = null; } if (reader != null) reader.removeAllViews(); }
    private void login() {
        RssSource source = selected(); if (source == null) return;
        String url = source.string("loginUrl"); if (!ResourceUrls.isHttp(url)) { report("该 RSS 源没有登录地址"); return; }
        readCache.clear(); startActivity(new Intent(this, LoginActivity.class).putExtra("url", url));
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        if (activeSource != null) state.putString("sourceId", activeSource.id());
        if (articleToken != null) state.putString("articleToken", articleToken);
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        destroyed = true; tasks.close(); cancelPreloads(); preloads.shutdownNow(); closeBrowser();
        if (isFinishing()) selections.remove(articleToken);
        super.onDestroy();
    }
    private static boolean nonblank(String value) { return value != null && !value.isBlank(); }
}
