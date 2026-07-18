package com.visorcraft.mongreldb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fluent builder for {@code POST /kit/search} — multi-retriever hybrid search
 * with reciprocal-rank fusion and optional exact-vector rerank.
 *
 * <p>Wire format matches the daemon KitSearchRequest (flattened retrievers).
 */
public final class SearchBuilder {
    private final MongrelDB client;
    private final String table;
    private final List<Map<String, Object>> must = new ArrayList<>();
    private final List<Map<String, Object>> retrievers = new ArrayList<>();
    private Map<String, Object> fusion;
    private Map<String, Object> rerank;
    private long limit = 10;
    private List<Long> projection;
    private boolean explain;
    private String cursor;

    SearchBuilder(MongrelDB client, String table) {
        this.client = client;
        this.table = table;
        this.fusion = new LinkedHashMap<>();
        Map<String, Object> rr = new LinkedHashMap<>();
        rr.put("constant", 60);
        this.fusion.put("reciprocal_rank", rr);
    }

    /** Hard filter (same condition shapes as {@link QueryBuilder#where}). */
    public SearchBuilder must(String type, Map<String, ?> params) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put(type, QueryBuilder.normalizeCondition(type, params));
        must.add(entry);
        return this;
    }

    public SearchBuilder annRetriever(
            String name, long columnId, List<Double> query, long k, double weight) {
        Map<String, Object> ann = new LinkedHashMap<>();
        ann.put("column_id", columnId);
        ann.put("query", query);
        ann.put("k", k);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", name);
        r.put("weight", weight);
        r.put("ann", ann);
        retrievers.add(r);
        return this;
    }

    public SearchBuilder annRetriever(String name, long columnId, List<Double> query) {
        return annRetriever(name, columnId, query, 64, 1.0);
    }

    /** {@code terms} is a list of {@code [tokenId, weight]} pairs. */
    public SearchBuilder sparseRetriever(
            String name, long columnId, List<List<? extends Number>> terms, long k, double weight) {
        List<List<Object>> pairs = new ArrayList<>();
        for (List<? extends Number> t : terms) {
            List<Object> pair = new ArrayList<>(2);
            pair.add(t.get(0).longValue());
            pair.add(t.get(1).doubleValue());
            pairs.add(pair);
        }
        Map<String, Object> sparse = new LinkedHashMap<>();
        sparse.put("column_id", columnId);
        sparse.put("query", pairs);
        sparse.put("k", k);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", name);
        r.put("weight", weight);
        r.put("sparse", sparse);
        retrievers.add(r);
        return this;
    }

    public SearchBuilder minHashRetriever(
            String name, long columnId, List<String> members, long k, double weight) {
        Map<String, Object> mh = new LinkedHashMap<>();
        mh.put("column_id", columnId);
        mh.put("members", members);
        mh.put("k", k);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("name", name);
        r.put("weight", weight);
        r.put("min_hash", mh);
        retrievers.add(r);
        return this;
    }

    public SearchBuilder fusion(int constant) {
        Map<String, Object> rr = new LinkedHashMap<>();
        rr.put("constant", Math.max(1, constant));
        fusion = new LinkedHashMap<>();
        fusion.put("reciprocal_rank", rr);
        return this;
    }

    /** {@code metric} is {@code cosine}, {@code dot_product}, or {@code euclidean}. */
    public SearchBuilder exactRerank(
            long embeddingColumn,
            List<Double> query,
            String metric,
            long candidateLimit,
            double weight) {
        Map<String, Object> ev = new LinkedHashMap<>();
        ev.put("embedding_column", embeddingColumn);
        ev.put("query", query);
        ev.put("metric", metric);
        ev.put("candidate_limit", candidateLimit);
        ev.put("weight", weight);
        rerank = new LinkedHashMap<>();
        rerank.put("exact_vector", ev);
        return this;
    }

    public SearchBuilder limit(long limit) {
        this.limit = limit;
        return this;
    }

    public SearchBuilder projection(List<Long> columnIds) {
        this.projection = columnIds == null ? null : new ArrayList<>(columnIds);
        return this;
    }

    public SearchBuilder explain(boolean on) {
        this.explain = on;
        return this;
    }

    public SearchBuilder cursor(String cursor) {
        this.cursor = cursor;
        return this;
    }

    public Map<String, Object> build() {
        if (retrievers.isEmpty()) {
            throw new IllegalArgumentException("search requires at least one retriever");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("search limit must be positive");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("table", table);
        payload.put("retrievers", retrievers);
        payload.put("fusion", fusion);
        payload.put("limit", limit);
        if (!must.isEmpty()) {
            payload.put("must", must);
        }
        if (rerank != null) {
            payload.put("rerank", rerank);
        }
        if (projection != null) {
            payload.put("projection", projection);
        }
        if (explain) {
            payload.put("explain", true);
        }
        if (cursor != null && !cursor.isEmpty()) {
            payload.put("cursor", cursor);
        }
        return payload;
    }

    /**
     * Executes the hybrid search.
     *
     * @return decoded body with {@code hits} (and optional {@code next_cursor}/{@code trace})
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> execute() {
        byte[] body = client.post("/kit/search", build());
        Object parsed = body.length == 0 ? null : MongrelDB.Json.parse(body);
        if (parsed instanceof Map<?, ?>) {
            return (Map<String, Object>) parsed;
        }
        Map<String, Object> empty = new LinkedHashMap<>();
        empty.put("hits", new ArrayList<>());
        return empty;
    }
}
