import { openSimliStream, SIMLI_PCM_CHUNK_BYTES } from "./simli-stream-client.js?v=4";
import { buildLatencyReport, createLatencyTurn, markLatency, reportLatency, reportStaleEvent } from "./latency-monitor.js";
import { createPushToTalkController } from "./push-to-talk-controller.js?v=13";

const video =
    document.getElementById("avatar");

const audio =
    document.getElementById("avatar-audio");

const text =
    document.getElementById("text");

const connectButton =
    document.getElementById("connect");

const speakButton =
    document.getElementById("speak");

const disconnectButton =
    document.getElementById("disconnect");

const pushToTalkButton = document.getElementById("push-to-talk");
const userTranscriptText = document.getElementById("user-transcript-text");

const status =
    document.getElementById("status");

const trainingTitle = document.getElementById("training-title");
const trainerTranscript = document.getElementById("trainer-transcript");
const finishButton = document.getElementById("finish-training");
const finishModal = document.getElementById("finish-modal");
const cancelFinishButton = document.getElementById("cancel-finish");
const confirmFinishButton = document.getElementById("confirm-finish");
const finishRecovery = document.getElementById("finish-recovery");
const finishRecoveryMessage = document.getElementById("finish-recovery-message");
const checkFinishResultButton = document.getElementById("check-finish-result");
const retryFinishButton = document.getElementById("retry-finish");


let agentManager = null;
let simliClient = null;
let simliConnected = false;
let streamRelay = null;
let avatarSpeaking = false;
let simliSessionStartedAt = null;
let simliSpeakingStartedAt = null;
let simliSpeakingTotalMs = 0;
let activeLatencyTurn = null;
let pendingInterruptedTurn = null;
let interruptionTimer = null;
let chatSessionId = null;
let appConfig = null;
let trainingFinishing = false;
let realtimeShuttingDown = false;
let currentAssistantText = "";
let activeStreamEpoch = 0;
const pageStartedAt = performance.now();
const scenarioSelection = readScenarioSelection();
const pushToTalk = createPushToTalkController({
    button: pushToTalkButton,
    transcriptElement: userTranscriptText,
    ensureAvatarConnected: () => Boolean(agentManager || (simliClient && simliConnected)),
    interrupt: () => {
        if (appConfig) interruptActiveTurn(appConfig);
    },
    submit: (message, metadata) => submitUserMessage(message, metadata),
    setStatus,
    onStateChange: () => updateFinishAvailability()
});

/** Читает одноразовый сценарий, выбранный до создания новой сессии. */
function readScenarioSelection() {

    try {

        const raw = sessionStorage.getItem("talking-heads.scenario-selection");
        if (!raw) {
            return null;
        }

        const selection = JSON.parse(raw);
        const hasPreset = typeof selection.presetId === "string" && selection.presetId.length > 0;
        const hasMarkdown = typeof selection.markdown === "string" && selection.markdown.length > 0;
        if (hasPreset === hasMarkdown) {
            sessionStorage.removeItem("talking-heads.scenario-selection");
            return null;
        }

        if (hasMarkdown && selection.markdown.length > 32 * 1024) {
            sessionStorage.removeItem("talking-heads.scenario-selection");
            return null;
        }

        return hasPreset ? { presetId: selection.presetId } : { markdown: selection.markdown };

    } catch {

        sessionStorage.removeItem("talking-heads.scenario-selection");
        return null;

    }

}

/** Возвращает сценарий исключительно для первого хода новой тренировки. */
function scenarioForNewSession() {

    return chatSessionId === null ? scenarioSelection : null;

}

/** Показывает выбранный режим, не выводя содержимое пользовательского Markdown. */
function renderScenarioLabel() {
    if (!trainingTitle) return;
    const display = readScenarioDisplay();
    trainingTitle.textContent = display?.title || (scenarioSelection?.markdown ? "Пользовательская тренировка" : "Свободная тренировка");
}

/** Читает только безопасные display-метаданные сценария, не его Markdown-инструкции. */
function readScenarioDisplay() {
    try { return JSON.parse(sessionStorage.getItem("talking-heads.scenario-display") || "null"); } catch { return null; }
}

/** Синхронизирует business action с состоянием созданной сессии и PTT. */
function updateFinishAvailability() {
    if (!finishButton) return;
    finishButton.disabled = !chatSessionId || trainingFinishing || ["LISTENING", "COMMITTING"].includes(pushToTalk.state);
}

