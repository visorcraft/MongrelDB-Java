package dev.visorcraft.mongreldb;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * The MongrelDB HTTP client.
 *
 * <p>A pure-Java client for a running {@code mongreldb-server} daemon, built on
 * the standard library {@link HttpClient} (Java 11+). No external dependencies.
 * The API mirrors the MongrelDB PHP and Go clients: typed CRUD, a fluent query
 * builder that pushes conditions down to the engine's native indexes, idempotent
 * batch transactions, full SQL access, and schema introspection.
 *
 * <p>Connect with a base URL:
 * <pre>{@code
 * MongrelDB db = new MongrelDB("http://127.0.0.1:8453");
 * boolean ok = db.health();
 * }</pre>
 *
 * <p>A {@code MongrelDB} instance is safe for concurrent use by multiple threads
 * once constructed: the underlying {@link HttpClient} is thread-safe and the
 * instance is immutable after configuration.
 *
 * @see <a href="https://www.MongrelDB.com">MongrelDB</a>
 */
public final class MongrelDB {

    /** The daemon address used when none is supplied. */
    public static final String DEFAULT_BASE_URL = "http://127.0.0.1:8453";

    private final String baseURL;
    private final String token;
    private final String username;
    private final String password;
    private final HttpClient http;

    /**
     * Constructs a client for the daemon at {@code url} with no authentication.
     * An empty or {@code null} url falls back to {@link #DEFAULT_BASE_URL}.
     *
     * @param url the daemon base URL (e.g. {@code http://127.0.0.1:8453})
     */
    public MongrelDB(String url) {
        this(url, null, null, null, null);
    }

    /**
     * Constructs a client for the daemon at {@code url} with optional
     * authentication.
     *
     * <p>A non-null {@code token} authenticates requests with a Bearer header
     * ({@code --auth-token} mode) and takes precedence over basic-auth
     * credentials. When {@code token} is {@code null}, a non-null
     * {@code username} enables HTTP Basic auth ({@code --auth-users} mode); the
     * password may be {@code null}.
     *
     * @param url      the daemon base URL, or {@code null} for the default
     * @param token    a Bearer token, or {@code null}
     * @param username the Basic-auth username, or {@code null}
     * @param password the Basic-auth password, or {@code null}
     */
    public MongrelDB(String url, String token, String username, String password) {
        this(url, token, username, password, null);
    }

    /**
     * Constructs a client with full control over the underlying transport.
     *
     * @param url      the daemon base URL, or {@code null} for the default
     * @param token    a Bearer token, or {@code null}
     * @param username the Basic-auth username, or {@code null}
     * @param password the Basic-auth password, or {@code null}
     * @param http     a custom {@link HttpClient}, or {@code null} to build one
     *                 with a 30-second connect/request timeout
     */
    public MongrelDB(String url, String token, String username, String password, HttpClient http) {
        String base = url == null || url.isEmpty() ? DEFAULT_BASE_URL : url;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.baseURL = base;
        this.token = token;
        this.username = username;
        this.password = password;
        this.http = http != null ? http : HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    /** @return the daemon base URL this client was configured with */
    public String baseURL() {
        return baseURL;
    }

    // ── Health & tables ───────────────────────────────────────────────────

    /**
     * Reports whether the daemon is reachable and healthy.
     *
     * @return {@code true} if the daemon answered {@code /health} with a 2xx
     */
    public boolean health() {
        try {
            get("/health");
            return true;
        } catch (MongrelDBException e) {
            return false;
        }
    }

    /**
     * Lists all table names in the database.
     *
     * @return a list of table names (never {@code null})
     */
    public List<String> tableNames() {
        byte[] body = get("/tables");
        if (body.length == 0) {
            return new ArrayList<>();
        }
        Object parsed = Json.parse(body);
        if (parsed instanceof List<?>) {
            List<String> names = new ArrayList<>();
            for (Object o : (List<?>) parsed) {
                names.add(o == null ? null : o.toString());
            }
            return names;
        }
        throw new QueryException("mongreldb: unexpected table-list response: " + Json.preview(body));
    }

    /**
     * Creates a table named {@code name} with the given columns and returns the
     * assigned table id.
     *
     * <p>Each column is a {@code Map<String,Object>} sent verbatim to the
     * daemon. Recognized keys are {@code id}, {@code name}, {@code ty},
     * {@code primary_key}, and {@code nullable}.
     *
     * @param name    the table name
     * @param columns the column descriptors
     * @return the assigned table id
     */
    public long createTable(String name, List<Map<String, Object>> columns) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(columns, "columns");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", name);
        payload.put("columns", columns);
        byte[] body = post("/kit/create_table", payload);
        Object parsed = body.length == 0 ? null : Json.parse(body);
        if (parsed instanceof Map<?, ?>) {
            Object id = ((Map<?, ?>) parsed).get("table_id");
            if (id instanceof Number) {
                return ((Number) id).longValue();
            }
        }
        return 0L;
    }

