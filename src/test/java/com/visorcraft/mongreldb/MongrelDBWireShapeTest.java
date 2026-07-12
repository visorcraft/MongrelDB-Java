package com.visorcraft.mongreldb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    @DisplayName("createTable preserves enum, static-default, and dynamic-default fields")
    void testCreateTableEmitsEnumVariantsAndDefaultValue() throws Exception {
        AtomicReference<byte[]> captured = new AtomicReference<>();
        HttpServer srv = newServer("/kit/create_table", captured,
                "{\"table_id\":42}".getBytes(StandardCharsets.UTF_8));
        try {
            int port = srv.getAddress().getPort();
            MongrelDB db = new MongrelDB("http://127.0.0.1:" + port);

            // Generic maps preserve both static scalar and dynamic defaults.
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
            createdAt.put("default_expr", "now");

            Map<String, Object> attempts = new LinkedHashMap<>();
            attempts.put("id", 4L);
            attempts.put("name", "attempts");
            attempts.put("ty", "int64");
            attempts.put("default_value", 3L);
            Map<String, Object> flag = new LinkedHashMap<>();
            flag.put("id", 5L); flag.put("name", "flag"); flag.put("ty", "bool"); flag.put("default_value", true);
            Map<String, Object> note = new LinkedHashMap<>();
            note.put("id", 6L); note.put("name", "note"); note.put("ty", "varchar"); note.put("default_value", null);
            Map<String, Object> label = new LinkedHashMap<>();
            label.put("id", 7L); label.put("name", "label"); label.put("ty", "varchar"); label.put("default_value", "draft");

            Map<String, Object> constraints = Map.of("checks", List.of(Map.of(
                    "id", 1L,
                    "name", "id_present",
                    "expr", Map.of("IsNotNull", 1L))));
            long tableId = db.createTable("qa_enum_" + System.nanoTime(), List.of(
                    Map.of("id", 1L, "name", "id", "ty", "int64",
                            "primary_key", true, "nullable", false),
                    status,
                    createdAt,
                    attempts, flag, note, label), constraints);

            assertEquals(42L, tableId, "stubbed table_id should be returned verbatim");

            byte[] body = captured.get();
            assertNotNull(body, "server should have received the request body");

            Object parsed = MongrelDB.Json.parse(body);
            assertTrue(parsed instanceof Map<?, ?>, "top-level JSON should be an object");
            Object colsObj = ((Map<?, ?>) parsed).get("columns");
            assertTrue(colsObj instanceof List<?>, "columns should be a JSON array");

            Map<?, ?> statusWire = findColumn((List<?>) colsObj, "status");
            Map<?, ?> createdWire = findColumn((List<?>) colsObj, "created_at");
            Map<?, ?> attemptsWire = findColumn((List<?>) colsObj, "attempts");
            assertNotNull(statusWire, "status column missing from request body: " + asString(body));
            assertNotNull(createdWire, "created_at column missing from request body: " + asString(body));
            assertNotNull(attemptsWire, "attempts column missing from request body: " + asString(body));

            // enum_variants must appear as a JSON array of strings, in order.
            assertTrue(statusWire.containsKey("enum_variants"),
                    "enum_variants missing from status column: " + asString(body));
            Object variants = statusWire.get("enum_variants");
            assertTrue(variants instanceof List<?>, "enum_variants should serialize as a JSON array");
            assertEquals(List.of("draft", "active", "archived"),
                    new ArrayList<>((List<?>) variants),
                    "enum_variants must appear verbatim, in order");

            assertEquals("now", createdWire.get("default_expr"),
                    "default_expr must appear verbatim");
            assertEquals(3L, attemptsWire.get("default_value"),
                    "numeric default_value must preserve its JSON type");
            String jsonBody = asString(body);
            assertTrue(jsonBody.contains("\"default_value\":true"));
            assertTrue(jsonBody.contains("\"default_value\":null"));
            assertTrue(jsonBody.contains("\"default_value\":\"draft\""));

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

    @Test
    @DisplayName("createTable preserves the full static-default matrix and separates default_expr")
    void testCreateTableFullStaticDefaultMatrix() throws Exception {
        AtomicReference<byte[]> captured = new AtomicReference<>();
        HttpServer srv = newServer("/kit/create_table", captured,
                "{\"table_id\":99}".getBytes(StandardCharsets.UTF_8));
        try {
            int port = srv.getAddress().getPort();
            MongrelDB db = new MongrelDB("http://127.0.0.1:" + port);

            List<Map<String, Object>> columns = new ArrayList<>();
            columns.add(Map.of("id", 1L, "name", "id", "ty", "int64",
                    "primary_key", true, "nullable", false));

            Map<String, Object> label = new LinkedHashMap<>();
            label.put("id", 2L);
            label.put("name", "label");
            label.put("ty", "varchar");
            label.put("primary_key", false);
            label.put("nullable", false);
            label.put("default_value", "draft");
            columns.add(label);

            Map<String, Object> count = new LinkedHashMap<>();
            count.put("id", 3L);
            count.put("name", "count");
            count.put("ty", "int64");
            count.put("primary_key", false);
            count.put("nullable", false);
            count.put("default_value", 7L);
            columns.add(count);

            Map<String, Object> flag = new LinkedHashMap<>();
            flag.put("id", 4L);
            flag.put("name", "flag");
            flag.put("ty", "bool");
            flag.put("primary_key", false);
            flag.put("nullable", false);
            flag.put("default_value", true);
            columns.add(flag);

            Map<String, Object> note = new LinkedHashMap<>();
            note.put("id", 5L);
            note.put("name", "note");
            note.put("ty", "varchar");
            note.put("primary_key", false);
            note.put("nullable", false);
            note.put("default_value", null);
            columns.add(note);

            Map<String, Object> literalNow = new LinkedHashMap<>();
            literalNow.put("id", 6L);
            literalNow.put("name", "literal_now");
            literalNow.put("ty", "varchar");
            literalNow.put("primary_key", false);
            literalNow.put("nullable", false);
            literalNow.put("default_value", "now");
            columns.add(literalNow);

            Map<String, Object> dynamicNow = new LinkedHashMap<>();
            dynamicNow.put("id", 7L);
            dynamicNow.put("name", "created_at");
            dynamicNow.put("ty", "timestamp_nanos");
            dynamicNow.put("primary_key", false);
            dynamicNow.put("nullable", false);
            dynamicNow.put("default_expr", "now");
            columns.add(dynamicNow);

            Map<String, Object> dynamicUuid = new LinkedHashMap<>();
            dynamicUuid.put("id", 8L);
            dynamicUuid.put("name", "uuid_col");
            dynamicUuid.put("ty", "varchar");
            dynamicUuid.put("primary_key", false);
            dynamicUuid.put("nullable", false);
            dynamicUuid.put("default_expr", "uuid");
            columns.add(dynamicUuid);

            long tableId = db.createTable("qa_defaults_" + System.nanoTime(), columns);
            assertEquals(99L, tableId, "stubbed table_id should be returned verbatim");

            byte[] body = captured.get();
            assertNotNull(body, "server should have received the request body");
            Object parsed = MongrelDB.Json.parse(body);
            assertTrue(parsed instanceof Map<?, ?>, "top-level JSON should be an object");
            Object colsObj = ((Map<?, ?>) parsed).get("columns");
            assertTrue(colsObj instanceof List<?>, "columns should be a JSON array");

            Map<?, ?> labelWire = findColumn((List<?>) colsObj, "label");
            Map<?, ?> countWire = findColumn((List<?>) colsObj, "count");
            Map<?, ?> flagWire = findColumn((List<?>) colsObj, "flag");
            Map<?, ?> noteWire = findColumn((List<?>) colsObj, "note");
            Map<?, ?> literalNowWire = findColumn((List<?>) colsObj, "literal_now");
            Map<?, ?> dynamicNowWire = findColumn((List<?>) colsObj, "created_at");
            Map<?, ?> dynamicUuidWire = findColumn((List<?>) colsObj, "uuid_col");

            assertNotNull(labelWire, "label column missing");
            assertNotNull(countWire, "count column missing");
            assertNotNull(flagWire, "flag column missing");
            assertNotNull(noteWire, "note column missing");
            assertNotNull(literalNowWire, "literal_now column missing");
            assertNotNull(dynamicNowWire, "created_at column missing");
            assertNotNull(dynamicUuidWire, "uuid_col column missing");

            // String, integer, boolean, and explicit null preserve their JSON types.
            assertEquals("draft", labelWire.get("default_value"));
            assertEquals(7L, countWire.get("default_value"));
            assertEquals(Boolean.TRUE, flagWire.get("default_value"));
            assertTrue(noteWire.containsKey("default_value"),
                    "explicit null default_value must be present, not omitted");
            assertNull(noteWire.get("default_value"));

            // Literal "now" is a string default_value, not a dynamic expression.
            assertEquals("now", literalNowWire.get("default_value"));
            assertFalse(literalNowWire.containsKey("default_expr"),
                    "literal \"now\" must not be rewritten to default_expr");

            // Dynamic defaults use only default_expr.
            assertEquals("now", dynamicNowWire.get("default_expr"));
            assertFalse(dynamicNowWire.containsKey("default_value"),
                    "default_expr column must not also emit default_value");
            assertEquals("uuid", dynamicUuidWire.get("default_expr"));
            assertFalse(dynamicUuidWire.containsKey("default_value"),
                    "default_expr column must not also emit default_value");
        } finally {
            srv.stop(0);
        }
    }

    @Test
    @DisplayName("history retention endpoints use exact method, path, body, and response keys")
    void testHistoryRetentionWireShape() throws Exception {
        AtomicReference<String> capturedMethodPath = new AtomicReference<>();
        AtomicReference<byte[]> capturedBody = new AtomicReference<>();
        byte[] response = ("{\"history_retention_epochs\":123,"
                + "\"earliest_retained_epoch\":456}").getBytes(StandardCharsets.UTF_8);

        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/history/retention", exchange -> {
            capturedMethodPath.set(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            try (InputStream is = exchange.getRequestBody()) {
                capturedBody.set(is.readAllBytes());
            }
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        srv.start();

        try {
            int port = srv.getAddress().getPort();
            MongrelDB db = new MongrelDB("http://127.0.0.1:" + port);

            long[] setResult = db.setHistoryRetentionEpochs(123);
            assertEquals("PUT /history/retention", capturedMethodPath.get());
            assertNotNull(capturedBody.get(), "PUT body should be present");
            Object putParsed = MongrelDB.Json.parse(capturedBody.get());
            assertTrue(putParsed instanceof Map<?, ?>, "PUT body should be a JSON object");
            assertEquals(123L, ((Map<?, ?>) putParsed).get("history_retention_epochs"),
                    "PUT body must contain history_retention_epochs as a typed integer");
            assertEquals(123L, setResult[0], "setHistoryRetentionEpochs should echo retention");
            assertEquals(456L, setResult[1], "setHistoryRetentionEpochs should echo earliest");

            long epochs = db.historyRetentionEpochs();
            assertEquals("GET /history/retention", capturedMethodPath.get());
            assertEquals(123L, epochs);

            long earliest = db.earliestRetainedEpoch();
            assertEquals("GET /history/retention", capturedMethodPath.get());
            assertEquals(456L, earliest);
        } finally {
            srv.stop(0);
        }
    }

    @Test
    @DisplayName("history retention endpoints propagate non-2xx responses")
    void testHistoryRetentionErrorPropagation() throws Exception {
        byte[] response = "{\"error\":{\"message\":\"bad request\",\"code\":\"BAD_REQUEST\"}}"
                .getBytes(StandardCharsets.UTF_8);

        HttpServer srv = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/history/retention", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, response.length);
            try (var os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        srv.start();

        try {
            int port = srv.getAddress().getPort();
            MongrelDB db = new MongrelDB("http://127.0.0.1:" + port);

            MongrelDBException putError = assertThrows(MongrelDBException.class,
                    () -> db.setHistoryRetentionEpochs(123));
            assertEquals(400, putError.status(), "PUT non-2xx status should propagate");

            MongrelDBException getError = assertThrows(MongrelDBException.class,
                    db::historyRetentionEpochs);
            assertEquals(400, getError.status(), "GET non-2xx status should propagate");
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