/** Отображает текущую реплику AI-тренера без HTML-интерполяции. */
function setTrainerTranscript(value) {
    currentAssistantText = value;
    if (trainerTranscript) trainerTranscript.textContent = value;
}

/** Логирует измерение пользовательского пути без содержимого сообщений и секретов. */
function logTiming(event, startedAt, details = {}) {

    console.info("[timing]", {
        event,
        durationMs: Math.round(performance.now() - startedAt),
        sincePageStartMs: Math.round(performance.now() - pageStartedAt),
        ...details
    });

}

/** Фиксирует browser latency stage и не пишет текст пользовательской реплики в консоль. */
function markTurnLatency(turn, stage) {

    const elapsedMs = markLatency(turn, stage);
    console.info("[latency]", { turnId: turn.turnId, stage, elapsedMs });
    return elapsedMs;

}

/** Публикует завершённый browser-отчёт; telemetry не должна влиять на разговор. */
function finishTurnLatency(turn, config, outcome) {

    if (!turn || turn.reported) {
        return;
    }

    turn.reported = true;
    const report = buildLatencyReport(turn, outcome);
    console.info("[latency]", { turnId: report.turnId, outcome, metrics: report.metrics, slo: report.slo });
    reportLatency(config.chat_api_url, report, turn.sessionId || chatSessionId)
        .catch(() => console.warn("Не удалось передать latency-метрики"));

}

/** Пишет факт отброшенного старого callback в существующую telemetry без влияния на UI. */
function recordStaleStreamEvent(config, turn, eventType) {

    const sessionId = turn?.sessionId || chatSessionId;
    console.info("[latency]", { turnId: turn?.turnId, event: `stale_${eventType}_dropped` });
    reportStaleEvent(config.chat_api_url, sessionId, turn?.turnId, eventType)
        .catch(() => console.warn("Не удалось передать telemetry устаревшего события"));

}

/** Отменяет текущую речь перед новым вводом и начинает измерение target 300 мс. */
function interruptActiveTurn(config) {

    if (!activeLatencyTurn && !streamRelay && !avatarSpeaking) {
        return;
    }

    activeStreamEpoch += 1;

    const interruptedTurn = activeLatencyTurn;
    if (interruptedTurn) {
        interruptedTurn.interrupted = true;
        markTurnLatency(interruptedTurn, "interruption_requested");
        pendingInterruptedTurn = interruptedTurn;
    }
    activeLatencyTurn = null;

    if (streamRelay) {
        streamRelay.cancel();
        streamRelay = null;
    }
    simliClient?.ClearBuffer();
    setTrainerTranscript("Ответ тренера прерван.");
    clearTimeout(interruptionTimer);
    interruptionTimer = window.setTimeout(() => {
        if (pendingInterruptedTurn === interruptedTurn) {
            finishTurnLatency(interruptedTurn, config, "interruption_timeout");
            pendingInterruptedTurn = null;
        }
    }, 300);

}

/** Логирует переходы HTML video, чтобы отделить WebRTC от загрузки и воспроизведения. */
["loadedmetadata", "canplay", "playing", "waiting", "stalled", "ended"].forEach((event) => {

    video.addEventListener(event, () => {

        console.info("[timing]", {
            event: `video_${event}`,
            sincePageStartMs: Math.round(performance.now() - pageStartedAt),
            readyState: video.readyState,
            networkState: video.networkState
        });

    });

});

/** Обновляет отображаемый статус подключения или запроса. */
function setStatus(message) {

    status.textContent = message;

}

/** Загружает и кэширует безопасную публичную конфигурацию выбранного аватара. */
async function loadConfig() {

    if (appConfig) {

        return appConfig;

    }

    const startedAt = performance.now();
    const response =
        await fetch("/api/config");

    logTiming("config_response", startedAt, { status: response.status });

    if (!response.ok) {

        throw new Error(
            "Не удалось получить конфигурацию"
        );

    }

    appConfig = await response.json();

    return appConfig;

}

