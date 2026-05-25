package com.zzzzyj.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.zzzzyj.smartpai.client.EmbeddingClient;
import com.zzzzyj.smartpai.entity.EsDocument;
import com.zzzzyj.smartpai.entity.SearchResult;
import com.zzzzyj.smartpai.exception.CustomException;
import com.zzzzyj.smartpai.model.User;
import com.zzzzyj.smartpai.repository.FileUploadRepository;
import com.zzzzyj.smartpai.repository.UserRepository;
import com.zzzzyj.smartpai.model.FileUpload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 混合检索服务（纯 Elasticsearch）
 *
 * <p>检索流程：
 * <ol>
 *   <li>ES BM25 召回（纯文本关键词匹配）</li>
 *   <li>ES KNN 召回（向量近邻搜索，使用 dense_vector 字段）</li>
 *   <li>RRF（Reciprocal Rank Fusion）融合两路结果，返回最终 topK</li>
 * </ol>
 *
 * <p>降级策略：
 * <ul>
 *   <li>向量生成失败 → 退化为纯 BM25</li>
 *   <li>KNN 检索失败 → 退化为纯 BM25</li>
 *   <li>ES 检索也失败 → 返回空列表</li>
 * </ul>
 */
@Service
public class HybridSearchService {

    private static final Logger logger = LoggerFactory.getLogger(HybridSearchService.class);

    /** RRF 标准超参数，通常取 60 */
    private static final int RRF_K = 60;

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private UserService userService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OrgTagCacheService orgTagCacheService;

    @Autowired
    private FileUploadRepository fileUploadRepository;

    // =========================================================================
    // 公开入口
    // =========================================================================

    /**
     * 带权限过滤的混合检索（主要入口）
     */
    public List<SearchResult> searchWithPermission(String query, String userId, int topK) {
        long startTime = System.currentTimeMillis();
        logger.debug("混合检索开始，query={}, userId={}, topK={}", query, userId, topK);

        try {
            String userDbId = getUserDbId(userId);
            List<String> orgTags = getUserEffectiveOrgTags(userId);
            int recallK = topK * 10;

            // --- 1. ES BM25 召回 ---
            long bm25Start = System.currentTimeMillis();
            List<SearchResult> bm25Results = esBm25Search(query, userDbId, orgTags, recallK);
            logger.info("步骤1-BM25召回耗时: {}ms, 召回数={}", System.currentTimeMillis() - bm25Start, bm25Results.size());

            // --- 2. ES KNN 召回 ---
            long knnStart = System.currentTimeMillis();
            List<EsKnnHit> knnResults = Collections.emptyList();
            List<Float> queryVec = embedToVectorList(query, userId);
            if (queryVec != null) {
                knnResults = esKnnSearch(queryVec, userDbId, orgTags, recallK);
                logger.info("步骤2-KNN召回耗时: {}ms, 召回数={}", System.currentTimeMillis() - knnStart, knnResults.size());
            } else {
                logger.warn("向量生成失败，退化为纯 BM25");
            }

            // --- 3. RRF 融合 ---
            long rrfStart = System.currentTimeMillis();
            List<SearchResult> merged = rrfMerge(bm25Results, knnResults, topK);
            logger.info("步骤3-RRF融合耗时: {}ms, 融合后结果数={}", System.currentTimeMillis() - rrfStart, merged.size());

            long attachStart = System.currentTimeMillis();
            attachFileNames(merged);
            logger.info("步骤4-附加文件名耗时: {}ms", System.currentTimeMillis() - attachStart);

            logger.info("混合检索总耗时: {}ms, query={}", System.currentTimeMillis() - startTime, query);
            return merged;

        } catch (Exception e) {
            logger.error("混合检索失败，尝试纯 BM25 降级", e);
            try {
                String userDbId = getUserDbId(userId);
                List<String> orgTags = getUserEffectiveOrgTags(userId);
                long fallbackStart = System.currentTimeMillis();
                List<SearchResult> fallback = esBm25Search(query, userDbId, orgTags, topK);
                attachFileNames(fallback);
                logger.info("BM25降级搜索耗时: {}ms", System.currentTimeMillis() - fallbackStart);
                return fallback;
            } catch (Exception fe) {
                logger.error("降级搜索也失败", fe);
                return Collections.emptyList();
            }
        }
    }

