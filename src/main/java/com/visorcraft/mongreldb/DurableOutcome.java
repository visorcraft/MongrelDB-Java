package com.visorcraft.mongreldb;

import java.util.Map;

/** Nested durable recovery payload (server outcome/durable JSON). */
public final class DurableOutcome {
    public final Boolean committed;
    public final Integer committedStatements;
    public final Long lastCommitEpoch;
    public final String lastCommitEpochText;
    public final CommitHlc lastCommitHlc;
    public final Integer firstCommitStatementIndex;
    public final Integer lastCommitStatementIndex;
    public final Integer completedStatements;
    public final Integer statementIndex;
    public final String serialization;
    public final String serializationState;
    public final String terminalState;

    public DurableOutcome(
            Boolean committed,
            Integer committedStatements,
            Long lastCommitEpoch,
            String lastCommitEpochText,
            CommitHlc lastCommitHlc,
            Integer firstCommitStatementIndex,
            Integer lastCommitStatementIndex,
            Integer completedStatements,
            Integer statementIndex,
            String serialization,
            String serializationState,
            String terminalState) {
        this.committed = committed;
        this.committedStatements = committedStatements;
        this.lastCommitEpoch = lastCommitEpoch;
        this.lastCommitEpochText = lastCommitEpochText;
        this.lastCommitHlc = lastCommitHlc;
        this.firstCommitStatementIndex = firstCommitStatementIndex;
        this.lastCommitStatementIndex = lastCommitStatementIndex;
        this.completedStatements = completedStatements;
        this.statementIndex = statementIndex;
        this.serialization = serialization == null ? "" : serialization;
        this.serializationState = serializationState;
        this.terminalState = terminalState;
    }

    public static DurableOutcome fromMap(Object raw) {
        if (!(raw instanceof Map)) {
            return new DurableOutcome(null, null, null, null, null, null, null, null, null, "", null, null);
        }
        Map<?, ?> map = (Map<?, ?>) raw;
        return new DurableOutcome(
                map.containsKey("committed") ? (Boolean) map.get("committed") : null,
                asInt(map.get("committed_statements")),
                asLong(map.get("last_commit_epoch")),
                map.get("last_commit_epoch_text") == null
                        ? null
                        : String.valueOf(map.get("last_commit_epoch_text")),
                CommitHlc.fromMap(map.get("last_commit_hlc")),
                asInt(map.get("first_commit_statement_index")),
                asInt(map.get("last_commit_statement_index")),
                asInt(map.get("completed_statements")),
                asInt(map.get("statement_index")),
                map.get("serialization") == null ? "" : String.valueOf(map.get("serialization")),
                map.get("serialization_state") == null
                        ? null
                        : String.valueOf(map.get("serialization_state")),
                map.get("terminal_state") == null ? null : String.valueOf(map.get("terminal_state")));
    }

    private static Integer asInt(Object v) {
        return v instanceof Number ? ((Number) v).intValue() : null;
    }

    private static Long asLong(Object v) {
        return v instanceof Number ? ((Number) v).longValue() : null;
    }
}
