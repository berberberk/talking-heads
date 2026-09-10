# 🗣 Talking Heads

Голосовой AI-тренажёр корпоративных диалогов. Сотрудник проходит тренировочный диалог с говорящим аватаром, а система сохраняет историю, управляет поколениями ответа и формирует персональную обратную связь.

Методист выбирает готовый сценарий или передаёт одноразовый Markdown-сценарий. Сотрудник отвечает голосом через push-to-talk либо использует текстовый fallback. В режиме Simli ответ проходит потоково: Gemini → ElevenLabs → PCM16 → Simli, без публичного callback endpoint и передачи ключей в браузер.

## Возможности

- постоянная история и отчёты в PostgreSQL либо локальное file-хранилище;
- обычный HTTP-чат для D-ID и совместимых клиентов: `POST /api/chat`;
- потоковый Simli-путь: `WS /api/chat/stream`;
- идентификатор поколения (`generationId`), отмена устаревшего ответа и отбрасывание поздних событий;
- субтитры с alignment-метаданными PCM-фреймов;
- realtime voice input через ElevenLabs Scribe v2: удержание кнопки, partial transcript и ручной commit при отпускании;
- метрики `gemini_first_delta`, `tts_first_audio`, `browser_first_pcm`, `simli_speaking` и итоговые browser-метрики;
- идемпотентное завершение тренировки, безопасная стенограмма и строгий итоговый report с повторной попыткой evaluation;
- встроенные и одноразовые сценарии с неизменяемым snapshot внутри сессии.

## Архитектура

Kotlin/Ktor-бэкенд на порту `8080` — единый runtime для сессий, LLM, сценариев, отчётов, Simli и метрик. FastAPI из [app.py](./app.py) раздаёт статический frontend на порту `8000` и безопасную публичную конфигурацию.

```text
browser ── HTTP / WS ──> Kotlin/Ktor ──> Gemini SSE
   │                         │
   ├── single-use Scribe token ──> ElevenLabs Scribe Realtime ──> committed user text
   │                         │
   │                         ├── PostgreSQL: session aggregate, история, report
   │                         │
   │ <── PCM16 frames ─ ElevenLabs WebSocket <── text deltas
   │
   └── Simli SDK sendAudioData(PCM16) ── LiveKit/WebRTC ──> avatar
```

`TrainingSessionManager` — единый источник истины для HTTP-чата, Simli WebSocket и расширенного training WebSocket. Он сериализует переходы состояния сессии и отменяет только активную генерацию.

## Готовые сценарии

Сценарии находятся в [src/main/resources/scenarios](./src/main/resources/scenarios). Каждый задаёт роль аватара, 4–5 этапов диалога, условия перехода и три критерия оценки.

| ID | Тренировка | Роль аватара | Основные этапы | Критерии отчёта |
| --- | --- | --- | --- | --- |
| `sales-discovery` | Выявление потребности B2B-клиента | Занятой B2B-клиент | контакт → потребность → ценность → следующий шаг | Выявление потребности, аргументация ценности, следующий шаг |
| `sales-objection` | Работа с возражением о цене | Потенциальный клиент | возражение → уточнение → ценность → договорённость | Выяснение причины, аргументация ценности, согласование действия |
| `structured-interview` | Структурированное интервью | Кандидат на аккаунт-менеджера | мотивация → опыт → рабочий кейс → вопросы → завершение | Структура интервью, уточняющие вопросы, профессиональная коммуникация |
| `policy-knowledge` | Проверка знания политики безопасности | Тренер по ИБ | рабочий случай → правило → пограничный случай → безопасные действия | Точность правила, применение к случаю, объяснение решения |
| `manager-feedback` | Развивающая обратная связь | Сотрудник с задержанным статусом | факт → влияние → позиция сотрудника → план развития → завершение | Конкретность наблюдений, диалогичность, план развития |

Backend публикует безопасные карточки сценариев без закрытых prompt-инструкций:

```http
GET /api/scenarios
```

### Выбор сценария

Сценарий необязателен. Его разрешено передать только при создании сессии — после первой реплики нормализованный snapshot сохраняется внутри сессии и не может быть подменён клиентом.

Preset:

```json
{
  "sessionId": null,
  "message": "Начнём тренировку",
  "scenario": { "presetId": "sales-discovery" }
}
```

Одноразовый Markdown-сценарий можно предварительно проверить, не сохраняя его:

