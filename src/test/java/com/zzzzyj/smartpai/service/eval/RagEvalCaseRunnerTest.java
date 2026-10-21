package com.zzzzyj.smartpai.service.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzzzyj.smartpai.config.RagEvalProperties;
import com.zzzzyj.smartpai.entity.SearchResult;
import com.zzzzyj.smartpai.service.HybridSearchService;
import com.zzzzyj.smartpai.service.LlmProviderRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RagEvalCaseRunnerTest {

    private final HybridSearchService searchService = mock(HybridSearchService.class);
    private final LlmProviderRouter router = mock(LlmProviderRouter.class);
    private final RagEvalProperties properties = new RagEvalProperties();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HumanReviewSampler noSampling = new HumanReviewSampler(0d, new Random(1));
    private final List<List<Map<String, Object>>> llmCalls = new ArrayList<>();
    private final List<String> llmReplies = new ArrayList<>();
    /** 与 llmReplies 一一对应，为空时按 stop 处理 */
    private final List<String> llmFinishReasons = new ArrayList<>();
    private final List<Integer> llmMaxTokens = new ArrayList<>();
    private RagEvalCaseRunner runner;

    @BeforeEach
    void setUp() {
        when(searchService.searchWithPermissionTrace(anyString(), anyString(), anyInt())).thenAnswer(invocation -> {
            List<SearchResult> results = searchService.searchWithPermission(
                    invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            return new HybridSearchService.SearchTrace(results, List.of(), List.of(), "UNAVAILABLE", 0);
        });
        runner = new RagEvalCaseRunner(searchService, router,
                properties, objectMapper, Runnable::run);
        when(router.buildRagMessages(anyString(), anyString()))
                .thenAnswer(invocation -> List.of(Map.<String, Object>of("role", "user", "content", invocation.getArgument(0))));
        when(router.completeReActTurn(anyString(), anyList(), anyList(), anyInt())).thenAnswer(invocation -> {
            List<Map<String, Object>> messages = invocation.getArgument(1);
            llmCalls.add(messages);
            llmMaxTokens.add(invocation.getArgument(3));
            String reply = llmReplies.remove(0);
            String finishReason = llmFinishReasons.isEmpty() ? "stop" : llmFinishReasons.remove(0);
            if (reply == null) {
                throw new RuntimeException("upstream 500");
            }
            return new LlmProviderRouter.ReActTurn(reply, List.of(), Map.of(), finishReason, 1, 1);
        });
    }

    @Test
    void retrievalHitAndCorrectAnswer() throws Exception {
        when(searchService.searchWithPermission("端口？", "7", 3)).thenReturn(List.of(
                result("other", "x.pdf", "HYBRID"), result("m1", "config.pdf", "HYBRID")));
        llmReplies.add("端口是 8080（来源#2: config.pdf）");
        llmReplies.add("{\"score\": 5, \"correct\": true, \"reason\": \"一致\"}");

        EvalCaseResult result = runner.run(evalCase(List.of("m1")), "7", 3, noSampling);

        assertEquals("HYBRID", result.retrievalMode());
        assertTrue(result.retrievalHit());
        assertEquals(2, result.firstRelevantRank());
        assertEquals(0.5, result.reciprocalRank(), 1e-9);
        assertTrue(result.retrieved().get(1).relevant());
        assertEquals("端口配置为 8080", result.retrieved().get(1).textContent());
        assertEquals("端口是 8080（来源#2: config.pdf）", result.answer());
        assertEquals(5, result.judgeScore());
        assertEquals(EvalDiagnosis.OK, result.diagnosis());
        assertFalse(result.needsHumanReview());

        // 生成和评委是两次独立调用；评委的输入作为 JSON 数据放在 user 消息里
        assertEquals(2, llmCalls.size());
        List<Map<String, Object>> judgeMessages = llmCalls.get(1);
        assertEquals(RagEvalCaseRunner.JUDGE_SYSTEM_PROMPT, judgeMessages.get(0).get("content"));
        JsonNode payload = objectMapper.readTree((String) judgeMessages.get(1).get("content"));
        assertEquals("端口？", payload.get("question").asText());
        assertEquals("8080", payload.get("reference_answer").asText());
        assertEquals(2, payload.get("evidence").size());
    }

    @Test
    void retrievalOnlyRecordsRerankedOrderAndScores() {
        SearchResult relevant = result("m1", "config.pdf", "RERANK");
        relevant.setScore(0.93);
        when(searchService.searchWithPermission("端口？", "7", 3)).thenReturn(List.of(relevant));

        EvalCaseResult report = runner.run(evalCase(List.of("m1")), "7", 3, noSampling, true);

        verify(searchService).searchWithPermission("端口？", "7", 3);
        assertEquals("RERANK", report.retrievalMode());
        assertEquals(0.93, report.retrieved().get(0).score());
        assertEquals(1, report.firstRelevantRank());
        assertEquals(1d, report.reciprocalRank());
        assertEquals(1, report.retrieved().size());
        assertTrue(llmCalls.isEmpty());
    }

    @Test
    void sameCandidatesExposeRerankRegression() {
        SearchResult relevant = result("m1", "config.pdf", "RRF");
        SearchResult other = result("other", "x.pdf", "RRF");
        org.mockito.Mockito.doReturn(new HybridSearchService.SearchTrace(List.of(other), List.of(relevant, other),
                List.of(other, relevant), "SUCCESS", 0))
                .when(searchService).searchWithPermissionTrace("端口？", "7", 1);
        EvalCaseResult report = runner.run(evalCase(List.of("m1")), "7", 1, noSampling, true);
        assertTrue(report.stages().candidateHit());
        assertTrue(report.stages().comparison().rrfHit());
        assertFalse(report.stages().comparison().rerankHit());
        assertEquals("REGRESSED", report.stages().comparison().change());
        assertEquals(2, report.stages().comparison().reranked().size());
        assertEquals(1, report.stages().candidates().get(0).rank());
        EvalReport.StageSummary summary = EvalReportAggregator.summarize(List.of(report)).stages();
        assertEquals(1, summary.comparedCases());
        assertEquals(1d, summary.rrfHitRate());
        assertEquals(0d, summary.rerankHitRate());
        assertEquals(1, summary.regressedCases());
    }

    @Test
    void thresholdFilteringDoesNotLookLikeRankingRegression() {
        SearchResult relevant = result("m1", "config.pdf", "RRF");
        org.mockito.Mockito.doReturn(new HybridSearchService.SearchTrace(List.of(), List.of(relevant),
                List.of(relevant), "SUCCESS", 1))
                .when(searchService).searchWithPermissionTrace("端口？", "7", 1);
        EvalCaseResult report = runner.run(evalCase(List.of("m1")), "7", 1, noSampling, true);
        assertFalse(report.retrievalHit());
        assertTrue(report.stages().comparison().rerankHit());
        assertEquals("UNCHANGED", report.stages().comparison().change());
        assertEquals(1, report.stages().thresholdDroppedInTopK());
    }

    @Test
    void failedRerankIsExcludedFromModelComparison() {
        SearchResult relevant = result("m1", "config.pdf", "RRF");
        org.mockito.Mockito.doReturn(new HybridSearchService.SearchTrace(List.of(relevant), List.of(relevant),
                List.of(), "FAILED", 0))
                .when(searchService).searchWithPermissionTrace("端口？", "7", 1);
        EvalCaseResult report = runner.run(evalCase(List.of("m1")), "7", 1, noSampling, true);
        assertTrue(report.retrievalHit());
        assertNull(report.stages().comparison().rerankHit());
        assertEquals(0, EvalReportAggregator.summarize(List.of(report)).stages().comparedCases());
    }

    @Test
    void missingEvidenceInCandidatesIsRecallProblem() {
        SearchResult other = result("other", "x.pdf", "RRF");
        org.mockito.Mockito.doReturn(new HybridSearchService.SearchTrace(List.of(other), List.of(other),
                List.of(other), "SUCCESS", 0))
                .when(searchService).searchWithPermissionTrace("端口？", "7", 1);
        EvalCaseResult report = runner.run(evalCase(List.of("m1")), "7", 1, noSampling, true);
        assertFalse(report.stages().candidateHit());
        assertEquals("CANDIDATE_MISS", report.stages().comparison().change());
    }

    @Test
    void malformedJudgeOutputAlwaysNeedsHumanReview() {
        when(searchService.searchWithPermission(anyString(), eq("7"), anyInt())).thenReturn(List.of(result("m1", "config.pdf", "HYBRID_RRF")));
        llmReplies.add("端口是 8080");
        llmReplies.add("我给 5 分");

        EvalCaseResult result = runner.run(evalCase(List.of("m1")), "7", 3, noSampling);

        assertEquals("HYBRID_RRF", result.retrievalMode());
        assertEquals(EvalDiagnosis.JUDGE_FAILED, result.diagnosis());
        assertTrue(result.needsHumanReview());
        assertEquals(HumanReviewSampler.REASON_JUDGE_FAILED, result.reviewReason());
        assertNull(result.judgeScore());
        assertEquals("我给 5 分", result.judgeRawOutput());
    }

    @Test
    void retrievalMissIsDiagnosedAsRetrievalProblem() {
        when(searchService.searchWithPermission(anyString(), eq("7"), anyInt())).thenReturn(List.of(result("other", "x.pdf", "TEXT_ONLY")));
        llmReplies.add("资料中没有相关说明");
        llmReplies.add("{\"score\": 1, \"correct\": false, \"reason\": \"没答出来\"}");

        EvalCaseResult result = runner.run(evalCase(List.of("m1")), "7", 3, noSampling);

        assertFalse(result.retrievalHit());
        assertEquals(0d, result.reciprocalRank(), 1e-9);
        assertEquals("TEXT_ONLY", result.retrievalMode());
        assertEquals(EvalDiagnosis.RETRIEVAL_MISS, result.diagnosis());
    }

    @Test
    void retrievalMissJudgedCorrectAlwaysNeedsHumanReview() {
        when(searchService.searchWithPermission(anyString(), eq("7"), anyInt())).thenReturn(List.of(result("other", "x.pdf", "HYBRID")));
        llmReplies.add("端口是 8080");
        llmReplies.add("{\"score\": 5, \"correct\": true, \"reason\": \"一致\"}");

        EvalCaseResult result = runner.run(evalCase(List.of("m1")), "7", 3, noSampling);

        assertEquals(EvalDiagnosis.MISS_BUT_CORRECT, result.diagnosis());
        assertTrue(result.needsHumanReview());
        assertEquals(HumanReviewSampler.REASON_MISS_BUT_CORRECT, result.reviewReason());
    }

    @Test
    void generationFailureSkipsJudge() {
        when(searchService.searchWithPermission(anyString(), eq("7"), anyInt())).thenReturn(List.of(result("m1", "config.pdf", "HYBRID")));
        llmReplies.add(null);

        EvalCaseResult result = runner.run(evalCase(List.of("m1")), "7", 3, noSampling);

        assertEquals(EvalDiagnosis.GENERATION_FAILED, result.diagnosis());
        assertEquals(1, llmCalls.size());
        assertTrue(result.error().contains("upstream 500"));
        assertFalse(result.needsHumanReview());
    }

    @Test
    void judgeTruncatedByReasoningIsRetriedWithDoubledBudget() {
        when(searchService.searchWithPermission(anyString(), eq("7"), anyInt())).thenReturn(List.of(result("m1", "config.pdf", "HYBRID")));
        llmReplies.addAll(List.of("端口是 8080", "", "{\"score\": 5, \"correct\": true, \"reason\": \"一致\"}"));
        llmFinishReasons.addAll(List.of("stop", "length", "stop"));

        EvalCaseResult result = runner.run(evalCase(List.of("m1")), "7", 3, noSampling);

        assertEquals(EvalDiagnosis.OK, result.diagnosis());
        assertEquals(List.of(4000, 2000, 4000), llmMaxTokens);
    }

    @Test
    void emptyAnswerAfterRetryFailsWithFinishReasonAndCappedBudget() {
        properties.setMaxRetryTokens(6000);
        when(searchService.searchWithPermission(anyString(), eq("7"), anyInt())).thenReturn(List.of(result("m1", "config.pdf", "HYBRID")));
        llmReplies.addAll(List.of("", " "));
        llmFinishReasons.addAll(List.of("length", "length"));

        EvalCaseResult result = runner.run(evalCase(List.of("m1")), "7", 3, noSampling);

        assertEquals(EvalDiagnosis.GENERATION_FAILED, result.diagnosis());
        assertTrue(result.error().contains("finish_reason=length"));
        assertEquals(List.of(4000, 6000), llmMaxTokens, "重试预算翻倍但不超过 max-retry-tokens");
    }

    @Test
    void emptyOutputNotCausedByLengthRetriesWithSameBudget() {
        when(searchService.searchWithPermission(anyString(), eq("7"), anyInt())).thenReturn(List.of(result("m1", "config.pdf", "HYBRID")));
        llmReplies.addAll(List.of("端口是 8080", "", ""));

        EvalCaseResult result = runner.run(evalCase(List.of("m1")), "7", 3, noSampling);

        assertEquals(EvalDiagnosis.JUDGE_FAILED, result.diagnosis());
        assertTrue(result.error().contains("评委输出为空（finish_reason=stop）"));
        assertEquals(List.of(4000, 2000, 2000), llmMaxTokens);
    }

    @Test
    void emptyRetrievalUsesNoResultPromptAndModeNone() {
        when(searchService.searchWithPermission(anyString(), eq("7"), anyInt())).thenReturn(List.of());
        llmReplies.add("暂无相关信息");
        llmReplies.add("{\"score\": 1, \"correct\": false}");

        EvalCaseResult result = runner.run(evalCase(List.of()), "7", 3, noSampling);

        assertEquals("NONE", result.retrievalMode());
        assertNull(result.retrievalHit());
        assertEquals(EvalDiagnosis.NOT_LABELED, result.diagnosis());
    }

    @Test
    void retrievalExceptionIsRecorded() {
        when(searchService.searchWithPermission(anyString(), eq("7"), anyInt())).thenThrow(new IllegalStateException("es down"));

        EvalCaseResult result = runner.run(evalCase(List.of("m1")), "7", 3, noSampling);

        assertEquals(EvalDiagnosis.RETRIEVAL_FAILED, result.diagnosis());
        assertTrue(llmCalls.isEmpty());
    }

    @Test
    void retrievalOnlySkipsGenerationAndJudge() {
        when(searchService.searchWithPermission("端口？", "7", 3))
                .thenReturn(List.of(result("m1", "config.pdf", "HYBRID_RRF")));
        EvalCaseResult result = runner.run(evalCase(List.of("m1")), "7", 3, noSampling, true);
        assertTrue(result.retrievalHit());
        assertNull(result.answer());
        assertNull(result.judgeScore());
        assertNull(result.stages());
        org.mockito.Mockito.verifyNoInteractions(router);
    }

    private static EvalCase evalCase(List<String> md5s) {
        return new EvalCase("c1", "端口？", md5s, List.of(), "8080");
    }

    private static SearchResult result(String md5, String fileName, String mode) {
        return new SearchResult(md5, 1, "端口配置为 8080", 0.9, "7", "dept", false, fileName, null, null, mode, null);
    }

}
