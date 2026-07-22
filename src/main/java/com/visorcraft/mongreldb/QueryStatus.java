package com.visorcraft.mongreldb;

import java.util.Collections;
import java.util.Map;

/** GET /queries/{query_id} decoded status for durable recovery. */
public final class QueryStatus {
    public final String queryId;
    public final String status;
    public final String state;
    public final String serverState;
    public final String terminalState;
    public final Boolean committed;
    public final DurableOutcome outcome;
    public final DurableOutcome durable;
    public final CommitHlc lastCommitHlc;
    public final Map<String, Object> raw;

    public QueryStatus(
            String queryId,
            String status,
            String state,
            String serverState,
            String terminalState,
            Boolean committed,
            DurableOutcome outcome,
            DurableOutcome durable,
            CommitHlc lastCommitHlc,
            Map<String, Object> raw) {
        this.queryId = queryId;
        this.status = status;
        this.state = state;
        this.serverState = serverState;
        this.terminalState = terminalState;
        this.committed = committed;
        this.outcome = outcome;
        this.durable = durable;
        this.lastCommitHlc = lastCommitHlc;
        this.raw = raw == null ? Collections.emptyMap() : raw;
    }

    @SuppressWarnings("unchecked")
    public static QueryStatus fromMap(Map<String, Object> raw) {
        if (raw == null) {
            raw = Collections.emptyMap();
        }
        DurableOutcome outcome = DurableOutcome.fromMap(raw.get("outcome"));
        DurableOutcome durable =
                raw.get("durable") instanceof Map ? DurableOutcome.fromMap(raw.get("durable")) : null;
        return new QueryStatus(
                String.valueOf(raw.getOrDefault("query_id", "")),
                String.valueOf(raw.getOrDefault("status", "")),
                String.valueOf(raw.getOrDefault("state", "")),
                String.valueOf(raw.getOrDefault("server_state", raw.getOrDefault("state", ""))),
                raw.get("terminal_state") == null ? null : String.valueOf(raw.get("terminal_state")),
                raw.containsKey("committed") ? (Boolean) raw.get("committed") : null,
                outcome,
                durable,
                CommitHlc.fromMap(raw.get("last_commit_hlc")),
                raw);
    }

    /** Prefer nested durable / outcome HLC, then top-level. */
    public CommitHlc commitHlc() {
        if (durable != null && durable.lastCommitHlc != null) {
            return durable.lastCommitHlc;
        }
        if (outcome != null && outcome.lastCommitHlc != null) {
            return outcome.lastCommitHlc;
        }
        return lastCommitHlc;
    }

    public String serializationState() {
        if (durable != null) {
            if (durable.serializationState != null && !durable.serializationState.isEmpty()) {
                return durable.serializationState;
            }
            if (durable.serialization != null && !durable.serialization.isEmpty()) {
                return durable.serialization;
            }
        }
        if (outcome != null) {
            if (outcome.serializationState != null && !outcome.serializationState.isEmpty()) {
                return outcome.serializationState;
            }
            return outcome.serialization;
        }
        return "";
    }
}