```http
POST /api/scenarios/validate
Content-Type: application/json

{ "markdown": "..." }
```

Максимальный размер custom Markdown — 32 КБ. Он не добавляется в каталог и доступен только в snapshot одной сессии.

## Быстрый запуск

Нужны Docker Desktop, JDK 17+, Node.js 18+ и Python с `uv`.

```bash
cp .env.example .env
# заполните как минимум GEMINI_API_KEY и выберите AVATAR_PROVIDER
docker compose up --build -d

npm ci
npm run build:frontend
uv run --with-requirements requirements.txt uvicorn app:app --port 8000
```

Проверка backend:

```bash
curl http://localhost:8080/health
```

Откройте `http://localhost:8000`. Frontend получает только `AVATAR_PROVIDER`, `CHAT_API_URL`, безопасные параметры Scribe (`stt_enabled`, model, language) и, для D-ID, публичные browser credentials. Gemini, Simli и ElevenLabs keys остаются в `.env` и Kotlin backend.

Текущий legacy D-ID frontend также читает публичные browser-значения из игнорируемых `agent_id.txt` и `client_key.txt`; Simli-режиму эти файлы не нужны.

## Конфигурация

Минимум для D-ID:

```dotenv
GEMINI_API_KEY=...
AVATAR_PROVIDER=did
DID_AGENT_ID=...
DID_CLIENT_KEY=...
```

Для потокового Simli:

```dotenv
GEMINI_API_KEY=...
AVATAR_PROVIDER=simli
SIMLI_API_KEY=...
SIMLI_FACE_ID=...
SIMLI_TRANSPORT=livekit
ELEVENLABS_API_KEY=...
ELEVENLABS_VOICE_ID=...
ELEVENLABS_MODEL=eleven_flash_v2_5
SCRIBE_MODEL=scribe_v2_realtime
SCRIBE_LANGUAGE_CODE=ru
STREAM_MIN_CHARS=50
```

Для Free tier ElevenLabs используйте голос категории `premade`/`default`. Community/Voice Library голоса могут прослушиваться в веб-интерфейсе, но API вернёт `paid_plan_required`.

Некоторые новые Gemini-аккаунты больше не имеют доступа к `gemini-2.5-flash-lite`. Для такого ключа укажите в локальном `.env` совместимую доступную модель, например `GEMINI_MODEL=gemini-3.6-flash`.

## API

### Обычный чат

```http
POST /api/chat
Content-Type: application/json
```

```json
{ "sessionId": null, "message": "Здравствуйте" }
```

Ответ содержит `sessionId`, итоговую `assistantMessage` и `generationId`. Пустое сообщение, неверный UUID или смена сценария существующей сессии возвращают `400`; неизвестная сессия — `404`; ошибка LLM — `502`.

Дополнительные endpoints:

- `GET /api/chat/{sessionId}/history` — сохранённая история;
- `POST /api/chat/{sessionId}/finish` — сформировать либо получить существующий report;
- `GET /api/chat/{sessionId}/report` — получить report, если тренировка завершена.

`finish` идемпотентен. После нажатия тренировка сразу фиксируется как завершённая, а формирование report имеет отдельный статус: `GENERATING`, `READY` или `FAILED`. Поэтому сбой Gemini или невалидный JSON не теряет стенограмму и не возвращает пользователя в активную тренировку — report можно сформировать повторно.

Страница результата переживает reload: она ждёт только переходные статусы ограниченное время, не опрашивает активную тренировку и предлагает вручную проверить статус при долгой генерации. При `FAILED` стенограмма остаётся доступной, а evaluation можно безопасно запустить повторно.

### Результат тренировки

Продуктовый поток использует эти endpoints:

- `POST /api/sessions/{sessionId}/finish` — завершить тренировку и начать evaluation;
- `GET /api/sessions/{sessionId}/result` — безопасные данные для страницы результата;
- `POST /api/sessions/{sessionId}/report/retry` — повторить failed evaluation;
- `GET /api/sessions/{sessionId}/report` — получить готовый JSON report.

`result` специально не передаёт в браузер скрытые системные инструкции сценария и правила выхода из этапов. Готовый report включает детерминированную итоговую оценку `overallScore` от 1 до 5, summary, 1–3 рекомендации и ровно критерии выбранного сценария. Каждая оценка содержит `comment`, `evidence` и, если оно известно, `evidenceGenerationId` со ссылкой на реплику сотрудника в стенограмме.

