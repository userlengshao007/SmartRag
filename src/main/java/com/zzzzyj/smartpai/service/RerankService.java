package com.zzzzyj.smartpai.service;

import com.zzzzyj.smartpai.client.RerankClient;
import com.zzzzyj.smartpai.config.RerankProperties;
import com.zzzzyj.smartpai.entity.SearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/** 对 RRF 候选精排；远程调用失败时保留原始 RRF 排序和分数。 */
@Service
public class RerankService {
    private static final Logger logger = LoggerFactory.getLogger(RerankService.class);

    private final RerankClient client;
    private final RerankProperties properties;

    /** 注入模型客户端与排序配置。 */
    public RerankService(RerankClient client, RerankProperties properties) {
        this.client = client;
        this.properties = properties;
    }

    /** 获取融合阶段需要保留的候选数量，至少满足最终 topK。 */
    public int candidateLimit(int topK) {
        return properties.isEnabled() ? Math.max(topK, properties.getMaxCandidates()) : topK;
    }

    /** 排序并过滤低分结果；空结果表示没有足够相关的证据，不触发回退。 */
    public List<SearchResult> rerank(String query, List<SearchResult> candidates, int topK) {
        return rerankWithTrace(query, candidates, topK).results();
    }

    /** 同一批候选只调用一次模型，同时保留过滤前全量排名供评测分析。 */
    public Outcome rerankWithTrace(String query, List<SearchResult> candidates, int topK) {
        if (topK <= 0 || candidates.isEmpty()) {
            return new Outcome(List.of(), List.of(), "EMPTY", 0);
        }
        List<SearchResult> fallback = new ArrayList<>(candidates.subList(0, Math.min(topK, candidates.size())));
        if (!properties.isEnabled()) {
            return new Outcome(fallback, List.of(), "DISABLED", 0);
        }
        List<RerankClient.RerankHit> hits;
        try {
            List<String> documents = candidates.stream().map(this::documentText).toList();
            hits = client.rerank(query, documents);
        } catch (RuntimeException e) {
            logger.warn("重排序失败，回退 RRF: candidates={}, topK={}", candidates.size(), topK, e);
            return new Outcome(fallback, List.of(), "FAILED", 0);
        }
        List<SearchResult> ranked = new ArrayList<>();
        for (RerankClient.RerankHit hit : hits) {
            // 不修改 RRF 候选对象，确保原始分数和排序快照独立。
            SearchResult result = new SearchResult(null, null, null, null);
            org.springframework.beans.BeanUtils.copyProperties(candidates.get(hit.index()), result);
            result.setScore(hit.score());
            result.setRetrievalMode("RERANK");
            ranked.add(result);
        }
        int dropped = (int) ranked.stream().limit(topK)
                .filter(result -> result.getScore() < properties.getScoreThreshold()).count();
        List<SearchResult> results = ranked.stream()
                .filter(result -> result.getScore() >= properties.getScoreThreshold()).limit(topK).toList();
        return new Outcome(results, List.copyOf(ranked), "SUCCESS", dropped);
    }

    /** status 区分模型成功、关闭、异常回退和无候选；ranked 保留阈值过滤前全部结果。 */
    public record Outcome(List<SearchResult> results, List<SearchResult> ranked,
                          String status, int thresholdDroppedInTopK) {
    }

    /** 仅裁剪发送给模型的文本，保留返回结果的完整正文。 */
    private String documentText(SearchResult result) {
        String text = result.getTextContent() == null ? "" : result.getTextContent();
        return text.substring(0, Math.min(text.length(), Math.max(1, properties.getMaxDocumentChars())));
    }
}
