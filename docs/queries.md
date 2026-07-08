# Queries

The fluent `QueryBuilder` pushes conditions down to MongrelDB's native indexes
for sub-millisecond lookups - bitmap, learned-range, FM-index full text, HNSW
vector similarity, and more. Each condition type maps to one specialized
index; conditions are AND-ed together.

```java
QueryBuilder q = db.query("orders")
        .where("range", Map.of("column", 3L, "min", 100.0, "max", 500.0))
        .projection(List.of(1L, 2L))
        .limit(100);
List<Map<String, Object>> rows = q.execute();
```

This guide covers every condition type, projection, limits and truncation,
combining conditions, and the friendly aliases the builder translates for you.

---

## The basics

Every query starts with `MongrelDB.query(table)` and ends with `execute()`:

| Method | Purpose |
|--------|---------|
| `where(condType, params)` | Add a native condition. Multiple `where` calls are AND-ed. |
| `projection(columnIDs)` | Return only these column ids (`null` means all columns). |
| `limit(n)` | Cap the number of rows. |
| `build()` | Produce the request payload (useful for debugging). |
| `execute()` | Send and decode. Records the `truncated` flag. |
| `truncated()` | Whether the last `execute` hit the limit. |

The request body produced by `build()` matches the daemon's `/kit/query`
shape:

```json
{
  "table": "orders",
  "conditions": [{"range": {"column_id": 3, "lo": 100.0, "hi": 500.0}}],
  "projection": [1, 2],
  "limit": 100
}
```

## Condition types

`params` is a `Map<String, ?>`. Column references use the numeric **column
id** (`Long`), never the column name. Always suffix integer literals with `L`.

### `pk` - exact primary-key match

The fastest lookup. `value` is the primary-key value.

```java
db.query("orders")
        .where("pk", Map.of("value", 42L))
        .execute();
```

### `range` - integer range (learned-range index)

Inclusive bounds. Omit `lo` or `hi` for an open range.

```java
db.query("orders")
        .where("range", Map.of(
                "column", 3L,   // column id
                "min", 100L,
                "max", 500L))
        .execute();

// Open-ended: amount >= 100
db.query("orders")
        .where("range", Map.of("column", 3L, "min", 100L))
        .execute();
```

### `range_f64` - float range with inclusive/exclusive control

Adds `lo_inclusive` / `hi_inclusive` flags (default inclusive).

```java
db.query("orders")
        .where("range_f64", Map.of(
                "column", 3L,
                "min", 100.0,
                "max", 500.0,
                "min_inclusive", true,
                "max_inclusive", false)) // (100.0, 500.0]
        .execute();
```

### `bitmap_eq` - equality on a bitmap-indexed column

Best for low-cardinality columns (status, category, booleans).

```java
db.query("orders")
        .where("bitmap_eq", Map.of("column", 2L, "value", "Alice"))
        .execute();
```

### `bitmap_in` - IN predicate on a bitmap-indexed column

Match any of a set of values.

```java
db.query("orders")
        .where("bitmap_in", Map.of(
                "column", 2L,
                "values", List.of("Alice", "Bob", "Carol")))
        .execute();
```

### `is_null` / `is_not_null` - null checks

```java
db.query("orders").where("is_null", Map.of("column", 3L)).execute();
db.query("orders").where("is_not_null", Map.of("column", 3L)).execute();
```

### `fm_contains` - full-text substring search (FM-index)

Substring match within a column. Use `pattern` (the server key) or the
friendly `value` alias - both translate to `pattern` on the wire for FTS
conditions.

```java
db.query("documents")
        .where("fm_contains", Map.of(
                "column", 2L,
                "pattern", "database performance"))
        .limit(10)
        .execute();

// Friendly alias: "value" -> "pattern" for fm_contains only.
db.query("documents")
        .where("fm_contains", Map.of("column", 2L, "value", "database"))
        .execute();
```

### `fm_contains_all` - multiple substrings, all must match

```java
db.query("documents")
        .where("fm_contains_all", Map.of(
                "column", 2L,
                "patterns", List.of("database", "performance")))
        .execute();
```

### `ann` - dense vector similarity (HNSW)

Approximate nearest-neighbors over a `float` vector column. `k` is the result
count. Pass the query vector as a `List<Float>` or `float[]`.

```java
db.query("embeddings")
        .where("ann", Map.of(
                "column", 2L,
                "query", new float[]{0.1f, 0.2f, 0.3f, 0.4f},
                "k", 10L))
        .execute();
```

