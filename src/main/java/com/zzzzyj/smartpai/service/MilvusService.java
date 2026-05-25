package com.zzzzyj.smartpai.service;

import com.google.gson.JsonObject;
import com.zzzzyj.smartpai.entity.EsDocument;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.InsertReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.request.SearchReq;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.response.DeleteResp;
import io.milvus.v2.service.vector.response.InsertResp;
import io.milvus.v2.service.vector.response.QueryResp;
import io.milvus.v2.service.vector.response.SearchResp;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Milvus 向量数据库服务
 * 负责 Collection 初始化、向量写入、ANN 检索、按文件删除、数量统计
 */
@Service
@ConditionalOnProperty(name = "milvus.enabled", havingValue = "true", matchIfMissing = false)
public class MilvusService {

    private static final Logger logger = LoggerFactory.getLogger(MilvusService.class);

    static final String COLLECTION = "knowledge_base";

    @Value("${embedding.api.dimension:2048}")
    private int vectorDim;

    @Autowired
    private MilvusClientV2 milvusClient;

    // -------------------------------------------------------------------------
    // Collection 初始化
    // -------------------------------------------------------------------------

    @PostConstruct
    public void initCollection() {
        try {
            boolean exists = milvusClient.hasCollection(
                    HasCollectionReq.builder().collectionName(COLLECTION).build());
            if (!exists) {
                milvusClient.createCollection(CreateCollectionReq.builder()
                        .collectionName(COLLECTION)
                        .collectionSchema(buildSchema())
                        .indexParams(buildIndexParams())
                        .build());
                logger.info("Milvus collection '{}' 已创建，向量维度={}", COLLECTION, vectorDim);
            } else {
                logger.info("Milvus collection '{}' 已存在", COLLECTION);
            }
        } catch (Exception e) {
            // 仅打印警告，不阻断启动；搜索时会自然失败并降级
            logger.warn("Milvus collection 初始化失败，向量检索将不可用: {}", e.getMessage(), e);
        }
    }

    private CreateCollectionReq.CollectionSchema buildSchema() {
        CreateCollectionReq.CollectionSchema schema = milvusClient.createSchema();
        schema.addField(AddFieldReq.builder()
                .fieldName("id").dataType(DataType.VarChar).maxLength(64)
                .isPrimaryKey(true).autoID(false).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("fileMd5").dataType(DataType.VarChar).maxLength(64).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("chunkId").dataType(DataType.Int32).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("userId").dataType(DataType.VarChar).maxLength(64).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("orgTag").dataType(DataType.VarChar).maxLength(128).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("isPublic").dataType(DataType.Bool).build());
        schema.addField(AddFieldReq.builder()
                .fieldName("vector").dataType(DataType.FloatVector).dimension(vectorDim).build());
        return schema;
    }

    private List<IndexParam> buildIndexParams() {
        return List.of(IndexParam.builder()
                .fieldName("vector")
                .indexType(IndexParam.IndexType.HNSW)
                .metricType(IndexParam.MetricType.COSINE)
                .extraParams(Map.of("M", 16, "efConstruction", 200))
                .build());
    }

    // -------------------------------------------------------------------------
    // 写入
    // -------------------------------------------------------------------------

    /**
     * 批量插入向量数据（row-based）
     * 每条数据对应一个 EsDocument，id 与 ES doc id 保持一致
     */
    public void bulkInsert(List<EsDocument> docs) {
        if (docs == null || docs.isEmpty()) {
            return;
        }
        try {
            List<JsonObject> rows = new ArrayList<>(docs.size());
            for (EsDocument doc : docs) {
                JsonObject row = new JsonObject();
                row.addProperty("id", doc.getId());
                row.addProperty("fileMd5", doc.getFileMd5());
                row.addProperty("chunkId", doc.getChunkId());
                row.addProperty("userId", doc.getUserId() != null ? doc.getUserId() : "");
                row.addProperty("orgTag", doc.getOrgTag() != null ? doc.getOrgTag() : "");
                row.addProperty("isPublic", doc.isPublic());

                // 将 float[] 转为 JSON array
                com.google.gson.JsonArray vecArr = new com.google.gson.JsonArray();
                for (float v : doc.getVector()) {
                    vecArr.add(v);
                }
                row.add("vector", vecArr);
                rows.add(row);
            }

            InsertResp resp = milvusClient.insert(InsertReq.builder()
                    .collectionName(COLLECTION)
                    .data(rows)
                    .build());
            logger.info("Milvus 批量插入完成，请求={} 条，成功={} 条", docs.size(), resp.getInsertCnt());
        } catch (Exception e) {
            logger.error("Milvus 批量插入失败，文档数={}", docs.size(), e);
            throw new RuntimeException("Milvus 批量插入失败", e);
        }
    }

