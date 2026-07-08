package dev.visorcraft.mongreldb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds a request for the daemon's {@code /kit/query} endpoint, where
 * conditions push down to the engine's specialized indexes for sub-millisecond
 * lookups.
 *
 * <p>Condition parameters accept friendly aliases that are translated to the
 * server's exact on-wire keys before sending (see {@link #where}):
 *
 * <ul>
 *   <li>{@code column}        → {@code column_id}
 *   <li>{@code min} / {@code max} → {@code lo} / {@code hi}
 *   <li>{@code min_inclusive} → {@code lo_inclusive}
 *   <li>{@code max_inclusive} → {@code hi_inclusive}
 * </ul>
 *
 * <p>The server's canonical keys are accepted directly too.
 *
 * <p>Usage:
 * <pre>{@code
 * List<Map<String,Object>> rows = db.query("orders")
 *     .where("range", Map.of("column", 3L, "min", 100.0, "max", 150.0))
 *     .projection(List.of(1L, 2L))
 *     .limit(100)
 *     .execute();
 * if (builder.truncated()) {
 *     // result set hit the limit; more matches exist on the server
 * }
 * }</pre>
 */
public final class QueryBuilder {

    private final MongrelDB client;
    private final String table;
    private final List<Map<String, Object>> conditions = new ArrayList<>();
    private List<Long> projection;
    private Long limit;
    private boolean lastTruncated;

    QueryBuilder(MongrelDB client, String table) {
        this.client = client;
        this.table = table;
    }

    /**
     * Adds a native condition. Conditions are AND-ed together.
     *
     * <p>Available condition types include:
     * <ul>
     *   <li>{@code pk} - exact primary-key match ({@code {value: pk}})
     *   <li>{@code bitmap_eq} - equality on a bitmap-indexed column
     *   <li>{@code bitmap_in} - IN predicate on a bitmap-indexed column
     *   <li>{@code range} - integer range predicate (lo/hi, inclusive)
     *   <li>{@code range_f64} - float range predicate (lo/hi + lo_inclusive/hi_inclusive)
     *   <li>{@code is_null} - null check
     *   <li>{@code is_not_null} - non-null check
     *   <li>{@code fm_contains} - full-text substring search (FM-index)
     *   <li>{@code fm_contains_all} - multiple substring patterns (all must match)
     *   <li>{@code ann} - dense vector similarity search (HNSW)
     *   <li>{@code sparse_match} - sparse vector match
     *   <li>{@code min_hash_similar} - MinHash similarity search
     * </ul>
     *
     * @param condType the condition type
     * @param params   the condition parameters (friendly aliases accepted)
     * @return this builder, for chaining
     */
    public QueryBuilder where(String condType, Map<String, ?> params) {
        Objects.requireNonNull(condType, "condType");
        Objects.requireNonNull(params, "params");
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put(condType, normalizeCondition(condType, params));
        conditions.add(entry);
        return this;
    }

    /**
     * Sets the column ids to return. {@code null} (the default) means all
     * columns.
     *
     * @param columnIDs the projection, or {@code null} for all columns
     * @return this builder, for chaining
     */
    public QueryBuilder projection(List<Long> columnIDs) {
        this.projection = columnIDs == null ? null : new ArrayList<>(columnIDs);
        return this;
    }

    /**
     * Caps the number of rows returned.
     *
     * @param limit the row limit
     * @return this builder, for chaining
     */
    public QueryBuilder limit(long limit) {
        this.limit = limit;
        return this;
    }

    /**
     * Builds the request payload that will be sent to {@code /kit/query}.
     *
     * @return the request payload
     */
    public Map<String, Object> build() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("table", table);
        if (!conditions.isEmpty()) {
            // The daemon expects externally-tagged conditions: [{type: {...}}, ...]
            payload.put("conditions", conditions);
        }
        if (projection != null) {
            payload.put("projection", projection);
        }
        if (limit != null) {
            payload.put("limit", limit);
        }
        return payload;
    }

    /**
     * Runs the query and returns the matching rows. Also records whether the
     * result was truncated by {@link #limit}; check it with {@link #truncated()}.
     *
     * @return the matching rows (never {@code null})
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> execute() {
        byte[] body = client.post("/kit/query", build());
        Object parsed = body.length == 0 ? null : MongrelDB.Json.parse(body);
        List<Map<String, Object>> rows = new ArrayList<>();
        boolean truncated = false;
        if (parsed instanceof Map<?, ?>) {
            Object r = ((Map<?, ?>) parsed).get("rows");
            if (r instanceof List<?>) {
                for (Object row : (List<?>) r) {
                    if (row instanceof Map<?, ?>) {
                        rows.add((Map<String, Object>) row);
                    } else {
                        rows.add(new LinkedHashMap<>());
                    }
                }
            }
            Object t = ((Map<?, ?>) parsed).get("truncated");
            if (t instanceof Boolean) {
                truncated = (Boolean) t;
            }
        }
        this.lastTruncated = truncated;
        return rows;
    }

    /**
     * Reports whether the most recent {@link #execute} result was capped by the
     * query limit. Returns {@code false} until {@link #execute} has been called.
     *
     * @return {@code true} if the last result was truncated
     */
    public boolean truncated() {
        return lastTruncated;
    }

    /**
     * Translates friendly parameter aliases to the server's canonical on-wire
     * keys. Both spellings are accepted, so callers may use whichever is
     * clearer.
     *
     * <p>Generic aliases (applied to all condition types):
     * <ul>
     *   <li>{@code column}        → {@code column_id}
     *   <li>{@code min}           → {@code lo}
     *   <li>{@code max}           → {@code hi}
     *   <li>{@code min_inclusive} → {@code lo_inclusive}
     *   <li>{@code max_inclusive} → {@code hi_inclusive}
     * </ul>
     *
     * <p>Type-specific aliases:
     * <ul>
     *   <li>{@code fm_contains} / {@code fm_contains_all}: {@code value} → {@code pattern}
     *       (other types like {@code pk}/{@code bitmap_eq} use {@code value} as
     *       their canonical key, so the {@code value}→{@code pattern} alias must
     *       NOT apply globally)
     * </ul>
     */
    private static Map<String, Object> normalizeCondition(String condType, Map<String, ?> params) {
        Map<String, Object> normalized = new LinkedHashMap<>(params.size());
        for (Map.Entry<String, ?> e : params.entrySet()) {
            String key = e.getKey();
            String canonical;
            switch (key) {
                case "column":
                    canonical = "column_id";
                    break;
                case "min":
                    canonical = "lo";
                    break;
                case "max":
                    canonical = "hi";
                    break;
                case "min_inclusive":
                    canonical = "lo_inclusive";
                    break;
                case "max_inclusive":
                    canonical = "hi_inclusive";
                    break;
                case "value":
                    // The docs historically used "value" for the FTS pattern;
                    // the server's fm_contains key is "pattern". Only apply this
                    // for FTS conditions, since pk/bitmap_eq use "value"
                    // canonically.
                    canonical = (condType.equals("fm_contains") || condType.equals("fm_contains_all"))
                            ? "pattern" : "value";
                    break;
                default:
                    canonical = key;
            }
            normalized.put(canonical, e.getValue());
        }
        return normalized;
    }
}