/** Подключает выбранный провайдер аватара и включает элементы управления диалогом. */
async function connect() {

    try {

        // Safari требует разблокировать Web Audio в user gesture. Сам микрофон
        // открывает только Scribe: второй probe getUserMedia создавал второй
        // browser prompt и ломал PTT на Safari.
        if (appConfig?.stt_enabled) {
            pushToTalk.prepareBrowserAudio();
        }

        const connectStartedAt = performance.now();

        setStatus(
            "Подключение к аватару..."
        );

        connectButton.disabled = true;


        // Кнопка становится доступной только после initialize(), поэтому повторный
        // HTTP-запрос здесь не нужен: используем уже полученную конфигурацию.
        const config = appConfig ?? await loadConfig();
        const sttPreconnection = beginSpeechRecognition(config);

        if (config.avatar_provider === "simli") {
            await connectSimli(config, connectStartedAt, sttPreconnection);
            return;
        }


        const callbacks = {

            /**
             * Прикрепляет готовый WebRTC-поток к элементу видео аватара.
             * @param {MediaStream} stream Поток, полученный от D-ID.
             */
            onSrcObjectReady(stream) {

                console.log(
                    "WebRTC stream ready"
                );

                video.srcObject =
                    stream;

                video.play()
                    .catch(console.error);

            },


            /**
             * Отображает изменение состояния подключения D-ID.
             * @param {string} state Новое состояние подключения.
             */
            onConnectionStateChange(state) {

                if (trainingFinishing || realtimeShuttingDown) return;

                console.log(
                    "Connection:",
                    state
                );

                setStatus(
                    "Соединение: " + state
                );

            },


            /**
             * Логирует служебное сообщение, полученное от D-ID.
             * @param {unknown} messages Данные сообщения D-ID.
             * @param {string} type Тип сообщения.
             */
            onNewMessage(messages, type) {

                console.log(
                    "Message:",
                    messages,
                    type
                );

            },


            /**
             * Логирует ошибку D-ID и показывает пользователю безопасный статус.
             * @param {unknown} error Основная ошибка D-ID.
             * @param {unknown} errorData Дополнительные данные ошибки.
             */
            onError(error, errorData) {

                if (trainingFinishing || realtimeShuttingDown) return;

                console.error(
                    "D-ID error:",
                    error,
                    errorData
                );

                setStatus(
                    "Ошибка D-ID"
                );

            }

        };


        const managerStartedAt = performance.now();
        const did =
            await import("https://cdn.jsdelivr.net/npm/@d-id/client-sdk/+esm");

        agentManager =
            await did.createAgentManager(

                config.agent_id,

                {

                    auth: {

                        type: "key",

                        clientKey:
                            config.client_key

                    },


                    callbacks,


                    streamOptions: {

                        compatibilityMode:
                            "auto",

                        streamWarmup:
                            true

                    }

                }

            );
        logTiming("did_manager_created", managerStartedAt);


        const didConnectStartedAt = performance.now();
        await agentManager.connect();
        logTiming("did_connect_completed", didConnectStartedAt);
        logTiming("avatar_connect_total", connectStartedAt);


        setStatus(
            "Аватар подключён"
        );


        speakButton.disabled = false;
        // Распознавание не должно задерживать уже готового аватара: Scribe
        // догружается отдельно, а PTT включится сам после успешного подключения.
        void activatePushToTalk(sttPreconnection);

        disconnectButton.disabled =
            false;


    }
    catch (error) {

        console.error(error);
        pushToTalk.disconnect();

        setStatus(
            "Ошибка подключения: "
            + error.message
        );

        connectButton.disabled =
            false;

    }

}

