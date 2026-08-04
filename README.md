# voicenote

voicenote 是面向会议、面试、访谈等音频场景的 AI 听记与私人知识库平台。它保存原始音频、异步进行说话人转写，并将成功听记自动沉淀为可跨文档检索、可回跳原声的知识文档。

## What is implemented

- 账号密码登录：账号区分大小写、不能含空白字符；密码无格式限制且安全哈希保存。
- JWT authentication and user-scoped audio ownership.
- Content-addressed uploads: the client declares SHA-256 and the server verifies it while streaming to MinIO.
- Task creation idempotency, MySQL transactional outbox, and RocketMQ consumer deduplication.
- DashScope-compatible ASR and chat-model adapters, disabled safely until credentials are configured.
- Time-stamped transcript segments, manual analysis runs, evidence links, retries, and a Vue workbench.
- 自动知识文档、Qdrant Dense + BM25 混合检索，以及带原文证据的跨文档 Agent 任务。
- 音频流水线阶段追踪：每次阶段尝试与排队等待时间会持久化；工作台通过认证 SSE 接收阶段完成、索引完成和 Agent 完成通知，不轮询任务状态。

## Local development

The application needs MySQL and MinIO to be reachable through local ports. RocketMQ is only required after `ROCKETMQ_ENABLED=true`; Redis is not part of the MVP correctness path. Qdrant is required only when knowledge indexing is enabled. Connection and credential configuration is loaded automatically from `backend/.env`; process environment variables take precedence. There are no local fallback credentials. Do not commit infrastructure endpoints or credentials.

```bash
cp .tunnel.env.example .tunnel.env
# Set SSH_TUNNEL_TARGET in .tunnel.env, then keep this terminal open.
./scripts/start-ssh-tunnels.sh

# In another terminal:
cd backend
cp .env.example .env
# Edit .env with the local ports and credentials exposed by your SSH tunnel.

# Optional: start the local vector index used by the knowledge base.
docker run --rm -p 6333:6333 -p 6334:6334 qdrant/qdrant
# Then set DASHSCOPE_ENABLED=true and VOICENOTE_KNOWLEDGE_ENABLED=true in backend/.env.
mvn spring-boot:run

cd ../frontend
npm install
npm run dev
```

See `backend/src/main/resources/application.yml` for the required variables. Project-owned connection variables use the `VOICENOTE_` prefix (for example, `VOICENOTE_DB_URL`) to avoid collisions with generic shell variables. With external provider integrations disabled, the application starts without contacting DashScope or RocketMQ.

### DashScope models

ASR, Agent inference, and Dense vector embeddings share one DashScope switch and API key. To enable any of them, set `DASHSCOPE_ENABLED=true` and provide `DASHSCOPE_API_KEY` in the ignored `backend/.env` file. The model variables below are explicit so they can be changed without editing source code:

```dotenv
DASHSCOPE_ASR_MODEL=paraformer-v2
DASHSCOPE_CHAT_MODEL=qwen-plus
DASHSCOPE_EMBEDDING_MODEL=text-embedding-v4
```

The embedding model is required when `VOICENOTE_KNOWLEDGE_ENABLED=true`; the ASR and chat model are respectively used for transcription and evidence-backed Agent answers. Never commit a real API key or a private provider endpoint.

### Knowledge base

When `VOICENOTE_KNOWLEDGE_ENABLED=true`, each successfully organized recording creates a private knowledge document. Its topic-aware chunks are indexed in Qdrant with a DashScope Dense embedding and Qdrant BM25 sparse representation; the backend fuses both rankings and always filters by the authenticated owner. Use a Qdrant release that supports server-side `qdrant/bm25` documents and hybrid Query API. Qdrant must remain reachable only from the backend, never directly from the browser.

The Agent creates a one-off, evidence-backed knowledge task. It can search the owner's collection and read returned document chunks, with four tool calls maximum. Every factual finding must cite transcript segments; citations in the workbench seek the source audio to the original timestamp.

### Three-phase audio pipeline

After the client has uploaded and verified audio bytes, it calls `POST /api/uploads/intents/{blobId}/complete` with an idempotency key. The response is `202 Accepted` and contains the task ID; it never waits for ASR, document organization, or vector indexing.

