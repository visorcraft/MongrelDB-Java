package com.example;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.visorcraft.mongreldb.MongrelDB;

/**
 * Example: query builder conditions with the MongrelDB Java client.
 *
 * <p>Compile and run from the {@code examples} directory:
 *
 * <pre>{@code
 * cd examples
 * mvn compile exec:java -Dexec.mainClass="com.example.QueryBuilderExample"
 * }</pre>
 *
 * <p>Requires a mongreldb-server daemon running on
 * {@code http://127.0.0.1:8453}.
 *
 * <p>Creates a table, inserts five rows with varying scores, then uses the
 * native query builder to fetch rows by a range condition and by an exact
 * primary-key match. Cleans up by dropping the table.
 */
public class QueryBuilderExample {

    private static final String URL = "http://127.0.0.1:8453";
    private static final String TABLE = "example_query";

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

        // Five rows with varying scores.
        db.put(TABLE, cells(1L, 1L, 2L, "Alice", 3L, 40.0), null);
        db.put(TABLE, cells(1L, 2L, 2L, "Bob", 3L, 65.0), null);
        db.put(TABLE, cells(1L, 3L, 2L, "Carol", 3L, 82.0), null);
        db.put(TABLE, cells(1L, 4L, 2L, "Dave", 3L, 91.0), null);
        db.put(TABLE, cells(1L, 5L, 2L, "Eve", 3L, 12.5), null);
        System.out.println("Inserted 5 rows");

        // Range condition: scores in [60.0, 90.0]. "column" maps to column_id,
        // so pass the numeric column id (3L), not the name. Use range_f64
        // because the score column is float64 (plain range expects i64).
        List<Map<String, Object>> rng = db.query(TABLE)
                .where("range_f64", Map.of(
                        "column", 3L,
                        "min", 60.0, "max", 90.0,
                        "min_inclusive", true, "max_inclusive", true))
                .execute();
        System.out.printf("Range query (score in [60,90]) returned %d rows:%n", rng.size());
        for (Map<String, Object> row : rng) {
            System.out.printf("  %s%n", row);
        }

        // Primary-key condition: fetch the single row with id == 4.
        List<Map<String, Object>> pk = db.query(TABLE)
                .where("pk", Map.of("value", 4L))
                .execute();
        System.out.printf("PK query (id == 4) returned %d rows:%n", pk.size());
        for (Map<String, Object> row : pk) {
            System.out.printf("  %s%n", row);
        }

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
