package com.example;

import dev.visorcraft.mongreldb.native_mode.NativeDB;

/**
 * Example: basic CRUD with the native embedded MongrelDB engine (Tier 1).
 *
 * <p>Unlike {@link BasicCrud} which connects to a daemon over HTTP, this
 * example runs the engine in-process via JNI. No daemon needed.
 *
 * <p>Compile and run:
 * <pre>{@code
 * cd examples
 * # Point at the directory containing libmongreldb_jni.so / .dylib / .dll
 * export MONGRELDB_NATIVE_DIR=/path/to/native/libs
 * mvn compile exec:java -Dexec.mainClass="com.example.NativeBasicCrud"
 * }</pre>
 *
 * <p>Downloads the native library from
 * <a href="https://github.com/visorcraft/MongrelDB/releases">GitHub releases</a>
 * (mongreldb-jni JAR or mongreldb-native archives).
 *
 * <p>Creates a table via schema JSON, inserts rows via SQL, queries them
 * back, runs a migration, and uses the Kit query builder - all in-process.
 */
public class NativeBasicCrud {

    public static void main(String[] args) throws Exception {
        if (!NativeDB.nativeAvailable()) {
            System.err.println("Native library not available.");
            System.err.println("Set MONGRELDB_NATIVE_DIR to the directory containing");
            System.err.println("libmongreldb_jni.so (Linux), .dylib (macOS), or .dll (Windows).");
            System.err.println("Download from https://github.com/visorcraft/MongrelDB/releases");
            System.exit(1);
        }

        String dbDir = System.getProperty("java.io.tmpdir") + "/mdb_native_example_" + System.currentTimeMillis();
        String schemaJson =
            "{\"tables\":[{\"id\":1,\"name\":\"users\"," +
            "\"columns\":[" +
            "{\"id\":1,\"name\":\"id\",\"storage_type\":\"int64\",\"application_type\":\"int64\",\"nullable\":false,\"primary_key\":true,\"default\":null,\"generated\":false}," +
            "{\"id\":2,\"name\":\"name\",\"storage_type\":\"text\",\"application_type\":\"text\",\"nullable\":true,\"primary_key\":false,\"default\":null,\"generated\":false}," +
            "{\"id\":3,\"name\":\"email\",\"storage_type\":\"text\",\"application_type\":\"text\",\"nullable\":true,\"primary_key\":false,\"default\":null,\"generated\":false}" +
            "],\"primary_key\":[\"id\"]}]}";

        System.out.println("=== Native Embedded Basic CRUD ===");
        System.out.println("Database dir: " + dbDir);
        System.out.println();

        try (NativeDB db = NativeDB.create(dbDir, schemaJson)) {
            System.out.println("1. Database created with schema (users table)");

            // Insert rows via SQL.
            db.sqlRows("INSERT INTO users (id, name, email) VALUES (1, 'Alice', 'alice@example.com')");
            db.sqlRows("INSERT INTO users (id, name, email) VALUES (2, 'Bob', 'bob@example.com')");
            db.sqlRows("INSERT INTO users (id, name, email) VALUES (3, 'Carol', 'carol@example.com')");
            System.out.println("2. Inserted 3 rows via SQL");

            // SELECT via SQL (JSON rows).
            String rows = db.sqlRows("SELECT id, name, email FROM users ORDER BY id");
            System.out.println("3. SELECT all rows:");
            System.out.println("   " + rows);

            // Arrow IPC for columnar reads.
            byte[] arrow = db.sqlArrow("SELECT id FROM users");
            System.out.println("4. Arrow IPC: " + arrow.length + " bytes");

            // Migration: add an orders table.
            String migrations =
                "[{\"version\":1,\"name\":\"add_orders\"," +
                "\"ops\":[{\"raw_sql\":\"CREATE TABLE orders (id INT64 PRIMARY KEY, user_id INT64, total FLOAT64)\"}]}]";
            db.migrate(migrations);
            System.out.println("5. Migration: created 'orders' table");

            // Insert into the migrated table.
            db.sqlRows("INSERT INTO orders (id, user_id, total) VALUES (1, 1, 99.99)");
            db.sqlRows("INSERT INTO orders (id, user_id, total) VALUES (2, 2, 49.99)");

            // SQL JOIN across both tables.
            String joinResult = db.sqlRows(
                "SELECT u.name, o.total FROM users u " +
                "JOIN orders o ON u.id = o.user_id ORDER BY o.total DESC");
            System.out.println("6. SQL JOIN (users + orders):");
            System.out.println("   " + joinResult);

            // Kit query builder: SELECT with filter.
            String selectJson =
                "{\"table\":\"users\",\"columns\":[],\"filter\":null,\"order_by\":[],\"limit\":null,\"offset\":null}";
            String queryResult = db.querySelect(selectJson);
            System.out.println("7. Kit query builder SELECT:");
            System.out.println("   " + queryResult);

            // Read back applied migrations.
            String applied = db.appliedMigrations();
            System.out.println("8. Applied migrations: " + applied);

            System.out.println();
            System.out.println("=== All operations completed successfully! ===");
        }
    }
}
