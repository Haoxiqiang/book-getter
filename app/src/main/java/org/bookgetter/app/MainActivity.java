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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import org.bookgetter.core.Models.*;
import org.bookgetter.core.SearchCoordinator;
import org.bookgetter.core.SourceEngine;
import org.bookgetter.core.RhinoRuntime;
import org.bookgetter.core.ResourceUrls;
import org.bookgetter.runtime.AndroidTransport;
import org.bookgetter.runtime.ResourceDownloader;

/** Small inspection UI for source import, capture and export; the engine has no Activity dependency. */
public final class MainActivity extends Activity {
    private static final int OPEN_JSON = 1, CREATE_TXT = 2, CREATE_EPUB = 3, CREATE_RESOURCE = 4;
    private final ExecutorService io = Executors.newFixedThreadPool(5);
    private final ExecutorService searchWorkers = Executors.newFixedThreadPool(4);
    private SourceStore sources;
    private ChapterStore cache;
    private CaptureJobStore jobs;
    private AndroidTransport transport;
    private RhinoRuntime scripts;
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
    private PendingResourceDownload pendingResource;
    private List<Diagnostic> importDiagnostics = List.of();
    private record RunContext(SourceEngine engine, ConcurrentLinkedQueue<Diagnostic> diagnostics,
                              ConcurrentLinkedQueue<StageTrace> traces) {}
    private String reportDetails = "";
    private String reportTitle = "阶段诊断";
    private int resultPage = 1;
    private String lastQuery = "";
    private String lastSource = "";
    private ExploreEntry activeExplore;
    private CaptureJobStore.Job pendingJob;

    @Override public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sources = new SourceStore(this); cache = new ChapterStore(this); jobs = new CaptureJobStore(this);
        transport = new AndroidTransport(this);
        scripts = new RhinoRuntime(transport);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(20, 20, 20, 20);
        sourcePicker = new Spinner(this); root.addView(sourcePicker);
        input = new EditText(this); input.setSingleLine(true); input.setHint("关键词或书源/合集 JSON URL"); root.addView(input);
        row(root, button("剪贴板导入", () -> importClipboard()), button("文件导入", () -> openJson()), button("URL 导入", () -> importUrl()), button("更新订阅", () -> updateSubscriptions()));
        row(root, button("搜索", () -> search(1)), button("发现", () -> chooseExplore()),
                button("下一页", () -> nextPage()), button("登录", () -> login()), button("取消", () -> cancel()));
        row(root, button("导出 TXT", () -> createDocument(false)), button("导出 EPUB", () -> createDocument(true)),
                button("继续上次", () -> resumeCapture()), button("资源下载", () -> prepareResources()), button("返回结果", () -> showHits()));
        status = new TextView(this); status.setText("导入书源后搜索"); root.addView(status);
        status.setOnClickListener(v -> {
            if (!reportDetails.isEmpty()) new AlertDialog.Builder(this).setTitle(reportTitle)
                    .setMessage(reportDetails).setPositiveButton("关闭", null).show();
        });
        list = new ListView(this); root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        refreshSources();
        if (savedInstanceState != null)
            pendingResource = PendingResourceDownload.restore(savedInstanceState.getString("pendingResource"));
        try {
            CaptureJobStore.Job saved = jobs.get();
            if (saved != null) report("有未完成抓取：" + saved.book().hit().name() + "。点击「继续上次」复用已抓章节。");
        } catch (IllegalStateException e) { report("抓取任务文件损坏；创建新任务时可先备份原文件。"); }
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        if (pendingResource != null) outState.putString("pendingResource", pendingResource.serialize());
        super.onSaveInstanceState(outState);
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
    private RunContext newRun() {
        ConcurrentLinkedQueue<Diagnostic> diagnostics = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<StageTrace> traces = new ConcurrentLinkedQueue<>();
        return new RunContext(new SourceEngine(transport, scripts, diagnostics::add, traces::add), diagnostics, traces);
    }
    private void runAsync(Runnable work) { runAsync(null, work); }
    private void runAsync(RunContext run, Runnable work) {
        if (task != null) task.cancel(true);
        int owner = generation.incrementAndGet();
        task = io.submit(() -> {
            workerGeneration.set(owner);
            try { work.run(); }
            catch (Exception e) {
                String message = formatError(e);
                onCurrentUi(() -> {
                    reportTitle = "阶段失败";
                    reportDetails = message + "\n\n" + traceText(run)
                            + (run == null ? "" : diagnosticText(run.diagnostics()));
                });
                report(message);
            }
            finally { workerGeneration.remove(); }
        });
    }