    // -------------------------------------------------------------------------
    // ANN 检索
    // -------------------------------------------------------------------------

    /**
     * 向量近邻检索，带权限过滤
     *
     * @param queryVector 查询向量
     * @param userDbId    用户数据库 ID（字符串形式）
     * @param orgTags     用户有效组织标签列表
     * @param topK        召回数量
     * @return 命中结果列表
     */
    public List<MilvusHit> search(List<Float> queryVector, String userDbId,
                                  List<String> orgTags, int topK) {
        try {
            String filter = buildPermissionFilter(userDbId, orgTags);
            logger.debug("Milvus ANN 检索，filter={}, topK={}", filter, topK);

            SearchResp resp = milvusClient.search(SearchReq.builder()
                    .collectionName(COLLECTION)
                    .data(List.of(new FloatVec(queryVector)))
                    .annsField("vector")
                    .topK(topK)
                    .filter(filter)
                    .outputFields(List.of("fileMd5", "chunkId"))
                    .build());

            List<List<SearchResp.SearchResult>> results = resp.getSearchResults();
            if (results == null || results.isEmpty()) {
                return List.of();
            }

            return results.get(0).stream()
                    .map(r -> new MilvusHit(
                            (String) r.getEntity().get("fileMd5"),
                            ((Number) r.getEntity().get("chunkId")).intValue(),
                            r.getScore()
                    ))
                    .collect(Collectors.toList());
        } catch (Exception e) {
            logger.error("Milvus ANN 检索失败", e);
            // 检索失败降级为空列表，由上层 RRF 处理（退化为纯 BM25）
            return List.of();
        }
    }

    // -------------------------------------------------------------------------
    // 删除
    // -------------------------------------------------------------------------

    /**
     * 按 fileMd5 删除所有相关向量，返回是否成功
     */
    public boolean deleteByFileMd5(String fileMd5) {
        try {
            DeleteResp resp = milvusClient.delete(DeleteReq.builder()
                    .collectionName(COLLECTION)
                    .filter("fileMd5 == \"" + fileMd5 + "\"")
                    .build());
            logger.info("Milvus 删除完成，fileMd5={}，删除数={}", fileMd5, resp.getDeleteCnt());
            return true;
        } catch (Exception e) {
            logger.error("Milvus 删除失败，fileMd5={}", fileMd5, e);
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // 对账用：计数
    // -------------------------------------------------------------------------

    /**
     * 统计指定 fileMd5 在 Milvus 中的向量数量，用于对账
     */
    public long countByFileMd5(String fileMd5) {
        try {
            QueryResp resp = milvusClient.query(QueryReq.builder()
                    .collectionName(COLLECTION)
                    .filter("fileMd5 == \"" + fileMd5 + "\"")
                    .outputFields(List.of("id"))
                    .build());
            return resp.getQueryResults().size();
        } catch (Exception e) {
            logger.error("Milvus 计数失败，fileMd5={}", fileMd5, e);
            return -1L;
        }
    }

    // -------------------------------------------------------------------------
    // 私有辅助方法
    // -------------------------------------------------------------------------

    /**
     * 构建 Milvus filter 表达式（权限过滤）
     * 语义：userId 匹配 OR isPublic=true OR orgTag 在用户有效标签列表内
     */
    private String buildPermissionFilter(String userDbId, List<String> orgTags) {
        List<String> conditions = new ArrayList<>();
        conditions.add("userId == \"" + userDbId + "\"");
        conditions.add("isPublic == true");
        if (orgTags != null && !orgTags.isEmpty()) {
            String tagList = orgTags.stream()
                    .map(t -> "\"" + t + "\"")
                    .collect(Collectors.joining(", "));
            conditions.add("orgTag in [" + tagList + "]");
        }
        return String.join(" || ", conditions);
    }

    // -------------------------------------------------------------------------
    // 结果 DTO
    // -------------------------------------------------------------------------

    /**
     * Milvus ANN 检索单条结果
     *
     * @param fileMd5 文件指纹
     * @param chunkId chunk 序号
     * @param score   相似度分数（cosine，越大越相似）
     */
    public record MilvusHit(String fileMd5, int chunkId, float score) {}
}
