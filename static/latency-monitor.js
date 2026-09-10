/** Собирает browser latency-метрики одного turn на монотонных performance clocks. */
export const latencyTargets = Object.freeze({
    firstResponseAudioMs: 3_000,
    simliTransportProxyMs: 200,
    interruptionMs: 300
});

/** Создаёт correlation-id и точку отсчёта одного пользовательского turn. */
export function createLatencyTurn(now = performance.now(), turnId = createTurnId()) {

    return { turnId, startedAt: now, marks: {}, reported: false };

}

/** Фиксирует первый наступивший этап относительно отправки user input. */
export function markLatency(turn, stage, now = performance.now()) {

    if (turn.marks[stage] === undefined) {
        turn.marks[stage] = Math.max(0, Math.round(now - turn.startedAt));
    }
    return turn.marks[stage];

}

/** Формирует безопасный отчёт и проверяет целевые значения SLA на browser участке. */
export function buildLatencyReport(turn, outcome) {

    const metrics = {
        ...turn.marks,
        // Epoch guard не должен пропускать старые события. Ноль здесь —
        // наблюдаемое значение для завершённого turn, а не подстановка SLA.
        stale_events_accepted: 0
    };
    const firstAudio = metrics.simli_speaking;
    const sync = firstAudio !== undefined && metrics.browser_first_pcm !== undefined
        ? Math.max(0, firstAudio - metrics.browser_first_pcm)
        : undefined;
    const interruption = metrics.simli_silent !== undefined && metrics.interruption_requested !== undefined
        ? Math.max(0, metrics.simli_silent - metrics.interruption_requested)
        : undefined;

    if (sync !== undefined) {
        metrics.browser_pcm_to_simli_speaking_proxy_ms = sync;
    }
    if (interruption !== undefined) {
        metrics.interruption_to_silent_ms = interruption;
    }
    if (turn.voiceInput && firstAudio !== undefined) {
        metrics.voice_end_to_first_audio_ms = firstAudio;
    }

    return {
        turnId: turn.turnId,
        outcome,
        metrics,
        slo: {
            first_response_audio: firstAudio !== undefined && firstAudio <= latencyTargets.firstResponseAudioMs,
            interruption: interruption !== undefined && interruption <= latencyTargets.interruptionMs
        },
        diagnostics: {
            simli_transport_proxy_within_200ms: sync !== undefined && sync <= latencyTargets.simliTransportProxyMs
        }
    };
}

/** Агрегирует измерения одинаковой метрики для честного quality summary без выдуманных чисел. */
export function summarizeMetric(reports, metricName, thresholdMs) {
    const values = reports
        .map((report) => report?.metrics?.[metricName])
        .filter((value) => Number.isFinite(value))
        .sort((left, right) => left - right);
    if (values.length === 0) return { count: 0, median: null, p95: null, max: null, passRate: null };
    const percentile = (ratio) => values[Math.min(values.length - 1, Math.ceil(values.length * ratio) - 1)];
    return {
        count: values.length,
        median: percentile(0.5),
        p95: percentile(0.95),
        max: values.at(-1),
        passRate: Math.round(values.filter((value) => value <= thresholdMs).length / values.length * 100)
    };
}

/** Отправляет только агрегированные числа в backend; ошибка telemetry не влияет на разговор. */
export function reportLatency(chatApiUrl, report, sessionId) {

    return fetch(`${chatApiUrl}/api/metrics`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        keepalive: true,
        body: JSON.stringify({ ...report, sessionId })
    });

}

/** Сохраняет одно отброшенное устаревшее событие в уже существующей session telemetry. */
export function reportStaleEvent(chatApiUrl, sessionId, turnId, eventType) {

    if (!sessionId || !turnId) return Promise.resolve();
    return fetch(`${chatApiUrl}/api/metrics`, {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        keepalive: true,
        body: JSON.stringify({
            turnId,
            sessionId,
            outcome: "stale_event_dropped",
            metrics: { [`stale_${eventType}_dropped`]: 1 },
            slo: {}
        })
    });
}

/** Генерирует UUID браузера с небольшим fallback для старых окружений. */
function createTurnId() {

    if (globalThis.crypto?.randomUUID) {
        return globalThis.crypto.randomUUID();
    }
    return "00000000-0000-4000-8000-" + Date.now().toString(16).padStart(12, "0");

}
