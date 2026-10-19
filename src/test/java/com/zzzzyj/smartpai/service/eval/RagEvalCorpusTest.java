package com.zzzzyj.smartpai.service.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzzzyj.smartpai.entity.SearchResult;
import com.zzzzyj.smartpai.service.ParseService;
import org.apache.tika.Tika;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 验证基准集与真实文本抽取、分块及证据匹配规则兼容，不调用外部模型。 */
class RagEvalCorpusTest {

    /** 文档抽取并切分后，所有正向题的每个期望文件仍须包含可匹配的证据。 */
    @Test
    void corpusEvidenceSurvivesTikaAndCurrentFixedChunking() throws Exception {
        Path base = Path.of("docs/eval/smartrag-v1");
        List<EvalCase> cases;
        try (var reader = Files.newBufferedReader(base.resolve("smartrag-eval-50-v1.jsonl"))) {
            cases = EvalDatasetParser.parse(reader, new ObjectMapper(), 500);
        }
        assertEquals(50, cases.size());
        assertEquals(44, cases.stream().filter(EvalCase::hasExpectedDocs).count());
        assertEquals(6, cases.stream().filter(item -> !item.hasExpectedDocs()).count());
        assertTrue(cases.stream().filter(EvalCase::hasExpectedDocs).allMatch(EvalCase::hasExpectedEvidence));
        assertEquals(Files.readString(base.resolve("smartrag-eval-50-v1.jsonl")),
                Files.readString(Path.of("src/main/resources/eval/smartrag-eval-50-v1.jsonl")));

        ParseService parser = new ParseService();
        ReflectionTestUtils.setField(parser, "chunkingStrategy", "fixed");
        ReflectionTestUtils.setField(parser, "overlapRatio", 0.1);
        Map<String, List<String>> chunksByFile = new HashMap<>();
        try (var files = Files.list(base.resolve("corpus"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".txt")).sorted().toList()) {
                String text;
                try (var in = Files.newInputStream(file)) {
                    text = new Tika().parseToString(in);
                }
                List<String> chunks = ReflectionTestUtils.invokeMethod(
                        parser, "splitTextIntoChunksWithSemantics", text, 512);
                assertTrue(chunks != null && chunks.size() >= 2, file.getFileName().toString());
                chunksByFile.put(file.getFileName().toString(), chunks);
            }
        }
        assertEquals(10, chunksByFile.size());
        int chunkCount = chunksByFile.values().stream().mapToInt(List::size).sum();
        assertTrue(chunkCount >= 25, "资料应形成足够多的候选块，而不是一块简介");
        for (EvalCase item : cases) {
            for (String file : item.expectedFileNames()) {
                assertTrue(chunksByFile.containsKey(file), item.id() + ": missing " + file);
                List<SearchResult> results = new ArrayList<>();
                List<String> chunks = chunksByFile.get(file);
                for (int index = 0; index < chunks.size(); index++) {
                    results.add(new SearchResult("fixture", index, chunks.get(index), 1.0, file));
                }
                assertTrue(results.stream().anyMatch(result -> RetrievalMetrics.isRelevant(result, item)),
                        item.id() + ": no chunk evidence in " + file);
            }
        }
    }
}
