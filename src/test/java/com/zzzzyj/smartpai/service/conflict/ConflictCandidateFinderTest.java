package com.zzzzyj.smartpai.service.conflict;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.zzzzyj.smartpai.config.DocumentConflictProperties;
import com.zzzzyj.smartpai.entity.EsDocument;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ConflictCandidateFinderTest {
    private final ConflictCandidateFinder finder = new ConflictCandidateFinder(
            mock(ElasticsearchClient.class), new DocumentConflictProperties());

    @Test
    void acceptsDifferentValuesInSimilarText() {
        assertTrue(finder.evaluate(doc("new", "8080", "model-a"), doc("old", "9090", "model-a"), 0.97).isPresent());
    }

    @Test
    void rejectsSameFileLowSimilarityAndDifferentModel() {
        EsDocument source = doc("new", "8080", "model-a");
        assertTrue(finder.evaluate(source, doc("new", "9090", "model-a"), 0.97).isEmpty());
        assertTrue(finder.evaluate(source, doc("old", "9090", "model-a"), 0.89).isEmpty());
        assertTrue(finder.evaluate(source, doc("old", "9090", "model-b"), 0.97).isEmpty());
    }

    @Test
    void permissionScopeRequiresVisibilityAndExcludesSourceFile() {
        var scope = ConflictCandidateFinder.buildScopeFilter("new", "7", "dept-a");
        assertEquals("1", scope.bool().filter().get(0).bool().minimumShouldMatch());
        assertEquals(3, scope.bool().filter().get(0).bool().should().size());
        assertEquals("new", scope.bool().mustNot().get(0).term().value().stringValue());
    }

    @Test
    void restoresCosineScore() {
        assertEquals(0.9, ConflictCandidateFinder.cosineFromScore(0.95), 1e-9);
    }

    private EsDocument doc(String md5, String port, String model) {
        EsDocument doc = new EsDocument();
        doc.setFileMd5(md5);
        doc.setChunkId(1);
        doc.setTextContent("订单服务的 HTTP 端口配置为 " + port + "，连接超时时间为 30 秒。");
        doc.setModelVersion(model);
        return doc;
    }
}
