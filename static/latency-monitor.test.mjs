import assert from "node:assert/strict";
import test from "node:test";
import { buildLatencyReport, createLatencyTurn, markLatency, summarizeMetric } from "./latency-monitor.js";

test("отчёт проверяет целевые latency для первого звука, синхронизации и прерывания", () => {
    const turn = createLatencyTurn(1_000, "00000000-0000-4000-8000-000000000001");

    markLatency(turn, "browser_first_pcm", 2_000);
    markLatency(turn, "simli_speaking", 2_150);
    markLatency(turn, "interruption_requested", 4_000);
    markLatency(turn, "simli_silent", 4_280);
    const report = buildLatencyReport(turn, "completed");

    assert.equal(report.metrics.simli_speaking, 1_150);
    assert.equal(report.metrics.browser_pcm_to_simli_speaking_proxy_ms, 150);
    assert.equal(report.metrics.interruption_to_silent_ms, 280);
    assert.deepEqual(report.slo, {
        first_response_audio: true,
        interruption: true
    });
    assert.deepEqual(report.diagnostics, { simli_transport_proxy_within_200ms: true });
});

test("voice first audio считается от ptt release и агрегируется для quality summary", () => {
    const turn = createLatencyTurn(1_000, "voice-turn");
    turn.voiceInput = true;
    markLatency(turn, "ptt_release", 1_000);
    markLatency(turn, "simli_speaking", 3_500);
    const report = buildLatencyReport(turn, "completed");

    assert.equal(report.metrics.voice_end_to_first_audio_ms, 2_500);
    assert.deepEqual(summarizeMetric([
        report,
        { metrics: { voice_end_to_first_audio_ms: 1_000 } },
        { metrics: { voice_end_to_first_audio_ms: 4_000 } }
    ], "voice_end_to_first_audio_ms", 3_000), { count: 3, median: 2_500, p95: 4_000, max: 4_000, passRate: 67 });
});
