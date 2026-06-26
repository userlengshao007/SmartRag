# Performance Benchmark

This benchmark is skipped by default. It targets a running SmartRag backend through HTTP and WebSocket, then prints Markdown tables for README usage.

## Run All Metrics

```bash
/Users/lengshao/.m2/wrapper/dists/apache-maven-3.9.12-bin/5nmfsn99br87k5d4ajlekdq10k/apache-maven-3.9.12/bin/mvn \
  -Dtest=PerformanceBenchmarkTest \
  -Dperf.enabled=true \
  -Dperf.baseUrl=http://localhost:8081 \
  -Dperf.username=YOUR_USERNAME \
  -Dperf.password=YOUR_PASSWORD \
  test
```

The generated report is written to:

```text
target/perf-results.md
```

You can also pass an existing JWT directly:

```bash
/Users/lengshao/.m2/wrapper/dists/apache-maven-3.9.12-bin/5nmfsn99br87k5d4ajlekdq10k/apache-maven-3.9.12/bin/mvn \
  -Dtest=PerformanceBenchmarkTest \
  -Dperf.enabled=true \
  -Dperf.token=YOUR_JWT \
  test
```

## Run One Metric

```bash
# Document ingestion latency
-Dperf.targets=ingest

# Hybrid search p95
-Dperf.targets=search

# Chat TTFT
-Dperf.targets=chat
```

Multiple targets are comma-separated:

```bash
-Dperf.targets=search,chat
```

## Useful Parameters

| Parameter | Default | Description |
| --- | --- | --- |
| `perf.baseUrl` | `http://localhost:8081` | Backend HTTP base URL |
| `perf.wsUrl` | derived from `baseUrl` | WebSocket base URL |
| `perf.output` | `target/perf-results.md` | Markdown report path |
| `perf.ingest.fileSizesMb` | `1` | Comma-separated generated file sizes |
| `perf.ingest.timeoutSeconds` | `300` | Max wait for uploaded content to become searchable |
| `perf.search.queries` | built-in queries | Search queries separated by `|` |
| `perf.search.topK` | `5` | Search result size |
| `perf.search.warmupRuns` | `5` | Warmup requests per query |
| `perf.search.runsPerQuery` | `30` | Measured requests per query |
| `perf.chat.prompt` | built-in prompt | Chat prompt used for TTFT |
| `perf.chat.runs` | `3` | Chat runs |
| `perf.chat.timeoutSeconds` | `180` | Max wait per chat run |

## Metric Definitions

- Document ingestion latency: from successful `/api/v1/upload/merge` response until the uploaded document can be returned by `/api/v1/search/hybrid`.
- Hybrid search p95: p95 of end-to-end HTTP latency for `/api/v1/search/hybrid` after warmup.
- Chat TTFT: from WebSocket message send until the first non-empty `type=chunk` payload.
