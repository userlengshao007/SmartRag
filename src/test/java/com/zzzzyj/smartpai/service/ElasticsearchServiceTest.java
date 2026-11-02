package com.zzzzyj.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.DeleteByQueryRequest;
import co.elastic.clients.elasticsearch.core.DeleteByQueryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ElasticsearchServiceTest {
    private final ElasticsearchClient client = mock(ElasticsearchClient.class);
    private final ElasticsearchService service = new ElasticsearchService();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "esClient", client);
    }

    @Test
    void deletionWaitsForRefreshAndFiltersFile() throws Exception {
        var successful = response(false, 0);
        when(client.deleteByQuery(any(DeleteByQueryRequest.class))).thenReturn(successful);
        service.deleteByFileMd5("file-a");
        var request = ArgumentCaptor.forClass(DeleteByQueryRequest.class);
        verify(client).deleteByQuery(request.capture());
        assertTrue(request.getValue().refresh());
        assertEquals("file-a", request.getValue().query().term().value().stringValue());
    }

    @Test
    void incompleteDeletionThrowsForRetry() throws Exception {
        var timedOut = response(true, 0);
        when(client.deleteByQuery(any(DeleteByQueryRequest.class))).thenReturn(timedOut);
        assertThrows(RuntimeException.class, () -> service.deleteByFileMd5("file-a"));
        var conflicted = response(false, 1);
        when(client.deleteByQuery(any(DeleteByQueryRequest.class))).thenReturn(conflicted);
        assertThrows(RuntimeException.class, () -> service.deleteByFileMd5("file-a"));
    }

    private DeleteByQueryResponse response(boolean timedOut, long conflicts) {
        return DeleteByQueryResponse.of(r -> r.timedOut(timedOut)
                .versionConflicts(conflicts).failures(List.of()));
    }
}
