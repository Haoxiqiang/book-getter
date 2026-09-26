package org.bookgetter.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.bookgetter.core.Models.*;
import org.bookgetter.core.SearchCoordinator;
import org.bookgetter.core.SourceEngine;
import org.bookgetter.export.BookExporter;
import org.bookgetter.runtime.AndroidTransport;
import org.bookgetter.runtime.ResourceDownloader;

/** Small inspection UI for source import, capture and export; the engine has no Activity dependency. */
public final class MainActivity extends Activity {
    private static final int OPEN_JSON = 1, CREATE_TXT = 2, CREATE_EPUB = 3, CREATE_RESOURCE = 4;
    private final ExecutorService io = Executors.newFixedThreadPool(5);
    private SourceStore sources;
    private ChapterStore cache;
    private AndroidTransport transport;
    private SourceEngine engine;
    private EditText input;
    private Spinner sourcePicker;
    private ListView list;
    private TextView status;
    private List<Source> available = List.of();
    private List<BookHit> hits = List.of();
    private List<Chapter> chapters = List.of();
    private BookDetail activeBook;
    private Source activeSource;
    private Future<?> task;
    private final AtomicInteger generation = new AtomicInteger();
    private final ThreadLocal<Integer> workerGeneration = new ThreadLocal<>();
    private List<Resource> pendingResources = List.of();
    private List<Diagnostic> importDiagnostics = List.of();

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sources = new SourceStore(this); cache = new ChapterStore(this);
        transport = new AndroidTransport(this); engine = new SourceEngine(transport);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(20, 20, 20, 20);
        sourcePicker = new Spinner(this); root.addView(sourcePicker);
        input = new EditText(this); input.setSingleLine(true); input.setHint("关键词或书源 JSON URL"); root.addView(input);
        row(root, button("剪贴板导入", () -> importClipboard()), button("文件导入", () -> openJson()), button("URL 导入", () -> importUrl()));
        row(root, button("搜索", () -> search()), button("登录", () -> login()), button("取消", () -> cancel()));
        row(root, button("导出 TXT", () -> createDocument(false)), button("导出 EPUB", () -> createDocument(true)), button("资源下载", () -> prepareResources()), button("返回结果", () -> showHits()));
        status = new TextView(this); status.setText("导入书源后搜索"); root.addView(status);
        status.setOnClickListener(v -> {
            if (importDiagnostics.isEmpty()) return;
            StringBuilder details = new StringBuilder();
            for (Diagnostic diagnostic : importDiagnostics)
                details.append(diagnostic.stage()).append(" / ").append(diagnostic.field()).append(" / ").append(diagnostic.code()).append("\n")
                        .append(diagnostic.message()).append("\n\n");
            new AlertDialog.Builder(this).setTitle("书源预检").setMessage(details.toString()).setPositiveButton("关闭", null).show();
        });
        list = new ListView(this); root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        refreshSources();
    }

    private Button button(String title, Runnable action) {
        Button button = new Button(this); button.setText(title); button.setOnClickListener(v -> action.run()); return button;
    }
    private void row(LinearLayout root, Button... buttons) {
        LinearLayout row = new LinearLayout(this); root.addView(row);
        for (Button button : buttons) row.addView(button, new LinearLayout.LayoutParams(0, -2, 1));
    }
    private void report(String message) {
        Integer owner = workerGeneration.get();
        runOnUiThread(() -> { if (owner == null || generation.get() == owner) status.setText(message); });
    }
    private void onCurrentUi(Runnable action) {
        Integer owner = workerGeneration.get();
        runOnUiThread(() -> { if (owner == null || generation.get() == owner) action.run(); });
    }
    private void runAsync(Runnable work) {
        if (task != null) task.cancel(true);
        int owner = generation.incrementAndGet();
        task = io.submit(() -> {
            workerGeneration.set(owner);
            try { work.run(); }
            catch (Exception e) { report(formatError(e)); }
            finally { workerGeneration.remove(); }
        });
    }
    private String formatError(Exception e) {
        if (e instanceof SourceFailure failure) return failure.stage + " / " + failure.field + " / " + failure.sourceId + ": " + failure.getMessage();
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
    private void cancel() {
        generation.incrementAndGet();
        if (task != null) task.cancel(true);
        report("已取消；成功章节保留在缓存中");
    }

    private void refreshSources() {
        available = sources.all();
        List<String> names = new ArrayList<>(); names.add("全部书源");
        for (Source source : available) names.add(source.name());
        sourcePicker.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
    }
    private Source selectedSource() { int index = sourcePicker.getSelectedItemPosition(); return index > 0 && index <= available.size() ? available.get(index - 1) : null; }
    private void importClipboard() {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        ClipData data = clipboard.getPrimaryClip();
        if (data == null || data.getItemCount() == 0) { report("剪贴板为空"); return; }
        importText(data.getItemAt(0).coerceToText(this).toString());
    }
    private void importUrl() {
        String url = input.getText().toString().trim();
        if (!url.startsWith("https://") && !url.startsWith("http://")) { report("请输入书源 JSON URL"); return; }
        runAsync(() -> { try { importTextBlocking(transport.fetch(new FetchRequest(url)).body()); } catch (Exception e) { report(e.toString()); } });
    }
    private void openJson() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, OPEN_JSON);
    }
    private void importText(String text) { runAsync(() -> importTextBlocking(text)); }
    private void importTextBlocking(String text) {
        try {
            ImportResult imported = sources.importText(text, transport);
            onCurrentUi(() -> { importDiagnostics = imported.diagnostics(); refreshSources(); });
            report("现有 " + imported.sources().size() + " 个书源；预检提示 " + imported.diagnostics().size() + " 项。点击此处查看。" + (imported.diagnostics().isEmpty() ? "" : " 首项：" + imported.diagnostics().get(0).message()));
        } catch (Exception e) { report("导入失败：" + e.getMessage()); }
    }

    private void search() {
        String keyword = input.getText().toString().trim();
        if (keyword.isEmpty()) { report("请输入关键词"); return; }
        Source selected = selectedSource();
        List<Source> selectedSources = selected == null ? available : List.of(selected);
        if (selectedSources.isEmpty()) { report("请先导入书源"); return; }
        report("搜索中…");
        runAsync(() -> {
            SearchResult result = new SearchCoordinator(engine, io).search(selectedSources, keyword, 1);
            onCurrentUi(() -> { hits = result.hits(); showHits(); });
            report("找到 " + result.hits().size() + " 条；失败书源 " + result.errors().size() + " 个" + (result.errors().isEmpty() ? "" : "：" + result.errors().values().iterator().next()));
        });
    }
    private void showHits() {
        activeBook = null; chapters = List.of();
        List<String> labels = new ArrayList<>();
        for (BookHit hit : hits) labels.add(hit.name() + " · " + hit.author() + "\n" + hit.sourceId());
        list.setChoiceMode(ListView.CHOICE_MODE_NONE);
        list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels));
        list.setOnItemClickListener((parent, view, position, id) -> openBook(hits.get(position)));
    }
    private void openBook(BookHit hit) {
        activeSource = available.stream().filter(source -> source.id().equals(hit.sourceId())).findFirst().orElse(null);
        if (activeSource == null) { report("书源已经移除"); return; }
        Source source = activeSource;
        report("获取详情与目录…");
        runAsync(() -> {
            BookDetail detail = engine.detail(source, hit);
            List<Chapter> found = engine.chapters(source, detail);
            onCurrentUi(() -> {
                activeBook = detail; activeSource = source; chapters = found;
                List<String> labels = new ArrayList<>();
                if (source.kind() == Kind.FILE) labels.addAll(detail.downloadUrls());
                else for (Chapter chapter : found) labels.add(chapter.title());
                list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
                list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_multiple_choice, labels));
                list.setOnItemClickListener(null);
            });
            report(source.kind() == Kind.FILE ? "找到 " + detail.downloadUrls().size() + " 个文件链接；请选择一个并点资源下载。"
                    : detail.hit().name() + "，" + found.size() + " 章。可勾选章节；不选则处理整本。");
        });
    }
    private void login() {
        Source source = selectedSource();
        if (source == null) { report("登录前请选择一个书源"); return; }
        Intent intent = new Intent(this, LoginActivity.class);
        intent.putExtra("url", source.string("loginUrl") == null ? source.id() : source.string("loginUrl"));
        startActivity(intent);
    }
    private void createDocument(boolean epub) {
        if (activeBook == null || chapters.isEmpty()) { report("请先选书并加载目录"); return; }
        if (activeSource.kind() != Kind.TEXT) { report("EPUB/TXT 只接受文本章节；请使用资源下载"); return; }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(epub ? "application/epub+zip" : "text/plain")
                .putExtra(Intent.EXTRA_TITLE, activeBook.hit().name().replaceAll("[\\\\/:*?\"<>|]", "_") + (epub ? ".epub" : ".txt"));
        startActivityForResult(intent, epub ? CREATE_EPUB : CREATE_TXT);
    }
    private void prepareResources() {
        if (activeBook == null) { report("请先选书"); return; }
        if (activeSource.kind() == Kind.TEXT) { report("文本请使用 EPUB/TXT 导出"); return; }
        if (activeSource.kind() == Kind.FILE) {
            int chosen = -1;
            for (int i = 0; i < activeBook.downloadUrls().size(); i++) if (list.isItemChecked(i)) { chosen = i; break; }
            if (chosen < 0 && !activeBook.downloadUrls().isEmpty()) chosen = 0;
            if (chosen < 0) { report("没有可下载的文件链接"); return; }
            pendingResources = List.of(new Resource(activeBook.downloadUrls().get(chosen), activeBook.hit().name(), null));
            openResourceDocument(false);
            return;
        }
        int chosen = -1;
        for (int i = 0; i < chapters.size(); i++) if (list.isItemChecked(i)) { chosen = i; break; }
        if (chosen < 0 && !chapters.isEmpty()) chosen = 0;
        if (chosen < 0) { report("没有章节"); return; }
        Chapter chapter = chapters.get(chosen);
        Source source = activeSource;
        BookDetail book = activeBook;
        report("解析资源中…");
        runAsync(() -> {
            ChapterContent content = engine.content(source, book, chapter, nextChapterUrl(chapter));
            if (content.resources().isEmpty()) { report("未解析到资源"); return; }
            onCurrentUi(() -> { pendingResources = content.resources(); openResourceDocument(source.kind() == Kind.IMAGE); });
        });
    }
    private void openResourceDocument(boolean zip) {
        String extension = ".bin";
        if (!zip && !pendingResources.isEmpty()) {
            String path = Uri.parse(pendingResources.get(0).url()).getPath();
            if (path != null && path.matches("(?i).*\\.(epub|txt|pdf|mp3|m4a|mp4|webm|mkv|zip|cbz)"))
                extension = path.substring(path.lastIndexOf('.'));
        }
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(zip ? "application/zip" : "application/octet-stream")
                .putExtra(Intent.EXTRA_TITLE, activeBook.hit().name().replaceAll("[\\\\/:*?\"<>|]", "_") + (zip ? "-images.zip" : extension));
        startActivityForResult(intent, CREATE_RESOURCE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == OPEN_JSON) runAsync(() -> { try { importTextBlocking(read(uri)); } catch (Exception e) { report(e.toString()); } });
        else if (requestCode == CREATE_TXT || requestCode == CREATE_EPUB) captureAndExport(uri, requestCode == CREATE_EPUB);
        else if (requestCode == CREATE_RESOURCE) downloadResources(uri);
    }
    private String read(Uri uri) throws Exception {
        try (InputStream stream = getContentResolver().openInputStream(uri); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            if (stream == null) throw new IllegalStateException("无法读取文件");
            byte[] buffer = new byte[8192]; int read;
            while ((read = stream.read(buffer)) >= 0) bytes.write(buffer, 0, read);
            return bytes.toString(StandardCharsets.UTF_8.name());
        }
    }
    private void captureAndExport(Uri uri, boolean epub) {
        List<Chapter> selected = new ArrayList<>();
        for (int i = 0; i < chapters.size(); i++) if (list.isItemChecked(i)) selected.add(chapters.get(i));
        if (selected.isEmpty()) selected.addAll(chapters);
        Source source = activeSource; BookDetail book = activeBook;
        report("抓取 0/" + selected.size());
        runAsync(() -> {
            List<ChapterContent> captured = new ArrayList<>();
            List<String> failures = new ArrayList<>();
            for (Chapter chapter : selected) {
                if (Thread.currentThread().isInterrupted()) { report("已取消；成功章节保留在缓存中"); return; }
                try {
                    ChapterContent content = cache.get(source.id(), chapter);
                    if (content == null) { content = engine.content(source, book, chapter, nextChapterUrl(chapter)); cache.put(source.id(), content); }
                    captured.add(content);
                } catch (Exception e) { failures.add(chapter.title() + ": " + e.getMessage()); }
                report("抓取 " + (captured.size() + failures.size()) + "/" + selected.size() + "；失败 " + failures.size());
            }
            if (captured.isEmpty()) { report("所有章节抓取失败" + (failures.isEmpty() ? "" : "：" + failures.get(0))); return; }
            try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new IllegalStateException("无法写入导出文件");
                new BookExporter().write(epub ? BookExporter.Format.EPUB : BookExporter.Format.TXT, book, captured, output);
                report("已导出 " + captured.size() + " 章；失败 " + failures.size() + (failures.isEmpty() ? "" : "。首项：" + failures.get(0)));
            } catch (Exception e) { report("导出失败：" + e.getMessage()); }
        });
    }
    private void downloadResources(Uri uri) {
        List<Resource> resources = pendingResources;
        Source source = activeSource;
        runAsync(() -> {
            try (OutputStream output = getContentResolver().openOutputStream(uri, "wt")) {
                if (output == null) throw new IllegalStateException("无法写入文件");
                ResourceDownloader downloader = new ResourceDownloader();
                if (source.kind() == Kind.IMAGE) downloader.downloadZip(source, resources, output);
                else downloader.download(source, resources.get(0), output);
                report("已下载 " + resources.size() + " 个资源");
            } catch (Exception e) { report("资源下载失败：" + e.getMessage()); }
        });
    }
    private String nextChapterUrl(Chapter chapter) {
        int index = chapters.indexOf(chapter);
        return index >= 0 && index + 1 < chapters.size() ? chapters.get(index + 1).url() : null;
    }
    @Override protected void onDestroy() { generation.incrementAndGet(); if (task != null) task.cancel(true); io.shutdownNow(); super.onDestroy(); }
}
