package com.zzzzyj.smartpai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzzzyj.smartpai.service.eval.EvalCase;
import com.zzzzyj.smartpai.service.eval.EvalHumanReviewService;
import com.zzzzyj.smartpai.service.eval.RagEvalService;
import com.zzzzyj.smartpai.utils.JwtUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 评测接口输入校验和检索身份传递，不调用外部服务。 */
class RagEvalAdminControllerTest {
    private final RagEvalService service = mock(RagEvalService.class);
    private final JwtUtils jwtUtils = mock(JwtUtils.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new RagEvalAdminController(service,
                mock(EvalHumanReviewService.class), new ObjectMapper(), jwtUtils)).build();
    }

    @Test
    void rejectsConflictingDatasetInputs() throws Exception {
        mvc.perform(post("/api/v1/admin/eval/run").header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"datasetPath\":\"a.jsonl\",\"jsonl\":\"data\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    @Test
    void passesAuthenticatedUserAndRetrievalOnlyToJob() throws Exception {
        when(jwtUtils.extractUsernameFromToken("token")).thenReturn("admin");
        when(jwtUtils.extractUserIdFromToken("token")).thenReturn("7");
        List<EvalCase> cases = List.of(new EvalCase("c1", "q", List.of(), List.of(), "a"));
        when(service.loadDataset("a.jsonl")).thenReturn(cases);
        RagEvalService.RunStatus result = new RagEvalService.RunStatus(
                "abc123", "baseline", "a.jsonl", 5, RagEvalService.RunState.RUNNING, 1, 0,
                "admin", null, null, null, null, java.time.Instant.now(), null);
        when(service.start(any(), anyString(), any(), any(), anyString(), anyString(), eq(true)))
                .thenReturn(result);
        mvc.perform(post("/api/v1/admin/eval/run").header("Authorization", "Bearer token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"datasetPath\":\"a.jsonl\",\"topK\":5,\"label\":\"baseline\",\"retrievalOnly\":true}"))
                .andExpect(status().isOk());
        verify(service).start(cases, "a.jsonl", 5, "baseline", "7", "admin", true);
    }
    @Test
    void previewsDatasetWithoutStartingJob() throws Exception {
        when(service.parseInline("test-data")).thenReturn(List.of(
                new EvalCase("c1", "q", List.of(), List.of(), "a")));
        mvc.perform(post("/api/v1/admin/eval/preview").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonl\":\"test-data\"}"))
                .andExpect(status().isOk());
        verify(service).parseInline("test-data");
        org.mockito.Mockito.verifyNoMoreInteractions(service);
    }

    @Test
    void rejectsEmptyPreview() throws Exception {
        mvc.perform(post("/api/v1/admin/eval/preview").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonl\":\"\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
}
