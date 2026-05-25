# SmartRag

SmartRag 是一个面向企业/团队内部知识库场景的智能问答系统，基于 RAG（Retrieval-Augmented Generation，检索增强生成）构建，支持文档上传、解析切块、混合检索、权限隔离、WebSocket 流式对话以及 ReAct 工具调用。

项目目标是把“文档进入知识库”到“用户获得可溯源回答”的完整链路工程化落地，而不是只做一个简单的大模型聊天接口。

## 核心功能

- **文档上传与断点续传**：基于 Redis Bitmap 记录分片状态，结合 MinIO 存储分片和合并后的文件。
- **异步文档处理**：文件落盘后通过 Kafka 投递处理任务，异步完成文档解析、切块、向量化和索引写入。
- **递归语义分块**：按照段落、句子、词语逐级切分，并通过滑动窗口 overlap 保留跨 chunk 上下文。
- **混合检索**：基于 Elasticsearch 实现 BM25 关键词检索 + KNN 向量检索，并通过 RRF 融合两路召回结果。
- **权限感知检索**：结合用户角色、组织标签、文件归属和公开属性，将权限条件作为 ES 前置过滤条件。
- **WebSocket 流式对话**：支持实时响应、停止生成、生成状态追踪和历史对话查询。
- **ReAct 工具调用**：通过 `AgentToolRegistry` 注册知识库检索、文档总结、用户反馈、知识库统计等工具，由大模型按需调用。
- **记忆与状态管理**：Redis 保存短期上下文和生成态，MySQL 持久化会话、消息和引用映射。

## 技术架构

```text
Vue 3 前端
  |
  | WebSocket / REST
  v
Spring Boot 后端
  |
  |-- 认证授权：Spring Security + JWT
  |-- 文件上传：Redis Bitmap + MinIO
  |-- 异步处理：Kafka Consumer
  |-- 文档解析：Apache Tika / PDFBox
  |-- 文本分块：段落 -> 句子 -> 词语 + 滑动窗口
  |-- 混合检索：Elasticsearch BM25 + KNN + RRF
  |-- Agent：ReAct Loop + AgentToolRegistry
  |-- 记忆管理：Redis 短期上下文 + MySQL 历史归档
```

## 核心链路

1. 用户在前端上传 PDF、Word、TXT 等文档。
2. 后端按分片接收文件，并将分片和合并文件存储到 MinIO。
3. 文件合并完成后，Kafka 触发异步文档处理任务。
4. 消费者解析文档内容，执行递归分块和 Embedding 向量化。
5. 文本内容、向量和权限元数据写入 Elasticsearch。
6. 用户通过 WebSocket 发起对话。
7. ReAct 循环判断是否需要调用工具，例如 `search_knowledge` 或 `generate_summary`。
8. 检索阶段带上用户权限过滤条件，只召回当前用户可访问的文档片段。
9. 大模型基于检索结果生成流式回答，并保留引用映射，支持答案溯源。

## 技术栈

- **后端**：Java 17、Spring Boot 3、Spring Security、Spring Data JPA、WebSocket、WebFlux
- **前端**：Vue 3、Vite、TypeScript、Naive UI、Pinia
- **存储**：MySQL、Redis、MinIO
- **检索**：Elasticsearch
- **消息队列**：Kafka
- **AI 能力**：OpenAI-compatible LLM、Embedding Provider
- **文档解析**：Apache Tika、PDFBox

## 项目亮点

### 1. ReAct 工具调用链路

系统通过 `AgentToolRegistry` 将后端能力封装为大模型可调用的工具，包括：

- `search_knowledge`：知识库权限检索
- `generate_summary`：指定主题的结构化摘要生成
- `submit_feedback`：用户反馈记录
- `knowledge_stats`：知识库统计信息查询

大模型在 ReAct 循环中根据上下文自主决定是否调用工具。后端负责工具执行、参数校验、权限控制和异常兜底。

### 2. BM25 + KNN + RRF 混合检索

单一向量检索在专有名词、编号、接口名等场景下容易召回不稳定。SmartRag 使用 BM25 负责精确词匹配，KNN 负责语义相似召回，并通过 RRF 基于排名融合结果，避免 BM25 分数和向量相似度分数量纲不一致的问题。

### 3. 权限前置过滤

RAG 系统不能只控制文件下载权限，还要防止无权限文档进入大模型上下文。SmartRag 在检索阶段将用户 ID、组织标签、公开属性等权限条件下沉为 Elasticsearch filter，从源头隔离隐私数据。

### 4. 大文件上传与异步知识库构建

上传阶段只负责网络 I/O 和对象存储，文档解析、切块、向量化、索引写入等重任务通过 Kafka 异步执行，降低接口同步耗时并提升系统稳定性。

## 本地运行

启动前请先准备 MySQL、Redis、Elasticsearch、MinIO、Kafka 等基础设施。默认配置位于：

```text
src/main/resources/application.yml
frontend/.env*
```

后端启动：

```bash
./mvnw spring-boot:run
```

前端启动：

```bash
cd frontend
pnpm install
pnpm dev
```

## 目录说明

```text
src/main/java/com/zzzzyj/smartpai/
├── client/        # 大模型、Embedding 等外部客户端
├── config/        # Spring Security、Redis、Kafka、MinIO、ES 等配置
├── consumer/      # Kafka 异步消费链路
├── controller/    # REST API
├── handler/       # WebSocket 处理器
├── model/         # 数据模型
├── repository/    # JPA Repository
├── service/       # 核心业务逻辑
└── utils/         # 工具类

frontend/
├── src/views/     # 页面组件
├── src/store/     # Pinia 状态管理
├── src/service/   # 前端接口封装
└── src/layouts/   # 页面布局
```
