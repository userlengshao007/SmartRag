package com.zzzzyj.smartpai.service.conflict;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.zzzzyj.smartpai.config.DocumentConflictProperties;
import com.zzzzyj.smartpai.entity.EsDocument;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.FieldValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 在知识库索引上为新文件的每个分块做 KNN，找同一可见范围内其他文件里最相似的分块，
 * 再用 {@link KeyValueConflictAnalyzer} 过滤出"相似度高但关键值不同"的候选对。
 */
@Service
public class ConflictCandidateFinder {

    private static final Logger logger = LoggerFactory.getLogger(ConflictCandidateFinder.class);
    private static final int ES_MAX_NUM_CANDIDATES = 10000;
    private static final int MIN_NUM_CANDIDATES = 100;

    private final ElasticsearchClient esClient;
    private final DocumentConflictProperties properties;

    public ConflictCandidateFinder(ElasticsearchClient esClient,
                                   DocumentConflictProperties properties) {
        this.esClient = esClient;
        this.properties = properties;
    }

    public List<ConflictCandidate> findCandidates(String fileMd5) throws IOException {
        List<EsDocument> newChunks = loadNewChunks(fileMd5);
        if (newChunks.isEmpty()) {
            logger.info("冲突检测跳过：未在知识库中找到文件分块, fileMd5={}", fileMd5);
            return List.of();
        }

        EsDocument owner = newChunks.get(0);
        Query scope = buildScopeFilter(fileMd5, owner.getUserId(), owner.getOrgTag());
        int maxConflicts = Math.max(properties.getMaxConflictsPerFile(), 1);
        List<ConflictCandidate> candidates = new ArrayList<>();
        Set<String> seenPairs = new HashSet<>();

        for (EsDocument chunk : newChunks) {
            if (chunk.getVector() == null || chunk.getVector().length == 0) {
                continue;
            }
            Query modelScope = chunk.getModelVersion() == null ? scope : Query.of(q -> q.bool(b -> b
                    .filter(scope)
                    .filter(f -> f.term(term -> term.field("modelVersion").value(chunk.getModelVersion())))));
            List<Hit<EsDocument>> neighbors = esClient.search(buildKnnRequest(chunk.getVector(), modelScope), EsDocument.class)
                    .hits().hits();
            for (Hit<EsDocument> neighbor : neighbors) {
                Optional<ConflictCandidate> candidate = evaluate(chunk, neighbor.source(), cosineFromScore(neighbor.score()));
                if (candidate.isEmpty() || !seenPairs.add(candidate.get().pairKey())) {
                    continue;
                }
                candidates.add(candidate.get());
                if (candidates.size() >= maxConflicts) {
                    logger.warn("冲突候选达到单文件上限 {}，停止扫描: fileMd5={}", maxConflicts, fileMd5);
                    return candidates;
                }
            }
        }
        logger.info("冲突检测扫描完成: fileMd5={}, 扫描分块={}, 候选={}", fileMd5, newChunks.size(), candidates.size());
        return candidates;
    }

    /**
     * 判定一个相似分块是否构成疑似冲突：不同文件 + 向量相似度过阈值 + 关键值规则命中
     */
    Optional<ConflictCandidate> evaluate(EsDocument newChunk, EsDocument neighbor, double similarity) {
        if (neighbor == null || neighbor.getFileMd5() == null || neighbor.getChunkId() == null) {
            return Optional.empty();
        }
        if (neighbor.getFileMd5().equals(newChunk.getFileMd5())) {
            return Optional.empty();
        }
        if (!java.util.Objects.equals(newChunk.getModelVersion(), neighbor.getModelVersion())) {
            return Optional.empty();
        }
        if (similarity < properties.getSimilarityThreshold()) {
            return Optional.empty();
        }
        KeyValueConflictAnalyzer.Analysis analysis = KeyValueConflictAnalyzer.analyze(
                newChunk.getTextContent(), neighbor.getTextContent(), properties.getMaskedTextSimilarityThreshold());
        if (!analysis.conflict()) {
            logger.debug("相似分块未判为冲突: {}#{} vs {}#{}, similarity={}, reason={}",
                    newChunk.getFileMd5(), newChunk.getChunkId(), neighbor.getFileMd5(), neighbor.getChunkId(),
                    similarity, analysis.reason());
            return Optional.empty();
        }
        return Optional.of(new ConflictCandidate(
                new ConflictCandidate.ChunkRef(newChunk.getFileMd5(), newChunk.getChunkId(), newChunk.getTextContent()),
                new ConflictCandidate.ChunkRef(neighbor.getFileMd5(), neighbor.getChunkId(), neighbor.getTextContent()),
                similarity,
                analysis.maskedSimilarity(),
                analysis.onlyInFirst(),
                analysis.onlyInSecond(),
                null));
    }

    /**
     * 刷新索引后按 chunkId 顺序取前 N 个分块及其向量，兼容现有随机文档 ID。
     */
    List<EsDocument> loadNewChunks(String fileMd5) throws IOException {
        int limit = Math.min(Math.max(properties.getMaxChunksPerFile(), 1), 10000);
        // 当前索引用随机 ID；先 refresh 再按文件查询，确保刚完成的写入可见。
        esClient.indices().refresh(r -> r.index("knowledge_base"));
        return esClient.search(s -> s.index("knowledge_base")
                        .query(q -> q.term(term -> term.field("fileMd5").value(fileMd5)))
                        .sort(sort -> sort.field(f -> f.field("chunkId").order(SortOrder.Asc)))
                        .size(limit), EsDocument.class)
                .hits().hits().stream().map(Hit::source).filter(java.util.Objects::nonNull).toList();
    }

    /**
     * 可见范围：复用检索的权限过滤（同一上传者 OR 公开 OR 同组织标签），并排除新文件自身
     */
    static Query buildScopeFilter(String fileMd5, String ownerUserId, String orgTag) {
        List<String> tags = orgTag == null || orgTag.isBlank() ? List.of() : List.of(orgTag);
        Query visibility = Query.of(q -> q.bool(b -> {
            b.should(s -> s.term(t -> t.field("userId").value(ownerUserId)));
            b.should(s -> s.term(t -> t.field("public").value(true)));
            if (!tags.isEmpty()) {
                b.should(s -> s.terms(t -> t.field("orgTag")
                        .terms(v -> v.value(tags.stream().map(FieldValue::of).toList()))));
            }
            return b.minimumShouldMatch("1");
        }));
        return Query.of(q -> q.bool(b -> b
                .filter(visibility)
                .mustNot(m -> m.term(t -> t.field("fileMd5").value(fileMd5)))));
    }

    SearchRequest buildKnnRequest(float[] vector, Query scope) {
        int k = Math.min(Math.max(properties.getNeighborsPerChunk(), 1), 1000);
        long numCandidates = Math.min(Math.max((long) k * 20, MIN_NUM_CANDIDATES), ES_MAX_NUM_CANDIDATES);
        List<Float> queryVector = new ArrayList<>(vector.length);
        for (float v : vector) {
            queryVector.add(v);
        }
        return SearchRequest.of(s -> s
                .index("knowledge_base")
                .source(src -> src.filter(f -> f.excludes("vector")))
                .knn(kn -> kn.field("vector")
                        .queryVector(queryVector)
                        .k(k)
                        .numCandidates(numCandidates)
                        .filter(scope))
                .size(k));
    }

    /**
     * 索引的 dense_vector 使用 cosine，ES 返回的 _score = (1 + cos) / 2，这里换算回余弦值
     */
    static double cosineFromScore(Double score) {
        if (score == null) {
            return 0d;
        }
        return 2d * score - 1d;
    }
}
