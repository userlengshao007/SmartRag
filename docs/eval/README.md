# RAG 离线评测

从 smartPai 迁移并适配当前项目。只在管理员主动提交时运行，不随应用启动触发；不需要新增数据库表。报告和人工复核结果保存在本地文件。

## 页面入口

管理员登录后进入侧边栏「RAG 评测」（`/#/rag-eval`）。页面支持上传 JSONL 并校验预览、发起评测、自动更新进度、查看命中率与准确率、逐题查看证据和回答、人工复核及下载报告。

项目默认源文档是 `docs/smartrag.pdf`，另有 `docs/smartrag.md` 文字说明；配置路径不表示文档一定已经入库。本地关闭自动入库时，应先在知识库页面确认上传与索引状态。评测页面上传的是根据真实文档编写的 JSONL 题目文件，不是 PDF。

## 评测范围

每道题依次执行当前的 `HybridSearchService.searchWithPermission` → 生成回答 → LLM 评委评分。
检索以管理员 JWT 中的用户身份执行，仍受文件权限过滤；生成与评委共用当前激活的 LLM 和现有配额/限流链路，请求方默认 `system-eval`。
这是单轮 RAG 链路评测，不覆盖 Agent 多轮工具选择、历史压缩和长期记忆。
当前检索实现为 BM25 + KNN + RRF + Rerank 精排（当前主配置默认 qwen3-rerank），页面“仅检索”和“完整评测”均走此链路。精排默认开启，最多保留 max(50, topK) 个候选，按配置阈值过滤低分结果（当前主配置默认 0）；超时或调用失败回退 RRF。报告配置记录精排开关、模型、候选数、阈值和超时；逐题 `retrievalMode=RERANK` 表示使用精排结果，`RRF` 表示跳过或回退。新报告的 `stages` 保存同一轮 RRF 候选、精排全部排名和阶段对比，旧报告该字段仍可能为 null。当前不记录 BM25/KNN 单路阶段指标，相应汇总为 null。新报告会保存送给模型的检索证据原文（每条最多 1200 字符及截断标记）和页码，旧报告无这些字段时页面显示提示。检索服务自身会把最终故障降级为空列表，评测无法区分该空列表与正常无结果。

## 可直接使用的 50 题资料包

已提供 [SmartRag 50 题基线资料包 v1](smartrag-v1/README.md)：10 份可上传 TXT（含 2 份合成干扰资料）和 50 道题。先把 `smartrag-v1/corpus/` 的 TXT 上传到知识库并等待入库完成，再到评测页上传 `smartrag-v1/smartrag-eval-50-v1.jsonl`。不要把题目、答案和使用说明上传到知识库。

## 1. 准备数据集

在项目目录创建 `eval-datasets/my-eval.jsonl`，每行一题：

```json
{"id":"fact-01","question":"系统使用什么数据库？","expectedFileNames":["系统说明.pdf"],"expectedEvidence":["MySQL 8.0"],"referenceAnswer":"系统使用 MySQL 8.0。"}
{"id":"neg-01","question":"资料有公布下季度的利润预测吗？","referenceAnswer":"资料中没有相关信息，无法确定。"}
```

用真实知识库的文件名、证据原文和人工参考答案替换示例；先确认该管理员可以检索这些文件。
`id` 必须唯一；省略时使用行号。`question` 和 `referenceAnswer` 必填。支持空行及 `#`、`//` 开头的注释。

- 文件标注：`expectedFileNames` 或 `expectedFileMd5s`，任一匹配就算文件相关。
- 分块标注：在文件匹配基础上，分块原文包含任一 `expectedEvidence` 短语才算命中。匹配前统一全角字符、大小写和空白。
- 无答案题：不标注期望文件，参与回答评分，不计入检索命中率分母。未标注不自动证明题目无答案，需要人工确认。
- `classpath:eval/sample-eval.jsonl` 仅是格式样例，不是当前知识库的有效基准集。

## 2. 提交评测

后端启动并加载新代码后，使用管理员登录 token。以下 `$ADMIN_TOKEN` 是占位环境变量；端口按本地配置替换。

先只评检索：

```bash
curl -X POST 'http://localhost:8081/api/v1/admin/eval/run' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"datasetPath":"my-eval.jsonl","topK":5,"label":"baseline","retrievalOnly":true}'
```

完整评测将 `retrievalOnly` 改成 `false`，每题通常调用两次 LLM（生成一次、评委一次），空输出时可能额外重试一次。只评检索仍会调用 Embedding，开启重排序时也会调用 rerank API。

也可以上传文件：

```bash
curl -X POST 'http://localhost:8081/api/v1/admin/eval/run' \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -F 'file=@eval-datasets/my-eval.jsonl' -F 'topK=5' \
  -F 'label=baseline' -F 'retrievalOnly=true'
```

