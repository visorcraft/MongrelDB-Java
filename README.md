<p align="center">
  <img src="assets/mongrel.png" alt="MongrelDB logo" width="250" />
</p>

<h1 align="center">MongrelDB Java Client</h1>

<p align="center">
  <b>Pure Java client for MongrelDB - embedded+server database with SQL, vector search, full-text search, and AI-native retrieval.</b>
  <br />
  No external dependencies - built on the standard library <code>java.net.http.HttpClient</code> (Java 11+). The API mirrors the MongrelDB PHP and Go clients.
</p>

<p align="center">
  <a href="https://github.com/visorcraft/MongrelDB-Java/actions/workflows/ci.yml"><img src="https://github.com/visorcraft/MongrelDB-Java/actions/workflows/ci.yml/badge.svg" alt="Java CI" /></a>
  <a href="https://central.sonatype.com/artifact/com.visorcraft/mongreldb-java"><img src="https://img.shields.io/maven-central/v/com.visorcraft/mongreldb-java.svg?label=Maven%20Central" alt="Maven Central" /></a>
  <a href="https://openjdk.org/"><img src="https://img.shields.io/badge/Java-11%2B-blue.svg" alt="Java" /></a>
  <a href="#license"><img src="https://img.shields.io/badge/license-MIT%20OR%20Apache--2.0-blue.svg" alt="License" /></a>
</p>

## Package

| Surface | Coordinates | Install |
|---|---|---|
| Java client | `com.visorcraft:mongreldb-java:0.52.3` | Maven / Gradle snippets below |

### Maven

```xml
<dependency>
  <groupId>com.visorcraft</groupId>
  <artifactId>mongreldb-java</artifactId>
  <version>0.52.3</version>
</dependency>
```

### Gradle

```groovy
implementation 'com.visorcraft:mongreldb-java:0.52.3'
```

### Gradle (Kotlin DSL)

```kotlin
implementation("com.visorcraft:mongreldb-java:0.52.3")
```

The artifact has no runtime dependencies - only the Java standard library.

## Requirements