    /**
     * Drops a table by name.
     *
     * @param name the table name
     */
    public void dropTable(String name) {
        Objects.requireNonNull(name, "name");
        delete("/tables/" + urlPathEscape(name));
    }

    /**
     * Returns the row count for a table.
     *
     * @param table the table name
     * @return the number of rows
     */
    public long count(String table) {
        Objects.requireNonNull(table, "table");
        byte[] body = get("/tables/" + urlPathEscape(table) + "/count");
        Object parsed = body.length == 0 ? null : Json.parse(body);
        if (parsed instanceof Map<?, ?>) {
            Object c = ((Map<?, ?>) parsed).get("count");
            if (c instanceof Number) {
                return ((Number) c).longValue();
            }
        }
        return 0L;
    }

    // ── CRUD (via the Kit typed transaction endpoint) ─────────────────────

    /**
     * Inserts a row. {@code idempotencyKey}, when non-null and non-empty, makes
     * the commit safe to retry — the daemon returns the original result on
     * duplicate commits.
     *
     * @param table         the target table
     * @param cells         a column-id-to-value map (flattened to the server's
     *                      {@code [col_id, value, ...]} array before sending)
     * @param idempotencyKey an idempotency key, or {@code null}
     * @return the per-operation result object (the first element of the server's
     *         results array), or an empty map if none
     */
    public Map<String, Object> put(String table, Map<Long, ?> cells, String idempotencyKey) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(cells, "cells");
        Map<String, Object> op = new LinkedHashMap<>();
        Map<String, Object> put = new LinkedHashMap<>();
        put.put("table", table);
        put.put("cells", flattenCells(cells));
        op.put("put", put);
        List<Map<String, Object>> results = commitOne(listOf(op), idempotencyKey);
        return firstResult(results);
    }

    /**
     * Inserts a row, or updates it on a primary-key conflict.
     * {@code updateCells}, when non-null, supplies the values written on
     * conflict; {@code null} means DO NOTHING.
     *
     * @param table          the target table
     * @param cells          the column-id-to-value map to insert
     * @param updateCells    the values written on conflict, or {@code null}
     * @param idempotencyKey an idempotency key, or {@code null}
     * @return the per-operation result object, or an empty map if none
     */
    public Map<String, Object> upsert(String table, Map<Long, ?> cells,
                                      Map<Long, ?> updateCells, String idempotencyKey) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(cells, "cells");
        Map<String, Object> op = new LinkedHashMap<>();
        Map<String, Object> upsert = new LinkedHashMap<>();
        upsert.put("table", table);
        upsert.put("cells", flattenCells(cells));
        if (updateCells != null) {
            upsert.put("update_cells", flattenCells(updateCells));
        }
        op.put("upsert", upsert);
        List<Map<String, Object>> results = commitOne(listOf(op), idempotencyKey);
        return firstResult(results);
    }

    /**
     * Removes a row by its internal row id.
     *
     * @param table the target table
     * @param rowId the internal row id
     */
    public void delete(String table, long rowId) {
        Objects.requireNonNull(table, "table");
        Map<String, Object> op = new LinkedHashMap<>();
        Map<String, Object> del = new LinkedHashMap<>();
        del.put("table", table);
        del.put("row_id", rowId);
        op.put("delete", del);
        commitOne(listOf(op), null);
    }

    /**
     * Removes a row by its primary-key value.
     *
     * @param table the target table
     * @param pk    the primary-key value
     */
    public void deleteByPK(String table, Object pk) {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(pk, "pk");
        Map<String, Object> op = new LinkedHashMap<>();
        Map<String, Object> del = new LinkedHashMap<>();
        del.put("table", table);
        del.put("pk", pk);
        op.put("delete_by_pk", del);
        commitOne(listOf(op), null);
    }

    // commitOne sends a single-op transaction and returns the results array.
    private List<Map<String, Object>> commitOne(List<Map<String, Object>> ops, String idempotencyKey) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ops", ops);
        if (idempotencyKey != null && !idempotencyKey.isEmpty()) {
            payload.put("idempotency_key", idempotencyKey);
        }
        byte[] body = post("/kit/txn", payload);
        return decodeResults(body);
    }

    // ── Query ─────────────────────────────────────────────────────────────

    /**
     * Starts a fluent {@link QueryBuilder} against {@code table}.
     *
     * @param table the table to query
     * @return a new query builder
     */
    public QueryBuilder query(String table) {
        Objects.requireNonNull(table, "table");
        return new QueryBuilder(this, table);
    }

    // ── SQL ───────────────────────────────────────────────────────────────

    /**
     * Executes a SQL statement via the {@code /sql} endpoint. When the daemon
     * returns a JSON result set, the rows are decoded and returned; for
     * statements that yield no rows (DDL/DML) or a non-JSON (Arrow IPC) body, it
     * returns an empty list and a {@code null} (absent) error.
     *
     * @param sql the SQL statement
     * @return the decoded rows, or an empty list
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> sql(String sql) {
        Objects.requireNonNull(sql, "sql");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("sql", sql);
        byte[] body = post("/sql", payload);
        byte[] trimmed = trim(body);
        if (trimmed.length == 0) {
            return new ArrayList<>();
        }
        // The /sql endpoint generally streams Arrow IPC bytes for SELECTs; only
        // decode when the body is actually JSON to avoid noise.
        byte first = trimmed[0];
        if (first == '{' || first == '[') {
            Object parsed = Json.parse(body);
            if (parsed instanceof List<?>) {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (Object row : (List<?>) parsed) {
                    if (row instanceof Map<?, ?>) {
                        rows.add((Map<String, Object>) row);
                    } else {
                        rows.add(new LinkedHashMap<>());
                    }
                }
                return rows;
            }
            // A single JSON object (e.g. an error envelope) is not a row set.
            if (parsed instanceof Map<?, ?>) {
                return new ArrayList<>();
            }
        }
        return new ArrayList<>();
    }

    // ── Schema ────────────────────────────────────────────────────────────

    /**
     * Returns the full schema catalog: a table-name-to-descriptor map.
     *
     * @return the schema catalog (never {@code null})
     */
    @SuppressWarnings("unchecked")
    public Map<String, Map<String, Object>> schema() {
        byte[] body = get("/kit/schema");
        Object parsed = body.length == 0 ? null : Json.parse(body);
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        if (parsed instanceof Map<?, ?>) {
            Object tables = ((Map<?, ?>) parsed).get("tables");
            if (tables instanceof Map<?, ?>) {
                for (Map.Entry<?, ?> e : ((Map<?, ?>) tables).entrySet()) {
                    if (e.getValue() instanceof Map<?, ?>) {
                        out.put(String.valueOf(e.getKey()), (Map<String, Object>) e.getValue());
                    }
                }
            }
        }
        return out;
    }

    /**
     * Returns the descriptor for a single table.
     *
     * @param table the table name
     * @return the table descriptor (never {@code null})
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> schemaFor(String table) {
        Objects.requireNonNull(table, "table");
        byte[] body = get("/kit/schema/" + urlPathEscape(table));
        Object parsed = body.length == 0 ? null : Json.parse(body);
        if (parsed instanceof Map<?, ?>) {
            return (Map<String, Object>) parsed;
        }
        return new LinkedHashMap<>();
    }

    // ── Maintenance ───────────────────────────────────────────────────────

    /**
     * Merges sorted runs across all tables.
     *
     * @return the daemon's response object
     */
    public Map<String, Object> compact() {
        return postDecode("/compact");
    }

    /**
     * Merges sorted runs for a single table.
     *
     * @param table the table to compact
     * @return the daemon's response object
     */
    public Map<String, Object> compactTable(String table) {
        Objects.requireNonNull(table, "table");
        return postDecode("/tables/" + urlPathEscape(table) + "/compact");
    }

    // postDecode POSTs an empty body and decodes the JSON object response.
    @SuppressWarnings("unchecked")
    private Map<String, Object> postDecode(String path) {
        byte[] body = post(path, null);
        Object parsed = body.length == 0 ? null : Json.parse(body);
        if (parsed instanceof Map<?, ?>) {
            return (Map<String, Object>) parsed;
        }
        return new LinkedHashMap<>();
    }

    // ── Transactions ──────────────────────────────────────────────────────

    /**
     * Starts a new batch transaction. Operations staged on the returned
     * {@link Transaction} are committed atomically in a single
     * {@code /kit/txn} request.
     *
     * @return a new transaction
     */
    public Transaction begin() {
        return new Transaction(this);
    }

    /**
     * Sends a batch of staged operations atomically. Exposed for the
     * {@link Transaction} type; returns the per-operation results array.
     *
     * @param ops           the operations to commit
     * @param idempotencyKey an idempotency key, or {@code null}
     * @return the per-operation results, or {@code null} if {@code ops} is empty
     */
    List<Map<String, Object>> commitTxn(List<Map<String, Object>> ops, String idempotencyKey) {
        if (ops.isEmpty()) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ops", ops);
        if (idempotencyKey != null && !idempotencyKey.isEmpty()) {
            payload.put("idempotency_key", idempotencyKey);
        }
        byte[] body = post("/kit/txn", payload);
        return decodeResults(body);
    }

    // ── HTTP plumbing ─────────────────────────────────────────────────────

    byte[] get(String path) {
        return doRequest("GET", path, null);
    }

    byte[] post(String path, Object body) {
        return doRequest("POST", path, body);
    }

    private void delete(String path) {
        doRequest("DELETE", path, null);
    }

    /**
     * Builds and runs one request. The server's JSON extractors require an
     * explicit {@code Content-Type} header on any request carrying a JSON body,
     * so one is added whenever the body is non-null. Non-2xx responses are
     * mapped to typed client exceptions via {@link #toException}.
     */
    private byte[] doRequest(String method, String path, Object body) {
        byte[] payload = null;
        HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.noBody();
        if (body != null) {
            payload = Json.toBytes(body);
            publisher = HttpRequest.BodyPublishers.ofByteArray(payload);
        }

        HttpRequest.Builder req = HttpRequest.newBuilder()
                .uri(URI.create(baseURL + "/" + stripLeadingSlash(path)))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .method(method, publisher);
        if (payload != null) {
            req.header("Content-Type", "application/json");
        }
        applyAuth(req);

        HttpResponse<byte[]> resp;
        try {
            resp = http.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new QueryException("mongreldb: request " + method + " " + path + " failed: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new QueryException("mongreldb: request " + method + " " + path + " interrupted", e);
        }

        byte[] data = resp.body() == null ? new byte[0] : resp.body();
        int status = resp.statusCode();
        if (status < 200 || status >= 300) {
            throw toException(status, data);
        }
        return data;
    }

    // applyAuth sets the Authorization header according to the configured
    // credentials. A bearer token takes precedence over basic auth.
    private void applyAuth(HttpRequest.Builder req) {
        if (token != null && !token.isEmpty()) {
            req.header("Authorization", "Bearer " + token);
        } else if (username != null && !username.isEmpty()) {
            String creds = username + ":" + (password == null ? "" : password);
            String encoded = Base64.getEncoder().encodeToString(creds.getBytes(StandardCharsets.UTF_8));
            req.header("Authorization", "Basic " + encoded);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Flattens a column-id-to-value map to the server's flat
     * {@code [col_id, value, col_id, value, ...]} array. Pair order is not
     * significant — each value is preceded by its own column id.
     */
    static List<Object> flattenCells(Map<Long, ?> cells) {
        List<Object> flat = new ArrayList<>(cells.size() * 2);
        for (Map.Entry<Long, ?> e : cells.entrySet()) {
            flat.add(e.getKey());
            flat.add(e.getValue());
        }
        return flat;
    }

    // decodeResults pulls the results array out of a /kit/txn response.
    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> decodeResults(byte[] body) {
        if (trim(body).length == 0) {
            return new ArrayList<>();
        }
        Object parsed = Json.parse(body);
        if (!(parsed instanceof Map<?, ?>)) {
            throw new QueryException("mongreldb: decode txn response: unexpected JSON");
        }
        Object results = ((Map<?, ?>) parsed).get("results");
        List<Map<String, Object>> out = new ArrayList<>();
        if (results instanceof List<?>) {
            for (Object r : (List<?>) results) {
                if (r instanceof Map<?, ?>) {
                    out.add((Map<String, Object>) r);
                } else {
                    out.add(new LinkedHashMap<>());
                }
            }
        }
        return out;
    }

    // firstResult returns the first element of results, or an empty map.
    static Map<String, Object> firstResult(List<Map<String, Object>> results) {
        if (results.isEmpty()) {
            return new LinkedHashMap<>();
        }
        return results.get(0);
    }

    @SuppressWarnings("unchecked")
    private static <T> List<T> listOf(T e) {
        List<T> l = new ArrayList<>(1);
        l.add(e);
        return l;
    }

    private static byte[] trim(byte[] b) {
        int s = 0;
        int e = b.length;
        while (s < e && isSpace(b[s])) {
            s++;
        }
        while (e > s && isSpace(b[e - 1])) {
            e--;
        }
        if (s == 0 && e == b.length) {
            return b;
        }
        byte[] out = new byte[e - s];
        System.arraycopy(b, s, out, 0, e - s);
        return out;
    }

    private static boolean isSpace(byte b) {
        return b == ' ' || b == '\t' || b == '\n' || b == '\r';
    }

    private static String stripLeadingSlash(String s) {
        while (s.startsWith("/")) {
            s = s.substring(1);
        }
        return s;
    }

    /**
     * Percent-encodes a path segment (used for table names that may contain
     * characters unsafe in a URL). Leaves the forward slash intact so compound
     * identifiers survive.
     */
    static String urlPathEscape(String seg) {
        StringBuilder b = new StringBuilder(seg.length());
        for (int i = 0; i < seg.length(); i++) {
            char c = seg.charAt(i);
            if (c == '/' || c == '-' || c == '_' || c == '.' || c == '~'
                    || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9')) {
                b.append(c);
            } else {
                // Encode each UTF-8 byte of the char.
                for (byte bb : String.valueOf(c).getBytes(StandardCharsets.UTF_8)) {
                    b.append('%');
                    b.append(hexChar((bb >> 4) & 0x0F));
                    b.append(hexChar(bb & 0x0F));
                }
            }
        }
        return b.toString();
    }

    private static char hexChar(int n) {
        return (char) (n < 10 ? '0' + n : 'A' + (n - 10));
    }

    // Maps an HTTP status code and response body to a typed exception. It
    // best-effort decodes the server's JSON error envelope
    // ({error:{message,code,op_index}}) and falls back to the raw body.
    private static MongrelDBException toException(int status, byte[] body) {
        String message = null;
        String code = null;
        Integer opIndex = null;

        byte[] trimmed = trim(body);
        if (trimmed.length > 0 && trimmed[0] == '{') {
            Object parsed;
            try {
                parsed = Json.parse(body);
            } catch (RuntimeException ignored) {
                parsed = null;
            }
            if (parsed instanceof Map<?, ?>) {
                Map<?, ?> obj = (Map<?, ?>) parsed;
                // Prefer the nested {"error": {...}} envelope.
                Object err = obj.get("error");
                if (err instanceof Map<?, ?>) {
                    Map<?, ?> errMap = (Map<?, ?>) err;
                    message = strOrNull(errMap.get("message"));
                    code = strOrNull(errMap.get("code"));
                    Object oi = errMap.get("op_index");
                    if (oi instanceof Number) {
                        opIndex = ((Number) oi).intValue();
                    }
                }
                // Fall back to a flat {"message": ..., "code": ...} object.
                if (message == null && code == null && opIndex == null) {
                    message = strOrNull(obj.get("message"));
                    code = strOrNull(obj.get("code"));
                }
            }
        }
        if (message == null && body.length > 0) {
            message = new String(body, StandardCharsets.UTF_8);
        }

        if (message == null || message.isEmpty()) {
            switch (status) {
                case 401:
                case 403:
                    message = "authentication failed (" + status + ")";
                    break;
                case 404:
                    message = "resource not found";
                    break;
                case 409:
                    message = "constraint violation";
                    break;
                default:
                    message = "server error (" + status + ")";
            }
        }

        switch (status) {
            case 401:
            case 403:
                return new AuthException(message, status, code, opIndex);
            case 404:
                return new NotFoundException(message, status, code, opIndex);
            case 409:
                return new ConflictException(message, status, code, opIndex);
            default:
                return new QueryException(message, status, code, opIndex);
        }
    }

    private static String strOrNull(Object o) {
        return o == null ? null : o.toString();
    }

    /**
     * Minimal JSON codec used internally by the client. It encodes and decodes
     * {@code Map<String,Object>}, {@code List<Object>}, {@code Number},
     * {@code Boolean}, {@code String}, and {@code null} — the exact shape the
     * daemon's JSON API uses — without pulling in a third-party dependency.
     *
     * <p>This is intentionally narrow: it is not a general-purpose JSON library.
     */
    static final class Json {

        /** Encodes a value to UTF-8 JSON bytes. */
        static byte[] toBytes(Object value) {
            StringBuilder sb = new StringBuilder();
            write(sb, value);
            return sb.toString().getBytes(StandardCharsets.UTF_8);
        }

        /** Parses UTF-8 JSON bytes into {@code Map}/{@code List}/primitive. */
        static Object parse(byte[] body) {
            String s = new String(body, StandardCharsets.UTF_8);
            Parser p = new Parser(s);
            p.skipWs();
            Object v = p.readValue();
            p.skipWs();
            if (p.pos < p.src.length()) {
                throw new QueryException("mongreldb: trailing JSON content at " + p.pos);
            }
            return v;
        }

        /** A short, safe preview of a body for error messages. */
        static String preview(byte[] body) {
            String s = new String(body, StandardCharsets.UTF_8);
            if (s.length() > 120) {
                return s.substring(0, 120) + "...";
            }
            return s;
        }

        @SuppressWarnings("unchecked")
        private static void write(StringBuilder sb, Object v) {
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Map<?, ?>) {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    writeString(sb, String.valueOf(e.getKey()));
                    sb.append(':');
                    write(sb, e.getValue());
                }
                sb.append('}');
            } else if (v instanceof List<?>) {
                sb.append('[');
                boolean first = true;
                for (Object o : (List<?>) v) {
                    if (!first) {
                        sb.append(',');
                    }
                    first = false;
                    write(sb, o);
                }
                sb.append(']');
            } else if (v instanceof String) {
                writeString(sb, (String) v);
            } else if (v instanceof Boolean || v instanceof Number) {
                sb.append(v.toString());
            } else if (v instanceof Character) {
                writeString(sb, v.toString());
            } else {
                // Fallback: treat unknown types as their string form.
                writeString(sb, String.valueOf(v));
            }
        }

        private static void writeString(StringBuilder sb, String s) {
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"':
                        sb.append("\\\"");
                        break;
                    case '\\':
                        sb.append("\\\\");
                        break;
                    case '\n':
                        sb.append("\\n");
                        break;
                    case '\r':
                        sb.append("\\r");
                        break;
                    case '\t':
                        sb.append("\\t");
                        break;
                    case '\b':
                        sb.append("\\b");
                        break;
                    case '\f':
                        sb.append("\\f");
                        break;
                    default:
                        if (c < 0x20) {
                            sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                }
            }
            sb.append('"');
        }
    }

    /** A tiny recursive-descent JSON parser. */
    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) {
            this.src = src;
        }

        void skipWs() {
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object readValue() {
            skipWs();
            if (pos >= src.length()) {
                throw new QueryException("mongreldb: unexpected end of JSON");
            }
            char c = src.charAt(pos);
            switch (c) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                case 'f':
                    return readBool();
                case 'n':
                    return readNull();
                default:
                    return readNumber();
            }
        }

        Map<String, Object> readObject() {
            expect('{');
            Map<String, Object> obj = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') {
                pos++;
                return obj;
            }
            while (true) {
                skipWs();
                String key = readString();
                skipWs();
                expect(':');
                Object value = readValue();
                obj.put(key, value);
                skipWs();
                char c = next();
                if (c == ',') {
                    continue;
                }
                if (c == '}') {
                    break;
                }
                throw new QueryException("mongreldb: expected ',' or '}' at " + (pos - 1));
            }
            return obj;
        }

        List<Object> readArray() {
            expect('[');
            List<Object> arr = new ArrayList<>();
            skipWs();
            if (peek() == ']') {
                pos++;
                return arr;
            }
            while (true) {
                Object value = readValue();
                arr.add(value);
                skipWs();
                char c = next();
                if (c == ',') {
                    continue;
                }
                if (c == ']') {
                    break;
                }
                throw new QueryException("mongreldb: expected ',' or ']' at " + (pos - 1));
            }
            return arr;
        }

        String readString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < src.length()) {
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= src.length()) {
                        throw new QueryException("mongreldb: unterminated escape");
                    }
                    char e = src.charAt(pos++);
                    switch (e) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'u':
                            if (pos + 4 > src.length()) {
                                throw new QueryException("mongreldb: bad \\u escape");
                            }
                            String hex = src.substring(pos, pos + 4);
                            pos += 4;
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException ex) {
                                throw new QueryException("mongreldb: bad \\u escape: " + hex);
                            }
                            break;
                        default:
                            throw new QueryException("mongreldb: bad escape '\\" + e + "'");
                    }
                } else {
                    sb.append(c);
                }
            }
            throw new QueryException("mongreldb: unterminated string");
        }

        Boolean readBool() {
            if (src.startsWith("true", pos)) {
                pos += 4;
                return Boolean.TRUE;
            }
            if (src.startsWith("false", pos)) {
                pos += 5;
                return Boolean.FALSE;
            }
            throw new QueryException("mongreldb: invalid literal at " + pos);
        }

        Object readNull() {
            if (src.startsWith("null", pos)) {
                pos += 4;
                return null;
            }
            throw new QueryException("mongreldb: invalid literal at " + pos);
        }

        Object readNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            while (pos < src.length()) {
                char c = src.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    pos++;
                } else {
                    break;
                }
            }
            String num = src.substring(start, pos);
            if (num.isEmpty()) {
                throw new QueryException("mongreldb: invalid number at " + start);
            }
            // Preserve integer precision for values that fit in a long; use
            // double otherwise (including exponents and fractions).
            if (num.indexOf('.') < 0 && num.indexOf('e') < 0 && num.indexOf('E') < 0) {
                try {
                    return Long.parseLong(num);
                } catch (NumberFormatException ex) {
                    return new java.math.BigInteger(num);
                }
            }
            return Double.parseDouble(num);
        }

        private char peek() {
            if (pos >= src.length()) {
                return '\0';
            }
            return src.charAt(pos);
        }

        private char next() {
            if (pos >= src.length()) {
                throw new QueryException("mongreldb: unexpected end of JSON");
            }
            return src.charAt(pos++);
        }

        private void expect(char c) {
            char actual = next();
            if (actual != c) {
                throw new QueryException("mongreldb: expected '" + c + "' but got '" + actual + "' at " + (pos - 1));
            }
        }
    }
}