    /**
     * 不带权限过滤的搜索（保留向后兼容）
     */
    public List<SearchResult> search(String query, int topK) {
        logger.warn("使用了无权限过滤的搜索方法，建议改用 searchWithPermission");
        try {
            int recallK = topK * 5;

            List<SearchResult> bm25Results = esBm25SearchNoAuth(query, recallK);

            List<EsKnnHit> knnResults = Collections.emptyList();
            List<Float> queryVec = embedToVectorList(query, "system");
            if (queryVec != null) {
                // 无权限限制：传空 orgTags，filter 只靠 isPublic 兜底
                knnResults = esKnnSearch(queryVec, "", Collections.emptyList(), recallK);
            }

            return rrfMerge(bm25Results, knnResults, topK);
        } catch (Exception e) {
            logger.error("无权限搜索失败", e);
            return Collections.emptyList();
        }
    }

    // =========================================================================
    // ES BM25 检索
    // =========================================================================

    /**
     * 带权限过滤的 BM25 检索（纯文本，不再做 KNN）
     */
    private List<SearchResult> esBm25Search(String query, String userDbId,
                                             List<String> orgTags, int topK) {
        try {
            SearchResponse<EsDocument> response = esClient.search(s -> s
                    .index("knowledge_base")
                    .query(q -> q.bool(b -> b
                            .must(m -> m.match(ma -> ma
                                    .field("textContent")
                                    .query(query)))
                            .filter(f -> f.bool(bf -> {
                                bf.should(s1 -> s1.term(t -> t.field("userId").value(userDbId)));
                                bf.should(s2 -> s2.term(t -> t.field("public").value(true)));
                                if (!orgTags.isEmpty()) {
                                    if (orgTags.size() == 1) {
                                        bf.should(s3 -> s3.term(t -> t.field("orgTag").value(orgTags.get(0))));
                                    } else {
                                        bf.should(s3 -> s3.bool(inner -> {
                                            orgTags.forEach(tag -> inner.should(
                                                    sh -> sh.term(t -> t.field("orgTag").value(tag))));
                                            return inner;
                                        }));
                                    }
                                }
                                return bf;
                            }))
                    ))
                    .size(topK),
                    EsDocument.class);

            return toSearchResults(response, "BM25");
        } catch (Exception e) {
            logger.error("ES BM25 检索失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 无权限过滤的 BM25（兼容旧接口）
     */
    private List<SearchResult> esBm25SearchNoAuth(String query, int topK) {
        try {
            SearchResponse<EsDocument> response = esClient.search(s -> s
                    .index("knowledge_base")
                    .query(q -> q.match(m -> m.field("textContent").query(query)))
                    .size(topK),
                    EsDocument.class);
            return toSearchResults(response, "BM25");
        } catch (Exception e) {
            logger.error("ES BM25 无权限检索失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 从 ES 按 (fileMd5, chunkId) 精确查询单条文档，用于补全 ANN-only 结果的文本内容
     */
    private SearchResult fetchFromEs(String fileMd5, int chunkId) {
        try {
            SearchResponse<EsDocument> response = esClient.search(s -> s
                    .index("knowledge_base")
                    .query(q -> q.bool(b -> b
                            .must(m -> m.term(t -> t.field("fileMd5").value(fileMd5)))
                            .must(m -> m.term(t -> t.field("chunkId").value(chunkId)))
                    ))
                    .size(1),
                    EsDocument.class);

            return response.hits().hits().stream()
                    .findFirst()
                    .map(hit -> {
                        assert hit.source() != null;
                        return new SearchResult(
                                hit.source().getFileMd5(),
                                hit.source().getChunkId(),
                                hit.source().getTextContent(),
                                0.0,
                                hit.source().getUserId(),
                                hit.source().getOrgTag(),
                                hit.source().isPublic(),
                                null,
                                hit.source().getPageNumber(),
                                hit.source().getAnchorText(),
                                "ANN",
                                hit.source().getTextContent()
                        );
                    })
                    .orElse(null); // null 表示 ES 无此记录（Milvus 脏数据），RRF 会过滤掉
        } catch (Exception e) {
            logger.warn("fetchFromEs 失败，fileMd5={}, chunkId={}", fileMd5, chunkId, e);
            return null;
        }
    }

    // =========================================================================
    // ES KNN 检索（替代 Milvus）
    // =========================================================================

    /**
     * ES KNN 向量检索（带权限过滤）
     */
    private List<EsKnnHit> esKnnSearch(List<Float> queryVec, String userDbId,
                                        List<String> orgTags, int topK) {
        try {
            // 构建 float[] 数组
            float[] vectorArray = new float[queryVec.size()];
            for (int i = 0; i < queryVec.size(); i++) {
                vectorArray[i] = queryVec.get(i);
            }

            SearchResponse<EsDocument> response = esClient.search(s -> s
                    .index("knowledge_base")
                    .query(q -> q.bool(b -> {
                        // 权限过滤
                        b.should(s1 -> s1.term(t -> t.field("userId").value(userDbId)));
                        b.should(s2 -> s2.term(t -> t.field("public").value(true)));
                        if (!orgTags.isEmpty()) {
                            if (orgTags.size() == 1) {
                                b.should(s3 -> s3.term(t -> t.field("orgTag").value(orgTags.get(0))));
                            } else {
                                b.should(s3 -> s3.bool(inner -> {
                                    orgTags.forEach(tag -> inner.should(
                                            sh -> sh.term(t -> t.field("orgTag").value(tag))));
                                    return inner;
                                }));
                            }
                        }
                        return b.minimumShouldMatch("1");
                    }))
                    .knn(k -> k
                            .field("vector")
                            .queryVector(queryVec)
                            .k(topK)
                            .numCandidates(topK * 2)  // 候选集大小
                    )
                    .size(topK),
                    EsDocument.class);

            return toKnnHits(response);
        } catch (Exception e) {
            logger.error("ES KNN 检索失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 将 ES KNN 响应转换为 EsKnnHit 列表
     */
    private List<EsKnnHit> toKnnHits(SearchResponse<EsDocument> response) {
        return response.hits().hits().stream()
                .map(hit -> {
                    assert hit.source() != null;
                    return new EsKnnHit(
                            hit.source().getFileMd5(),
                            hit.source().getChunkId(),
                            hit.score() != null ? hit.score().floatValue() : 0.0f
                    );
                })
                .collect(Collectors.toList());
    }

    // =========================================================================
    // RRF 融合
    // =========================================================================

    /**
     * Reciprocal Rank Fusion
     * score(d) = Σ 1 / (RRF_K + rank_i(d))，rank 从 1 开始
     *
     * <p>key = fileMd5 + "_" + chunkId，唯一标识一个 chunk
     */
    private List<SearchResult> rrfMerge(List<SearchResult> bm25List,
                                         List<EsKnnHit> knnList,
                                         int topK) {
        Map<String, Double> scoreMap = new HashMap<>();
        // key → SearchResult（优先用 BM25 的，KNN-only 的从 ES fetch）
        Map<String, SearchResult> resultMap = new HashMap<>();

        // BM25 贡献分（rank 从 1 开始）
        for (int i = 0; i < bm25List.size(); i++) {
            SearchResult r = bm25List.get(i);
            String key = r.getFileMd5() + "_" + r.getChunkId();
            scoreMap.merge(key, 1.0 / (RRF_K + i + 1), Double::sum);
            resultMap.put(key, r);
        }

        // KNN 贡献分
        for (int i = 0; i < knnList.size(); i++) {
            EsKnnHit hit = knnList.get(i);
            String key = hit.fileMd5() + "_" + hit.chunkId();
            scoreMap.merge(key, 1.0 / (RRF_K + i + 1), Double::sum);
            // 若 BM25 没有召回此 chunk，从 ES 补充文本内容
            resultMap.computeIfAbsent(key, k -> fetchFromEs(hit.fileMd5(), hit.chunkId()));
        }

        // 按 RRF 分数降序排列，取 topK
        return scoreMap.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> {
                    SearchResult r = resultMap.get(e.getKey());
                    if (r == null) {
                        // fetchFromEs 返回 null → ES 中无此记录，直接丢弃
                        logger.debug("RRF 过滤无效数据 key={}", e.getKey());
                        return null;
                    }
                    r.setScore(e.getValue());
                    r.setRetrievalMode("RRF");
                    return r;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    // =========================================================================
    // 工具方法
    // =========================================================================

    private List<SearchResult> toSearchResults(SearchResponse<EsDocument> response, String mode) {
        return response.hits().hits().stream()
                .map(hit -> {
                    assert hit.source() != null;
                    return new SearchResult(
                            hit.source().getFileMd5(),
                            hit.source().getChunkId(),
                            hit.source().getTextContent(),
                            hit.score(),
                            hit.source().getUserId(),
                            hit.source().getOrgTag(),
                            hit.source().isPublic(),
                            null,
                            hit.source().getPageNumber(),
                            hit.source().getAnchorText(),
                            mode,
                            hit.source().getTextContent()
                    );
                })
                .collect(Collectors.toList());
    }

    private List<Float> embedToVectorList(String text, String requesterId) {
        try {
            List<float[]> vecs = embeddingClient.embed(
                    List.of(text), requesterId, EmbeddingClient.UsageType.QUERY);
            if (vecs == null || vecs.isEmpty()) {
                return null;
            }
            float[] raw = vecs.get(0);
            List<Float> list = new ArrayList<>(raw.length);
            for (float v : raw) list.add(v);
            return list;
        } catch (Exception e) {
            logger.error("向量生成失败", e);
            return null;
        }
    }

    private List<String> getUserEffectiveOrgTags(String userId) {
        try {
            User user = resolveUser(userId);
            return orgTagCacheService.getUserEffectiveOrgTags(user.getUsername());
        } catch (Exception e) {
            logger.error("获取用户有效组织标签失败，userId={}", userId, e);
            return Collections.emptyList();
        }
    }

    private String getUserDbId(String userId) {
        try {
            Long userIdLong = Long.parseLong(userId);
            userRepository.findById(userIdLong)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
            return userIdLong.toString();
        } catch (NumberFormatException e) {
            User user = userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
            return user.getId().toString();
        }
    }

    private User resolveUser(String userId) {
        try {
            Long id = Long.parseLong(userId);
            return userRepository.findById(id)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
        } catch (NumberFormatException e) {
            return userRepository.findByUsername(userId)
                    .orElseThrow(() -> new CustomException("User not found: " + userId, HttpStatus.NOT_FOUND));
        }
    }

    private void attachFileNames(List<SearchResult> results) {
        if (results == null || results.isEmpty()) return;
        try {
            Set<String> md5Set = results.stream()
                    .map(SearchResult::getFileMd5)
                    .collect(Collectors.toSet());
            List<FileUpload> uploads = fileUploadRepository.findByFileMd5In(new ArrayList<>(md5Set));
            Map<String, String> md5ToName = uploads.stream()
                    .collect(Collectors.toMap(
                            FileUpload::getFileMd5,
                            FileUpload::getFileName,
                            (a, b) -> a));
            results.forEach(r -> r.setFileName(md5ToName.get(r.getFileMd5())));
        } catch (Exception e) {
            logger.error("补充文件名失败", e);
        }
    }

    // =========================================================================
    // 内部类
    // =========================================================================

    /**
     * ES KNN 检索结果（轻量级 DTO）
     */
    private record EsKnnHit(String fileMd5, int chunkId, float score) {
    }
}
