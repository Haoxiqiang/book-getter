package org.bookgetter.core;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import java.util.Map;
import org.bookgetter.core.Models.*;
import org.junit.jupiter.api.Test;

final class LoginHeaderPrecedenceTest {
    private Source source() {
        JsonObject json = new JsonObject(); json.addProperty("bookSourceUrl", "https://headers.test");
        json.addProperty("header", "{\"Authorization\":\"default-token\",\"X-Default\":\"default\"}");
        return new Source("https://headers.test", "Source", Kind.TEXT, json);
    }
    private SourceStateStore states(Source source) {
        MemorySourceStateStore states = new MemorySourceStateStore();
        states.put(SourcePolicy.of(source).stateKey(), "loginHeader", "{\"authorization\":\"login-token\",\"X-Login\":\"login\"}");
        return states;
    }
    private String header(FetchRequest request, String name) {
        return request.headers().entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }
    @Test void persistedLoginOverridesSourceDefaultsButExplicitUrlOptionWins() throws Exception {
        Source source = source(); SourceStateStore states = states(source);
        RequestPlanner planner = new RequestPlanner(new RhinoRuntime(request -> null));
        FetchRequest planned = planner.plan("/item", source, "", 1, source.id());
        assertEquals("default-token", header(planned, "Authorization"));
        FetchRequest login = SourceRequests.prepare(planned, states);
        assertEquals("login-token", header(login, "Authorization"));
        assertEquals("default", header(login, "X-Default"));
        assertEquals("login", header(login, "X-Login"));
        FetchRequest explicit = planner.plan("/item,{\"headers\":{\"AUTHORIZATION\":\"explicit-token\"}}", source, "", 1, source.id());
        assertEquals("explicit-token", header(SourceRequests.prepare(explicit, states), "Authorization"));
        assertEquals(1, SourceRequests.prepare(explicit, states).headers().keySet().stream()
                .filter(name -> name.equalsIgnoreCase("Authorization")).count());
    }
    @Test void explicitOptionEqualToOldDefaultStillOverridesLogin() throws Exception {
        Source source = source();
        RequestPlanner planner = new RequestPlanner(new RhinoRuntime(request -> null));
        FetchRequest planned = planner.plan("/item,{\"headers\":{\"authorization\":\"default-token\"}}", source, "", 1, source.id());
        assertEquals("default-token", header(SourceRequests.prepare(planned, states(source)), "Authorization"));
    }
    @Test void crossOriginNeverAddsSavedLoginAndNestedExplicitHeaderWins() throws Exception {
        Source source = source(); SourceStateStore states = states(source);
        assertNull(header(SourceRequests.prepare(new FetchRequest("https://cdn.test/image").forSource(source), states), "Authorization"));
        FetchRequest nested = new FetchRequest(source.id() + "/item", "GET", Map.of("Authorization", "nested-explicit"), null, null, false).forSource(source);
        assertEquals("nested-explicit", header(SourceRequests.prepare(nested, states), "Authorization"));
    }
    @Test void sourceIdentityFragmentDoesNotDisableSameOriginLoginHeaders() throws Exception {
        Source source = source();
        Source tagged = new Source(source.id() + "#grid tr!0#label", source.name(), source.kind(), source.json());
        FetchRequest planned = new RequestPlanner(new RhinoRuntime(request -> null))
                .plan("/item", tagged, "", 1, tagged.id());
        assertEquals("login-token", header(SourceRequests.prepare(planned, states(tagged)), "Authorization"));
        assertNull(header(SourceRequests.prepare(new FetchRequest("https://cdn.test/a").forSource(tagged), states(tagged)), "Authorization"));
    }
}
