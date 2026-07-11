package dev.visorcraft.mongreldb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Offline wire-shape conformance tests for {@link MongrelDB}.
 *
 * <p>These tests stand up an in-process {@link HttpServer} that captures the
 * request body and answers with a canned response, so the client is exercised
 * end-to-end (transport, JSON codec, headers) without needing a running
 * {@code mongreldb-server}. They pin down the exact JSON shape sent on the
 * wire for {@code /kit/create_table} so future refactors cannot silently drop
 * keys the engine relies on.
 */
class MongrelDBWireShapeTest {

    @Test
    @DisplayName("createTable JSON body includes enum_variants and default_value verbatim")
    void testCreateTableEmitsEnumVariantsAndDefaultValue() throws Exception {
        AtomicReference<byte[]> captured = new AtomicReference<>();
        HttpServer srv = newServer("/kit/create_table", captured,
                "{\"table_id\":42}".getBytes(StandardCharsets.UTF_8));
        try {
            int port = srv.getAddress().getPort();
            MongrelDB db = new MongrelDB("http://127.0.0.1:" + port);

            // Build a status column carrying both enum_variants and default_value,
            // and a created_at column carrying only default_value = "now".
            Map<String, Object> status = new LinkedHashMap<>();
            status.put("id", 2L);
            status.put("name", "status");
            status.put("ty", "enum");
            status.put("primary_key", false);
            status.put("nullable", false);
            status.put("enum_variants", List.of("draft", "active", "archived"));

            Map<String, Object> createdAt = new LinkedHashMap<>();
            createdAt.put("id", 3L);
            createdAt.put("name", "created_at");
            createdAt.put("ty", "timestamp_nanos");
            createdAt.put("primary_key", false);
            createdAt.put("nullable", false);
            createdAt.put("default_value", "now");

            Map<String, Object> constraints = Map.of("checks", List.of(Map.of(
                    "id", 1L,
                    "name", "id_present",
                    "expr", Map.of("IsNotNull", 1L))));
            long tableId = db.createTable("qa_enum_" + System.nanoTime(), List.of(
                    Map.of("id", 1L, "name", "id", "ty", "int64",
                            "primary_key", true, "nullable", false),
                    status,
                    createdAt), constraints);

            assertEquals(42L, tableId, "stubbed table_id should be returned verbatim");

            byte[] body = captured.get();
            assertNotNull(body, "server should have received the request body");

            Object parsed = MongrelDB.Json.parse(body);
            assertTrue(parsed instanceof Map<?, ?>, "top-level JSON should be an object");
            Object colsObj = ((Map<?, ?>) parsed).get("columns");
            assertTrue(colsObj instanceof List<?>, "columns should be a JSON array");

            Map<?, ?> statusWire = findColumn((List<?>) colsObj, "status");
            Map<?, ?> createdWire = findColumn((List<?>) colsObj, "created_at");
            assertNotNull(statusWire, "status column missing from request body: " + asString(body));
            assertNotNull(createdWire, "created_at column missing from request body: " + asString(body));

            // enum_variants must appear as a JSON array of strings, in order.
            assertTrue(statusWire.containsKey("enum_variants"),
                    "enum_variants missing from status column: " + asString(body));
            Object variants = statusWire.get("enum_variants");
            assertTrue(variants instanceof List<?>, "enum_variants should serialize as a JSON array");
            assertEquals(List.of("draft", "active", "archived"),
                    new ArrayList<>((List<?>) variants),
                    "enum_variants must appear verbatim, in order");

            // default_value must appear as a JSON string on both columns.
            assertTrue(createdWire.containsKey("default_value"),
                    "default_value missing from created_at column: " + asString(body));
            assertEquals("now", createdWire.get("default_value"),
                    "default_value must appear verbatim");

            Object constraintsWire = ((Map<?, ?>) parsed).get("constraints");
            assertTrue(constraintsWire instanceof Map<?, ?>,
                    "constraints missing from request body: " + asString(body));
            Object checks = ((Map<?, ?>) constraintsWire).get("checks");
            assertTrue(checks instanceof List<?> && ((List<?>) checks).size() == 1,
                    "constraints.checks missing from request body: " + asString(body));
        } finally {
            srv.stop(0);
        }
    }

    @Test
    @DisplayName("createTable JSON body omits enum_variants and default_value when unset")
    void testCreateTableOmitsUnsetEnumVariantsAndDefaultValue() throws Exception {
        AtomicReference<byte[]> captured = new AtomicReference<>();
        HttpServer srv = newServer("/kit/create_table", captured,
                "{\"table_id\":7}".getBytes(StandardCharsets.UTF_8));
        try {
            int port = srv.getAddress().getPort();
            MongrelDB db = new MongrelDB("http://127.0.0.1:" + port);

            // No enum_variants, no default_value anywhere. Regression: the
            // client must not introduce either key with a null/empty value
            // when the caller did not ask for one.
            db.createTable("qa_plain_" + System.nanoTime(),
                    List.of(Map.of(
                            "id", 1L, "name", "id", "ty", "int64",
                            "primary_key", true, "nullable", false)));

            byte[] body = captured.get();
            assertNotNull(body, "server should have received the request body");
            String json = asString(body);
            assertFalse(json.contains("enum_variants"),
                    "enum_variants must be omitted when not set: " + json);
            assertFalse(json.contains("default_value"),
                    "default_value must be omitted when not set: " + json);
        } finally {
            srv.stop(0);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static HttpServer newServer(String path, AtomicReference<byte[]> sink, byte[] response)
            throws IOException {
        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext(path, exchange -> {
            try (InputStream is = exchange.getRequestBody()) {
                sink.set(is.readAllBytes());
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        srv.start();
        return srv;
    }

    private static Map<?, ?> findColumn(List<?> cols, String name) {
        for (Object o : cols) {
            if (o instanceof Map<?, ?>) {
                Map<?, ?> m = (Map<?, ?>) o;
                if (name.equals(m.get("name"))) {
                    return m;
                }
            }
        }
        return null;
    }

    private static String asString(byte[] body) {
        return new String(body, StandardCharsets.UTF_8);
    }
}