### `sparse_match` - sparse vector match

For sparse/bag-of-words vectors.

```java
db.query("docs")
        .where("sparse_match", Map.of(
                "column", 2L,
                "query", Map.of(0L, 1.0, 7L, 0.5, 42L, 2.0),
                "k", 10L))
        .execute();
```

### `min_hash_similar` - MinHash similarity

Near-duplicate detection via MinHash signatures.

```java
db.query("pages")
        .where("min_hash_similar", Map.of(
                "column", 2L,
                "query", List.of(12L, 99L, 421L, 7L),
                "k", 5L))
        .execute();
```

## Projection (column selection)

`projection(List.of(...))` restricts the columns in each returned row. Pass
`null` (or skip the call) for all columns. Projecting to only the columns you
need cuts bandwidth and decode cost.

```java
// Return only the id and customer columns.
db.query("orders")
        .where("range", Map.of("column", 3L, "min", 100L))
        .projection(List.of(1L, 2L))
        .execute();
```

Returned rows are `Map<String, Object>` keyed by the column id as a
JSON-decoded key (a string like `"2"`). Cast accordingly:

```java
List<Map<String, Object>> rows = db.query("orders")
        .projection(List.of(1L, 2L))
        .execute();
for (Map<String, Object> r : rows) {
    Object customer = r.get("2"); // likely a String
    System.out.println(customer);
}
```

## Limit and the truncated flag

`limit(n)` caps the result. When the server has more matches than the limit
allows, it returns the first `n` and sets `truncated: true`. Read it with
`truncated()` **after** `execute`.

```java
QueryBuilder q = db.query("orders")
        .where("range", Map.of("column", 3L, "min", 0L))
        .limit(100);
List<Map<String, Object>> rows = q.execute();
if (q.truncated()) {
    // 100 rows came back but more exist on the server. Either raise the
    // limit, page with a range predicate on the PK, or accept the cap.
    System.out.println("result capped at " + rows.size() + "; more rows available");
}
```

`truncated()` returns `false` until `execute` has run, so build a fresh query
for each independent lookup.

## Multiple AND conditions

Chain `where` calls. Every condition must match; the server intersects the
index results.

```java
// Customer is Alice AND amount is between 100 and 500.
db.query("orders")
        .where("bitmap_eq", Map.of("column", 2L, "value", "Alice"))
        .where("range", Map.of("column", 3L, "min", 100L, "max", 500L))
        .projection(List.of(1L, 3L))
        .limit(50)
        .execute();
```

Because each `where` targets a different specialized index, the engine can
pick the most selective one to drive the lookup and intersect the rest.

## Friendly alias translation

The builder accepts readable parameter names and translates them to the
server's canonical on-wire keys. Both spellings work, so use whichever is
clearer in context.

| You write | Sent as | Applies to |
|-----------|---------|------------|
| `column` | `column_id` | all condition types |
| `min` | `lo` | `range`, `range_f64` |
| `max` | `hi` | `range`, `range_f64` |
| `min_inclusive` | `lo_inclusive` | `range_f64` |
| `max_inclusive` | `hi_inclusive` | `range_f64` |
| `value` | `pattern` | `fm_contains`, `fm_contains_all` only |

The `value` → `pattern` alias applies **only** to FTS conditions, because
`pk` and `bitmap_eq` use `value` as their canonical key. For those, write
`value` directly.

```java
// pk: "value" stays "value" (canonical)
.where("pk", Map.of("value", 42L))

// fm_contains: "value" is translated to "pattern"
.where("fm_contains", Map.of("column", 2L, "value", "search term"))
// equivalent to:
.where("fm_contains", Map.of("column_id", 2L, "pattern", "search term"))
```

## Putting it together

A realistic combined lookup - bitmap equality + range + projection + limit +
truncation check:

```java
public List<Map<String, Object>> topSpenders(String customer) {
    QueryBuilder q = db.query("orders")
            .where("bitmap_eq", Map.of("column", 2L, "value", customer))
            .where("range", Map.of("column", 3L, "min", 100L))
            .projection(List.of(1L, 3L))
            .limit(50);
    List<Map<String, Object>> rows = q.execute();
    if (q.truncated()) {
        System.err.println("warning: topSpenders result capped at 50");
    }
    return rows;
}
```

For arbitrary predicates, joins, and aggregations that the native indexes do
not cover, use SQL instead - see [sql.md](sql.md).