- **Java 11 or newer**
- A running [`mongreldb-server`](https://github.com/visorcraft/MongrelDB) daemon

## What It Provides

- **Typed CRUD** over the Kit transaction endpoint: `put`, `upsert` (insert-or-update on PK conflict), `delete` by row id or primary key, all with optional idempotency keys for safe retries.
- **Fluent query builder** that pushes conditions down to the engine's specialized indexes for sub-millisecond lookups: bitmap equality/IN, learned-range, null checks, FM-index full-text search, HNSW vector similarity (`ann`), and sparse vector match. Friendly aliases (`column` → `column_id`, `min`/`max` → `lo`/`hi`) are translated to the server's on-wire keys.
- **Idempotent batch transactions** - operations staged locally and committed atomically, with the engine enforcing unique, foreign-key, and check constraints at commit time. Idempotency keys return the original response on duplicate commits, even after a crash.
- **Full SQL access** through the DataFusion-backed `/sql` endpoint: recursive CTEs, window functions, `CREATE TABLE AS SELECT`, materialized views, and multi-statement execution.
- **Schema management**: typed table creation, full schema catalog, and per-table descriptors.
- **User/role/credentials management** via SQL: Argon2id-hashed catalog users, roles, and `GRANT`/`REVOKE` table-level permissions, all executed through `sql`.
- **Maintenance**: compaction (all tables or per-table).
- **History retention**: configure the durable time-travel window with
  `setHistoryRetentionEpochs`, and read historical snapshots via SQL
  `AS OF EPOCH`.
- **Pluggable transport**: bring your own `java.net.http.HttpClient`. Bearer token and HTTP Basic auth are first-class options.
- **Typed errors**: `AuthException` (401/403), `NotFoundException` (404), `ConflictException` (409, with error code + op index), and `QueryException` (everything else), all extending `MongrelDBException` and carrying the status code and decoded server envelope.

## Examples

Task-focused, commented guides live in [`docs/`](docs):

- [Quickstart](docs/quickstart.md) - install, start the daemon, write and run a complete program.
- [Transactions](docs/transactions.md) - batch commits, idempotency keys, constraint handling.
- [Queries](docs/queries.md) - every native condition type and the index it pushes down to.
- [SQL](docs/sql.md) - recursive CTEs, window functions, advanced SQL.
- [Authentication](docs/auth.md) - Bearer token, HTTP Basic, and open modes.
- [Errors](docs/errors.md) - the exception hierarchy and recovery patterns.

## Quick Example

```java
import com.visorcraft.mongreldb.MongrelDB;
import java.util.List;
import java.util.Map;

public class Example {
    public static void main(String[] args) {
        // Connect to a running mongreldb-server daemon.
        MongrelDB db = new MongrelDB("http://127.0.0.1:8453");

        // Create a table. Column ids are stable on-wire identifiers.
        db.createTable("orders", List.of(
            Map.of("id", 1, "name", "id",       "ty", "int64",   "primary_key", true,  "nullable", false),
            Map.of("id", 2, "name", "customer", "ty", "varchar", "primary_key", false, "nullable", false),
            Map.of("id", 3, "name", "amount",   "ty", "float64", "primary_key", false, "nullable", false)
        ));

        // Insert rows (cells map column id -> value).
        db.put("orders", Map.of(1L, 1L, 2L, "Alice", 3L, 99.50), null);
        db.put("orders", Map.of(1L, 2L, 2L, "Bob",   3L, 150.00), null);

        // Upsert (insert or update on PK conflict).
        db.upsert("orders",
            Map.of(1L, 1L, 2L, "Alice", 3L, 120.00),
            Map.of(3L, 120.00), null);

        // Query with a native index condition (learned-range index).
        List<Map<String, Object>> rows = db.query("orders")
            .where("range", Map.of("column", 3L, "min", 100.0))
            .projection(List.of(1L, 2L))
            .limit(100)
            .execute();
        System.out.println("rows: " + rows.size());

        long n = db.count("orders");
        System.out.println("count: " + n); // 2

        // Run SQL.
        db.sql("UPDATE orders SET amount = 200.0 WHERE customer = 'Bob'");
    }
}
```

## Enum, default, and CHECK constraints

`createTable` forwards every column-spec key the caller puts in the `Map` to
the daemon's `/kit/create_table` endpoint. The engine recognises
`enum_variants` (required for `ty: "enum"`), scalar `default_value`, dynamic
`default_expr` (`"now"` or `"uuid"`), and a top-level
`constraints` block (unique / foreign-key / check).

- `default_value` preserves its JSON scalar type. Use `"draft"` for a string,
  `7L` for an integer, `true` for a boolean, and an explicit `null` value for
  a JSON-null default.
- A literal string `"now"` in `default_value` stays a literal string; dynamic
  defaults must use `default_expr: "now"` (or `"uuid"`).

```java
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

Map<String, Object> constraints = Map.of("checks", List.of(Map.of(
    "id", 1L,
    "name", "id_present",
    "expr", Map.of("IsNotNull", 1L))));

db.createTable("orders", List.of(
    Map.of("id", 1L, "name", "id", "ty", "int64", "primary_key", true, "nullable", false),
    status,
    createdAt), constraints);
```

`enum_variants` arrives at the engine as a JSON array of strings, in order;
`default_value` preserves its JSON scalar type. The current `createTable(String,
List<Map<String,Object>>)` signature forwards both keys verbatim, so no
client-side rename is needed. CHECK constraints (regex, range, equality,
boolean composition) live in the same request body's `constraints` block -
the on-wire shape is:

```json
{
  "name": "users",
  "columns": [
    { "id": 1, "name": "id",       "ty": "int64",          "primary_key": true,  "nullable": false },
    { "id": 2, "name": "role",     "ty": "enum",           "enum_variants": ["admin", "user"], "default_value": "user" },
    { "id": 3, "name": "email",    "ty": "varchar" }
  ],
  "constraints": {
    "checks": [
      {
        "id": 1,
        "name": "email_format",
        "expr": { "regex": { "col": 3, "pattern": "^[^@]+@[^@]+$", "negated": false, "case_insensitive": true } }
      }
    ]
  }
}
```

The three-argument `createTable` overload sends that `constraints` map
unchanged. The two-argument overload remains source-compatible and omits it.

## History retention and time-travel reads

Set the database-wide retention window, in epochs, before relying on historical
reads:

```java
db.setHistoryRetentionEpochs(10_000L);

long window = db.historyRetentionEpochs();   // 10000
long earliest = db.earliestRetainedEpoch();  // oldest still-readable epoch
```

With the window configured, SQL `AS OF EPOCH` reads an older snapshot. After
updating a row, the value at the epoch of the original write remains readable:

```java
db.sql("INSERT INTO orders (id, amount) VALUES (1, 10.0)");
// ... later, after the row is updated to 20.0:
db.sql("SELECT amount FROM orders AS OF EPOCH 42 WHERE id = 1");
// returns [{"amount": 10.0}]
```

The retention window is durable and admin-only. Increasing it cannot restore
history that has already been pruned.

## Authentication

```java
// Bearer token (--auth-token mode)
MongrelDB db = new MongrelDB("http://127.0.0.1:8453", "my-secret-token", null, null);

// HTTP Basic (--auth-users mode)
MongrelDB db = new MongrelDB("http://127.0.0.1:8453", null, "admin", "s3cret");

// Default URL (http://127.0.0.1:8453) when url is null/empty
MongrelDB db = new MongrelDB(null);

// Custom HttpClient (timeouts, transport, etc.)
HttpClient http = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(60))
    .build();
MongrelDB db = new MongrelDB("http://127.0.0.1:8453", null, null, null, http);
```

A bearer token takes precedence over basic-auth credentials when both are supplied.

## Batch transactions

Operations are staged locally and committed atomically. The engine enforces
unique, foreign-key, and check constraints at commit time.

```java
Transaction txn = db.begin();
txn.put("orders", Map.of(1L, 10L, 2L, "Dave", 3L, 50.00), false);
txn.put("orders", Map.of(1L, 11L, 2L, "Eve",  3L, 75.00), false);
txn.deleteByPk("orders", 2L);

try {
    List<Map<String, Object>> results = txn.commit(null); // atomic - all or nothing
} catch (ConflictException e) {
    // A constraint violation rolled back every op.
    System.out.printf("duplicate: %s at op %d%n", e.code(), e.opIndex());
    txn.rollback(); // discard locally as well
}

// Idempotent commit - safe to retry; the daemon returns the original response.
Transaction txn2 = db.begin();
txn2.put("orders", Map.of(1L, 20L, 2L, "Frank", 3L, 100.00), false);
txn2.commit("order-20-create");
```

A `Transaction` is single-use: calling `commit` or `rollback` twice throws
`IllegalStateException`. Create a fresh one with `db.begin()` for each batch.

## Native query builder

Conditions push down to the engine's specialized indexes. The builder accepts
friendly aliases that are translated to the server's on-wire keys: `column`
(→ `column_id`), `min`/`max` (→ `lo`/`hi`). The canonical keys are also
accepted directly.

```java
// Bitmap equality (low-cardinality columns).
db.query("orders")
    .where("bitmap_eq", Map.of("column", 2L, "value", "Alice"))
    .execute();

// Range query (learned-range index).
db.query("orders")
    .where("range", Map.of("column", 3L, "min", 50.0, "max", 150.0))
    .limit(100).execute();

// Full-text search (FM-index).
db.query("documents")
    .where("fm_contains", Map.of("column", 2L, "pattern", "database performance"))
    .limit(10).execute();

// Vector similarity search (HNSW).
db.query("embeddings")
    .where("ann", Map.of("column", 2L, "query", new double[]{0.1, 0.2, 0.3}, "k", 10))
    .execute();

// Check whether a result was capped by the limit.
QueryBuilder q = db.query("orders")
    .where("range", Map.of("column", 3L, "min", 0L))
    .limit(100);
List<Map<String, Object>> rows = q.execute();
if (q.truncated()) {
    // result set hit the limit; more matches exist on the server
}
```

## SQL

```java
db.sql("INSERT INTO orders (id, customer, amount) VALUES (99, 'Zoe', 999.0)");
db.sql("CREATE TABLE archive AS SELECT * FROM orders WHERE amount > 500");

// Recursive CTEs and window functions
db.sql("WITH RECURSIVE r(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM r WHERE n<10) SELECT n FROM r");
db.sql("SELECT id, ROW_NUMBER() OVER (PARTITION BY customer ORDER BY amount DESC) FROM orders");
```

The `/sql` endpoint generally streams Arrow IPC bytes for `SELECT`s; `sql()`
decodes JSON row sets when the daemon returns them and returns an empty list
otherwise (DDL/DML or binary bodies).

## User & role management

User, role, and permission management is performed through SQL against the
daemon's catalog. Passwords are Argon2id-hashed server-side.

```java
db.sql("CREATE USER admin WITH PASSWORD 's3cret-pw'");
db.sql("ALTER USER admin SET ADMIN TRUE");

db.sql("CREATE ROLE analyst");
db.sql("GRANT select ON orders TO analyst"); // table-level permission
db.sql("GRANT analyst TO alice");

db.sql("SELECT username FROM catalog.users"); // list users
db.sql("SELECT name FROM catalog.roles");     // list roles
```

## Error handling

Every non-2xx response is mapped to a typed exception. Catch the specific
subclass for the category, or catch `MongrelDBException` to handle any failure.
Each carries the HTTP status code and the server's decoded error envelope
(`code`, `opIndex`).

```java
try {
    db.schemaFor("missing_table");
} catch (NotFoundException e) {
    System.out.println("not found: " + e.getMessage());
} catch (AuthException e) {
    System.out.println("not authorized: " + e.getMessage());
} catch (ConflictException e) {
    System.out.printf("constraint %s at op %d%n", e.code(), e.opIndex());
} catch (QueryException e) {
    System.out.println("query/server error: " + e.getMessage() + " (status " + e.status() + ")");
}

// Or inspect directly on the base type:
try {
    db.schemaFor("missing_table");
} catch (MongrelDBException e) {
    System.out.printf("status=%d code=%s msg=%s%n", e.status(), e.code(), e.getMessage());
    // e.g. status=404 code=NOT_FOUND msg=no such table
}
```

## API reference

### `MongrelDB`

| Method | Description |
|--------|-------------|
| `new MongrelDB(url)` | Construct a client (url defaults to `http://127.0.0.1:8453`) |
| `new MongrelDB(url, token, user, pass)` | With Bearer token or Basic auth |
| `new MongrelDB(url, token, user, pass, httpClient)` | With a custom `java.net.http.HttpClient` |
| `health()` | Check daemon health |
| `tableNames()` | List table names |
| `createTable(name, columns[, constraints])` | Create a table, optionally attach engine constraints; returns the table id |
| `dropTable(name)` | Drop a table |
| `count(table)` | Row count |
| `put(table, cells, idempotencyKey)` | Insert a row |
| `upsert(table, cells, updateCells, key)` | Upsert a row |
| `delete(table, rowId)` | Delete by row id |
| `deleteByPK(table, pk)` | Delete by primary key |
| `query(table)` | Start a native query |
| `sql(sql)` | Execute SQL |
| `schema()` | Full schema catalog |
| `schemaFor(table)` | Single-table descriptor |
| `compact()` | Compact all tables |
| `compactTable(table)` | Compact one table |
| `setHistoryRetentionEpochs(epochs)` | Set the durable time-travel window |
| `historyRetentionEpochs()` | Current retention window, in epochs |
| `earliestRetainedEpoch()` | Oldest epoch still retained |
| `begin()` | Start a batch |

### `QueryBuilder`

| Method | Description |
|--------|-------------|
| `where(type, params)` | Add a native condition (AND-ed) |
| `projection(columnIDs)` | Set column projection |
| `limit(limit)` | Set row limit |
| `offset(offset)` | Skip matching rows before the limit |
| `build()` | Build the request payload |
| `execute()` | Run the query |
| `truncated()` | Whether the last `execute` result hit the limit |

### `Transaction`

| Method | Description |
|--------|-------------|
| `put(table, cells, returning)` | Stage an insert |
| `upsert(table, cells, updateCells, returning)` | Stage an upsert |
| `delete(table, rowId)` | Stage a delete by row id |
| `deleteByPk(table, pk)` | Stage a delete by primary key |
| `count()` | Number of staged operations |
| `commit(idempotencyKey)` | Commit atomically |
| `rollback()` | Discard all operations |

### Exceptions

| Exception | HTTP status | Meaning |
|-----------|-------------|---------|
| `MongrelDBException` | any | Base class for all client errors |
| `AuthException` | 401, 403 | Bad or missing credentials |
| `NotFoundException` | 404 | Missing table, schema, or resource |
| `ConflictException` | 409 | Unique, FK, check, or trigger violation (carries `code` + `opIndex`) |
| `QueryException` | 400, 5xx | Malformed query, server error, or transport failure |

All exceptions extend `MongrelDBException` and expose `status()`, `code()`, and `opIndex()`.

## Building and testing

The test suite is a live integration suite: it boots a real `mongreldb-server`
daemon and exercises the full client surface against it. Live tests skip
automatically when no daemon is available (using JUnit's `assumeTrue`); two
offline sanity tests always run.

```sh
# Compile and run the offline checks:
mvn compile

# Run the live suite. The harness boots mongreldb-server itself if it can find
# the binary (in this order):
#   1. the MONGRELDB_SERVER env var (path to the server binary)
#   2. ./bin/mongreldb-server
#   3. mongreldb-server on PATH
# Or point it at an already-running daemon with MONGRELDB_URL.
MONGRELDB_SERVER=./bin/mongreldb-server mvn test
```

Fetch a prebuilt server binary from the [MongrelDB releases](https://github.com/visorcraft/MongrelDB/releases):

```sh
mkdir -p bin
curl -fsSL -o bin/mongreldb-server \
  https://github.com/visorcraft/MongrelDB/releases/download/v0.52.3/mongreldb-server-linux-x64
chmod +x bin/mongreldb-server
```

## Native embedding (Tier 1)

For in-process access with zero serialization overhead, use the `NativeDB`
class (in `com.visorcraft.mongreldb.native_mode`). It loads the JNI shim
(`libmongreldb_jni`) via `System.load()` and runs the engine directly in the
JVM - no daemon needed.

Download the prebuilt native library from the
[MongrelDB releases](https://github.com/visorcraft/MongrelDB/releases) page
(`mongreldb-jni-*.jar` or the `mongreldb-native-*` archives):

```sh
# Point at the directory containing libmongreldb_jni.so
export MONGRELDB_NATIVE_DIR=/path/to/native/libs
```

```java
import com.visorcraft.mongreldb.native_mode.NativeDB;

String schemaJson = "{\"tables\":[{\"id\":1,\"name\":\"users\",...}]}";

try (NativeDB db = NativeDB.create("/path/to/dbdir", schemaJson)) {
    db.sqlRows("INSERT INTO users (id, name) VALUES (1, 'alice')");
    String rows = db.sqlRows("SELECT id, name FROM users");
    byte[] arrow = db.sqlArrow("SELECT * FROM users");
    db.migrate(migrationsJson);
}
```

The HTTP client (`MongrelDB`) remains the default for connecting to a shared
daemon. Use `NativeDB` when you want the embedded experience.

## Contributing

Contributions are welcome. Please:

1. Open an issue first for non-trivial changes.
2. Add focused tests near your change - the suite must stay green.
3. Keep the client dependency-free (Java standard library only).

## License

Dual-licensed under the **MIT License** or the **Apache License, Version 2.0**,
at your option. See [MIT](LICENSE-MIT) OR [Apache-2.0](LICENSE-APACHE) for the full text.

`SPDX-License-Identifier: MIT OR Apache-2.0`
