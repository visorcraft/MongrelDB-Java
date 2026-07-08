# Transactions

MongrelDB commits every write through a single atomic transaction endpoint
(`POST /kit/txn`). This guide covers the two ways to use it - a one-shot
single op, and a staged batch - plus idempotency keys for safe retries, typed
constraint-violation handling, and rollback.

The engine enforces `UNIQUE`, foreign-key, check, and trigger constraints at
**commit time**. A violation aborts the entire batch: no op in the batch
becomes visible, and `commit` throws `ConflictException`.

---

## Single puts vs. batch transactions

### Single op: `MongrelDB.put`

`MongrelDB.put` is a convenience wrapper that sends a one-op transaction. Use
it when a write is independent and you do not need atomicity across multiple
rows.

```java
// One row, one atomic op. null means "no idempotency key".
Map<String, Object> res = db.put("orders",
        Map.of(1L, 1L, 2L, "Alice", 3L, 99.5),
        null);
```

`MongrelDB.upsert`, `MongrelDB.delete`, and `MongrelDB.deleteByPK` are the
same shape: single-op transactions.

### Batch: `MongrelDB.begin` + `Transaction`

When several writes must succeed or fail together, stage them on a
`Transaction` and commit once. All ops go to the server in a single HTTP
request and commit atomically.

```java
Transaction txn = db.begin();
txn.put("orders", Map.of(1L, 10L, 2L, "Dave", 3L, 50.0), false);
txn.put("orders", Map.of(1L, 11L, 2L, "Eve", 3L, 75.0), false);
txn.deleteByPk("orders", 2L);

List<Map<String, Object>> results = txn.commit(null); // atomic - all or nothing
System.out.println("committed " + results.size() + " ops");
```

The third argument to `Transaction.put` is `returning`. Set it to `true` to
have the daemon echo the written row back in the result map - useful for
reading server-assigned values.

```java
Transaction txn = db.begin();
txn.put("orders", Map.of(1L, 42L, 2L, "Hal", 3L, 12.0), true /* returning */);
List<Map<String, Object>> res = txn.commit(null);
System.out.println("server echoed: " + res.get(0));
```

`Transaction.upsert(table, cells, updateCells, returning)` takes an
`updateCells` map applied on a primary-key conflict. A `null` `updateCells`
means "do nothing on conflict".

```java
txn.upsert("orders",
        Map.of(1L, 1L, 2L, "Alice", 3L, 120.0),     // insert these...
        Map.of(3L, 120.0),                           // ...or update only amount on conflict
        false);
```

## Idempotency keys for safe retries

Networks drop requests and daemons crash after committing but before
replying. An idempotency key makes a commit safe to retry: the daemon
remembers the key and replays the **original** result on a duplicate commit,
even across restarts.

Pass the key as the argument to `commit` (or the third argument to
`MongrelDB.put`/`MongrelDB.upsert`):

```java
// A web handler that must not double-charge, even if the client retries or
// the connection drops after the daemon committed.
public void charge(String orderId) {
    Transaction txn = db.begin();
    txn.put("charges", Map.of(1L, orderId, 2L, 199.0), false);

    // Use a stable, business-meaningful key derived from the request.
    // On a retry with the same key the daemon returns the first commit's
    // result instead of inserting a second row.
    txn.commit("charge:" + orderId);
}
```

Rules for keys:

- Any non-empty string works. Prefer content-derived, globally-unique values
  (e.g. `"charge:" + orderId`).
- `null` (or the empty string) disables idempotency - a retry will commit
  again.
- The key scopes the **entire batch**, not individual ops. Reuse the exact
  same ops and key together when retrying.

A safe retry loop - build the transaction inside the loop so a failed attempt
can be retried cleanly:

