package dev.visorcraft.mongreldb;

import dev.visorcraft.mongreldb.native_mode.NativeDB;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the native embedded mode (libmongreldb_jni via JNI).
 *
 * <p>These tests self-skip when the native library cannot be loaded (the
 * {@code libmongreldb_jni} shared library is not on the system path or
 * {@code MONGRELDB_NATIVE_DIR}).</p>
 */
public class NativeDBTest {

    private static final String SCHEMA_JSON = """
        {
            "tables": [{
                "id": 1,
                "name": "users",
                "columns": [
                    {"id":1,"name":"id","storage_type":"int64","application_type":"int64","nullable":false,"primary_key":true,"default":null,"generated":false},
                    {"id":2,"name":"name","storage_type":"text","application_type":"text","nullable":true,"primary_key":false,"default":null,"generated":false}
                ],
                "primary_key": ["id"]
            }]
        }
        """;

    private static boolean nativeAvailable() {
        try {
            NativeDB.class.getName(); // triggers static initializer
            return true;
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    @Test
    void createAndSqlInsertSelect(@TempDir Path tempDir) {
        Assumptions.assumeTrue(nativeAvailable(), "native library not available");

        try (NativeDB db = NativeDB.create(tempDir.toString(), SCHEMA_JSON)) {
            // Insert via SQL.
            db.sqlRows("INSERT INTO users (id, name) VALUES (1, 'alice')");

            // SELECT via SQL.
            String rows = db.sqlRows("SELECT id, name FROM users");
            assertTrue(rows.contains("alice"), "rows should contain 'alice': " + rows);
            assertTrue(rows.contains("\"id\""), "rows should contain id column: " + rows);
        }
    }

    @Test
    void sqlArrowReturnsArrowMagic(@TempDir Path tempDir) {
        Assumptions.assumeTrue(nativeAvailable(), "native library not available");

        try (NativeDB db = NativeDB.create(tempDir.toString(), SCHEMA_JSON)) {
            db.sqlRows("INSERT INTO users (id, name) VALUES (1, 'bob')");

            byte[] arrow = db.sqlArrow("SELECT id FROM users");
            assertTrue(arrow.length >= 6, "Arrow IPC should be at least 6 bytes");
            assertEquals("ARROW1", new String(arrow, 0, 6),
                "should start with ARROW1 magic");
        }
    }

    @Test
    void migrateCreatesTableAndReadsBack(@TempDir Path tempDir) {
        Assumptions.assumeTrue(nativeAvailable(), "native library not available");

        try (NativeDB db = NativeDB.create(tempDir.toString(), SCHEMA_JSON)) {
            String migrations = """
                [{
                    "version": 1,
                    "name": "add_orders",
                    "ops": [{"raw_sql": "CREATE TABLE orders (id INT64 PRIMARY KEY, total FLOAT64)"}]
                }]
                """;
            db.migrate(migrations);

            // Insert into the migrated table.
            db.sqlRows("INSERT INTO orders (id, total) VALUES (1, 99.99)");

            // Read back applied migrations.
            String applied = db.appliedMigrations();
            assertTrue(applied.contains("add_orders"), "should contain 'add_orders': " + applied);
        }
    }

    @Test
    void querySelectReturnsRows(@TempDir Path tempDir) {
        Assumptions.assumeTrue(nativeAvailable(), "native library not available");

        try (NativeDB db = NativeDB.create(tempDir.toString(), SCHEMA_JSON)) {
            db.sqlRows("INSERT INTO users (id, name) VALUES (1, 'carol')");

            String selectJson = """
                {"table":"users","columns":[],"filter":null,"order_by":[],"limit":null,"offset":null}
                """;
            String result = db.querySelect(selectJson);
            assertTrue(result.contains("carol"), "should contain 'carol': " + result);
        }
    }
}
