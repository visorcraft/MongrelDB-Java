package dev.visorcraft.mongreldb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Live integration tests for the MongrelDB Java client.
 *
 * <p>These tests boot a real {@code mongreldb-server} daemon and exercise the
 * full client surface against it. They resolve the daemon binary in this order:
 * <ol>
 *   <li>the {@code MONGRELDB_SERVER} env var (path to the server binary)
 *   <li>a prebuilt binary at {@code ./bin/mongreldb-server}
 *   <li>{@code mongreldb-server} on {@code PATH}
 * </ol>
 *
 * <p>If no binary is available, the suite is skipped. Set {@code MONGRELDB_URL}
 * to point at an already-running daemon to skip the boot and connect directly.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MongrelDBLiveTest {

    private static final Logger LOG = Logger.getLogger(MongrelDBLiveTest.class.getName());

    private MongrelDB db;
    private Process serverProcess;
    private Path dataDir;
    private Path logFile;
    private boolean externalDaemon;

    @BeforeAll
    void bootDaemon() throws Exception {
        String existing = env("MONGRELDB_URL");
        if (!existing.isEmpty()) {
            // If a daemon is already running, connect to it directly.
            db = new MongrelDB(existing, env("MONGRELDB_TOKEN"), null, null);
            if (db.health()) {
                externalDaemon = true;
                LOG.info("Using existing daemon at " + existing);
                return;
            }
            fail("MONGRELDB_URL=" + existing + " is not reachable");
        }

        String bin = resolveServerBinary();
        if (bin == null) {
            // No daemon available: skip the entire suite. The offline test below
            // (TestOfflineHealthSkipsCleanly) still runs so the suite isn't
            // reported as "no tests".
            LOG.info("No mongreldb-server binary available; live tests skipped");
            return;
        }

        int port = freePort();
        dataDir = Files.createTempDirectory("mongreldb-java-test-");
        logFile = Files.createTempFile("mongreldb-java-server-", ".log");

        ProcessBuilder pb = new ProcessBuilder(bin, dataDir.toString(), "--port", String.valueOf(port))
                .redirectOutput(logFile.toFile())
                .redirectErrorStream(true);
        serverProcess = pb.start();

        String url = "http://127.0.0.1:" + port;
        if (!waitForHealth(url, 40)) {
            String log = readLog();
            destroyProcess();
            fail("mongreldb-server did not become healthy. Log:\n" + log);
        }
        db = new MongrelDB(url);
        LOG.info("Booted mongreldb-server on " + url);
    }

    @AfterAll
    void tearDown() {
        if (serverProcess != null) {
            destroyProcess();
        }
        if (dataDir != null) {
            try {
                deleteRecursively(dataDir);
            } catch (IOException e) {
                LOG.warning("Could not delete data dir: " + e.getMessage());
            }
        }
        if (logFile != null) {
            try {
                Files.deleteIfExists(logFile);
            } catch (IOException ignored) {
                // best-effort
            }
        }
    }

    /** Skip every live test when no daemon was booted. */
    private void requireDaemon() {
        assumeTrue(db != null, "no mongreldb-server available");
    }

    @Test
    @Order(1)
    @DisplayName("health() reports the daemon as healthy")
    void testHealth() {
        requireDaemon();
        assertTrue(db.health(), "expected healthy daemon");
    }

    @Test
    @Order(2)
    @DisplayName("createTable + count round-trip")
    void testCreateTableAndCount() {
        requireDaemon();
        String name = uniqueTable("java_tbl");
        freshTable(name, intCol(1, "id", true), floatCol(2, "amount"));

        assertEquals(0L, db.count(name), "expected 0 rows");
    }

    @Test
    @Order(3)
    @DisplayName("put + count round-trip")
    void testPutAndCountRoundTrip() {
        requireDaemon();
        String name = uniqueTable("java_put");
        freshTable(name, intCol(1, "id", true), floatCol(2, "amount"));

        db.put(name, cells(1L, 1L, 2L, 99.5), null);
        db.put(name, cells(1L, 2L, 2L, 150.0), null);

        assertEquals(2L, db.count(name), "expected 2 rows");
    }

    @Test
    @Order(4)
    @DisplayName("query by primary key")
    void testQueryByPK() {
        requireDaemon();
        String name = uniqueTable("java_pk");
        freshTable(name, intCol(1, "id", true));

        db.put(name, cells(1L, 42L), null);
        db.put(name, cells(1L, 43L), null);

        List<Map<String, Object>> rows = db.query(name)
                .where("pk", Map.of("value", 42L))
                .execute();
        assertEquals(1, rows.size(), "expected exactly 1 row");
        // The returned row must carry the queried PK value.
        assertEquals(42L, cellLong(rows.get(0), 1L), "expected pk 42");
    }

    @Test
    @Order(5)
    @DisplayName("query with a range condition using friendly aliases")
    void testQueryRange() {
        requireDaemon();
        String name = uniqueTable("java_range");
        freshTable(name, intCol(1, "id", true), intCol(2, "amount", false));

        db.put(name, cells(1L, 1L, 2L, 50L), null);
        db.put(name, cells(1L, 2L, 2L, 120L), null);
        db.put(name, cells(1L, 3L, 2L, 200L), null);

        // Range predicate using friendly aliases (column/min/max -> column_id/lo/hi).
        QueryBuilder q = db.query(name)
                .where("range", Map.of("column", 2L, "min", 100L, "max", 150L));
        List<Map<String, Object>> rows = q.execute();
        // Only the row with amount=120 (pk=2) falls in [100, 150].
        assertEquals(1, rows.size(), "range query should return exactly the matching row");
        assertFalse(q.truncated(), "result should not be truncated");
        // Verify the PK and amount values of returned rows match the filter range.
        for (Map<String, Object> row : rows) {
            assertEquals(2L, cellLong(row, 1L), "expected returned pk 2");
            long amt = cellLong(row, 2L);
            assertTrue(amt >= 100 && amt <= 150, "returned amount " + amt + " outside range [100,150]");
        }
    }

    @Test
    @Order(6)
    @DisplayName("batch transaction: put + commit")
    void testTransactionPutCommit() {
        requireDaemon();
        String name = uniqueTable("java_txn");
        freshTable(name, intCol(1, "id", true));

        Transaction txn = db.begin();
        txn.put(name, cells(1L, 1L), false);
        txn.put(name, cells(1L, 2L), false);
        txn.put(name, cells(1L, 3L), false);
        assertEquals(3, txn.count(), "expected 3 staged ops");

        List<Map<String, Object>> results = txn.commit(null);
        assertEquals(3, results.size(), "expected 3 results");
        assertEquals(3L, db.count(name), "expected 3 rows after commit");
    }

    @Test
    @Order(7)
    @DisplayName("transaction rollback discards staged ops")
    void testTransactionRollback() {
        requireDaemon();
        String name = uniqueTable("java_rb");
        freshTable(name, intCol(1, "id", true));

        Transaction txn = db.begin();
        txn.put(name, cells(1L, 1L), false);
        assertEquals(1, txn.count());
        txn.rollback();
        assertEquals(0L, db.count(name), "rollback should leave the table empty");
    }

    @Test
    @Order(8)
    @DisplayName("deleteByPK removes a row")
    void testDeleteByPK() {
        requireDaemon();
        String name = uniqueTable("java_del");
        freshTable(name, intCol(1, "id", true));

        db.put(name, cells(1L, 5L), null);
        assertEquals(1L, db.count(name));

        db.deleteByPK(name, 5L);
        assertEquals(0L, db.count(name), "expected 0 rows after delete");
    }

    @Test
    @Order(9)
    @DisplayName("sql INSERT increases count and SELECT returns the row")
    void testSQL() {
        requireDaemon();
        String name = uniqueTable("java_sql");
        freshTable(name, intCol(1, "id", true), intCol(2, "amount", false));

        assertEquals(0L, db.count(name), "expected 0 rows");
        // INSERT via SQL must increase the row count.
        db.sql("INSERT INTO " + name + " (id, amount) VALUES (10, 42)");
        assertEquals(1L, db.count(name), "count must increase after INSERT");

        // JSON SQL mode must return the inserted row.
        List<Map<String, Object>> rows = db.sql("SELECT id, amount FROM " + name);
        assertEquals(1, rows.size(), "expected 1 row from JSON SELECT");
        assertEquals(10L, ((Number) rows.get(0).get("id")).longValue(), "expected id 10");
    }

    @Test
    @Order(10)
    @DisplayName("schema lists the created table")
    void testSchema() {
        requireDaemon();
        String name = uniqueTable("java_schema");
        freshTable(name, intCol(1, "id", true), floatCol(2, "amount"));

        Map<String, Map<String, Object>> schema = db.schema();
        assertTrue(schema.containsKey(name), "schema catalog missing table " + name);
    }

    @Test
    @Order(11)
    @DisplayName("schemaFor returns a single-table descriptor")
    void testSchemaFor() {
        requireDaemon();
        String name = uniqueTable("java_schema_for");
        freshTable(name, intCol(1, "id", true), floatCol(2, "amount"));

        Map<String, Object> desc = db.schemaFor(name);
        assertNotNull(desc.get("schema_id"), "descriptor missing schema_id; got " + desc);
        Object cols = desc.get("columns");
        assertTrue(cols instanceof List<?>, "columns should be a list");
        assertEquals(2, ((List<?>) cols).size(), "expected 2 columns");
    }

    @Test
    @Order(12)
    @DisplayName("tableNames lists a created table")
    void testTableNamesListsCreatedTable() {
        requireDaemon();
        String name = uniqueTable("java_tables");
        freshTable(name, intCol(1, "id", true));

        List<String> names = db.tableNames();
        assertTrue(names.contains(name), "table list " + names + " missing " + name);
    }

    @Test
    @Order(13)
    @DisplayName("schemaFor on a nonexistent table throws NotFoundException")
    void testErrorOnNonexistentTable() {
        requireDaemon();
        String name = uniqueTable("java_missing");
        try {
            db.schemaFor(name);
            fail("expected NotFoundException for nonexistent table");
        } catch (NotFoundException e) {
            assertEquals(404, e.status(), "expected status 404");
        }
    }

    @Test
    @Order(14)
    @DisplayName("error carries the HTTP status code")
    void testErrorTypeCarriesStatus() {
        requireDaemon();
        String name = uniqueTable("java_missing2");
        try {
            db.schemaFor(name);
            fail("expected an error");
        } catch (MongrelDBException e) {
            assertEquals(404, e.status(), "expected status 404");
            assertTrue(e instanceof NotFoundException, "expected NotFoundException, got " + e.getClass().getSimpleName());
        }
    }

    @Test
    @Order(15)
    @DisplayName("upsert updates on a primary-key conflict")
    void testUpsertOnConflict() {
        requireDaemon();
        String name = uniqueTable("java_upsert");
        freshTable(name, intCol(1, "id", true), intCol(2, "amount", false));

        db.put(name, cells(1L, 1L, 2L, 50L), null);
        // Upsert the same PK with an update_cells that rewrites amount.
        db.upsert(name, cells(1L, 1L, 2L, 50L), cells(2L, 999L), null);
        assertEquals(1L, db.count(name), "upsert should not add a second row");

        // The updated value should be visible via a PK query.
        List<Map<String, Object>> rows = db.query(name)
                .where("pk", Map.of("value", 1L))
                .execute();
        assertEquals(1, rows.size(), "expected the upserted row");
        // Assert the updated cell value and the PK.
        assertEquals(1L, cellLong(rows.get(0), 1L), "expected pk 1");
        assertEquals(999L, cellLong(rows.get(0), 2L), "expected updated amount 999");
    }

    @Test
    @Order(16)
    @DisplayName("idempotent put returns the same result on retry")
    void testIdempotentPut() {
        requireDaemon();
        String name = uniqueTable("java_idem");
        freshTable(name, intCol(1, "id", true));

        String key = "idem-" + name;
        Map<String, Object> first = db.put(name, cells(1L, 7L), key);
        Map<String, Object> second = db.put(name, cells(1L, 7L), key);
        // The daemon returns the original response on duplicate commits. The
        // row count must remain 1 either way.
        assertEquals(1L, db.count(name), "idempotent put should not duplicate the row");
        assertNotNull(first, "first result should not be null");
        assertNotNull(second, "second result should not be null");
    }

    @Test
    @Order(17)
    @DisplayName("compact and compactTable run without error")
    void testCompact() {
        requireDaemon();
        String name = uniqueTable("java_compact");
        freshTable(name, intCol(1, "id", true));
        db.put(name, cells(1L, 1L), null);

        // Both compaction endpoints should succeed without throwing.
        assertNotNull(db.compact());
        assertNotNull(db.compactTable(name));
    }

    @Test
    @Order(18)
    @DisplayName("dropTable removes a table")
    void testDropTable() {
        requireDaemon();
        String name = uniqueTable("java_drop");
        freshTable(name, intCol(1, "id", true));
        assertTrue(db.tableNames().contains(name), "table should exist before drop");

        db.dropTable(name);
        assertFalse(db.tableNames().contains(name), "table should be gone after drop");
    }

    @Test
    @Order(19)
    @DisplayName("history retention window preserves older epoch reads")
    void testHistoryRetention() {
        requireDaemon();

        db.setHistoryRetentionEpochs(10_000L);
        assertEquals(10_000L, db.historyRetentionEpochs(),
                "retention window should be updated");

        String name = uniqueTable("java_retention");
        freshTable(name, intCol(1, "id", true), intCol(2, "value", false));

        db.put(name, cells(1L, 1L, 2L, 10L), null);
        long writeEpoch = db.lastCommitEpoch();
        assertTrue(writeEpoch > 0L, "commit should report a positive epoch");

        db.upsert(name, cells(1L, 1L, 2L, 10L), cells(2L, 20L), null);

        List<Map<String, Object>> oldRows = db.sql(
                "SELECT value FROM " + name + " AS OF EPOCH " + writeEpoch + " WHERE id = 1");
        assertEquals(1, oldRows.size(), "historical read should return the row");
        assertEquals(10L, ((Number) oldRows.get(0).get("value")).longValue(),
                "older epoch should see the pre-update value");

        List<Map<String, Object>> curRows = db.sql(
                "SELECT value FROM " + name + " WHERE id = 1");
        assertEquals(1, curRows.size(), "current read should return the row");
        assertEquals(20L, ((Number) curRows.get(0).get("value")).longValue(),
                "current read should see the updated value");

        assertTrue(db.earliestRetainedEpoch() <= writeEpoch,
                "the write epoch should still be retained");
    }

    /**
     * A standalone sanity test that always runs (no daemon needed): a client
     * constructed with no reachable server reports {@code health() == false}
     * rather than throwing.
     */
    @Test
    @Order(20)
    @DisplayName("health() returns false when the daemon is unreachable (offline)")
    void testHealthReturnsFalseWhenUnreachable() {
        MongrelDB unreachable = new MongrelDB("http://127.0.0.1:1");
        assertFalse(unreachable.health(), "health should be false for an unreachable daemon");
    }
    /**
     * A standalone test (no daemon needed): a client constructed with a token
     * attaches a Bearer header. Verified against an in-process server.
     */
    @Test
    @Order(21)
    @DisplayName("bearer-token auth header is attached (offline, in-process server)")
    void testAuthOptionIsApplied() throws Exception {
        AtomicReference<String> lastAuth = new AtomicReference<>();
        com.sun.net.httpserver.HttpServer srv = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/health", exchange -> {
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] resp = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (var os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });
        srv.start();
        try {
            int port = srv.getAddress().getPort();
            MongrelDB c = new MongrelDB("http://127.0.0.1:" + port, "super-secret", null, null);
            assertTrue(c.health(), "expected healthy");
            assertEquals("Bearer super-secret", lastAuth.get(),
                    "expected Bearer auth header, got " + lastAuth.get());
        } finally {
            srv.stop(0);
        }
    }

    /**
     * Exercises the retention-floor lifecycle that the AS-of read test does not:
     * shrinking the window must prune old epochs (the floor advances), and
     * re-expanding must NOT bring the pruned history back (the floor never
     * retreats).
     */
    @Test
    @Order(22)
    @DisplayName("shrinking retention prunes old epochs and re-expanding does not restore them")
    void testHistoryRetentionShrinkAdvancesFloorAndDoesNotRestore() {
        requireDaemon();

        long initial = db.historyRetentionEpochs();
        try {
            // 1. Wide window so writes are retained well below the current epoch.
            db.setHistoryRetentionEpochs(10_000L);
            long wideFloor = db.earliestRetainedEpoch();

            String name = uniqueTable("java_shrink");
            freshTable(name, intCol(1, "id", true), intCol(2, "value", false));
            db.put(name, cells(1L, 1L, 2L, 10L), null);
            // Advance the epoch clock with a few writes so the narrow floor
            // lands well above the wide floor.
            for (int i = 0; i < 3; i++) {
                db.put(name, cells(1L, (long) (100 + i), 2L, (long) i), null);
            }

            // 2. Shrink to a narrow window. The floor must advance (old epochs pruned).
            db.setHistoryRetentionEpochs(1L);
            long narrowFloor = db.earliestRetainedEpoch();
            assertTrue(narrowFloor >= wideFloor,
                    "narrow floor " + narrowFloor + " below wide floor " + wideFloor
                            + " (floor should advance on shrink)");

            // 3. Re-expand to the wide window. Pruned history must NOT come back:
            // the floor cannot retreat below the narrow-window floor.
            db.setHistoryRetentionEpochs(10_000L);
            long reexpandedFloor = db.earliestRetainedEpoch();
            assertTrue(reexpandedFloor >= narrowFloor,
                    "re-expanded floor " + reexpandedFloor + " retreated below narrow floor "
                            + narrowFloor + " (pruned history was restored)");
        } finally {
            db.setHistoryRetentionEpochs(initial);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private Map<Long, Object> cells(Object... kv) {
        Map<Long, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(((Number) kv[i]).longValue(), kv[i + 1]);
        }
        return m;
    }

    /**
     * Extracts a long value for {@code colID} from a Kit row's flat cells array
     * (shape: {@code [col_id, value, ...]}).
     */
    private static long cellLong(Map<String, Object> row, long colID) {
        Object cellsObj = row.get("cells");
        if (cellsObj instanceof List<?>) {
            List<?> cells = (List<?>) cellsObj;
            for (int i = 0; i + 1 < cells.size(); i += 2) {
                Object id = cells.get(i);
                if (id instanceof Number && ((Number) id).longValue() == colID) {
                    Object v = cells.get(i + 1);
                    if (v instanceof Number) {
                        return ((Number) v).longValue();
                    }
                    fail("cell " + colID + " value not numeric: " + v);
                }
            }
        }
        fail("cell " + colID + " not found in row " + row);
        return 0L;
    }

    private Map<String, Object> intCol(long id, String name, boolean primaryKey) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", id);
        c.put("name", name);
        c.put("ty", "int64");
        c.put("primary_key", primaryKey);
        c.put("nullable", false);
        return c;
    }

    private Map<String, Object> floatCol(long id, String name) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("id", id);
        c.put("name", name);
        c.put("ty", "float64");
        c.put("primary_key", false);
        c.put("nullable", false);
        return c;
    }

    /**
     * freshTable drops {@code name} if present then creates it with the given
     * columns. A missing table on drop is the expected pre-condition and is
     * ignored (the daemon returns a 5xx for a missing table, so we catch any
     * client exception here).
     */
    @SafeVarargs
    private void freshTable(String name, Map<String, Object>... columns) {
        try {
            db.dropTable(name); // ignore "not found"
        } catch (MongrelDBException ignored) {
            // expected when the table doesn't exist yet
        }
        List<Map<String, Object>> cols = new ArrayList<>(List.of(columns));
        db.createTable(name, cols);
    }

    private String uniqueTable(String prefix) {
        return prefix + "_" + Long.toHexString(System.nanoTime());
    }

    private static String env(String name) {
        String v = System.getenv(name);
        return v == null ? "" : v;
    }

    /** Finds the daemon binary, or returns null to skip the live suite. */
    private static String resolveServerBinary() {
        String env = env("MONGRELDB_SERVER");
        if (!env.isEmpty()) {
            Path p = Paths.get(env);
            if (Files.isExecutable(p)) {
                return p.toAbsolutePath().toString();
            }
            LOG.warning("MONGRELDB_SERVER=" + env + " not found or not executable (live tests skipped)");
            return null;
        }
        Path local = Paths.get("bin", "mongreldb-server");
        if (Files.isExecutable(local)) {
            return local.toAbsolutePath().toString();
        }
        for (String dir : System.getenv("PATH").split(":")) {
            Path p = Paths.get(dir, "mongreldb-server");
            if (Files.isExecutable(p)) {
                return p.toAbsolutePath().toString();
            }
        }
        return null;
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static boolean waitForHealth(String url, int maxSeconds) {
        MongrelDB probe = new MongrelDB(url);
        long deadline = System.currentTimeMillis() + maxSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (probe.health()) {
                return true;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private void destroyProcess() {
        if (serverProcess == null) {
            return;
        }
        serverProcess.destroy();
        try {
            if (!serverProcess.waitFor(5, TimeUnit.SECONDS)) {
                serverProcess.destroyForcibly();
            }
            serverProcess.waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String readLog() {
        try {
            return Files.readString(logFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(could not read log: " + e.getMessage() + ")";
        }
    }

    private static void deleteRecursively(Path p) throws IOException {
        if (Files.isDirectory(p)) {
            try (var stream = Files.newDirectoryStream(p)) {
                for (Path child : stream) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(p);
    }
}
