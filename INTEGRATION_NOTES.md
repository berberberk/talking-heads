# Integration notes

## Что забрано из `llm-service`

- `POST /api/chat` + history/finish/report compatibility API.
- PostgreSQL/Hikari/Flyway.
- bounded context через `MAX_CONTEXT_MESSAGES`.
- совместимые env: `DATABASE_*`, `CHAT_API_URL`, `FRONTEND_HOST`.

## Что забрано из `simli-rnd`

- `AVATAR_PROVIDER=did|simli`.
- server-side Simli token endpoint.
- ElevenLabs WebSocket TTS `pcm_16000`.
- `/api/chat/stream` text + binary PCM protocol.
- subtitle alignment metadata.
- browser/server latency telemetry.
- Gemini fallback policy до первой streaming delta.

## Что сохранено из текущего `backend`

- package/project structure `talkingheads`.
- `/api/sessions/*`.
- `/ws/training/{sessionId}`.
- monotonic `generationId` и stale-generation protection.
- barge-in / cancellation semantics.
- partial interrupted assistant persistence.
- streaming segmentation для D-ID.
- training report model и criteria behavior.

## Решения при конфликте архитектур

1. Не оставлено два параллельных conversation service. Все endpoints идут через `TrainingSessionManager`.
2. PostgreSQL хранит текущий aggregate как JSONB, потому что старая normalized schema не содержит `generationId`, interruption flags и backend metrics.
3. Старые таблицы не удаляются и не модифицируются; Flyway использует отдельную history table `flyway_schema_history_integrated_backend`.
4. D-ID и Simli взаимоисключаются на уровне `AVATAR_PROVIDER`, чтобы не делать двойной TTS/расход кредитов.
5. Simli stream использует тот же generation manager: browser `cancel` и новый turn отменяют backend LLM job, а TTS job закрывается вместе с turn.

## Перед production merge

- `./gradlew clean test` в среде с Maven/Gradle access.
- smoke D-ID или Simli с реальными ключами.
- если есть production данные старого llm-service: сделать отдельную data migration из `chat_*`/`training_*` в `integrated_training_sessions`.
- добавить auth/ownership сессий до публичного развертывания.
