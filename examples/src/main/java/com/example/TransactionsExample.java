package com.example;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.visorcraft.mongreldb.MongrelDB;
import dev.visorcraft.mongreldb.Transaction;

/**
 * Example: atomic batch transactions with the MongrelDB Java client.
 *
 * <p>Compile and run from the {@code examples} directory:
 *
 * <pre>{@code
 * cd examples
 * mvn compile exec:java -Dexec.mainClass="com.example.TransactionsExample"
 * }</pre>
 *
 * <p>Requires a mongreldb-server daemon running on
 * {@code http://127.0.0.1:8453}.
 *
 * <p>Creates a table, stages three inserts in a single transaction, commits
 * them atomically, verifies the count, then demonstrates idempotent retries by
 * re-committing with the same idempotency key (the daemon returns the original
 * result and applies no duplicate rows). Cleans up by dropping the table.
 */
public class TransactionsExample {

    private static final String URL = "http://127.0.0.1:8453";
    private static final String TABLE = "example_txn";

    public static void main(String[] args) {
        MongrelDB db = new MongrelDB(URL);
        if (!db.health()) {
            System.err.println("daemon not reachable at " + URL);
            System.exit(1);
        }
        System.out.println("Connected to MongrelDB");

        db.createTable(TABLE, java.util.List.of(
                column(1L, "id", "int64", true),
                column(2L, "name", "varchar", false),
                column(3L, "score", "float64", false)));
        System.out.printf("Created table %s%n", TABLE);

        // Stage three puts and commit them atomically. Either every op lands
        // or none do; a constraint violation rolls back the whole batch.
        Transaction txn = db.begin();
        txn.put(TABLE, cells(1L, 1L, 2L, "Alice", 3L, 95.5), false);
        txn.put(TABLE, cells(1L, 2L, 2L, "Bob", 3L, 82.0), false);
        txn.put(TABLE, cells(1L, 3L, 2L, "Carol", 3L, 78.3), false);
        System.out.printf("Staged %d operations%n", txn.count());

        List<Map<String, Object>> results = txn.commit(null);
        System.out.printf("Committed atomically: %d operations applied%n", results.size());

        System.out.printf("Verified row count after commit: %d%n", db.count(TABLE));

        // Idempotent retry: stage the same batch again with an idempotency key,
        // then commit a second time with the SAME key. The daemon replays the
        // original result and applies no extra rows.
        Transaction retry = db.begin();
        retry.put(TABLE, cells(1L, 4L, 2L, "Dave", 3L, 60.0), false);
        retry.commit("example-txn-key");
        System.out.printf("After first idempotent commit: %d rows%n", db.count(TABLE));

        Transaction retry2 = db.begin();
        retry2.put(TABLE, cells(1L, 4L, 2L, "Dave", 3L, 60.0), false);
        retry2.commit("example-txn-key");
        System.out.printf("After duplicate idempotent commit (same key): %d rows (no double-apply)%n",
                db.count(TABLE));

        db.dropTable(TABLE);
        System.out.printf("Dropped table %s%n", TABLE);
    }

    private static Map<String, Object> column(long id, String name, String ty, boolean pk) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", id);
        c.put("name", name);
        c.put("ty", ty);
        c.put("primary_key", pk);
        c.put("nullable", false);
        return c;
    }

    private static Map<Long, Object> cells(Object... kv) {
        Map<Long, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(((Number) kv[i]).longValue(), kv[i + 1]);
        }
        return m;
    }
}