/** Подключает Simli по token, не получая API key и Face ID в браузер. */
async function connectSimli(config, connectStartedAt, sttPreconnection) {

    setStatus("Подключение к Simli...");
    simliConnected = false;
    const tokenStartedAt = performance.now();
    const response = await fetch(`${config.chat_api_url}/api/avatar/simli/session`, { method: "POST" });
    logTiming("simli_session_token_response", tokenStartedAt, { status: response.status });
    const payload = await response.json().catch(() => ({}));

    if (!response.ok) {
        throw new Error(payload.error || "Не удалось открыть сессию Simli");
    }

    const simliModule =
        await import("/vendor/simli-client.js");
    const { LogLevel, SimliClient } =
        simliModule.default;

    simliClient = new SimliClient(
        payload.token,
        video,
        audio,
        null,
        LogLevel.INFO,
        payload.transport
    );
    simliClient.on("start", () => {
        if (trainingFinishing || realtimeShuttingDown) return;
        simliConnected = true;
        simliSessionStartedAt = performance.now();
        logTiming("simli_start", connectStartedAt);
        setStatus("Аватар подключён");
    });
    simliClient.on("speaking", () => {
        if (trainingFinishing || realtimeShuttingDown) return;
        avatarSpeaking = true;
        simliSpeakingStartedAt = performance.now();
        if (activeLatencyTurn) {
            markTurnLatency(activeLatencyTurn, "simli_speaking");
        }
        logTiming("simli_speaking", simliSessionStartedAt || connectStartedAt);
        setStatus("Аватар говорит...");
    });
    simliClient.on("silent", () => {
        if (trainingFinishing || realtimeShuttingDown) return;
        avatarSpeaking = false;
        if (simliSpeakingStartedAt) {
            simliSpeakingTotalMs += performance.now() - simliSpeakingStartedAt;
            logTiming("simli_silent", simliSpeakingStartedAt, {
                speakingTotalMs: Math.round(simliSpeakingTotalMs)
            });
            simliSpeakingStartedAt = null;
        }
        if (pendingInterruptedTurn) {
            markTurnLatency(pendingInterruptedTurn, "simli_silent");
            finishTurnLatency(pendingInterruptedTurn, config, "interrupted");
            pendingInterruptedTurn = null;
            clearTimeout(interruptionTimer);
        } else if (activeLatencyTurn && !streamRelay) {
            markTurnLatency(activeLatencyTurn, "simli_silent");
            finishTurnLatency(activeLatencyTurn, config, "completed");
            activeLatencyTurn = null;
        }
        if (!streamRelay) {
            setStatus("Готов");
            speakButton.disabled = false;
        }
    });
    simliClient.on("ack", () => console.info("[timing]", { event: "simli_ack" }));
    simliClient.on("stop", () => handleSimliTransportStopped("Сессия Simli завершилась из-за неактивности. Подключите аватара заново."));
    simliClient.on("startup_error", (error) => {
        console.error("Simli startup error", error);
        handleSimliTransportStopped("Не удалось запустить Simli. Подключите аватара заново.");
    });
    simliClient.on("error", (error) => {
        console.error("Simli error", error);
        handleSimliTransportStopped("Соединение с Simli потеряно. Подключите аватара заново.");
    });

    const startedAt = performance.now();
    await simliClient.start();
    logTiming("simli_connect_completed", startedAt);
    logTiming("avatar_connect_total", connectStartedAt, { provider: "simli" });
    speakButton.disabled = false;
    disconnectButton.disabled = false;
    // Scribe подключается параллельно, но его timeout или provider error не
    // может удерживать Simli в состоянии «Подключение».
    void activatePushToTalk(sttPreconnection);
}

/** Возвращает UI в состояние переподключения после idle timeout или ошибки Simli. */
function handleSimliTransportStopped(message) {

    if (trainingFinishing || realtimeShuttingDown) {
        return;
    }

    if (!simliClient && !simliConnected) {
        return;
    }

    simliConnected = false;
    avatarSpeaking = false;
    if (streamRelay) {
        try {
            streamRelay.cancel();
        } catch (error) {
            console.warn("Не удалось отменить завершённый поток Simli", error);
        }
        streamRelay = null;
    }
    simliClient = null;
    video.srcObject = null;
    audio.srcObject = null;
    speakButton.disabled = true;
    disconnectButton.disabled = true;
    pushToTalk.disconnect();
    connectButton.disabled = false;
    setStatus(message);

}

/** Запрашивает ответ LLM у Kotlin backend и передаёт его в D-ID. */
async function speak() {
    await submitUserMessage(text.value.trim(), { source: "text" });
}

