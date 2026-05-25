package com.zzzzyj.smartpai.config;

import com.zzzzyj.smartpai.model.MilvusPendingDelete;
import com.zzzzyj.smartpai.repository.FileUploadRepository;
import com.zzzzyj.smartpai.repository.MilvusPendingDeleteRepository;
import com.zzzzyj.smartpai.service.ElasticsearchService;
import com.zzzzyj.smartpai.service.MilvusService;
import com.zzzzyj.smartpai.service.VectorizationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 数据一致性定时任务
 *
 * <p>包含两个任务：
 * <ol>
 *   <li><b>Milvus 删除补偿</b>：每5分钟扫描 milvus_pending_delete 表，
 *       对 ES 删除成功但 Milvus 删除失败的文件重试删除。</li>
 *   <li><b>ES / Milvus 双端对账</b>：每天凌晨3点对比 MySQL / ES / Milvus
 *       数据，自动修复孤立数据。</li>
 * </ol>
 */
@Component
@EnableScheduling
public class DataConsistencyScheduler {

    private static final Logger logger = LoggerFactory.getLogger(DataConsistencyScheduler.class);

    /** 补偿重试上限，超过后停止重试并打告警日志 */
    private static final int MAX_RETRY = 5;

    @Autowired
    private MilvusPendingDeleteRepository pendingDeleteRepository;

    @Autowired(required = false)
    private MilvusService milvusService;

    @Autowired
    private ElasticsearchService elasticsearchService;

    @Autowired
    private FileUploadRepository fileUploadRepository;

    @Autowired
    private VectorizationService vectorizationService;

    // -------------------------------------------------------------------------
    // 任务一：Milvus 删除补偿（每5分钟）
    // -------------------------------------------------------------------------

    @Scheduled(fixedDelay = 5 * 60 * 1000)
    public void retryMilvusDelete() {
        if (milvusService == null) {
            logger.debug("[补偿] Milvus 服务未启用，跳过删除补偿任务");
            return;
        }

        List<MilvusPendingDelete> pending = pendingDeleteRepository.findByRetryCountLessThan(MAX_RETRY);
        if (pending.isEmpty()) {
            return;
        }
        logger.info("[补偿] 本轮待重试 Milvus 删除记录数={}", pending.size());

        for (MilvusPendingDelete record : pending) {
            String fileMd5 = record.getFileMd5();
            boolean success = milvusService.deleteByFileMd5(fileMd5);
            record.setRetryCount(record.getRetryCount() + 1);
            record.setLastRetryAt(LocalDateTime.now());

            if (success) {
                pendingDeleteRepository.delete(record);
                logger.info("[补偿] Milvus 删除成功，fileMd5={}，已移除补偿记录", fileMd5);
            } else {
                pendingDeleteRepository.save(record);
                if (record.getRetryCount() >= MAX_RETRY) {
                    logger.warn("[补偿] Milvus 删除重试已达上限({}次)，fileMd5={}，请人工检查 Milvus 状态",
                            MAX_RETRY, fileMd5);
                } else {
                    logger.warn("[补偿] Milvus 删除失败，fileMd5={}，已重试 {} 次", fileMd5, record.getRetryCount());
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // 任务二：ES / Milvus 双端对账（每天凌晨3点）
    // -------------------------------------------------------------------------

    @Scheduled(cron = "0 0 3 * * ?")
    public void reconcile() {
        if (milvusService == null) {
            logger.info("[对账] Milvus 服务未启用，跳过 ES / Milvus 双端对账");
            return;
        }

        logger.info("[对账] 开始 ES / Milvus 双端对账");

        // 以 MySQL file_upload 表中已完成向量化的文件为基准（status=1 表示已完成）
        List<String> allMd5s = fileUploadRepository.findAll().stream()
                .filter(f -> f.getStatus() == 1)
                .map(com.zzzzyj.smartpai.model.FileUpload::getFileMd5)
                .distinct()
                .toList();

        logger.info("[对账] 共扫描 {} 个已向量化文件", allMd5s.size());

        int esOnly = 0, milvusOnly = 0, bothMissing = 0, normal = 0;

        for (String fileMd5 : allMd5s) {
            boolean inEs     = elasticsearchService.countByFileMd5(fileMd5) > 0;
            boolean inMilvus = milvusService.countByFileMd5(fileMd5) > 0;

            if (inEs && inMilvus) {
                normal++;
                continue;
            }

            if (inEs && !inMilvus) {
                // ES 有，Milvus 没有 → 补写 Milvus（触发完整重向量化会重写两端）
                esOnly++;
                logger.warn("[对账] ES有Milvus无，fileMd5={}，触发重建", fileMd5);
                triggerReindex(fileMd5);
            } else if (!inEs && inMilvus) {
                // Milvus 有，ES 没有 → 同样触发重建
                milvusOnly++;
                logger.warn("[对账] Milvus有ES无，fileMd5={}，触发重建", fileMd5);
                triggerReindex(fileMd5);
            } else {
                // 两端都没有
                bothMissing++;
                logger.warn("[对账] ES和Milvus均无数据，fileMd5={}，触发重建", fileMd5);
                triggerReindex(fileMd5);
            }
        }

        logger.info("[对账] 完成。正常={}, ES孤立={}, Milvus孤立={}, 双端缺失={}",
                normal, esOnly, milvusOnly, bothMissing);
    }

    /**
     * 触发指定文件的重新向量化（先清理两端旧数据，再双写）
     * 使用 DocumentService.reindexDocument 完整流程
     */
    private void triggerReindex(String fileMd5) {
        try {
            elasticsearchService.deleteByFileMd5(fileMd5);
            if (milvusService != null) {
                milvusService.deleteByFileMd5(fileMd5);
            }

            // 获取文件元信息
            var fileUpload = fileUploadRepository.findFirstByFileMd5OrderByCreatedAtDesc(fileMd5)
                    .orElse(null);
            if (fileUpload == null) {
                logger.warn("[对账] 找不到文件元信息，跳过重建，fileMd5={}", fileMd5);
                return;
            }

            // 仅重新向量化（chunks 已在 MySQL 中），不重新解析
            vectorizationService.vectorize(
                    fileMd5,
                    fileUpload.getUserId(),
                    fileUpload.getOrgTag(),
                    fileUpload.isPublic(),
                    "system-reconcile"
            );
            logger.info("[对账] 重建完成，fileMd5={}", fileMd5);
        } catch (Exception e) {
            logger.error("[对账] 重建失败，fileMd5={}，需人工介入", fileMd5, e);
        }
    }
}