The durable pipeline is `TRANSCRIPTION` (speaker-labelled, timestamped raw segments), `DOCUMENT_ORGANIZATION` (cleaned turns and evidence-preserving topic sections), then `KNOWLEDGE_BUILD` (topic-aware chunks, embeddings, and Qdrant upserts). MySQL records task and phase attempts; RocketMQ dispatches transcription, document, and knowledge events through the transactional outbox. The recovery coordinator repairs expired leases and due retries after a restart.

`POST /api/transcription-tasks/{taskId}/cancel` performs cooperative cancellation. A provider request already in flight may finish, but its result is discarded and cannot trigger a downstream phase. A failed phase can be retried without re-running completed phases. Once document organization is ready, `POST /api/organized-documents/{documentId}/summary` creates an evidence-backed summary run on that organized-document snapshot.

### `Communications link failure` on startup

This error means the configured MySQL address cannot be reached. The default is `127.0.0.1:3306`, so first verify that the SSH tunnel (or local MySQL) is running and that `VOICENOTE_DB_URL`, `VOICENOTE_DB_USERNAME`, and `VOICENOTE_DB_PASSWORD` in `backend/.env` match it:

```bash
nc -z 127.0.0.1 3306
```

Do not disable Flyway or switch `ddl-auto` to bypass this error: the application requires the database migration to complete before it can start safely.

### `OBJECT_STORAGE_*` while uploading audio

The backend now logs a safe MinIO failure category and returns a matching error code without writing endpoints, object keys, or credentials to logs. Use the code to narrow the fix:

- `OBJECT_STORAGE_UNREACHABLE`: start `./scripts/start-ssh-tunnels.sh` and keep it open; the MinIO API tunnel is `127.0.0.1:9000` (not the console port).
- `OBJECT_STORAGE_CREDENTIALS_REJECTED`: replace `VOICENOTE_MINIO_ACCESS_KEY` and `VOICENOTE_MINIO_SECRET_KEY`, or grant that identity permission to list/create the bucket and write objects.
- `OBJECT_STORAGE_BUCKET_UNAVAILABLE` or `OBJECT_STORAGE_CONFIGURATION_INVALID`: set `VOICENOTE_MINIO_BUCKET` to a valid, lowercase S3 bucket name (3–63 characters; letters, digits, `-`, and `.` only). The application creates a missing bucket when the configured identity has permission.

Keep the actual endpoint, bucket name, and credentials in the ignored `backend/.env`; do not add them to this document.

### SSH tunnel ports

`scripts/start-ssh-tunnels.sh` forwards MySQL (3306), Redis (6379), MinIO API (9000), RocketMQ NameServer (9876), and RocketMQ Broker (10911). It reads optional overrides from the ignored `.tunnel.env` file and exits if any local forward cannot be opened.

If the server rejects authentication with `Permission denied (publickey)`, set `SSH_IDENTITY_FILE` in `.tunnel.env` to the absolute path of the private key whose public counterpart was installed for that server. If you already use an SSH config alias, set `SSH_TUNNEL_TARGET` to that alias instead. Keep private keys and the copied `.tunnel.env` out of Git.

For RocketMQ, the Broker must advertise an address reachable from the local machine. If it advertises a server-only container address, configure the Broker's advertised address on the server; forwarding the NameServer port alone is not sufficient.

## Correctness boundary

MySQL owns idempotency. Redis is intentionally not used as a distributed lock. RocketMQ is treated as at-least-once: duplicated messages become no-ops through the inbox table and conditional task-state claims. If an external ASR submission times out after it may have reached the provider, the attempt becomes `SUBMISSION_UNKNOWN`; it is never automatically resent.

An audio pipeline is accepted only when `VOICENOTE_KNOWLEDGE_ENABLED=true`, because “complete” means that the transcript has been saved and its knowledge document is searchable. Transient stage failures retry after 5, 15, and 45 seconds. A submission with an unknown ASR outcome is deliberately not retried automatically; users must explicitly restart that stage after confirming the risk of a second provider submission.
# voice-note