### Потоковый Simli

1. Получить короткоживущий token: `POST /api/avatar/simli/session`.
2. Открыть `ws://localhost:8080/api/chat/stream`.
3. Отправить:

```json
{ "type": "start", "sessionId": null, "message": "Здравствуйте", "turnId": "optional-uuid" }
```

Сервер последовательно отправляет `session`, `delta`, `audio_frame`, binary PCM16, `metrics` и `done`. При `{ "type": "cancel" }` backend отменяет текущие Gemini/TTS coroutine; браузер одновременно очищает буфер Simli через `ClearBuffer()`.

### Голосовой ввод / Push-to-talk

После подключения аватара браузер запрашивает доступ к микрофону и получает через `POST /api/stt/token` только одноразовый Scribe-токен. `ELEVENLABS_API_KEY` остаётся на Kotlin backend и никогда не попадает в browser bundle, storage или логи.
Подключение Scribe выполняется независимо от Simli/D-ID: отказ или timeout распознавания не задерживает появление аватара и оставляет доступным текстовый fallback.

1. Удерживайте кнопку «🎙 Удерживайте, чтобы говорить» — микрофон размьютится, а на экране появится partial transcript.
2. Отпустите кнопку — микрофон мьютится и Scribe получает ручной commit.
3. Только непустой committed transcript один раз передаётся в тот же chat/session pipeline, что и текст.

Короткие случайные касания не создают turn, одна реплика ограничена 25 секундами, а зависший commit завершается ошибкой с возможностью повторного подключения. Во время ответа аватара нажатие PTT отменяет текущую Simli-генерацию до открытия микрофона; automatic VAD interruption пока не реализован. При ошибке прав доступа, quota или Scribe connection остаётся доступен раздел «Ввести текст вручную».

### Расширенный training protocol

`POST /api/sessions` создаёт сессию и возвращает URL `WS /ws/training/{sessionId}`. Через этот протокол клиент явно передаёт `generationId`, `user_message`, `interrupt`, `metric` и `finish`.

## Надёжность и измерения

- история, `latestGenerationId`, частичные/прерванные сообщения, метрики и итоговый report сохраняются в одной session aggregate;
- поздние события отменённой генерации не попадают к клиенту;
- Gemini SSE имеет отдельные connection/request/socket timeout;
- TTS provider errors не маскируются под ошибку PCM-фрейма;
- ElevenLabs получает word-safe чанки текста и отдаёт PCM16 16 kHz;
- `simli_speaking` — диагностический proxy, а не доказательство lip-sync SLO: SDK не предоставляет timestamps видео-кадров.

Перед защитой используйте [acceptance-чек-лист](./docs/demo-acceptance.md): пять фиксированных диалогов, реальные значения first-audio/interrupt и ручная offline-проверка lip-sync при 60 fps.

Целевые продуктовые SLO:

- первый звук — до 3 секунд после отправки реплики;
- остановка речи/анимации — до 300 мс после нового текста;
- рассинхронизация речи и мимики — до 200 мс; для честного подтверждения необходимы media timestamps или анализ WebRTC-записи.

## Проверки

```bash
./gradlew test
./gradlew build
docker compose config
npm run build:frontend
npm run test:frontend
```

Реальные smoke-тесты Gemini, ElevenLabs, Simli и D-ID выполняйте только с настроенными ключами. Они расходуют квоты соответствующих провайдеров.


## Пользовательский flow

1. На стартовом экране выберите preset, загрузите Markdown-сценарий либо начните свободную тренировку.
2. На экране тренировки подключите аватара; отвечайте push-to-talk или раскройте ручной текстовый ввод.
3. Субтитры тренера отображаются по мере потокового ответа. Кнопка «Завершить тренировку» доступна только после создания сессии и вне активного голосового commit.
4. Подтвердите завершение в диалоге. На странице результата доступны оценка навыков, рекомендации, доказательства в стенограмме, статистика и повторная попытка отчёта при ошибке.

## Ограничения MVP

В MVP не входят automatic VAD turn detection, voice-activated interruption, RAG, auth и промышленная многопользовательская нагрузка. На стартовом экране можно выбрать один из preset-сценариев, загрузить валидный `.md` до 32 КБ или начать свободный диалог. Выбор применяется только к новой тренировке и закрепляется после первой реплики.