```java
public void commitWithRetry(List<Map<String, Object>> ops, String key) throws Exception {
    for (int attempt = 0; attempt < 3; attempt++) {
        Transaction txn = db.begin();
        for (Map<String, Object> op : ops) {
            // re-stage the same ops on a fresh transaction
            txn.put(/* ... */);
        }
        try {
            txn.commit(key);
            return;
        } catch (ConflictException | AuthException e) {
            throw e; // not transient - do not retry
        } catch (MongrelDBException e) {
            // Network/server error (QueryException). The idempotency key makes
            // it safe to retry.
            if (attempt == 2) {
                throw e;
            }
            Thread.sleep(1000L << attempt); // 1s, 2s, 4s
        }
    }
}
```

## Handling constraint violations

Constraint violations arrive as HTTP 409, mapped to `ConflictException`. It
extends `MongrelDBException` and carries the structured `code()` and
`opIndex()`:

```java
Transaction txn = db.begin();
txn.put("orders", Map.of(1L, 1L), false); // duplicate PK

try {
    txn.commit(null);
} catch (ConflictException e) {
    switch (e.code() == null ? "" : e.code()) {
        case "UNIQUE_VIOLATION":
            System.out.printf("duplicate at op %d: %s%n", e.opIndex(), e.getMessage());
            break;
        case "FK_VIOLATION":
            System.out.printf("missing parent at op %d: %s%n", e.opIndex(), e.getMessage());
            break;
        case "CHECK_VIOLATION":
            System.out.printf("check failed at op %d: %s%n", e.opIndex(), e.getMessage());
            break;
        default:
            System.out.println("other conflict: " + e.getMessage());
    }
}
```

The error envelope from the daemon looks like:

```json
{"status": "aborted", "error": {"code": "UNIQUE_VIOLATION", "message": "...", "op_index": 0}}
```

`opIndex()` points at the offending op within the batch so you can report
which row caused the failure. It returns `null` when the server did not report
one.

For simple category checks, catch the specific subclass:

```java
try {
    txn.commit(null);
} catch (ConflictException e) {
    // any constraint violation
} catch (NotFoundException e) {
    // table or row missing
} catch (AuthException e) {
    // bad credentials
}
```

## Rollback after failure

There are two notions of "rollback":

1. **Server-side.** When `commit` throws `ConflictException`, the engine has
   already discarded the entire batch. Nothing was written; there is no server
   rollback to perform.
2. **Client-side.** `Transaction.rollback()` clears the locally staged ops.
   Call it to release the `Transaction` when you decide not to commit (for
   example, after a validation error in your own code, before ever sending).

```java
Transaction txn = db.begin();
txn.put("orders", Map.of(1L, 1L, 2L, "Iris", 3L, 5.0), false);

if (!businessRuleOk()) {
    // Throw the staged ops away locally. Nothing has been sent to the daemon.
    txn.rollback();
    return;
}

try {
    txn.commit(null);
} catch (ConflictException e) {
    // On conflict the server already rolled back. No client-side cleanup of
    // server data is needed.
    System.err.println("conflict: " + e.getMessage());
}
```

`rollback` and `commit` both throw `IllegalStateException` if the transaction
was already committed or rolled back. Treat that as a programming error to
fix upstream, not a runtime condition to silence.

### Recovering from a failed batch

Because a failed commit rejects the whole batch, the usual recovery is to
re-issue the ops that are still valid, optionally splitting out the offender.
Keep your own list of the logical ops if you need surgical retry, since
`Transaction` does not expose its staged ops.

## Summary

| Goal | Use |
|------|-----|
| One independent write | `MongrelDB.put` / `upsert` / `delete` / `deleteByPK` |
| Several writes that must commit together | `MongrelDB.begin` + `Transaction.commit` |
| Retry safely after a network blip | `commit(idempotencyKey)` with a stable key |
| Distinguish constraint classes | catch `ConflictException`, read `.code()` / `.opIndex()` |
| Abort before sending | `Transaction.rollback()` |

See [errors.md](errors.md) for the full exception hierarchy and [queries.md](queries.md)
for read patterns.