JSON 提交还支持 `jsonl` 字符串，与 `datasetPath` 二选一。单次最多 500 题，上传最大 5MB，topK 为 1～20；同一实例同时只允许运行一个任务。

## 3. 查询结果

提交返回 `data.runId`，下列接口均需管理员 Authorization 头：

| 接口 | 用途 |
| --- | --- |
| `GET /api/v1/admin/eval/runs` | 最近任务 |
| `GET /api/v1/admin/eval/runs/latest` | 最新任务与进度 |
| `GET /api/v1/admin/eval/runs/{runId}` | 指定任务进度 |
| `GET /api/v1/admin/eval/runs/{runId}/report` | 完整报告及人工复核附件 |

状态为 RUNNING / SUCCEEDED / FAILED。JSON 报告默认写入 `eval-reports/`，包含配置快照、逐题检索明细、回答、评委理由和归因。页面合并内存任务与磁盘已完成报告，按时间展示最近 20 次；重启后仍可浏览已完成记录。运行中和失败任务仅保存在内存，重启后不会恢复。报告及本地数据集目录已被 Git 忽略。

指标口径：

- `recallAtK`：标注题中，前 K 条至少有一条相关证据的题目比例，实际是 Hit Rate@K，保留迁移接口字段名。
- `mrr`：第一条相关证据排名的倒数均值。
- `docRecallAtK`：只按文件判断的题目命中率；与分块命中率对比，检查是否只找到文件却没找到答案。
- `meanExpectedDocCoverage`：期望文件的覆盖比例。
- `accuracy` / `meanJudgeScore`：仅在评委成功打分的题上计算准确率和 1～5 分均值；必须同时关注 `judgedCases` 和 `failedCases`。
- `diagnosisCounts`：检索未命中、命中但答错、检索未命中却答对、调用失败等归因。

## 4. 人工复核

抽样比例默认 10%，固定随机种子 42；评委失败或检索未命中却被判答对的题会强制标记复核。

```bash
curl -X POST "http://localhost:8081/api/v1/admin/eval/runs/$RUN_ID/human-reviews" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"reviews":[{"caseId":"fact-01","correct":false,"score":2,"note":"遗漏关键条件"}]}'
```

`GET /api/v1/admin/eval/runs/{runId}/human-reviews` 查询复核结果。相同 caseId 后提交覆盖前判；原始报告保持不变，人工结果写入旁路 `.human-reviews.json` 文件。返回评委与人工一致率、Cohen’s kappa、分歧题号和校准提醒。

## 配置与对比方法

配置在 `application.yml` 的 `eval` 下，输出/数据集目录可由 `RAG_EVAL_OUTPUT_DIR`、`RAG_EVAL_DATASET_DIR` 覆盖。
先建立自己的固定测试集并跑 baseline；每次只改一类因素（分块、召回或提示词），相同数据集、身份、topK 和模型重新运行，用 label 区分。涉及重新分块或索引内容变化时，先完成对应重建。LLM 评委与生成共用模型，评分存在偏差，应结合人工复核解读。

## 比较重排序效果

保持知识库、题库、评测身份和 topK 一致，在原基线报告旁新建一次评测，label 可设为 `v1-gte精排`。优先用仅检索模式比较命中率、MRR、期望文档覆盖率和耗时，再逐题核对证据。报告中 RERANK 表示精排成功；RRF 表示关闭或调用失败回退，不能把全部回退的评测当成精排效果。

需要重新跑关闭精排的基线时，将后端环境变量 `SEARCH_RERANK_ENABLED=false` 并使配置生效后再运行；打开时设为 true。历史报告不会重新计算，必须新建评测。旧的 `HybridSearchBenchmarkTest` 是随机向量和合成数据的 ES/Milvus 性能对比，不用于衡量这条实际 RAG 链路的相关性效果。

## 同轮阶段对比

更新后重新运行“仅检索”即可在报告页看到 RRF 与精排的命中率、MRR、新增命中和丢失命中题数。对比仅统计标注了期望文件且模型调用成功的题，不把关闭、失败回退当成模型结果。精排对比使用阈值过滤前的 topK；页面原有命中率仍按过滤后最终结果计算，两者可以不同。

题目筛选新增“精排后丢失命中”和“候选未命中”。题目详情可展开 RRF 全部候选及精排全部排名，按 fileMd5 + chunkId 对照同一个分块的前后位置及分数。RRF 分和模型相关性分属于不同尺度，不直接比较数值大小。报告保留候选完整正文，模型输入长度仍由 rerankMaxDocumentChars 控制。无标注题不会被当作已知无答案题计算模型命中对比。

若正确片段不在 RRF 候选中，先查召回；若在候选中但精排落到 topK 之外，再比较模型；若精排 topK 命中而最终结果未命中，则检查阈值。这里对比共享当前权限过滤和知识库状态，避免跨天基线的混杂因素。不会重复调用排序模型。
