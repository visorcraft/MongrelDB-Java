package com.example;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.visorcraft.mongreldb.MongrelDB;

/**
 * Example: basic CRUD operations with the MongrelDB Java client.
 *
 * <p>Compile and run from the {@code examples} directory against the client on
 * the classpath. With Maven against an installed {@code mongreldb-java}:
 *
 * <pre>{@code
 * cd examples
 * mvn compile exec:java -Dexec.mainClass="com.example.BasicCrud"
 * }</pre>
 *
 * <p>Requires a mongreldb-server daemon running on
 * {@code http://127.0.0.1:8453}.
 *
 * <p>Creates a table, inserts three rows, counts them, queries all rows,
 * upserts (updates) one row by primary key, deletes one row, then demonstrates
 * enum and default-value column specs, then drops the table. Progress is
 * printed at every step.
 */
public class BasicCrud {

    private static final String URL = "http://127.0.0.1:8453";

    public static void main(String[] args) {
        // 1. Connect (the default constructor targets http://127.0.0.1:8453).
        MongrelDB db = new MongrelDB(URL);

        // Unique name per run so re-running the example never collides with a
        // leftover table from a previous (possibly failed) run.
        String table = "example_crud_" + System.currentTimeMillis();

        try {
        // 2. Health check; bail out if the daemon is unreachable.
        if (!db.health()) {
            System.err.println("daemon not reachable at " + URL);
            System.exit(1);
        }
        System.out.println("Connected to MongrelDB");

        // 3. Create the table. Schema: id (int64 PK), name (varchar), score (float64).
        long tableId = db.createTable(table, List.of(
                column(1L, "id", "int64", true),
                column(2L, "name", "varchar", false),
                column(3L, "score", "float64", false)));
        System.out.printf("Created table %s (id %d)%n", table, tableId);

        // 4. Insert three rows. Cells map column id (Long) -> value.
        db.put(table, cells(1L, 1L, 2L, "Alice", 3L, 95.5), null);
        db.put(table, cells(1L, 2L, 2L, "Bob", 3L, 82.0), null);
        db.put(table, cells(1L, 3L, 2L, "Carol", 3L, 78.3), null);
        System.out.println("Inserted 3 rows");

        System.out.printf("Total rows: %d%n", db.count(table));

        // 5. Query all rows (no conditions).
        List<Map<String, Object>> all = db.query(table).execute();
        System.out.printf("Query returned %d rows:%n", all.size());
        for (Map<String, Object> row : all) {
            System.out.printf("  %s%n", row);
        }

        // 6. Upsert (update) Alice's score. updateCells supplies the values
        //    written on a primary-key conflict.
        db.upsert(table,
                cells(1L, 1L, 2L, "Alice", 3L, 100.0),
                cells(2L, "Alice", 3L, 100.0), null);
        System.out.println("Upserted Alice's score to 100.0");
        System.out.printf("Total rows after upsert: %d%n", db.count(table));

        // 7. Delete Carol (primary key 3).
        db.deleteByPK(table, 3L);
        System.out.printf("Deleted Carol; remaining rows: %d%n", db.count(table));

        // 8. Enum + default value. createTable forwards every column-spec key
        //    the caller puts in the Map to /kit/create_table. The engine
        //    recognises `enum_variants` (required when ty is "enum") and
        //    `default_value` (per-column default discriminator, e.g. "now").
        String enumTable = "example_enum_" + System.currentTimeMillis();
        try {
            Map<String, Object> role = new LinkedHashMap<>();
            role.put("id", 2L);
            role.put("name", "role");
            role.put("ty", "enum");
            role.put("primary_key", false);
            role.put("nullable", false);
            role.put("enum_variants", List.of("admin", "user", "guest"));
            role.put("default_value", "guest");

            Map<String, Object> createdAt = new LinkedHashMap<>();
            createdAt.put("id", 3L);
            createdAt.put("name", "created_at");
            createdAt.put("ty", "timestamp_nanos");
            createdAt.put("primary_key", false);
            createdAt.put("nullable", false);
            createdAt.put("default_expr", "now");

            db.createTable(enumTable, List.of(
                    Map.of("id", 1L, "name", "id", "ty", "int64",
                            "primary_key", true, "nullable", false),
                    role,
                    createdAt));
            System.out.printf("Created enum table %s%n", enumTable);
        } finally {
            // Always clean up, even if something above threw.
            db.dropTable(enumTable);
            System.out.printf("Dropped table %s%n", enumTable);
        }
        } finally {
            // Always clean up, even if something above threw.
            db.dropTable(table);
            System.out.printf("Dropped table %s%n", table);
        }
    }

    /** Builds a column descriptor Map for createTable. */
    private static Map<String, Object> column(long id, String name, String ty, boolean pk) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", id);
        c.put("name", name);
        c.put("ty", ty);
        c.put("primary_key", pk);
        c.put("nullable", false);
        return c;
    }

    /** Builds a column-id -> value cells map from alternating id/value pairs. */
    private static Map<Long, Object> cells(Object... kv) {
        Map<Long, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(((Number) kv[i]).longValue(), kv[i + 1]);
        }
        return m;
    }
}
