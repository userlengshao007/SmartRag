package com.zzzzyj.smartpai.service;

import com.zzzzyj.smartpai.client.EmbeddingClient;
import com.zzzzyj.smartpai.model.DocumentVector;
import com.zzzzyj.smartpai.entity.EsDocument;
import com.zzzzyj.smartpai.entity.TextChunk;
import com.zzzzyj.smartpai.repository.DocumentVectorRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

// 向量化服务类
@Service
public class VectorizationService {

    private static final Logger logger = LoggerFactory.getLogger(VectorizationService.class);

    @Autowired
    private EmbeddingClient embeddingClient;

    @Autowired
    private ElasticsearchService elasticsearchService;

    @Autowired(required = false)
    private MilvusService milvusService;

    @Autowired
    private DocumentVectorRepository documentVectorRepository;

    /**
     * 执行向量化操作
     * @param fileMd5 文件指纹
     * @param userId 上传用户ID
     * @param orgTag 组织标签
     * @param isPublic 是否公开
     */
    public void vectorize(String fileMd5, String userId, String orgTag, boolean isPublic) {
        vectorizeWithUsage(fileMd5, userId, orgTag, isPublic, userId);
    }

    public void vectorize(String fileMd5, String userId, String orgTag, boolean isPublic, String requesterId) {
        vectorizeWithUsage(fileMd5, userId, orgTag, isPublic, requesterId);
    }

    public VectorizationUsageResult vectorizeWithUsage(String fileMd5, String userId, String orgTag, boolean isPublic, String requesterId) {
        long startTime = System.currentTimeMillis();
        try {
            logger.info("开始向量化文件，fileMd5: {}, userId: {}, orgTag: {}, isPublic: {}", 
                       fileMd5, userId, orgTag, isPublic);
                       
            // 获取文件分块内容
            long step1Start = System.currentTimeMillis();
            List<TextChunk> chunks = fetchTextChunks(fileMd5);
            if (chunks == null || chunks.isEmpty()) {
                logger.warn("未找到分块内容，fileMd5: {}", fileMd5);
                return new VectorizationUsageResult(0, 0, embeddingClient.currentModelVersion());
            }
            logger.info("步骤1-获取分块内容耗时: {}ms, fileMd5: {}, 分块数量: {}", System.currentTimeMillis() - step1Start, fileMd5, chunks.size());

            // 提取文本内容
            long step2Start = System.currentTimeMillis();
            List<String> texts = chunks.stream()
                    .map(TextChunk::getContent)
                    .toList();
            logger.info("步骤2-提取文本内容耗时: {}ms, fileMd5: {}", System.currentTimeMillis() - step2Start, fileMd5);

            // 调用外部模型生成向量
            long step3Start = System.currentTimeMillis();
            EmbeddingClient.EmbeddingUsageResult embeddingResult = embeddingClient.embedWithUsage(
                    texts,
                    requesterId,
                    EmbeddingClient.UsageType.UPLOAD
            );
            List<float[]> vectors = embeddingResult.vectors();
            logger.info("步骤3-生成向量耗时: {}ms, fileMd5: {}, token数量: {}", System.currentTimeMillis() - step3Start, fileMd5, embeddingResult.totalTokens());

            // 构建 Elasticsearch 文档并存储
            long step4Start = System.currentTimeMillis();
            List<EsDocument> esDocuments = IntStream.range(0, chunks.size())
                    .mapToObj(i -> new EsDocument(
                            UUID.randomUUID().toString(),
                            fileMd5,
                            chunks.get(i).getChunkId(),
                            chunks.get(i).getContent(),
                            chunks.get(i).getPageNumber(),
                            chunks.get(i).getAnchorText(),
                            vectors.get(i),
                            embeddingResult.modelVersion(),
                            userId,
                            orgTag,
                            isPublic
                    ))
                    .toList();
            logger.info("步骤4-构建ES文档耗时: {}ms, fileMd5: {}", System.currentTimeMillis() - step4Start, fileMd5);

            // 1. 先写 ES（BM25 文本索引）
            long step5Start = System.currentTimeMillis();
            elasticsearchService.bulkIndex(esDocuments);
            logger.info("步骤5-ES写入耗时: {}ms, fileMd5: {}", System.currentTimeMillis() - step5Start, fileMd5);
            logger.info("ES 写入完成，fileMd5: {}", fileMd5);

//            // 2. 再写 Milvus（ANN 向量索引）；失败时回滚 ES 并抛异常由上层重试
//            try {
//                milvusService.bulkInsert(esDocuments);
//                logger.info("Milvus 写入完成，fileMd5: {}", fileMd5);
//            } catch (Exception milvusEx) {
//                logger.error("Milvus 写入失败，开始回滚 ES，fileMd5: {}", fileMd5, milvusEx);
//                try {
//                    elasticsearchService.deleteByFileMd5(fileMd5);
//                    logger.info("ES 回滚成功，fileMd5: {}", fileMd5);
//                } catch (Exception rollbackEx) {
//                    logger.error("ES 回滚也失败，fileMd5={} 可能存在孤立数据，对账任务将自动修复", fileMd5, rollbackEx);
//                }
//                throw new RuntimeException("Milvus 双写失败，已尝试回滚 ES", milvusEx);
//            }

            logger.info("向量化双写完成，fileMd5: {}, 总耗时: {}ms", fileMd5, System.currentTimeMillis() - startTime);
            return new VectorizationUsageResult(
                    embeddingResult.totalTokens(),
                    chunks.size(),
                    embeddingResult.modelVersion()
            );
        } catch (Exception e) {
            logger.error("向量化失败，fileMd5: {}, 总耗时: {}ms", fileMd5, System.currentTimeMillis() - startTime, e);
            throw new RuntimeException("向量化失败", e);
        }
    }
    

    /**
     * 获取文件分块内容
     * @param fileMd5 文件指纹
     * @return 分块内容列表
     */
    // 从数据库获取分块内容
    private List<TextChunk> fetchTextChunks(String fileMd5) {
        // 调用 Repository 查询数据
        List<DocumentVector> vectors = documentVectorRepository.findByFileMd5(fileMd5);

        // 转换为 TextChunk 列表
        return vectors.stream()
                .map(vector -> new TextChunk(
                        vector.getChunkId(),
                        vector.getTextContent(),
                        vector.getPageNumber(),
                        vector.getAnchorText()
                ))
                .toList();
    }

    public record VectorizationUsageResult(int actualEmbeddingTokens, int actualChunkCount, String modelVersion) {
    }
}
