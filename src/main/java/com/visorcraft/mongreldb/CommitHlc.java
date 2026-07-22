package com.visorcraft.mongreldb;

import java.util.Map;

/** Structural HLC from durable recovery (0.64+). */
public final class CommitHlc {
    public final long physicalMicros;
    public final int logical;
    public final int nodeTiebreaker;

    public CommitHlc(long physicalMicros, int logical, int nodeTiebreaker) {
        this.physicalMicros = physicalMicros;
        this.logical = logical;
        this.nodeTiebreaker = nodeTiebreaker;
    }

    @SuppressWarnings("unchecked")
    public static CommitHlc fromMap(Object raw) {
        if (!(raw instanceof Map)) {
            return null;
        }
        Map<?, ?> map = (Map<?, ?>) raw;
        Object phys = map.get("physical_micros");
        if (phys == null) {
            return null;
        }
        return new CommitHlc(
                ((Number) phys).longValue(),
                map.get("logical") == null ? 0 : ((Number) map.get("logical")).intValue(),
                map.get("node_tiebreaker") == null
                        ? 0
                        : ((Number) map.get("node_tiebreaker")).intValue());
    }
}
