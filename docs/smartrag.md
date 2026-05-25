# SmartRag Overview

SmartRag is a RAG-oriented knowledge-base system for private document search and AI-assisted Q&A.

## Main Capabilities

- Chunked document upload with resumable progress tracking
- Object storage based on MinIO
- Kafka-driven asynchronous document parsing and vectorization
- Recursive semantic chunking with overlap
- BM25 + KNN hybrid retrieval in Elasticsearch
- RRF-based result fusion
- Permission-aware document retrieval
- WebSocket streaming chat
- ReAct-based tool calling and summary generation

## RAG Pipeline

```text
Upload document
  -> Store file in MinIO
  -> Send processing task to Kafka
  -> Parse document
  -> Split text into chunks
  -> Generate embeddings
  -> Write text and vector data to Elasticsearch
  -> Retrieve permission-filtered chunks during chat
  -> Generate grounded answers with references
```

## Agent Tools

SmartRag exposes backend abilities as ReAct tools:

- `search_knowledge`: search accessible knowledge-base chunks
- `generate_summary`: retrieve documents and generate a structured summary
- `submit_feedback`: record user feedback
- `knowledge_stats`: return knowledge-base statistics

## Permission Model

Document access is controlled by user identity, role, organization tags, file owner, and public/private attributes. RAG retrieval uses Elasticsearch filters before BM25 and KNN recall so unauthorized documents never enter the model context.
