package org.bookgetter.core;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class MiaogongziDirectoryAdapterTest {
    @Test void recognizesOnlyTheSupportedDirectoryConfiguration() {
        assertEquals("http://yuedu.miaogongzi.net/gx.html", MiaogongziDirectoryAdapter.directoryPage(
                "[{\"sourceUrl\":\"http://yuedu.miaogongzi.net/gx.html\"}]"));
        assertNull(MiaogongziDirectoryAdapter.directoryPage(
                "[{\"sourceUrl\":\"https://other.example/rss\"}]"));
        assertNull(MiaogongziDirectoryAdapter.directoryPage(
                "[{\"bookSourceUrl\":\"https://example.org\"}]"));
    }

    @Test void extractsOnlyNamedBookSourceImportLinksAndDecodesSrc() {
        String html = "<div class='aui-flex'><h1>合集甲</h1><a href='yuedu://booksource/importonline?src=https%3A%2F%2Ffeeds.example%2Fone.json'>导入</a></div>"
                + "<div class='aui-flex'><h1>广告</h1><a href='https://example.org'>访问</a></div>";
        var entries = MiaogongziDirectoryAdapter.discover(html);
        assertEquals(1, entries.size());
        assertEquals("合集甲", entries.get(0).name());
        assertEquals("https://feeds.example/one.json", entries.get(0).url());
        assertEquals("https://feeds.example/%E4%B9%A6%E6%BA%90.json",
                MiaogongziDirectoryAdapter.discover("<div class='aui-flex'><h1>中文路径</h1>"
                        + "<a href='yuedu://booksource/importonline?src=https://feeds.example/%E4%B9%A6%E6%BA%90.json'>导入</a></div>")
                        .get(0).url());
    }

    @Test void rejectsMissingLinksAndUnsafeImportTargets() {
        assertThrows(IllegalArgumentException.class, () -> MiaogongziDirectoryAdapter.discover("<h1>没有合集</h1>"));
        assertThrows(IllegalArgumentException.class, () -> MiaogongziDirectoryAdapter.discover(
                "<div class='aui-flex'><h1>错误</h1><a href='yuedu://booksource/importonline?src=file:///tmp/a.json'>导入</a></div>"));
    }
}
