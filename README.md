# EchoTrace

EchoTrace is an AI audio analysis MVP for uploaded recordings. It stores original audio in MinIO, submits asynchronous ASR jobs, and lets users run evidence-backed AI analysis after transcription completes.

## What is implemented

- JWT authentication and user-scoped audio ownership.
- Content-addressed uploads: the client declares SHA-256 and the server verifies it while streaming to MinIO.
- Task creation idempotency, MySQL transactional outbox, and RocketMQ consumer deduplication.
- DashScope-compatible ASR and chat-model adapters, disabled safely until credentials are configured.
- Time-stamped transcript segments, manual analysis runs, evidence links, retries, and a Vue workbench.

## Local development

The application expects MySQL, Redis, RocketMQ, and MinIO to be reachable through local ports. For the intended setup, expose those ports through SSH tunnels and set the matching variables in `backend/.env` or your shell. Start from `backend/.env.example`; do not commit infrastructure endpoints or credentials.

```bash
cd backend
mvn spring-boot:run

cd ../frontend
npm install
npm run dev
```

See `backend/src/main/resources/application.yml` for the required variables. With external provider integrations disabled, the application starts without contacting DashScope or RocketMQ.

## Correctness boundary

MySQL owns idempotency. Redis is intentionally not used as a distributed lock. RocketMQ is treated as at-least-once: duplicated messages become no-ops through the inbox table and conditional task-state claims. If an external ASR submission times out after it may have reached the provider, the attempt becomes `SUBMISSION_UNKNOWN`; it is never automatically resent.
# voice-note