/** Отправляет committed text любого источника в единственный существующий training pipeline. */
async function submitUserMessage(value, { source = "text", releasedAt = null } = {}) {


    if (!value) {

        setStatus(
            "Введите текст"
        );

        return;

    }


    if (!agentManager && !simliClient) {

        setStatus(
            "Сначала подключите аватар"
        );

        return;

    }


    try {

        if (trainingFinishing) return;
        if (source === "text") userTranscriptText.textContent = value;
        const speakStartedAt = performance.now();

        speakButton.disabled = true;


        setStatus("Запрашиваю ответ...");

        const config = await loadConfig();
        if (config.avatar_provider === "simli") {
            if (!simliConnected || !simliClient) {
                setStatus("Сессия Simli завершена. Сначала подключите аватара заново.");
                return;
            }
            interruptActiveTurn(config);
            setTrainerTranscript("Тренер формулирует ответ…");
            await speakWithSimli(config, value, speakStartedAt, source === "voice" ? releasedAt : null);
            return;
        }
        setTrainerTranscript("Тренер формулирует ответ…");
        const backendStartedAt = performance.now();
        const response = await fetch(`${config.chat_api_url}/api/chat`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                sessionId: chatSessionId,
                message: value,
                scenario: scenarioForNewSession()
            })
        });

        const payload = await response.json().catch(() => ({}));
        logTiming("chat_backend_response", backendStartedAt, { status: response.status });

        if (!response.ok) {
            throw new Error(payload.error || "Ошибка chat backend");
        }

        chatSessionId = payload.sessionId;
        updateFinishAvailability();
        setTrainerTranscript(payload.assistantMessage);
        setStatus("Аватар говорит...");


        const didSpeakStartedAt = performance.now();
        await agentManager.speak({

            type: "text",

            input: payload.assistantMessage

        });
        logTiming("did_speak_completed", didSpeakStartedAt, { sessionId: chatSessionId });
        logTiming("speak_total", speakStartedAt, { sessionId: chatSessionId, source });


        setStatus(
            "Готов"
        );


    }
    catch (error) {

        console.error(error);

        setStatus(
            "Ошибка: " +
            error.message
        );

    }
    finally {

        speakButton.disabled = false;

    }

}

/** Преобразует HTTP URL Kotlin API в URL browser WebSocket. */
function streamUrl(chatApiUrl) {

    const url = new URL("/api/chat/stream", chatApiUrl);
    url.protocol = url.protocol === "https:" ? "wss:" : "ws:";
    return url.toString();

}

/** Передаёт Scribe только публичные runtime-настройки и Kotlin token endpoint. */
function configurePushToTalk(config) {

    pushToTalk.configure({
        tokenUrl: new URL("/api/stt/token", config.chat_api_url).toString(),
        modelId: config.scribe_model || "scribe_v2_realtime",
        languageCode: config.scribe_language_code || undefined
    });

}

/**
 * Открывает Scribe параллельно с WebRTC аватара, сразу после browser gesture.
 * Кнопка PTT остаётся выключенной до activatePushToTalk(), то есть до готовности
 * самого аватара.
 */
function beginSpeechRecognition(config) {

    if (!config.stt_enabled) {
        return Promise.resolve(false);
    }

    configurePushToTalk(config);
    return pushToTalk.transcriber.connect()
        .then(() => pushToTalk.state === "READY")
        .catch(() => false);

}

/** Включает PTT в UI только когда одновременно готовы аватар и Scribe. */
async function activatePushToTalk(sttPreconnection) {

    if (!await sttPreconnection) {
        return;
    }
    await pushToTalk.connect();

}

/** Выбирает размер PCM16 блока для Simli: 3000 Int16-семплов = 6000 bytes. */
function resolvePcmChunkBytes() {

    const value = new URLSearchParams(window.location.search).get("pcmChunkBytes");
    if (value === "raw") {
        return null;
    }
    const parsed = Number(value || SIMLI_PCM_CHUNK_BYTES);
    return Number.isInteger(parsed) && parsed > 0 && parsed % 2 === 0 ? parsed : SIMLI_PCM_CHUNK_BYTES;

}

