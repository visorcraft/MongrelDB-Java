package com.visorcraft.mongreldb;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DurableRetrieveTest {

    @Test
    void queryStatusParsesStructuralHlcWithoutStringParsing() {
        Map<String, Object> hlc = new LinkedHashMap<>();
        hlc.put("physical_micros", 1_700_000_000_000_000L);
        hlc.put("logical", 3);
        hlc.put("node_tiebreaker", 7);

        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("committed", true);
        outcome.put("committed_statements", 1);
        outcome.put("last_commit_epoch", 17);
        outcome.put("last_commit_epoch_text", "17");
        outcome.put("last_commit_hlc", hlc);
        outcome.put("first_commit_statement_index", 0);
        outcome.put("last_commit_statement_index", 0);
        outcome.put("completed_statements", 1);
        outcome.put("statement_index", 0);
        outcome.put("serialization", "succeeded");
        outcome.put("serialization_state", "succeeded");

        Map<String, Object> fixture = new LinkedHashMap<>();
        fixture.put("query_id", "abcdefabcdefabcdefabcdefabcdefab");
        fixture.put("status", "committed");
        fixture.put("state", "completed");
        fixture.put("server_state", "completed");
        fixture.put("terminal_state", "committed");
        fixture.put("committed", true);
        fixture.put("last_commit_epoch", 17);
        fixture.put("last_commit_hlc", hlc);
        fixture.put("outcome", outcome);
        fixture.put("durable", outcome);

        QueryStatus status = QueryStatus.fromMap(fixture);
        assertEquals(Boolean.TRUE, status.committed);
        CommitHlc parsed = status.commitHlc();
        assertNotNull(parsed);
        assertEquals(1_700_000_000_000_000L, parsed.physicalMicros);
        assertEquals(3, parsed.logical);
        assertEquals(7, parsed.nodeTiebreaker);
        assertEquals("succeeded", status.serializationState());
        assertEquals(17L, status.outcome.lastCommitEpoch.longValue());
    }

    @Test
    void multiRetrieverSearchBuildIncludesTwoRetrieversAndFusion() {
        MongrelDB client = new MongrelDB("http://127.0.0.1:9");
        Map<String, Object> payload =
                client.search("docs")
                        .annRetriever("ann", 3L, List.of(0.1, 0.2), 10, 1.0)
                        .sparseRetriever("sparse", 4L, List.of(List.of(1, 0.5)), 10, 0.5)
                        .fusion(60)
                        .limit(5)
                        .build();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> retrievers = (List<Map<String, Object>>) payload.get("retrievers");
        assertEquals(2, retrievers.size());
        assertTrue(payload.containsKey("fusion"));
    }
}
