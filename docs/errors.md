# Error handling

Every non-2xx response from the daemon is mapped to a typed Java exception.
This is the complete reference: the exception hierarchy, the fields carried
on each exception, the HTTP-status mapping, the daemon's error envelope, and
recovery patterns for each category.

---

## The exception hierarchy

All client errors extend `MongrelDBException` (a `RuntimeException`, so
unchecked). Catch `MongrelDBException` to handle any failure, or catch one of
the specific subclasses:

```
RuntimeException
└── MongrelDBException            (base; carries status, code, opIndex)
    ├── AuthException             HTTP 401 / 403
    ├── NotFoundException         HTTP 404
    ├── ConflictException         HTTP 409
    └── QueryException            HTTP 400 / 5xx / everything else
```

| Exception | Meaning | Typical cause |
|-----------|---------|---------------|
| `MongrelDBException` | Base class; any client-side failure | Catch-all parent |
| `AuthException` | HTTP 401 or 403 | Missing/bad credentials against an auth-enabled daemon |
| `NotFoundException` | HTTP 404 | Missing table, schema, or other resource |
| `ConflictException` | HTTP 409 | Unique, foreign-key, check, or trigger violation at commit |
| `QueryException` | HTTP 400 or 5xx | Malformed request, transport failure, server error, JSON decode errors |

`QueryException` is also the type raised for client-side failures that do not
correspond to an HTTP response (e.g. an `IOException` from the transport, an
`InterruptedException`, or a malformed JSON body). In those cases `status()`
returns `-1`.

## Fields carried on every exception

`MongrelDBException` exposes three accessors inherited by all subclasses:

| Method | Returns |
|--------|---------|
| `status()` | The HTTP status code, or `-1` when unknown (client-side failure). |
| `code()` | The server's structured error code, e.g. `"UNIQUE_VIOLATION"`, or `null`. |
| `opIndex()` | The offending op index within a batch, or `null` when not reported. |

Plus the inherited `getMessage()`, `getCause()`, and the usual
`RuntimeException` behavior.

The daemon's JSON error envelope (decoded into the fields above):

```json
{
  "status": "aborted",
  "error": {
    "code": "UNIQUE_VIOLATION",
    "message": "duplicate key in column 1",
    "op_index": 0
  }
}
```

Structured codes you will commonly see in `code()`:

| `code()` | Meaning |
|----------|---------|
| `UNIQUE_VIOLATION` | A unique/PK constraint rejected the commit |
| `FK_VIOLATION` | A foreign-key reference was missing |
| `CHECK_VIOLATION` | A check constraint or trigger rejected the commit |
| `NOT_FOUND` | A named resource (table, schema) does not exist |

## HTTP status → exception mapping

| HTTP status | Exception | Notes |
|-------------|-----------|-------|
| 401, 403 | `AuthException` | Bad/missing credentials |
| 404 | `NotFoundException` | Resource not found |
| 409 | `ConflictException` | Constraint violation at commit |
| 400 | `QueryException` | Malformed request / bad query |
| 5xx | `QueryException` | Daemon-side failure |
| other non-2xx | `QueryException` | Catch-all |
| 2xx | (no exception) | Success |
| transport failure | `QueryException` | `status() == -1` |

## Discriminating errors

### By type - catch the specific subclass

```java
try {
    db.schemaFor("missing_table");
} catch (NotFoundException e) {
    System.out.println("table does not exist");
} catch (ConflictException e) {
    System.out.println("unexpected conflict on a read");
} catch (AuthException e) {
    System.out.println("bad credentials");
} catch (QueryException e) {
    System.out.println("server error or malformed request: " + e.getMessage());
}
```

Because all four subclasses share the parent, a single `catch
(MongrelDBException e)` handles everything if you only need to know it
failed.

### By details - read the fields

```java
try {
    db.schemaFor("missing_table");
} catch (MongrelDBException e) {
    System.out.printf("status=%d code=%s op=%s msg=%s%n",
            e.status(), e.code(), e.opIndex(), e.getMessage());
}
```

Combine the two for constraint-aware handling:

```java
try {
    txn.commit(null);
} catch (ConflictException e) {
    System.out.printf("constraint %s at op %s: %s%n",
            e.code(), e.opIndex(), e.getMessage());
}
```

## Recovery patterns

### Auth failure - do not retry blindly

A retry will not fix bad credentials. Surface the error to the caller or
operator.

```java
try {
    db.put("orders", cells, null);
} catch (AuthException e) {
    // Refresh credentials from your secret store, or fail fast.
    throw new IllegalStateException("credentials rejected; refresh token", e);
}
```

### Not found - fall back, do not crash

For lookups by primary key, a 404 may be a normal "absent" result.

```java
try {
    List<Map<String, Object>> rows = db.query("orders")
            .where("pk", Map.of("value", id))
            .execute();
    return rows;
} catch (NotFoundException e) {
    return List.of(); // table missing - treat as empty
}
```

Note: a `pk` query against an existing table returns zero rows, not a 404;
`NotFoundException` here means the table itself is missing.

### Constraint conflict - report the offending op

```java
try {
    txn.commit(null);
} catch (ConflictException e) {
    if (e.opIndex() != null) {
        throw new RuntimeException(
                "op " + e.opIndex() + " violated " + e.code() + ": " + e.getMessage(), e);
    }
    throw new RuntimeException("conflict " + e.code() + ": " + e.getMessage(), e);
}
```

The engine already rolled back the whole batch - there is nothing to undo.

### Transient failure - retry with an idempotency key

`QueryException` covers transport and 5xx failures. With an idempotency key,
retrying a transaction is safe (see [transactions.md](transactions.md)).

```java
public void run(Transaction txn, String key) {
    try {
        txn.commit(key);
    } catch (AuthException | ConflictException e) {
        throw e; // not transient
    } catch (MongrelDBException e) {
        // QueryException / network - caller may retry with the same key.
        throw e;
    }
}
```

### Transaction-state error

`Transaction.commit` and `Transaction.rollback` throw
`IllegalStateException` ("mongreldb: transaction already committed") if called
twice. Fix the control flow rather than catching it.

```java
txn.commit(null);
txn.commit(null); // throws IllegalStateException - logic bug
```

## Quick reference

```java
import com.visorcraft.mongreldb.*;

// Type-based discrimination:
try {
    db.put("orders", cells, null);
} catch (AuthException e) {
    // 401/403
} catch (NotFoundException e) {
    // 404
} catch (ConflictException e) {
    // 409; e.code(), e.opIndex()
} catch (QueryException e) {
    // 400/5xx/transport; e.status() == -1 for client-side failures
} catch (MongrelDBException e) {
    // catch-all parent
}

// Field access on any MongrelDBException:
//   e.status()   -> int (HTTP status, or -1)
//   e.code()     -> String ("UNIQUE_VIOLATION", ...) or null
//   e.opIndex()  -> Integer (offending op) or null
//   e.getMessage() -> human-readable message
```

## Next steps

- [transactions.md](transactions.md) - constraint handling and retries in context
- [auth.md](auth.md) - credential management