/** Передаёт Gemini text stream и PCM16 фреймы в уже подключённый Simli client. */
async function speakWithSimli(config, message, speakStartedAt, voiceReleasedAt = null) {

    setStatus("Запрашиваю потоковый ответ...");
    const latencyStartedAt = Number.isFinite(voiceReleasedAt) ? voiceReleasedAt : speakStartedAt;
    const turn = createLatencyTurn(latencyStartedAt);
    turn.voiceInput = Number.isFinite(voiceReleasedAt);
    if (turn.voiceInput) markLatency(turn, "ptt_release", voiceReleasedAt);
    activeLatencyTurn = turn;
    const streamEpoch = ++activeStreamEpoch;
    let receivedFirstPcm = false;
    let receivedFirstDelta = false;

    const relay = openSimliStream({
        url: streamUrl(config.chat_api_url),
        sessionId: chatSessionId,
        message,
        scenario: scenarioForNewSession(),
        simliClient,
        pcmChunkBytes: resolvePcmChunkBytes(),
        turnId: turn.turnId,
        onSession: (sessionId) => {
            if (streamEpoch !== activeStreamEpoch) return;
            chatSessionId = sessionId;
            turn.sessionId = sessionId;
            updateFinishAvailability();
            markTurnLatency(turn, "session_received");
        },
        onDelta: (delta) => {
            if (streamEpoch !== activeStreamEpoch) return;
            setTrainerTranscript(currentAssistantText === "Тренер формулирует ответ…" ? delta : currentAssistantText + delta);
            if (!receivedFirstDelta) {
                receivedFirstDelta = true;
                markTurnLatency(turn, "gemini_first_delta");
                logTiming("gemini_first_delta", speakStartedAt);
            }
        },
        onFirstPcm: (bytes) => {
            if (streamEpoch !== activeStreamEpoch) return;
            if (!receivedFirstPcm) {
                receivedFirstPcm = true;
                markTurnLatency(turn, "browser_first_pcm");
                logTiming("browser_first_pcm", speakStartedAt, { bytes });
            }
        },
        onMetrics: (payload) => {
            if (streamEpoch !== activeStreamEpoch) return;
            turn.backendMetrics = payload.metrics;
            console.info("[latency]", { turnId: turn.turnId, backend: payload.metrics, outcome: payload.outcome });
        },
        onDone: (sessionId) => {
            if (streamEpoch !== activeStreamEpoch) return;
            chatSessionId = sessionId || chatSessionId;
            turn.sessionId = chatSessionId;
            markTurnLatency(turn, "stream_completed");
            logTiming("stream_completed", speakStartedAt, { sessionId: chatSessionId });
        },
        onStale: (eventType) => recordStaleStreamEvent(config, turn, eventType),
        isActive: () => streamEpoch === activeStreamEpoch && !trainingFinishing
    });
    streamRelay = relay;
    try {
        await relay.completion;
    } catch (error) {
        if (turn.interrupted) {
            return;
        }
        finishTurnLatency(turn, config, "stream_failed");
        throw error;
    } finally {
        if (streamRelay === relay) {
            streamRelay = null;
        }
    }

    logTiming("speak_total", speakStartedAt, { sessionId: chatSessionId, provider: "simli" });
    window.setTimeout(() => {
        if (activeLatencyTurn === turn && !avatarSpeaking) {
            finishTurnLatency(turn, config, "completed_without_avatar_signal");
            activeLatencyTurn = null;
        }
    }, 3_000);
}

/** Закрывает transport, media и Scribe; используется и Disconnect, и terminal Finish. */
async function shutdownRealtimeMedia({ updateUi = true, finalStatus = null } = {}) {
    realtimeShuttingDown = true;
    activeStreamEpoch += 1;
    try {
        if (streamRelay) {
            try { streamRelay.cancel(); } catch (error) { console.warn("Не удалось отменить поток", error); }
            streamRelay = null;
        }
        try { simliClient?.ClearBuffer?.(); } catch (error) { console.warn("Не удалось очистить Simli buffer", error); }
        if (simliClient) {
            try { await simliClient.stop(); } catch (error) { console.warn("Не удалось штатно остановить Simli", error); }
            if (simliSessionStartedAt) logTiming("simli_session_closed", simliSessionStartedAt, { speakingTotalMs: Math.round(simliSpeakingTotalMs) });
        }
        if (agentManager) {
            try { await agentManager.disconnect(); } catch (error) { console.warn("Не удалось штатно отключить D-ID", error); }
        }
    } finally {
        simliClient = null;
        simliConnected = false;
        agentManager = null;
        avatarSpeaking = false;
        simliSessionStartedAt = null;
        simliSpeakingStartedAt = null;
        simliSpeakingTotalMs = 0;
        pushToTalk.disconnect();
        try { video.pause(); } catch (_) { /* media may not be initialized */ }
        try { audio.pause(); } catch (_) { /* media may not be initialized */ }
        video.srcObject = null;
        audio.srcObject = null;
        realtimeShuttingDown = false;
    }
    if (updateUi) {
        speakButton.disabled = true;
        disconnectButton.disabled = true;
        connectButton.disabled = false;
        if (finalStatus) setStatus(finalStatus);
    }
}

/** Отключает текущий avatar transport и отменяет незавершённый поток речи. */
async function disconnect() {
    if (trainingFinishing) return;
    await shutdownRealtimeMedia({ updateUi: true, finalStatus: "Отключено" });
}

