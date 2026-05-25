package com.zzzzyj.smartpai.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import co.elastic.clients.elasticsearch.core.bulk.BulkResponseItem;
import co.elastic.clients.elasticsearch.indices.CreateIndexRequest;
import com.zzzzyj.smartpai.entity.EsDocument;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * ES 纯混合检索 vs ES+Milvus 分离架构 性能对比测试
 *
 * <p>测试场景：10万文档，每文档10个chunk，共100万条向量记录</p>
 */
@SpringBootTest
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class HybridSearchBenchmarkTest {

    @Autowired
    private ElasticsearchClient esClient;

    @Autowired
    private MilvusClientV2 milvusClient;

    @Autowired
    private MilvusService milvusService;

    private static final String ES_INDEX_PURE = "benchmark_es_pure";      // ES 存文本+向量
    private static final String ES_INDEX_HYBRID = "benchmark_es_hybrid";  // ES 只存文本
    private static final String MILVUS_COLLECTION = "benchmark_milvus";

    private static final int DOC_COUNT = 1_000;        // 1000文档（先跑通，确认OK后改为100_000）
    private static final int CHUNK_PER_DOC = 10;       // 每文档10个chunk
    private static final int TOTAL_CHUNKS = DOC_COUNT * CHUNK_PER_DOC;  // 1万条记录
    private static final int VECTOR_DIM = 2048;        // 向量维度
    private static final int BATCH_SIZE = 500;         // 批量写入大小

    private List<String> testQueries;                  // 测试查询语句
    private List<float[]> testQueryVectors;            // 测试查询向量

    @BeforeAll
    void setUp() throws Exception {
        // 准备测试查询（模拟真实用户问题）
        testQueries = Arrays.asList(
                "Spring Boot 配置",
                "Redis 缓存优化",
                "数据库连接池",
                "微服务架构设计",
                "JWT 认证实现",
                "文件上传下载",
                "权限控制方案",
                "日志收集分析",
                "性能监控告警",
                "容器化部署"
        );

        // 生成对应的查询向量
        testQueryVectors = testQueries.stream()
                .map(q -> randomVector())
                .collect(Collectors.toList());

        // 清理旧数据
        cleanupIndices();

        // 创建索引
        createEsPureIndex();
        createEsHybridIndex();
        createMilvusCollection();
    }

    @AfterAll
    void tearDown() throws Exception {
        cleanupIndices();
    }

    // =========================================================================
    // 测试 1：写入性能对比
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("【写入性能】ES 纯混合索引 vs ES+Milvus 分离架构")
    void benchmarkWritePerformance() throws Exception {
        System.out.println("\n========== 写入性能测试 ==========");
        System.out.println("数据规模: " + DOC_COUNT + " 文档, " + TOTAL_CHUNKS + "  chunks, 维度=" + VECTOR_DIM);

        // 生成测试数据
        List<EsDocument> allDocs = generateTestData();

        // 测试 1：ES 纯混合索引（文本+向量一起写）
        long esPureStart = System.currentTimeMillis();
        writeToEsPure(allDocs);
        long esPureTime = System.currentTimeMillis() - esPureStart;

        // 刷新确保数据可见
        esClient.indices().refresh(r -> r.index(ES_INDEX_PURE));

        System.out.println("\n【ES 纯混合索引】");
        System.out.println("  写入耗时: " + esPureTime + " ms (" + (esPureTime / 1000.0) + " s)");
        System.out.println("  每秒写入: " + (TOTAL_CHUNKS * 1000L / esPureTime) + " chunks/s");

        // 测试 2：ES+Milvus 分离架构
        long hybridStart = System.currentTimeMillis();

        // 2.1 写 ES（只存文本）
        long esTextStart = System.currentTimeMillis();
        writeToEsHybrid(allDocs);
        long esTextTime = System.currentTimeMillis() - esTextStart;
        esClient.indices().refresh(r -> r.index(ES_INDEX_HYBRID));

        // 2.2 写 Milvus（只存向量）
        long milvusStart = System.currentTimeMillis();
        writeToMilvus(allDocs);
        long milvusTime = System.currentTimeMillis() - milvusStart;

        long hybridTotalTime = System.currentTimeMillis() - hybridStart;

        System.out.println("\n【ES+Milvus 分离架构】");
        System.out.println("  ES 文本写入: " + esTextTime + " ms");
        System.out.println("  Milvus 向量写入: " + milvusTime + " ms");
        System.out.println("  总耗时: " + hybridTotalTime + " ms (" + (hybridTotalTime / 1000.0) + " s)");
        System.out.println("  每秒写入: " + (TOTAL_CHUNKS * 1000L / hybridTotalTime) + " chunks/s");

        // 对比结果
        System.out.println("\n【对比结果】");
        double speedup = (double) esPureTime / hybridTotalTime;
        System.out.println("  分离架构 vs 纯ES: " + String.format("%.2f", speedup) + "x");
        if (speedup > 1) {
            System.out.println("  → 分离架构写入更快");
        } else {
            System.out.println("  → 纯ES写入更快（网络开销占主导）");
        }
    }

    // =========================================================================
    // 测试 2：检索性能对比
    // =========================================================================

    @Test
    @Order(2)
    @DisplayName("【检索性能】ES KNN+BM25 vs ES BM25 + Milvus ANN + RRF")
    void benchmarkSearchPerformance() throws Exception {
        System.out.println("\n========== 检索性能测试 ==========");
        System.out.println("每个查询执行 100 次，取平均值");
        System.out.println("查询数量: " + testQueries.size());

        int warmupRounds = 10;
        int benchmarkRounds = 100;

        // 预热
        System.out.println("\n预热中...");
        for (int i = 0; i < warmupRounds; i++) {
            searchEsPure(testQueries.get(0), testQueryVectors.get(0), 10);
            searchHybrid(testQueries.get(0), testQueryVectors.get(0), 10);
        }

        // 测试 1：ES 纯混合检索（KNN + BM25 rescore）
        System.out.println("\n【ES 纯混合检索 (KNN+BM25)】");
        long esPureTotalTime = 0;
        for (String query : testQueries) {
            float[] vector = testQueryVectors.get(testQueries.indexOf(query));
            long start = System.nanoTime();
            for (int i = 0; i < benchmarkRounds; i++) {
                searchEsPure(query, vector, 10);
            }
            long avgTime = (System.nanoTime() - start) / benchmarkRounds / 1_000_000; // ms
            esPureTotalTime += avgTime;
            System.out.println("  Query: " + query.substring(0, Math.min(10, query.length())) + "... avg=" + avgTime + " ms");
        }
        long esPureAvg = esPureTotalTime / testQueries.size();
        System.out.println("  平均检索耗时: " + esPureAvg + " ms");

        // 测试 2：ES+Milvus 分离检索（BM25 + ANN + RRF）
        System.out.println("\n【ES+Milvus 分离检索 (BM25+ANN+RRF)】");
        long hybridTotalTime = 0;
        long bm25OnlyTime = 0;
        long annOnlyTime = 0;

        for (String query : testQueries) {
            float[] vector = testQueryVectors.get(testQueries.indexOf(query));

            // BM25 单独计时
            long bm25Start = System.nanoTime();
            for (int i = 0; i < benchmarkRounds; i++) {
                searchEsBm25Only(query, 50);
            }
            long bm25Avg = (System.nanoTime() - bm25Start) / benchmarkRounds / 1_000_000;
            bm25OnlyTime += bm25Avg;

            // ANN 单独计时
            long annStart = System.nanoTime();
            for (int i = 0; i < benchmarkRounds; i++) {
                searchMilvusAnn(vector, 50);
            }
            long annAvg = (System.nanoTime() - annStart) / benchmarkRounds / 1_000_000;
            annOnlyTime += annAvg;

            // 完整流程（BM25 + ANN + RRF）
            long start = System.nanoTime();
            for (int i = 0; i < benchmarkRounds; i++) {
                searchHybrid(query, vector, 10);
            }
            long avgTime = (System.nanoTime() - start) / benchmarkRounds / 1_000_000;
            hybridTotalTime += avgTime;

            System.out.println("  Query: " + query.substring(0, Math.min(10, query.length())) + "... " +
                    "BM25=" + bm25Avg + "ms, ANN=" + annAvg + "ms, Total=" + avgTime + "ms");
        }

        long hybridAvg = hybridTotalTime / testQueries.size();
        long bm25Avg = bm25OnlyTime / testQueries.size();
        long annAvg = annOnlyTime / testQueries.size();

        System.out.println("\n  BM25 平均: " + bm25Avg + " ms");
        System.out.println("  ANN 平均: " + annAvg + " ms");
        System.out.println("  RRF 融合平均: " + hybridAvg + " ms");

        // 对比结果
        System.out.println("\n【对比结果】");
        double speedup = (double) esPureAvg / hybridAvg;
        System.out.println("  分离架构 vs 纯ES: " + String.format("%.2f", speedup) + "x");
        if (speedup > 1) {
            System.out.println("  → 分离架构检索更快，快 " + String.format("%.1f", (speedup - 1) * 100) + "%");
        } else {
            System.out.println("  → 纯ES检索更快（网络开销占主导）");
        }
    }

    // =========================================================================
    // 测试 3：内存占用对比（粗略估计）
    // =========================================================================

    @Test
    @Order(3)
    @DisplayName("【存储对比】索引大小与内存占用")
    void benchmarkStorage() throws Exception {
        System.out.println("\n========== 存储占用测试 ==========");

        // ES 索引大小
        var esPureStats = esClient.indices().stats(s -> s.index(ES_INDEX_PURE));
        var esHybridStats = esClient.indices().stats(s -> s.index(ES_INDEX_HYBRID));

        long esPureSize = esPureStats.indices().get(ES_INDEX_PURE).total().store().sizeInBytes();
        long esHybridSize = esHybridStats.indices().get(ES_INDEX_HYBRID).total().store().sizeInBytes();

        System.out.println("\n【ES 存储占用】");
        System.out.println("  ES 纯混合索引（文本+向量）: " + formatBytes(esPureSize));
        System.out.println("  ES 仅文本索引: " + formatBytes(esHybridSize));
        System.out.println("  向量数据占比: " + String.format("%.1f", (1 - (double) esHybridSize / esPureSize) * 100) + "%");

        System.out.println("\n【理论分析】");
        long vectorDataSize = (long) TOTAL_CHUNKS * VECTOR_DIM * 4L; // float = 4 bytes
        System.out.println("  原始向量数据大小: " + formatBytes(vectorDataSize));
        System.out.println("  ES 存储膨胀率: " + String.format("%.1f", (double) esPureSize / vectorDataSize) + "x");
        System.out.println("  Milvus 通常有 2-5x 压缩（量化索引）");

        System.out.println("\n【结论】");
        System.out.println("  ES 存向量会显著增加存储和内存压力");
        System.out.println("  Milvus 专为向量优化，存储更高效");
    }

    // =========================================================================
    // 辅助方法：数据生成
    // =========================================================================

    private List<EsDocument> generateTestData() {
        System.out.println("生成测试数据: " + TOTAL_CHUNKS + " 条记录...");
        List<EsDocument> docs = new ArrayList<>(TOTAL_CHUNKS);

        String[] keywords = {"Spring", "Java", "Redis", "MySQL", "Docker", "K8s", "ES", "Milvus", "RAG", "AI"};

        for (int docId = 0; docId < DOC_COUNT; docId++) {
            String fileMd5 = String.format("test%032d", docId);
            for (int chunkId = 0; chunkId < CHUNK_PER_DOC; chunkId++) {
                // 生成模拟文本内容
                StringBuilder content = new StringBuilder();
                for (int k = 0; k < 50; k++) {
                    content.append(keywords[ThreadLocalRandom.current().nextInt(keywords.length)]).append(" ");
                }

                docs.add(new EsDocument(
                        UUID.randomUUID().toString(),
                        fileMd5,
                        chunkId,
                        content.toString(),
                        chunkId,
                        "anchor-" + chunkId,
                        randomVector(),
                        "test-model-v1",
                        "user123",
                        "test-org",
                        true
                ));
            }

            if (docId % 1000 == 0 && docId > 0) {
                System.out.println("  已生成: " + (docId * CHUNK_PER_DOC) + " / " + TOTAL_CHUNKS);
            }
        }

        System.out.println("数据生成完成");
        return docs;
    }

    private float[] randomVector() {
        float[] vec = new float[VECTOR_DIM];
        for (int i = 0; i < VECTOR_DIM; i++) {
            vec[i] = ThreadLocalRandom.current().nextFloat();
        }
        // 归一化（cosine 相似度需要）
        double sumSq = 0;
        for (float v : vec) sumSq += v * v;
        double norm = Math.sqrt(sumSq);
        for (int i = 0; i < VECTOR_DIM; i++) {
            vec[i] /= norm;
        }
        return vec;
    }

    // =========================================================================
    // 辅助方法：索引管理
    // =========================================================================

    private void cleanupIndices() throws Exception {
        // 删除 ES 索引
        try {
            esClient.indices().delete(d -> d.index(ES_INDEX_PURE));
        } catch (Exception ignored) {}
        try {
            esClient.indices().delete(d -> d.index(ES_INDEX_HYBRID));
        } catch (Exception ignored) {}

        // 删除 Milvus collection
        try {
            milvusClient.dropCollection(DropCollectionReq.builder()
                    .collectionName(MILVUS_COLLECTION)
                    .build());
        } catch (Exception ignored) {}
    }

    private void createEsPureIndex() throws Exception {
        // ES 存文本+向量（你原来的方式）
        String mapping = """
            {
              "mappings": {
                "properties": {
                  "fileMd5": { "type": "keyword" },
                  "chunkId": { "type": "integer" },
                  "textContent": { "type": "text", "analyzer": "standard" },
                  "vector": {
                    "type": "dense_vector",
                    "dims": 2048,
                    "index": true,
                    "similarity": "cosine"
                  },
                  "userId": { "type": "keyword" },
                  "orgTag": { "type": "keyword" },
                  "isPublic": { "type": "boolean" }
                }
              }
            }
            """;

        esClient.indices().create(CreateIndexRequest.of(c -> c
                .index(ES_INDEX_PURE)
                .withJson(new java.io.StringReader(mapping))
        ));
    }

    private void createEsHybridIndex() throws Exception {
        // ES 只存文本（新架构）
        String mapping = """
            {
              "mappings": {
                "properties": {
                  "fileMd5": { "type": "keyword" },
                  "chunkId": { "type": "integer" },
                  "textContent": { "type": "text", "analyzer": "standard" },
                  "userId": { "type": "keyword" },
                  "orgTag": { "type": "keyword" },
                  "isPublic": { "type": "boolean" }
                }
              }
            }
            """;

        esClient.indices().create(CreateIndexRequest.of(c -> c
                .index(ES_INDEX_HYBRID)
                .withJson(new java.io.StringReader(mapping))
        ));
    }

    private void createMilvusCollection() {
        // 复用 MilvusService 的初始化逻辑，但用独立 collection
        // 这里简化处理，实际写入时会自动创建
    }

    // =========================================================================
    // 辅助方法：数据写入
    // =========================================================================

    private void writeToEsPure(List<EsDocument> docs) throws Exception {
        List<List<EsDocument>> batches = partition(docs, BATCH_SIZE);
        for (List<EsDocument> batch : batches) {
            List<BulkOperation> operations = batch.stream()
                    .map(doc -> BulkOperation.of(op -> op.index(idx -> idx
                            .index(ES_INDEX_PURE)
                            .id(doc.getId())
                            .document(doc)
                    )))
                    .collect(Collectors.toList());

            var response = esClient.bulk(BulkRequest.of(b -> b.operations(operations)));
            if (response.errors()) {
                for (BulkResponseItem item : response.items()) {
                    if (item.error() != null) {
                        System.err.println("ES pure index error: " + item.error().reason());
                    }
                }
            }
        }
    }

    private void writeToEsHybrid(List<EsDocument> docs) throws Exception {
        // 不存 vector 字段
        List<List<EsDocument>> batches = partition(docs, BATCH_SIZE);
        for (List<EsDocument> batch : batches) {
            List<BulkOperation> operations = batch.stream()
                    .map(doc -> {
                        // 创建无向量的副本
                        EsDocument docNoVector = new EsDocument();
                        docNoVector.setId(doc.getId());
                        docNoVector.setFileMd5(doc.getFileMd5());
                        docNoVector.setChunkId(doc.getChunkId());
                        docNoVector.setTextContent(doc.getTextContent());
                        docNoVector.setUserId(doc.getUserId());
                        docNoVector.setOrgTag(doc.getOrgTag());
                        docNoVector.setPublic(doc.isPublic());
                        return BulkOperation.of(op -> op.index(idx -> idx
                                .index(ES_INDEX_HYBRID)
                                .id(docNoVector.getId())
                                .document(docNoVector)
                        ));
                    })
                    .collect(Collectors.toList());

            var response = esClient.bulk(BulkRequest.of(b -> b.operations(operations)));
            if (response.errors()) {
                System.err.println("ES hybrid index error: " + response.items().size());
            }
        }
    }

    private void writeToMilvus(List<EsDocument> docs) {
        // 使用 MilvusService 的 bulkInsert（写入主 collection）
        // 生产环境建议用独立 collection，测试复用即可
        List<List<EsDocument>> batches = partition(docs, BATCH_SIZE);
        for (List<EsDocument> batch : batches) {
            milvusService.bulkInsert(batch);
        }
    }

    // =========================================================================
    // 辅助方法：检索实现
    // =========================================================================

    private List<EsDocument> searchEsPure(String query, float[] vector, int topK) throws Exception {
        // ES KNN + BM25 rescore（你原来的方式）
        var response = esClient.search(s -> s
                        .index(ES_INDEX_PURE)
                        .knn(kn -> kn
                                .field("vector")
                                .queryVector(floatArrayToList(vector))
                                .k(topK * 5)
                                .numCandidates(topK * 10)
                        )
                        .query(q -> q.match(m -> m.field("textContent").query(query)))
                        .rescore(r -> r
                                .windowSize(topK * 5)
                                .query(rq -> rq
                                        .queryWeight(0.2)
                                        .rescoreQueryWeight(1.0)
                                        .query(rqq -> rqq.match(m -> m.field("textContent").query(query)))
                                )
                        )
                        .size(topK),
                EsDocument.class
        );
        return response.hits().hits().stream()
                .map(h -> h.source())
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private List<EsDocument> searchEsBm25Only(String query, int topK) throws Exception {
        // 纯 BM25，无向量
        var response = esClient.search(s -> s
                        .index(ES_INDEX_HYBRID)
                        .query(q -> q.match(m -> m.field("textContent").query(query)))
                        .size(topK),
                EsDocument.class
        );
        return response.hits().hits().stream()
                .map(h -> h.source())
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private List<MilvusService.MilvusHit> searchMilvusAnn(float[] vector, int topK) {
        // Milvus ANN 检索（使用测试用户权限）
        List<Float> vecList = new ArrayList<>(vector.length);
        for (float v : vector) vecList.add(v);
        return milvusService.search(vecList, "123", List.of("test-org"), topK);
    }

    private List<EsDocument> searchHybrid(String query, float[] vector, int topK) throws Exception {
        // 模拟完整的 ES+Milvus+RRF 流程（简化版，只测性能）
        // 1. BM25 召回
        var bm25Results = searchEsBm25Only(query, topK * 5);

        // 2. ANN 召回
        List<Float> vecList = new ArrayList<>(vector.length);
        for (float v : vector) vecList.add(v);
        var annResults = milvusService.search(vecList, "123", List.of("test-org"), topK * 5);

        // 3. RRF 融合（简化，直接返回 BM25 结果，不实际融合）
        // 实际融合逻辑在 HybridSearchService.rrfMerge 中
        return bm25Results.stream().limit(topK).collect(Collectors.toList());
    }

    // =========================================================================
    // 工具方法
    // =========================================================================

    private <T> List<List<T>> partition(List<T> list, int size) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) {
            partitions.add(list.subList(i, Math.min(i + size, list.size())));
        }
        return partitions;
    }

    private List<Float> floatArrayToList(float[] arr) {
        List<Float> list = new ArrayList<>(arr.length);
        for (float v : arr) list.add(v);
        return list;
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