    @Override protected void onDestroy() {
        cancelCurrentWork();
        io.shutdownNow();
        searchWorkers.shutdownNow();
        super.onDestroy();
    }
    private String formatError(Exception e) {
        if (e instanceof SourceFailure failure) return failure.stage + " / " + failure.field + " / "
                + failure.code + " / " + failure.sourceId + ": " + failure.getMessage();
        return e.getMessage() == null ? e.toString() : e.getMessage();
    }
    private void cancel() {
        cancelCurrentWork();
        report("已取消；成功章节保留在缓存中");
    }
    private void cancelCurrentWork() {
        generation.incrementAndGet();
        if (task != null) task.cancel(true);
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
        runAsync(() -> {
            try {
                SourceStore.UrlImport found = sources.importUrlOrPreview(url, transport);
                if (found.directory() != null) onCurrentUi(() -> chooseCollections(found.directory()));
                else showImport(found.imported());
            }
            catch (Exception e) { report("导入失败：" + e.getMessage()); }
        });
    }
    private void chooseCollections(SourceStore.DirectoryPreview preview) {
        String[] labels = preview.collections().stream().map(entry -> entry.name()).toArray(String[]::new);
        boolean[] checked = new boolean[labels.length];
        new AlertDialog.Builder(this).setTitle("选择要订阅的书源合集")
                .setMultiChoiceItems(labels, checked, (dialog, index, enabled) -> checked[index] = enabled)
                .setPositiveButton("导入选中", (dialog, which) -> {
                    List<String> chosen = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++) if (checked[i]) chosen.add(labels[i]);
                    if (chosen.isEmpty()) { report("请至少选择一个合集"); return; }
                    runAsync(() -> {
                        try { showImport(sources.importDirectorySelection(preview, chosen, transport)); }
                        catch (Exception e) { report("合集导入失败：" + e.getMessage()); }
                    });
                }).setNegativeButton("取消", null).show();
    }
    private void updateSubscriptions() {
        runAsync(() -> {
            try {
                ImportResult updated = sources.updateSubscriptions(transport);
                showImport(updated);
                report("已更新 " + sources.subscriptions().size() + " 个订阅；现有 " + updated.sources().size() + " 个书源。");
            } catch (Exception e) { report("订阅更新失败：" + e.getMessage()); }
        });
    }
    private void openJson() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("application/json").addCategory(Intent.CATEGORY_OPENABLE);
        startActivityForResult(intent, OPEN_JSON);
    }
    private void importText(String text) { runAsync(() -> importTextBlocking(text)); }
    private void importTextBlocking(String text) {
        try {
            showImport(sources.importText(text, transport));
        } catch (Exception e) { report("导入失败：" + e.getMessage()); }
    }
    private void showImport(ImportResult imported) {
        onCurrentUi(() -> {
            importDiagnostics = imported.diagnostics(); refreshSources();
            reportTitle = "书源预检";
            reportDetails = diagnosticText(importDiagnostics);
        });
        report("现有 " + imported.sources().size() + " 个书源；预检提示 " + imported.diagnostics().size() + " 项。点击此处查看。" + (imported.diagnostics().isEmpty() ? "" : " 首项：" + imported.diagnostics().get(0).message()));
    }

    private static String diagnosticText(Iterable<Diagnostic> diagnostics) {
        StringBuilder details = new StringBuilder();
        for (Diagnostic diagnostic : diagnostics)
            details.append(diagnostic.stage()).append(" / ").append(diagnostic.field()).append(" / ")
                    .append(diagnostic.code()).append("\n").append(diagnostic.message()).append("\n\n");
        return details.toString();
    }

    private String traceText(RunContext run) {
        StringBuilder details = new StringBuilder();
        if (run == null) return "";
        for (StageTrace trace : run.traces())
            details.append(trace.sourceId()).append(" / ").append(trace.stage()).append(" / ")
                    .append(trace.field()).append("\n").append(trace.method()).append(" ")
                    .append(trace.requestUrl()).append(" → ").append(trace.responseUrl())
                    .append("\nHTTP ").append(trace.status()).append(" / ")
                    .append(trace.responseQuality()).append("\n\n");
        return details.toString();
    }

    private void search(int page) {
        String keyword = input.getText().toString().trim();
        if (keyword.isEmpty()) { report("请输入关键词"); return; }
        Source selected = selectedSource();
        List<Source> selectedSources = selected == null ? available : List.of(selected);
        if (selectedSources.isEmpty()) { report("请先导入书源"); return; }
        activeExplore = null;
        resultPage = page;
        lastQuery = keyword;
        lastSource = selected == null ? "" : selected.id();
        RunContext run = newRun();
        report("搜索第 " + page + " 页…");
        runAsync(run, () -> {
            SearchResult result = new SearchCoordinator(run.engine(), searchWorkers).search(selectedSources, keyword, page);
            StringBuilder details = new StringBuilder();
            result.errors().forEach((id, error) -> details.append(id).append(" / ").append(error).append("\n\n"));
            details.append(traceText(run)).append(diagnosticText(run.diagnostics()));
            onCurrentUi(() -> {
                hits = result.hits(); showHits();
                reportTitle = "第 " + page + " 页阶段诊断";
                reportDetails = details.toString();
            });
            report("第 " + page + " 页找到 " + result.hits().size() + " 条；失败书源 "
                    + result.errors().size() + " 个。点击状态查看诊断。");
        });
    }

    private void chooseExplore() {
        Source source = selectedSource();
        if (source == null) { report("发现需要先选择一个书源"); return; }
        RunContext run = newRun();
        runAsync(run, () -> {
            List<ExploreEntry> entries = run.engine().exploreEntries(source);
            if (entries.isEmpty()) { report("该书源没有发现入口"); return; }
            onCurrentUi(() -> {
                String[] titles = entries.stream().map(ExploreEntry::title).toArray(String[]::new);
                new AlertDialog.Builder(this).setTitle("选择发现分类").setItems(titles,
                        (dialog, index) -> explore(source, entries.get(index), 1)).show();
            });
        });
    }

    private void explore(Source source, ExploreEntry entry, int page) {
        activeExplore = entry;
        lastSource = source.id();
        resultPage = page;
        RunContext run = newRun();
        report("发现「" + entry.title() + "」第 " + page + " 页…");
        runAsync(run, () -> {
            List<BookHit> found = run.engine().explore(source, entry, page);
            String details = traceText(run) + diagnosticText(run.diagnostics());
            onCurrentUi(() -> {
                hits = found; showHits();
                reportTitle = "发现阶段诊断";
                reportDetails = details;
            });
            report("发现「" + entry.title() + "」第 " + page + " 页找到 " + found.size() + " 条。");
        });
    }

    private void nextPage() {
        Source selected = selectedSource();
        String id = selected == null ? "" : selected.id();
        if (!id.equals(lastSource)) { report("书源已更换，请重新搜索或选择发现分类"); return; }
        if (activeExplore != null) {
            if (selected == null) { report("请重新选择发现书源"); return; }
            explore(selected, activeExplore, resultPage + 1);
        } else if (!lastQuery.isEmpty() && lastQuery.equals(input.getText().toString().trim())) {
            search(resultPage + 1);
        } else report("请先搜索当前关键词");
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
        RunContext run = newRun();
        report("获取详情与目录…");
        runAsync(run, () -> {
            boolean hasDetail = source.isJsSource() || source.rules("ruleBookInfo") != null;
            boolean hasCatalog = source.isJsSource() || source.rules("ruleToc") != null;
            BookDetail detail = hasDetail ? run.engine().detail(source, hit) : new BookDetail(hit, hit.url(), List.of());
            List<Chapter> found = hasCatalog ? run.engine().chapters(source, detail) : List.of();
            String details = traceText(run) + diagnosticText(run.diagnostics());
            onCurrentUi(() -> {
                activeBook = detail; activeSource = source; chapters = found;
                reportTitle = "详情与目录诊断";
                reportDetails = details;
                List<String> labels = new ArrayList<>();
                if (source.kind() == Kind.FILE) labels.addAll(detail.downloadUrls());
                else for (Chapter chapter : found) labels.add(chapter.title());
                list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
                list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_multiple_choice, labels));
                list.setOnItemClickListener(null);
            });
            report(source.kind() == Kind.FILE ? "找到 " + detail.downloadUrls().size() + " 个文件链接；请选择一个并点资源下载。"
                    : !hasCatalog ? detail.hit().name() + "：该书源未配置目录，本次只验证到书籍信息。"
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
        List<Chapter> selected = new ArrayList<>();
        for (int i = 0; i < chapters.size(); i++) if (list.isItemChecked(i)) selected.add(chapters.get(i));
        if (selected.isEmpty()) selected.addAll(chapters);
        selected.removeIf(Chapter::volume);
        if (selected.isEmpty()) { report("没有可读取章节"); return; }
        CaptureJobStore.Job requested = new CaptureJobStore.Job(activeSource.id(), ChapterStore.sourceHash(activeSource),
                activeBook, chapters, selected, epub, 0, Map.of());
        CaptureJobStore.Job existing;
        try { existing = jobs.get(); }
        catch (IllegalStateException damaged) {
            new AlertDialog.Builder(this).setTitle("抓取任务文件损坏")
                    .setMessage("原任务无法读取。备份原文件后可创建新任务，旧文件仍保留在应用数据目录。")
                    .setPositiveButton("备份并创建", (dialog, which) -> {
                        try {
                            jobs.backupCorrupt();
                            if (!jobs.putNew(requested)) throw new IllegalStateException("已有其他抓取任务");
                            pendingJob = requested;
                            openCaptureDocument(requested);
                        } catch (Exception e) { report("无法保存抓取任务：" + e.getMessage()); }
                    })
                    .setNegativeButton("取消", null).show();
            return;
        }
        if (existing != null && !CaptureJobStore.sameTask(existing, requested)) {
            new AlertDialog.Builder(this).setTitle("已有未完成抓取")
                    .setMessage(existing.book().hit().name() + " 的抓取进度仍已保存。请选择继续或明确替换。")
                    .setPositiveButton("继续旧任务", (dialog, which) -> resumeCapture())
                    .setNeutralButton("替换并导出", (dialog, which) -> {
                        cancelCurrentWork();
                        try {
                            jobs.replace(requested);
                            pendingJob = requested;
                            openCaptureDocument(requested);
                        } catch (Exception e) { report("无法替换抓取任务：" + e.getMessage()); }
                    })
                    .setNegativeButton("取消", null).show();
            return;
        }
        try {
            if (!jobs.putNew(requested)) { report("已有其他抓取任务，请重试"); return; }
            pendingJob = existing == null ? requested : existing;
            openCaptureDocument(pendingJob);
        } catch (Exception e) { report("无法保存抓取任务：" + e.getMessage()); }
    }

    private void resumeCapture() {
        CaptureJobStore.Job saved;
        try { saved = jobs.get(); }
        catch (IllegalStateException damaged) { report("抓取任务文件损坏；创建新任务时可先备份原文件。"); return; }
        if (saved == null) { report("没有可继续的抓取任务"); return; }
        Source source = available.stream().filter(item -> item.id().equals(saved.sourceId())).findFirst().orElse(null);
        if (source == null) { report("原书源已移除，请先重新导入"); return; }
        if (!ChapterStore.sourceHash(source).equals(saved.sourceHash())) {
            report("书源规则已改变，不能复用旧目录；请重新搜索并创建抓取任务"); return;
        }
        activeSource = source;
        activeBook = saved.book();
        chapters = saved.catalog();
        pendingJob = saved;
        openCaptureDocument(saved);
    }

    private void openCaptureDocument(CaptureJobStore.Job job) {
        boolean epub = job.epub();
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(epub ? "application/epub+zip" : "text/plain")
                .putExtra(Intent.EXTRA_TITLE, job.book().hit().name().replaceAll("[\\\\/:*?\"<>|]", "_") + (epub ? ".epub" : ".txt"));
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
            if (!ResourceUrls.isHttp(activeBook.downloadUrls().get(chosen))) {
                report("文件下载地址无效或协议不受支持"); return;
            }
            pendingResource = PendingResourceDownload.forSource(activeSource,
                    List.of(new Resource(activeBook.downloadUrls().get(chosen), activeBook.hit().name(), null)));
            openResourceDocument(false);
            return;
        }
        int chosen = -1;
        for (int i = 0; i < chapters.size(); i++) if (list.isItemChecked(i)) { chosen = i; break; }
        if (chosen < 0 && !chapters.isEmpty()) chosen = 0;
        if (chosen < 0) { report("没有章节"); return; }
        Chapter chapter = chapters.get(chosen);
        if (chapter.volume()) { report("卷标题不是可读取章节"); return; }
        Source source = activeSource;
        BookDetail book = activeBook;
        RunContext run = newRun();
        report("解析资源中…");
        String nextChapter = nextChapterUrl(chapter);
        runAsync(run, () -> {
            ChapterContent content = run.engine().content(source, book, chapter, nextChapter);
            if (content.resources().isEmpty()) { report("未解析到资源"); return; }
            onCurrentUi(() -> {
                pendingResource = PendingResourceDownload.forSource(source, content.resources());
                openResourceDocument(source.kind() == Kind.IMAGE);
            });
        });
    }
    private void openResourceDocument(boolean zip) {
        String extension = ".bin";
        if (!zip && pendingResource != null) {
            String path = Uri.parse(pendingResource.resources().get(0).url()).getPath();
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
        else if (requestCode == CREATE_TXT || requestCode == CREATE_EPUB) captureAndExport(uri);
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
    private void captureAndExport(Uri uri) {
        CaptureJobStore.Job job;
        try { job = pendingJob == null ? jobs.get() : pendingJob; }
        catch (IllegalStateException damaged) { report("抓取任务文件损坏，无法继续导出"); return; }
        if (job == null) { report("抓取任务已丢失，请重新选择章节"); return; }
        Source source = available.stream().filter(item -> item.id().equals(job.sourceId())).findFirst().orElse(null);
        if (source == null) { report("原书源已移除，请先重新导入"); return; }
        if (!ChapterStore.sourceHash(source).equals(job.sourceHash())) {
            report("书源规则已改变，不能继续旧抓取任务"); return;
        }
        RunContext run = newRun();
        report("抓取 0/" + job.selected().size());
        runAsync(run, () -> {
            List<Chapter> captured = new ArrayList<>();
            Map<String, String> failures = new LinkedHashMap<>();
            for (Chapter chapter : job.selected()) {
                if (Thread.currentThread().isInterrupted()) { report("已取消；成功章节保留在缓存中"); return; }
                try {
                    String boundary = SourceEngine.nextReadableUrl(job.catalog(), chapter);
                    ChapterContent content = cache.get(source, job.book(), chapter, boundary);
                    if (content == null) {
                        content = run.engine().content(source, job.book(), chapter,
                                boundary);
                        cache.put(source, job.book(), content, boundary);
                    }
                    captured.add(chapter);
                } catch (Exception e) { failures.put(chapter.url(), chapter.title() + ": " + formatError(e)); }
                try {
                    jobs.update(new CaptureJobStore.Job(job.sourceId(), job.sourceHash(), job.book(), job.catalog(), job.selected(),
                            job.epub(), captured.size(), failures));
                } catch (Exception e) { report("无法保存抓取进度：" + e.getMessage()); return; }
                report("抓取 " + (captured.size() + failures.size()) + "/" + job.selected().size() + "；失败 " + failures.size());
            }
            if (captured.isEmpty()) { report("所有章节抓取失败；点击「继续上次」重试"); return; }
            try {
                CaptureExport.writeFromCache(getContentResolver().openOutputStream(uri, "wt"), job, captured,
                        source, cache, failures.isEmpty(), jobs);
                if (failures.isEmpty()) {
                    report("完整导出 " + captured.size() + "/" + job.selected().size() + " 章。");
                } else {
                    onCurrentUi(() -> {
                        reportTitle = "部分导出：缺章清单";
                        reportDetails = String.join("\n", failures.values());
                    });
                    report("部分导出 " + captured.size() + "/" + job.selected().size()
                            + " 章；缺 " + failures.size() + " 章。点击状态查看缺章，点击「继续上次」重试。");
                }
            } catch (Exception e) { report("导出失败：" + e.getMessage()); }
        });
    }
    private void downloadResources(Uri uri) {
        PendingResourceDownload selection = pendingResource;
        if (selection == null) { report("资源选择已失效，请重新选择章节"); return; }
        Source source = available.stream().filter(item -> item.id().equals(selection.sourceId())).findFirst().orElse(null);
        if (source == null || !ChapterStore.sourceHash(source).equals(selection.sourceHash())) {
            report("书源已移除或规则已改变，请重新解析资源"); return;
        }
        List<Resource> resources = selection.resources();
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
        return SourceEngine.nextReadableUrl(chapters, chapter);
    }
}
