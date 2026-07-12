package com.visorcraft.mongreldb;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stages operations locally and commits them atomically in a single
 * {@code /kit/txn} request. The engine enforces unique, foreign-key, check, and
 * trigger constraints at commit time; on any violation all operations roll back
 * and {@link #commit} throws a {@link ConflictException} carrying the server's
 * structured error code and offending op index.
 *
 * <p>A {@code Transaction} is single-use: after {@link #commit} or
 * {@link #rollback} it must not be reused. Calling {@link #commit} or
 * {@link #rollback} a second time throws {@link IllegalStateException}.
 *
 * <p>Start one with {@link MongrelDB#begin()}:
 * <pre>{@code
 * Transaction txn = db.begin();
 * txn.put("orders", Map.of(1L, 10L, 2L, "Dave"), false);
 * txn.put("orders", Map.of(1L, 11L, 2L, "Eve"), false);
 * txn.deleteByPk("orders", 2L);
 * List<Map<String,Object>> results = txn.commit(null); // atomic - all or nothing
 * }</pre>
 */
public final class Transaction {

    /**
     * Thrown when {@link #commit} or {@link #rollback} is called on a
     * transaction that has already been committed or rolled back.
     */
    public static final String ALREADY_COMMITTED = "mongreldb: transaction already committed";

    private final MongrelDB client;
    private final List<Map<String, Object>> ops = new ArrayList<>();
    private boolean committed;

    Transaction(MongrelDB client) {
        this.client = client;
    }

    private void ensureOpen() {
        if (committed) {
            throw new IllegalStateException(ALREADY_COMMITTED);
        }
    }

    /**
     * Stages an insert. {@code returning}, when {@code true}, asks the daemon to
     * echo the row in the per-operation result.
     *
     * @param table     the target table
     * @param cells     a column-id-to-value map
     * @param returning whether to echo the row in the result
     * @return this transaction, for chaining
     */
    public Transaction put(String table, Map<Long, ?> cells, boolean returning) {
        ensureOpen();
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(cells, "cells");
        Map<String, Object> op = new LinkedHashMap<>();
        Map<String, Object> put = new LinkedHashMap<>();
        put.put("table", table);
        put.put("cells", MongrelDB.flattenCells(cells));
        put.put("returning", returning);
        op.put("put", put);
        ops.add(op);
        return this;
    }

    /**
     * Stages an insert-or-update. {@code updateCells}, when non-null, supplies
     * the values written on a primary-key conflict; {@code null} means DO
     * NOTHING.
     *
     * @param table        the target table
     * @param cells        the column-id-to-value map to insert
     * @param updateCells  the values written on conflict, or {@code null}
     * @param returning    whether to echo the row in the result
     * @return this transaction, for chaining
     */
    public Transaction upsert(String table, Map<Long, ?> cells,
                              Map<Long, ?> updateCells, boolean returning) {
        ensureOpen();
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(cells, "cells");
        Map<String, Object> op = new LinkedHashMap<>();
        Map<String, Object> upsert = new LinkedHashMap<>();
        upsert.put("table", table);
        upsert.put("cells", MongrelDB.flattenCells(cells));
        upsert.put("returning", returning);
        if (updateCells != null) {
            upsert.put("update_cells", MongrelDB.flattenCells(updateCells));
        }
        op.put("upsert", upsert);
        ops.add(op);
        return this;
    }

    /**
     * Stages a delete by the internal row id.
     *
     * @param table the target table
     * @param rowId the internal row id
     * @return this transaction, for chaining
     */
    public Transaction delete(String table, long rowId) {
        ensureOpen();
        Objects.requireNonNull(table, "table");
        Map<String, Object> op = new LinkedHashMap<>();
        Map<String, Object> del = new LinkedHashMap<>();
        del.put("table", table);
        del.put("row_id", rowId);
        op.put("delete", del);
        ops.add(op);
        return this;
    }

    /**
     * Stages a delete by primary-key value.
     *
     * @param table the target table
     * @param pk    the primary-key value
     * @return this transaction, for chaining
     */
    public Transaction deleteByPk(String table, Object pk) {
        ensureOpen();
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(pk, "pk");
        Map<String, Object> op = new LinkedHashMap<>();
        Map<String, Object> del = new LinkedHashMap<>();
        del.put("table", table);
        del.put("pk", pk);
        op.put("delete_by_pk", del);
        ops.add(op);
        return this;
    }

    /** @return the number of staged operations */
    public int count() {
        return ops.size();
    }

    /**
     * Sends all staged operations atomically and returns the per-operation
     * results. {@code idempotencyKey}, when non-null and non-empty, makes the
     * commit safe to retry - the daemon returns the original response on
     * duplicate commits, even after a crash.
     *
     * @param idempotencyKey an idempotency key, or {@code null}
     * @return the per-operation results, or an empty list if nothing was staged
     * @throws IllegalStateException if called twice on the same transaction
     * @throws ConflictException     if a constraint violation rolled back the batch
     */
    public List<Map<String, Object>> commit(String idempotencyKey) {
        if (committed) {
            throw new IllegalStateException(ALREADY_COMMITTED);
        }
        committed = true;
        if (ops.isEmpty()) {
            return new ArrayList<>();
        }
        return client.commitTxn(ops, idempotencyKey);
    }

    /**
     * Discards all staged operations.
     *
     * @throws IllegalStateException if the transaction was already committed
     */
    public void rollback() {
        if (committed) {
            throw new IllegalStateException(ALREADY_COMMITTED);
        }
        ops.clear();
        committed = true;
    }
}
