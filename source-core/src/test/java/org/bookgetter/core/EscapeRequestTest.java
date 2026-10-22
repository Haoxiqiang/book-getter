package org.bookgetter.core;

import java.util.Map;
import org.bookgetter.core.Models.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EscapeRequestTest {
    @Test void escapeEncodesQueryAndFormWithoutPretendingItIsAResponseCharset() {
        Source source = SourceImporter.parse("{\"bookSourceUrl\":\"https://escape.test\"}").sources().get(0);
        RequestPlanner planner = new RequestPlanner(new RhinoRuntime(request -> new FetchResponse(request.url(), "", 200, Map.of())));
        var query = planner.plan("/s?q={{key}}, {\"charset\":\"escape\"}", source, "中文 +", 1, source.id());
        assertEquals("https://escape.test/s?q=%u4e2d%u6587%20%2b", query.url());
        assertNull(query.charset());
        var form = planner.plan("/s, {\"method\":\"POST\",\"body\":\"q=中文 +\",\"charset\":\"escape\"}", source, "", 1, source.id());
        assertEquals("q=%u4e2d%u6587%20%2b", form.body());
        assertNull(form.charset());
    }
}
