# Quickstart

Zero to a running MongrelDB Java program in fifteen minutes. This guide walks
through installing the prerequisites, starting the daemon, and writing,
running, and understanding a complete program.

---

## 1. Prerequisites

You need two things installed: a JDK and a `mongreldb-server` daemon.

### Install JDK 11 or newer

The client is built on the standard-library `java.net.http.HttpClient` and has
no external dependencies, so any JDK 11+ works. Verify it:

```sh
java -version
# openjdk version "11.0.x" (or newer)
```

If you do not have it, install from <https://adoptium.net/> or your package
manager (e.g. `pacman -S jdk-openjdk`, `brew install openjdk@21`).

### Install mongreldb-server

Fetch a prebuilt server binary from the
[MongrelDB releases](https://github.com/visorcraft/MongrelDB/releases):

```sh
mkdir -p bin
curl -fsSL -o bin/mongreldb-server \
  https://github.com/visorcraft/MongrelDB/releases/download/v0.48.0/mongreldb-server-linux-x64
chmod +x bin/mongreldb-server
```

Verify it runs:

```sh
./bin/mongreldb-server --version
```

## 2. Start the daemon

By default `mongreldb-server` listens on `http://127.0.0.1:8453` and stores
data in the current working directory.

```sh
mkdir -p /tmp/mdb-data && cd /tmp/mdb-data
/path/to/mongreldb-server
```

In another terminal, sanity-check it:

```sh
curl http://127.0.0.1:8453/health
# ok
```

Leave the daemon running for the rest of this guide.

## 3. Create a project and pull in the client

### With Maven

Add the dependency to `pom.xml`:

```xml
<dependency>
  <groupId>dev.visorcraft</groupId>
  <artifactId>mongreldb-java</artifactId>
  <version>0.1.0</version>
</dependency>
```

Make sure your compiler targets Java 11 or newer:

```xml
<properties>
  <maven.compiler.release>11</maven.compiler.release>
</properties>
```

### With Gradle

```groovy
implementation 'dev.visorcraft:mongreldb-java:0.1.0'
```

## 4. Write your first program

Create `src/main/java/com/example/Main.java`:

```java
package com.example;

import java.util.List;
import java.util.Map;

import dev.visorcraft.mongreldb.MongrelDB;

public class Main {
    public static void main(String[] args) {
        // 1. Connect to the daemon. The default constructor targets
        //    http://127.0.0.1:8453.
        MongrelDB db = new MongrelDB("http://127.0.0.1:8453");

        // 2. Health check before doing anything else.
        if (!db.health()) {
            System.err.println("daemon not reachable");
            System.exit(1);
        }

        // 3. Create a table. Each column is a Map with id, name, ty, and flags.
        //    The first column is the primary key. Column ids are stable on-wire
        //    identifiers - use them everywhere else.
        long tableId = db.createTable("orders", List.of(
                column(1L, "id", "int64", true),
                column(2L, "customer", "varchar", false),
                column(3L, "amount", "float64", false)));
        System.out.println("created table id: " + tableId);

        // 4. Insert rows. cells maps column id (Long) -> value. null means
        //    "no idempotency key" (fine for a one-shot demo).
        db.put("orders", Map.of(1L, 1L, 2L, "Alice", 3L, 99.5), null);
        db.put("orders", Map.of(1L, 2L, 2L, "Bob", 3L, 150.0), null);

        // 5. Query with a native index condition. The range index serves this
        //    in sub-millisecond. Projection selects only column ids 1 and 2.
        List<Map<String, Object>> rows = db.query("orders")
                .where("range", Map.of("column", 3L, "min", 100.0))
                .projection(List.of(1L, 2L))
                .limit(100)
                .execute();
        for (Map<String, Object> row : rows) {
            System.out.println("row: " + row);
        }

        // 6. Count the rows.
        System.out.println("total rows: " + db.count("orders"));
    }

    /** Builds a column descriptor Map for createTable. */
    private static Map<String, Object> column(long id, String name, String ty, boolean pk) {
        return Map.of(
                "id", id,
                "name", name,
                "ty", ty,
                "primary_key", pk,
                "nullable", false);
    }
}
```

Run it (Maven):

```sh
mvn compile exec:java -Dexec.mainClass="com.example.Main"
```

You should see:

```
created table id: 1
row: {2=Bob}
total rows: 2
```

## 5. Enum, default value, and CHECK constraints

`createTable(name, columns)` forwards every key in each column `Map` straight
to the daemon's `/kit/create_table` endpoint. The engine recognises a small
set of optional keys on top of `id`, `name`, `ty`, `primary_key`, `nullable`:

| Key | Type | Effect |
|-----|------|--------|
| `enum_variants` | `List<String>` | Required when `ty` is `"enum"`. Ordered list of allowed values. |
| `default_value` | JSON scalar | Static per-column default. |
| `default_expr` | `String` | Dynamic default: `"now"` or `"uuid"`. |

All arrive on the wire verbatim - the codec does not rename or strip them.

```java
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import dev.visorcraft.mongreldb.MongrelDB;

// Connect, health-check (see §4 above).
MongrelDB db = new MongrelDB("http://127.0.0.1:8453");

// Enum column with three allowed values and a default.
Map<String, Object> role = new LinkedHashMap<>();
role.put("id", 2L);
role.put("name", "role");
role.put("ty", "enum");
role.put("primary_key", false);
role.put("nullable", false);
role.put("enum_variants", List.of("admin", "user", "guest"));
role.put("default_value", "guest");

// Timestamp column that fills in "now" on insert.
Map<String, Object> createdAt = new LinkedHashMap<>();
createdAt.put("id", 3L);
createdAt.put("name", "created_at");
createdAt.put("ty", "timestamp_nanos");
createdAt.put("primary_key", false);
createdAt.put("nullable", false);
createdAt.put("default_expr", "now");

db.createTable("users", List.of(
        Map.of("id", 1L, "name", "id", "ty", "int64",
                "primary_key", true, "nullable", false),
        role,
        createdAt));
```

### CHECK constraints (regex, range, equality)

CHECK constraints - including regex, range, equality, and boolean composition -
live in a top-level `constraints` block on the same `/kit/create_table` payload:

```json
{
  "name": "users",
  "columns": [
    { "id": 1, "name": "id",    "ty": "int64",          "primary_key": true,  "nullable": false },
    { "id": 2, "name": "email", "ty": "varchar" }
  ],
  "constraints": {
    "checks": [
      {
        "id": 1,
        "name": "email_format",
        "expr": { "regex": { "col": 2, "pattern": "^[^@]+@[^@]+$", "negated": false, "case_insensitive": true } }
      }
    ]
  }
}
```

The Java client's typed `createTable(String, List<Map<String, Object>>)`
signatures accept only the column list today. Callers that need regex / range
/ FK CHECKs today must hand-build the full JSON payload against the
`/kit/create_table` endpoint - the column map above is the canonical reference
for the wire shape.

## 6. What each part does

| Code | What it does |
|------|--------------|
| `new MongrelDB(url)` | Builds an HTTP client targeting one daemon. Thread-safe once constructed. |
| `db.health()` | GET `/health`; returns `true` when the daemon answers. Always check before real work. |
| `db.createTable(name, columns[, constraints])` | POST `/kit/create_table`; optional constraints map carries engine checks. Column `id`s are the on-wire identifiers; use them everywhere else. |
| `db.put(table, cells, key)` | Single-op transaction: POST `/kit/txn` with one `put` op. `cells` is flattened to `[col_id, val, ...]`. |
| `db.query(table).where(...)` | Builds a `/kit/query` body. `where` pushes a condition down to a native index. |
| `.projection(List.of(1L, 2L))` | Server returns only those column ids, saving bandwidth. |
| `.limit(100)` | Caps the result; check `builder.truncated()` afterward to detect overflow. |
| `.execute()` | Sends the query and decodes the `rows` list. |
| `db.count(table)` | GET `/tables/{name}/count`. |

## 7. Common pitfalls

**Using the column name instead of the column id.** Every on-wire API uses
the numeric `id` from `createTable`, never the `name`. The query builder's
`column` alias maps to the server's `column_id` - pass the `Long` id, not the
string name:

```java
// Wrong:
.where("range", Map.of("column", "amount", "min", 100.0))
// Right:
.where("range", Map.of("column", 3L, "min", 100.0))
```

**Forgetting the `L` suffix on column ids.** Cells are `Map<Long, ?>` and
projection is `List<Long>`. Writing `Map.of(1, "Alice")` boxes to `Integer`,
which will not flatten correctly. Always use `1L`, `2L`, ...

**Treating a single `put` as non-transactional.** `put` is a one-op
transaction. A unique constraint violation throws `ConflictException` (HTTP
409), not a silent no-op.

**Calling `commit` twice on the same `Transaction`.** The second call throws
`IllegalStateException`. Create a fresh `db.begin()` for each logical unit of
work.

**Reusing a `QueryBuilder` and expecting a fresh `truncated`.** `truncated()`
reflects the most recent `execute`. Build a new query, or re-run `execute`
before reading it.

**Expecting `sql` to always return rows.** The `/sql` endpoint streams Arrow
IPC for `SELECT` in most builds, so `sql` returns an empty list (not an
exception) for result sets. Use it for DDL/DML and statements whose success is
the signal; use the native query builder for typed row retrieval.

**Pointing at a daemon that requires auth.** If the daemon was started with
`--auth-token` or `--auth-users`, every call throws `AuthException` unless you
construct the client with a token or Basic credentials. See [auth.md](auth.md).

## Next steps

- [transactions.md](transactions.md) - atomic batches, idempotency, retries
- [queries.md](queries.md) - every native index condition
- [sql.md](sql.md) - recursive CTEs, window functions, `CREATE TABLE AS SELECT`
- [auth.md](auth.md) - bearer tokens, basic auth, user/role management
- [errors.md](errors.md) - the full exception hierarchy and recovery patterns
