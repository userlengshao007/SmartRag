package com.zzzzyj.smartpai.perf;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * End-to-end performance benchmarks for README metrics.
 *
 * <p>These tests intentionally target a running SmartRag backend through HTTP/WebSocket
 * instead of Spring mocks. They are skipped by default and only run with
 * {@code -Dperf.enabled=true}.</p>
 */
public class PerformanceBenchmarkTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(intProperty("perf.connectTimeoutSeconds", 10)))
            .build();

    @Test
    void runPerformanceBenchmarks() throws Exception {
        Assumptions.assumeTrue(booleanProperty("perf.enabled", false),
                "Set -Dperf.enabled=true to run end-to-end performance benchmarks");

        PerfConfig config = PerfConfig.fromSystemProperties();
        String token = resolveToken(config);
        List<String> report = new ArrayList<>();

        report.add("# SmartRag Performance Benchmark");
        report.add("");
        report.add("- Base URL: `" + config.baseUrl + "`");
        report.add("- Targets: `" + String.join(",", config.targets) + "`");
        report.add("- Time: `" + Instant.now() + "`");
        report.add("");

        if (config.targets.contains("ingest")) {
            report.addAll(runIngestionBenchmarks(config, token));
            report.add("");
        }
        if (config.targets.contains("search")) {
            report.addAll(runSearchBenchmarks(config, token));
            report.add("");
        }
        if (config.targets.contains("chat")) {
            report.addAll(runChatBenchmarks(config, token));
            report.add("");
        }

        String markdown = String.join(System.lineSeparator(), report);
        System.out.println();
        System.out.println(markdown);
        writeReport(config.outputPath, markdown);
    }

    private List<String> runIngestionBenchmarks(PerfConfig config, String token) throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add("## Document Ingestion Latency");
        lines.add("");
        lines.add("Latency is measured from `/api/v1/upload/merge` success until the uploaded document can be returned by `/api/v1/search/hybrid`.");
        lines.add("");
        lines.add("| File Size | Chunks | Estimated Tokens | Estimated Chunks | Ingestion Latency | Search Polls |");
        lines.add("| ---: | ---: | ---: | ---: | ---: | ---: |");

        for (int sizeMb : config.ingestFileSizesMb) {
            String uniqueKeyword = "PERF_" + UUID.randomUUID().toString().replace("-", "");
            String fileName = "perf-ingest-" + sizeMb + "mb-" + uniqueKeyword + ".txt";
            byte[] content = generateTextFile(sizeMb, uniqueKeyword);
            String fileMd5 = md5Hex(content);
            int totalChunks = (int) Math.ceil(content.length / (double) config.chunkSizeBytes);

            uploadChunks(config, token, fileMd5, fileName, content, totalChunks);

            long mergeStartNanos = System.nanoTime();
            Map<String, Object> mergeBody = postJson(
                    config.baseUrl + "/api/v1/upload/merge",
                    token,
                    Map.of("fileMd5", fileMd5, "fileName", fileName)
            );
            long mergeFinishedNanos = System.nanoTime();
            requireApiCode(mergeBody, 200, "merge file");

            @SuppressWarnings("unchecked")
            Map<String, Object> data = mergeBody.get("data") instanceof Map<?, ?> rawData
                    ? (Map<String, Object>) rawData
                    : Map.of();
            long estimatedTokens = longValue(data.get("estimatedEmbeddingTokens"));
            long estimatedChunks = longValue(data.get("estimatedChunkCount"));

            PollResult pollResult = pollUntilSearchable(config, token, uniqueKeyword, fileName);
            long latencyMillis = TimeUnit.NANOSECONDS.toMillis(pollResult.hitNanos - mergeFinishedNanos);
            long mergeMillis = TimeUnit.NANOSECONDS.toMillis(mergeFinishedNanos - mergeStartNanos);

            lines.add("| " + sizeMb + " MB | "
                    + totalChunks + " | "
                    + displayLong(estimatedTokens) + " | "
                    + displayLong(estimatedChunks) + " | "
                    + latencyMillis + " ms"
                    + " (merge " + mergeMillis + " ms) | "
                    + pollResult.polls + " |");
        }

        return lines;
    }

    private List<String> runSearchBenchmarks(PerfConfig config, String token) throws Exception {
        List<String> lines = new ArrayList<>();
        List<Long> allDurations = new ArrayList<>();

        for (String query : config.searchQueries) {
            for (int i = 0; i < config.searchWarmupRuns; i++) {
                search(config, token, query, config.searchTopK);
            }
        }

        for (String query : config.searchQueries) {
            for (int i = 0; i < config.searchRunsPerQuery; i++) {
                long start = System.nanoTime();
                Map<String, Object> response = search(config, token, query, config.searchTopK);
                requireApiCode(response, 200, "hybrid search");
                allDurations.add(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            }
        }

        lines.add("## Hybrid Search Latency");
        lines.add("");
        lines.add("Latency is measured as end-to-end HTTP response time for `/api/v1/search/hybrid` after warmup.");
        lines.add("");
        lines.add("| Queries | Runs / Query | topK | p50 | p95 | p99 | Avg |");
        lines.add("| ---: | ---: | ---: | ---: | ---: | ---: | ---: |");
        lines.add("| " + config.searchQueries.size() + " | "
                + config.searchRunsPerQuery + " | "
                + config.searchTopK + " | "
                + percentile(allDurations, 0.50) + " ms | "
                + percentile(allDurations, 0.95) + " ms | "
                + percentile(allDurations, 0.99) + " ms | "
                + average(allDurations) + " ms |");
        return lines;
    }

    private List<String> runChatBenchmarks(PerfConfig config, String token) throws Exception {
        List<ChatRunResult> results = new ArrayList<>();
        for (int i = 0; i < config.chatRuns; i++) {
            String prompt = config.chatPrompt + " 第" + (i + 1) + "次测试。";
            results.add(measureChat(config, token, prompt));
            Thread.sleep(config.chatPauseMillis);
        }

        List<Long> ttft = results.stream()
                .filter(ChatRunResult::success)
                .map(ChatRunResult::ttftMillis)
                .toList();
        List<Long> total = results.stream()
                .filter(ChatRunResult::success)
                .map(ChatRunResult::totalMillis)
                .toList();
        double successRate = results.isEmpty()
                ? 0.0
                : results.stream().filter(ChatRunResult::success).count() * 100.0 / results.size();
        double avgToolCalls = results.stream()
                .mapToInt(ChatRunResult::toolCalls)
                .average()
                .orElse(0.0);

        List<String> lines = new ArrayList<>();
        lines.add("## Chat TTFT");
        lines.add("");
        lines.add("TTFT is measured from WebSocket message send until the first non-empty `type=chunk` payload.");
        lines.add("");
        lines.add("| Runs | Avg Tool Calls | TTFT p50 | TTFT p95 | Full Response p95 | Success Rate |");
        lines.add("| ---: | ---: | ---: | ---: | ---: | ---: |");
        lines.add("| " + config.chatRuns + " | "
                + String.format(Locale.ROOT, "%.2f", avgToolCalls) + " | "
                + displayMetric(percentileOrNull(ttft, 0.50)) + " | "
                + displayMetric(percentileOrNull(ttft, 0.95)) + " | "
                + displayMetric(percentileOrNull(total, 0.95)) + " | "
                + String.format(Locale.ROOT, "%.1f%%", successRate) + " |");

        lines.add("");
        lines.add("| Run | TTFT | Total | Tool Calls | Status |");
        lines.add("| ---: | ---: | ---: | ---: | --- |");
        for (int i = 0; i < results.size(); i++) {
            ChatRunResult item = results.get(i);
            lines.add("| " + (i + 1) + " | "
                    + displayMetric(item.ttftMillisOrNull()) + " | "
                    + displayMetric(item.totalMillisOrNull()) + " | "
                    + item.toolCalls + " | "
                    + item.status + " |");
        }
        return lines;
    }

    private void uploadChunks(PerfConfig config,
                              String token,
                              String fileMd5,
                              String fileName,
                              byte[] content,
                              int totalChunks) throws Exception {
        for (int chunkIndex = 0; chunkIndex < totalChunks; chunkIndex++) {
            int start = chunkIndex * config.chunkSizeBytes;
            int end = Math.min(content.length, start + config.chunkSizeBytes);
            byte[] chunk = Arrays.copyOfRange(content, start, end);
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("fileMd5", fileMd5);
            fields.put("chunkIndex", String.valueOf(chunkIndex));
            fields.put("totalSize", String.valueOf(content.length));
            fields.put("fileName", fileName);
            fields.put("totalChunks", String.valueOf(totalChunks));
            fields.put("isPublic", "true");

            HttpRequest request = multipartRequest(
                    URI.create(config.baseUrl + "/api/v1/upload/chunk"),
                    token,
                    fields,
                    "file",
                    fileName,
                    "text/plain",
                    chunk
            );
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            requireHttpSuccess(response, "upload chunk " + chunkIndex);
            requireApiCode(readJson(response.body()), 200, "upload chunk " + chunkIndex);
        }
    }

    private PollResult pollUntilSearchable(PerfConfig config,
                                           String token,
                                           String uniqueKeyword,
                                           String fileName) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(config.ingestTimeoutSeconds);
        int polls = 0;
        while (System.nanoTime() < deadline) {
            polls++;
            Map<String, Object> response = search(config, token, uniqueKeyword, 5);
            if (containsText(response.get("data"), uniqueKeyword) || containsText(response.get("data"), fileName)) {
                return new PollResult(polls, System.nanoTime());
            }
            Thread.sleep(config.ingestPollMillis);
        }
        throw new IllegalStateException("Timed out waiting for uploaded document to become searchable: " + uniqueKeyword);
    }

    private Map<String, Object> search(PerfConfig config, String token, String query, int topK) throws Exception {
        String url = config.baseUrl
                + "/api/v1/search/hybrid?query="
                + URLEncoder.encode(query, StandardCharsets.UTF_8)
                + "&topK="
                + topK;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(config.httpTimeoutSeconds))
                .header("Authorization", "Bearer " + token)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        requireHttpSuccess(response, "hybrid search");
        return readJson(response.body());
    }

    private ChatRunResult measureChat(PerfConfig config, String token, String prompt) throws Exception {
        String wsUrl = config.wsUrl + "/chat/" + URLEncoder.encode(token, StandardCharsets.UTF_8);
        CompletableFuture<ChatRunResult> completed = new CompletableFuture<>();
        AtomicLong firstChunkNanos = new AtomicLong(-1);
        AtomicLong completionNanos = new AtomicLong(-1);
        AtomicInteger toolCalls = new AtomicInteger(0);
        Set<String> executingToolCallIds = java.util.Collections.synchronizedSet(new LinkedHashSet<>());
        long sendNanos = System.nanoTime();

        WebSocket webSocket = httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(config.httpTimeoutSeconds))
                .buildAsync(URI.create(wsUrl), new WebSocket.Listener() {
                    private final StringBuilder partial = new StringBuilder();

                    @Override
                    public void onOpen(WebSocket webSocket) {
                        webSocket.request(1);
                    }

                    @Override
                    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                        partial.append(data);
                        if (last) {
                            handleChatPayload(partial.toString(), sendNanos, firstChunkNanos, completionNanos,
                                    toolCalls, executingToolCallIds, completed);
                            partial.setLength(0);
                        }
                        webSocket.request(1);
                        return CompletableFuture.completedFuture(null);
                    }

                    @Override
                    public void onError(WebSocket webSocket, Throwable error) {
                        completed.complete(new ChatRunResult(null, null, toolCalls.get(), "error: " + error.getMessage()));
                    }
                })
                .get(config.httpTimeoutSeconds, TimeUnit.SECONDS);

        webSocket.sendText(prompt, true).get(config.httpTimeoutSeconds, TimeUnit.SECONDS);

        ChatRunResult result;
        try {
            result = completed.get(config.chatTimeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception timeout) {
            result = new ChatRunResult(
                    firstChunkNanos.get() > 0 ? TimeUnit.NANOSECONDS.toMillis(firstChunkNanos.get() - sendNanos) : null,
                    null,
                    toolCalls.get(),
                    "timeout"
            );
        } finally {
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "benchmark complete");
        }
        return result;
    }

    private void handleChatPayload(String rawPayload,
                                   long sendNanos,
                                   AtomicLong firstChunkNanos,
                                   AtomicLong completionNanos,
                                   AtomicInteger toolCalls,
                                   Set<String> executingToolCallIds,
                                   CompletableFuture<ChatRunResult> completed) {
        try {
            Map<String, Object> payload = readJson(rawPayload);
            String type = stringValue(payload.get("type"));
            if ("tool_call".equals(type)) {
                String status = stringValue(payload.get("status"));
                String id = stringValue(payload.get("toolCallId"));
                String tool = stringValue(payload.get("tool"));
                String key = !id.isBlank() ? id : tool;
                if ("executing".equals(status) && !key.isBlank() && executingToolCallIds.add(key)) {
                    toolCalls.incrementAndGet();
                }
                return;
            }
            if ("chunk".equals(type) && !stringValue(payload.get("chunk")).isBlank()) {
                firstChunkNanos.compareAndSet(-1, System.nanoTime());
                return;
            }
            if ("completion".equals(type)) {
                completionNanos.compareAndSet(-1, System.nanoTime());
                Long ttft = firstChunkNanos.get() > 0
                        ? TimeUnit.NANOSECONDS.toMillis(firstChunkNanos.get() - sendNanos)
                        : null;
                Long total = TimeUnit.NANOSECONDS.toMillis(completionNanos.get() - sendNanos);
                String status = stringValue(payload.get("status"));
                completed.complete(new ChatRunResult(ttft, total, toolCalls.get(), status.isBlank() ? "finished" : status));
            }
        } catch (Exception ignored) {
            // The server also sends connection messages and heartbeat payloads. They are not part of TTFT.
        }
    }

    private String resolveToken(PerfConfig config) throws Exception {
        if (config.token != null && !config.token.isBlank()) {
            return config.token.trim();
        }
        if (config.username == null || config.username.isBlank()
                || config.password == null || config.password.isBlank()) {
            throw new IllegalArgumentException("Provide -Dperf.token=... or -Dperf.username=... -Dperf.password=...");
        }
        Map<String, Object> response = postJson(
                config.baseUrl + "/api/v1/users/login",
                null,
                Map.of("username", config.username, "password", config.password)
        );
        requireApiCode(response, 200, "login");
        @SuppressWarnings("unchecked")
        Map<String, Object> data = response.get("data") instanceof Map<?, ?> rawData
                ? (Map<String, Object>) rawData
                : Map.of();
        String token = stringValue(data.get("token"));
        if (token.isBlank()) {
            throw new IllegalStateException("Login response did not contain data.token");
        }
        return token;
    }

    private Map<String, Object> postJson(String url, String token, Object body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(intProperty("perf.httpTimeoutSeconds", 60)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(OBJECT_MAPPER.writeValueAsString(body)));
        if (token != null && !token.isBlank()) {
            builder.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        requireHttpSuccess(response, "POST " + url);
        return readJson(response.body());
    }

    private HttpRequest multipartRequest(URI uri,
                                         String token,
                                         Map<String, String> fields,
                                         String fileFieldName,
                                         String fileName,
                                         String contentType,
                                         byte[] fileBytes) {
        String boundary = "----SmartRagPerfBoundary" + UUID.randomUUID();
        List<byte[]> parts = new ArrayList<>();
        for (Map.Entry<String, String> field : fields.entrySet()) {
            parts.add(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"" + field.getKey() + "\"\r\n\r\n"
                    + field.getValue() + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        parts.add(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + fileFieldName + "\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        parts.add(fileBytes);
        parts.add(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        return HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(intProperty("perf.httpTimeoutSeconds", 60)))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArrays(parts))
                .build();
    }

    private byte[] generateTextFile(int sizeMb, String uniqueKeyword) {
        int sizeBytes = sizeMb * 1024 * 1024;
        String seed = """
                SmartRag performance benchmark document.
                Unique keyword: %s.
                This file is generated for measuring document ingestion latency, parsing, chunking, embedding, and Elasticsearch indexing.
                """.formatted(uniqueKeyword);
        StringBuilder builder = new StringBuilder(sizeBytes + seed.length());
        while (builder.length() < sizeBytes) {
            builder.append(seed);
        }
        return builder.substring(0, sizeBytes).getBytes(StandardCharsets.UTF_8);
    }

    private Map<String, Object> readJson(String body) throws IOException {
        if (body == null || body.isBlank()) {
            return Map.of();
        }
        return OBJECT_MAPPER.readValue(body, MAP_TYPE);
    }

    private void requireHttpSuccess(HttpResponse<String> response, String operation) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException(operation + " failed with HTTP "
                    + response.statusCode() + ": " + response.body());
        }
    }

    private void requireApiCode(Map<String, Object> body, int expected, String operation) {
        int code = intValue(body.get("code"), expected);
        if (code != expected) {
            throw new IllegalStateException(operation + " failed with code "
                    + code + ": " + body);
        }
    }

    private boolean containsText(Object value, String needle) {
        if (value == null || needle == null || needle.isBlank()) {
            return false;
        }
        if (String.valueOf(value).contains(needle)) {
            return true;
        }
        if (value instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (containsText(item, needle)) {
                    return true;
                }
            }
        }
        if (value instanceof Map<?, ?> map) {
            for (Object item : map.values()) {
                if (containsText(item, needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void writeReport(String outputPath, String markdown) throws IOException {
        Path path = Path.of(outputPath);
        if (path.getParent() != null) {
            Files.createDirectories(path.getParent());
        }
        Files.writeString(path, markdown, StandardCharsets.UTF_8);
    }

    private static String md5Hex(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("MD5");
        byte[] hash = digest.digest(bytes);
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }

    private static long percentile(List<Long> values, double percentile) {
        Long result = percentileOrNull(values, percentile);
        return result == null ? 0L : result;
    }

    private static Long percentileOrNull(List<Long> values, double percentile) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        List<Long> sorted = values.stream().sorted().toList();
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static long average(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return 0L;
        }
        return Math.round(values.stream().mapToLong(Long::longValue).average().orElse(0.0));
    }

    private static String displayMetric(Long millis) {
        return millis == null ? "N/A" : millis + " ms";
    }

    private static String displayLong(long value) {
        return value <= 0 ? "N/A" : String.valueOf(value);
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int intValue(Object value, int defaultValue) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static long longValue(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value != null) {
            try {
                return Long.parseLong(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return 0L;
            }
        }
        return 0L;
    }

    private static boolean booleanProperty(String key, boolean defaultValue) {
        return Boolean.parseBoolean(System.getProperty(key, String.valueOf(defaultValue)));
    }

    private static int intProperty(String key, int defaultValue) {
        return Integer.parseInt(System.getProperty(key, String.valueOf(defaultValue)));
    }

    private static List<Integer> intListProperty(String key, String defaultValue) {
        return Arrays.stream(System.getProperty(key, defaultValue).split(","))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .map(Integer::parseInt)
                .toList();
    }

    private static List<String> stringListProperty(String key, String defaultValue) {
        return Arrays.stream(System.getProperty(key, defaultValue).split("\\|"))
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
    }

    private record PollResult(int polls, long hitNanos) {
    }

    private record ChatRunResult(Long ttftMillis, Long totalMillis, int toolCalls, String status) {
        private boolean success() {
            return "finished".equals(status) && ttftMillis != null && totalMillis != null;
        }

        private Long ttftMillisOrNull() {
            return ttftMillis;
        }

        private Long totalMillisOrNull() {
            return totalMillis;
        }
    }

    private static final class PerfConfig {
        private final String baseUrl;
        private final String wsUrl;
        private final String token;
        private final String username;
        private final String password;
        private final Set<String> targets;
        private final int httpTimeoutSeconds;
        private final int chunkSizeBytes;
        private final List<Integer> ingestFileSizesMb;
        private final int ingestTimeoutSeconds;
        private final int ingestPollMillis;
        private final List<String> searchQueries;
        private final int searchTopK;
        private final int searchWarmupRuns;
        private final int searchRunsPerQuery;
        private final String chatPrompt;
        private final int chatRuns;
        private final int chatTimeoutSeconds;
        private final int chatPauseMillis;
        private final String outputPath;

        private PerfConfig(String baseUrl,
                           String wsUrl,
                           String token,
                           String username,
                           String password,
                           Set<String> targets,
                           int httpTimeoutSeconds,
                           int chunkSizeBytes,
                           List<Integer> ingestFileSizesMb,
                           int ingestTimeoutSeconds,
                           int ingestPollMillis,
                           List<String> searchQueries,
                           int searchTopK,
                           int searchWarmupRuns,
                           int searchRunsPerQuery,
                           String chatPrompt,
                           int chatRuns,
                           int chatTimeoutSeconds,
                           int chatPauseMillis,
                           String outputPath) {
            this.baseUrl = baseUrl;
            this.wsUrl = wsUrl;
            this.token = token;
            this.username = username;
            this.password = password;
            this.targets = targets;
            this.httpTimeoutSeconds = httpTimeoutSeconds;
            this.chunkSizeBytes = chunkSizeBytes;
            this.ingestFileSizesMb = ingestFileSizesMb;
            this.ingestTimeoutSeconds = ingestTimeoutSeconds;
            this.ingestPollMillis = ingestPollMillis;
            this.searchQueries = searchQueries;
            this.searchTopK = searchTopK;
            this.searchWarmupRuns = searchWarmupRuns;
            this.searchRunsPerQuery = searchRunsPerQuery;
            this.chatPrompt = chatPrompt;
            this.chatRuns = chatRuns;
            this.chatTimeoutSeconds = chatTimeoutSeconds;
            this.chatPauseMillis = chatPauseMillis;
            this.outputPath = outputPath;
        }

        private static PerfConfig fromSystemProperties() {
            String baseUrl = System.getProperty("perf.baseUrl", "http://localhost:8081").replaceAll("/+$", "");
            String defaultWsUrl = baseUrl.replaceFirst("^http://", "ws://").replaceFirst("^https://", "wss://");
            Set<String> targets = new LinkedHashSet<>(Arrays.stream(System.getProperty("perf.targets", "ingest,search,chat").split(","))
                    .map(String::trim)
                    .map(item -> item.toLowerCase(Locale.ROOT))
                    .filter(item -> !item.isBlank())
                    .toList());
            return new PerfConfig(
                    baseUrl,
                    System.getProperty("perf.wsUrl", defaultWsUrl).replaceAll("/+$", ""),
                    System.getProperty("perf.token", ""),
                    System.getProperty("perf.username", ""),
                    System.getProperty("perf.password", ""),
                    targets,
                    intProperty("perf.httpTimeoutSeconds", 60),
                    intProperty("perf.chunkSizeBytes", 5 * 1024 * 1024),
                    intListProperty("perf.ingest.fileSizesMb", "1"),
                    intProperty("perf.ingest.timeoutSeconds", 300),
                    intProperty("perf.ingest.pollMillis", 2000),
                    stringListProperty("perf.search.queries",
                            "SmartRag 核心功能|文档上传|混合检索|权限过滤|ReAct 工具调用|长期记忆"),
                    intProperty("perf.search.topK", 5),
                    intProperty("perf.search.warmupRuns", 5),
                    intProperty("perf.search.runsPerQuery", 30),
                    System.getProperty("perf.chat.prompt", "请根据知识库介绍一下 SmartRag 的核心功能，并给出引用来源。"),
                    intProperty("perf.chat.runs", 3),
                    intProperty("perf.chat.timeoutSeconds", 180),
                    intProperty("perf.chat.pauseMillis", 1000),
                    System.getProperty("perf.output", "target/perf-results.md")
            );
        }
    }
}