/** Открывает confirmation modal перед необратимым завершением сессии. */
function openFinishModal() {
    if (finishButton?.disabled) return;
    finishModal.hidden = false;
    confirmFinishButton.focus();
}

/** Закрывает confirmation modal без изменения training state. */
function closeFinishModal() {
    finishModal.hidden = true;
    finishButton.focus();
}

/** Блокирует conversational controls после подтверждённого terminal Finish. */
function lockFinishedTrainingUi() {
    finishButton.disabled = true;
    pushToTalkButton.disabled = true;
    speakButton.disabled = true;
    connectButton.disabled = true;
    disconnectButton.disabled = true;
}

/** Показывает безопасное восстановление при потере response после idempotent Finish. */
function showFinishRecovery(message) {
    finishRecoveryMessage.textContent = message;
    finishRecovery.hidden = false;
    checkFinishResultButton.disabled = false;
    retryFinishButton.disabled = false;
}

/** Запрашивает terminal finish и переходит к результату при успешном подтверждении. */
async function requestFinish() {
    const response = await fetch(`${appConfig.chat_api_url}/api/sessions/${chatSessionId}/finish`, { method: "POST" });
    if (!response.ok) throw new Error(response.status === 404 ? "Тренировка не найдена." : "Не удалось завершить тренировку.");
    window.location.assign(`/report.html?sessionId=${encodeURIComponent(chatSessionId)}`);
}

/** Завершает разговор, закрывает media до evaluation и не возвращает сессию в ACTIVE UX. */
async function finishTraining() {
    if (!chatSessionId || trainingFinishing) return;
    trainingFinishing = true;
    closeFinishModal();
    finishRecovery.hidden = true;
    lockFinishedTrainingUi();
    setStatus("✓ Тренировка завершена. Формируем обратную связь…");
    await shutdownRealtimeMedia({ updateUi: false });
    try {
        await requestFinish();
    } catch (error) {
        console.error(error);
        setStatus("Не удалось получить подтверждение от сервера.");
        showFinishRecovery("Тренировка уже закрыта в этом окне. Проверьте результат или повторите безопасный запрос завершения.");
    }
}

/** Проверяет сохранённое terminal state, не открывая обратно разговор. */
async function checkFinishResult() {
    checkFinishResultButton.disabled = true;
    try {
        const response = await fetch(`${appConfig.chat_api_url}/api/sessions/${chatSessionId}/result`);
        if (!response.ok) throw new Error("Результат пока недоступен.");
        const session = await response.json();
        if (session.status === "FINISHED") {
            window.location.assign(`/report.html?sessionId=${encodeURIComponent(chatSessionId)}`);
            return;
        }
        showFinishRecovery("Сервер ещё не подтвердил завершение. Повторите безопасный запрос.");
    } catch (error) {
        showFinishRecovery(error.message || "Не удалось проверить результат.");
    } finally {
        checkFinishResultButton.disabled = false;
    }
}

/** Повторяет идемпотентный finish, сохраняя выключенные media и controls. */
async function retryFinish() {
    retryFinishButton.disabled = true;
    try {
        await requestFinish();
    } catch (error) {
        showFinishRecovery(error.message || "Не удалось повторить завершение.");
    } finally {
        retryFinishButton.disabled = false;
    }
}


connectButton.addEventListener(
    "click",
    connect
);


speakButton.addEventListener(
    "click",
    speak
);


disconnectButton.addEventListener(
    "click",
    disconnect
);

finishButton.addEventListener("click", openFinishModal);
cancelFinishButton.addEventListener("click", closeFinishModal);
confirmFinishButton.addEventListener("click", finishTraining);
checkFinishResultButton.addEventListener("click", checkFinishResult);
retryFinishButton.addEventListener("click", retryFinish);
finishModal.addEventListener("click", (event) => {
    if (event.target.dataset.closeFinishModal !== undefined) closeFinishModal();
});

/** Загружает конфигурацию до первого click, чтобы PTT permission не терял user gesture. */
async function initialize() {

    renderScenarioLabel();
    try {
        await loadConfig();
        connectButton.disabled = false;
        setStatus("Нажмите «Подключить», затем разрешите доступ к микрофону.");
    } catch (error) {
        console.error(error);
        setStatus("Не удалось загрузить конфигурацию приложения.");
    }

}

void initialize();
