#!/usr/bin/env python3
"""Собирает честный demo acceptance report из сохранённых raw session artifacts."""

from __future__ import annotations

import argparse
import csv
import json
import math
import statistics
import sys
import urllib.error
import urllib.request
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
CONFIG_PATH = ROOT / "scripts" / "acceptance-config.json"


def read_json(path: Path, default: Any) -> Any:
    """Читает JSON artifact либо возвращает явный default, если файла ещё нет."""

    if not path.exists():
        return default
    with path.open(encoding="utf-8") as source:
        return json.load(source)


def write_json(path: Path, value: Any) -> None:
    """Записывает UTF-8 JSON с предсказуемой структурой для повторной генерации."""

    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def nearest_rank(values: list[float], ratio: float) -> float | None:
    """Возвращает percentile методом nearest-rank для малых demo-выборок."""

    if not values:
        return None
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * ratio) - 1)]


def metric_summary(values: list[float], threshold: int) -> dict[str, Any]:
    """Рассчитывает сводку измерений без подстановки отсутствующих значений."""

    valid = sorted(value for value in values if isinstance(value, (int, float)) and 0 <= value <= 600_000)
    if not valid:
        return {"status": "NOT_MEASURED", "n": 0, "medianMs": None, "p95Ms": None, "maxMs": None,
                "meanMs": None, "passCount": 0, "failCount": 0, "passRate": None}
    passed = sum(value <= threshold for value in valid)
    return {
        "status": "PASS" if max(valid) <= threshold else "FAIL",
        "n": len(valid),
        "medianMs": nearest_rank(valid, 0.5),
        "p95Ms": nearest_rank(valid, 0.95),
        "maxMs": max(valid),
        "meanMs": round(statistics.mean(valid), 2),
        "passCount": passed,
        "failCount": len(valid) - passed,
        "passRate": round(passed / len(valid) * 100, 2),
    }


def metric_values(metrics: list[dict[str, Any]], name: str) -> list[float]:
    """Извлекает валидные одноимённые session metrics и предупреждает о malformed данных."""

    values: list[float] = []
    for metric in metrics:
        if metric.get("name") != name:
            continue
        value = metric.get("valueMs")
        if isinstance(value, (int, float)) and 0 <= value <= 600_000:
            values.append(value)
        else:
            print(f"WARNING: malformed {name} metric ignored: {value!r}", file=sys.stderr)
    return values


def status_finished(value: dict[str, Any]) -> bool:
    """Проверяет terminal session state, требуемый для acceptance-прогона."""

    return value.get("status") == "FINISHED" and bool(value.get("messages"))


def load_session_artifacts(run_dir: Path) -> list[dict[str, Any]]:
    """Связывает ручные case answers с raw result/metrics responses."""

    manifest = read_json(run_dir / "sessions.json", {"sessions": []})
    rows = manifest.get("sessions", []) if isinstance(manifest, dict) else []
    result: list[dict[str, Any]] = []
    for row in rows:
        raw_result = read_json(run_dir / "raw" / row.get("resultFile", ""), {})
        raw_metrics = read_json(run_dir / "raw" / row.get("metricsFile", ""), {})
        metrics = raw_metrics.get("metrics", raw_result.get("metrics", []))
        result.append({**row, "result": raw_result, "metrics": metrics if isinstance(metrics, list) else []})
    return result


def aggregate(run_dir: Path) -> dict[str, Any]:
    """Строит presentation-ready aggregate только из raw artifacts и ручных наблюдений."""

    config = read_json(CONFIG_PATH, {})
    sessions = load_session_artifacts(run_dir)
    first_audio_values = [value for row in sessions for value in metric_values(row["metrics"], "browser_voice_end_to_first_audio_ms")]
    interruption_values = [value for row in sessions for value in metric_values(row["metrics"], "browser_interruption_to_silent_ms")]
    first_audio = metric_summary(first_audio_values, config["firstAudioMaxMs"])
    interruption = metric_summary(interruption_values, config["interruptMaxMs"])
    case_four = next((row for row in sessions if row.get("caseId") == 4), None)
    if case_four and not interruption_values:
        interruption["status"] = "FAIL"
        interruption["reason"] = "NO_SILENT_EVENT"

    stale = {key: 0 for key in ("text", "pcm", "done", "accepted")}
    stale_seen_accepted = False
    for row in sessions:
        metrics = row["metrics"]
        for key in ("text", "pcm", "done"):
            stale[key] += int(sum(metric_values(metrics, f"browser_stale_{key}_dropped")))
        accepted = metric_values(metrics, "browser_stale_events_accepted")
        if accepted:
            stale_seen_accepted = True
            stale["accepted"] += int(sum(accepted))
    old_audio = case_four.get("oldAudioResumed") if case_four else None
    old_caption = case_four.get("oldCaptionReappeared") if case_four else None
    cancellation_ok = stale_seen_accepted and stale["accepted"] == 0 and old_audio is False and old_caption is False
    cancellation = {
        "status": "PASS" if cancellation_ok else ("NOT_MEASURED" if not stale_seen_accepted or case_four is None else "FAIL"),
        "staleTextDropped": stale["text"], "stalePcmDropped": stale["pcm"], "staleDoneDropped": stale["done"],
        "staleEventsAccepted": stale["accepted"] if stale_seen_accepted else None,
        "manualOldAudioResume": old_audio, "manualOldCaptionResume": old_caption,
    }

    finished = [row for row in sessions if status_finished(row["result"])]
    ready = [row for row in finished if row["result"].get("reportStatus") == "READY" and row["result"].get("report")]
    reports = {"ready": len(ready), "finished": len(finished),
               "status": "PASS" if finished and len(ready) == len(finished) else "FAIL"}
    scenario_passed = sum(bool(row.get("scenarioPass")) and status_finished(row["result"]) for row in sessions)
    scenarios = {"passed": scenario_passed, "total": config["scenarioTotal"],
                 "status": "PASS" if scenario_passed >= config["scenarioPassRequired"] else "FAIL"}

    lip_raw = read_json(run_dir / "lip-sync.json", {"valuesMs": []})
    lip_values = [value for value in lip_raw.get("valuesMs", []) if isinstance(value, (int, float)) and value >= 0]
    lip_summary = metric_summary(lip_values, config["lipSyncMaxMs"])
    lip_sync = ({"status": "MANUAL_VALIDATION_REQUIRED", "n": 0, "methodology":
                 "Manual validation from 60 fps screen recording; 200 ms corresponds approximately to 12 frames."}
                if not lip_values else {**lip_summary, "methodology":
                 "Manual validation from 60 fps screen recording; 200 ms corresponds approximately to 12 frames."})

    automatic_pass = all(item["status"] == "PASS" for item in (first_audio, interruption, cancellation, scenarios, reports))
    overall = "PASS" if automatic_pass and lip_sync["status"] == "PASS" else ("PARTIAL" if automatic_pass and lip_sync["status"] == "MANUAL_VALIDATION_REQUIRED" else "FAIL")
    diagnostics: dict[str, dict[str, Any]] = {}
    for name in ("stream_session_ready", "stream_gemini_first_delta", "stream_tts_first_audio", "stream_stream_completed",
                 "browser_gemini_first_delta", "browser_browser_first_pcm", "browser_browser_pcm_to_simli_speaking_proxy_ms",
                 "backend_first_token", "backend_generation_total"):
        values = [value for row in sessions for value in metric_values(row["metrics"], name)]
        diagnostics[name] = metric_summary(values, 600_000)
    return {
        "runId": run_dir.name, "generatedAt": datetime.now(timezone.utc).isoformat(), "targets": {
            "firstAudioMs": config["firstAudioMaxMs"], "interruptMs": config["interruptMaxMs"],
            "lipSyncMs": config["lipSyncMaxMs"], "scenarioSuccessRequired": config["scenarioPassRequired"]},
        "preflight": read_json(run_dir / "preflight.json", {}), "firstAudio": first_audio, "interrupt": interruption,
        "cancellation": cancellation, "lipSync": lip_sync, "scenarios": scenarios, "reports": reports,
        "sessions": [{key: value for key, value in row.items() if key not in {"result", "metrics"}} | {
            "status": row["result"].get("status"), "reportStatus": row["result"].get("reportStatus")}
            for row in sessions], "diagnostics": diagnostics, "overallStatus": overall,
    }


def fmt(value: Any) -> str:
    """Отображает отсутствующие значения честным presentation-ready маркером."""

    return "NOT_MEASURED" if value is None else str(value)


def write_csv(run_dir: Path, report: dict[str, Any]) -> None:
    """Создаёт одну CSV-строку на browser turn, сохраняя пустые отсутствующие поля."""

    rows = load_session_artifacts(run_dir)
    with (run_dir / "metrics.csv").open("w", newline="", encoding="utf-8") as output:
        fields = ["run_id", "case_id", "session_id", "turn_id", "voice_end_to_first_audio_ms", "first_audio_pass",
                  "interruption_to_silent_ms", "interruption_pass", "stale_text_dropped", "stale_pcm_dropped",
                  "stale_done_dropped", "report_status", "scenario_pass"]
        writer = csv.DictWriter(output, fieldnames=fields)
        writer.writeheader()
        for row in rows:
            by_turn: dict[str, dict[str, float]] = defaultdict(dict)
            for metric in row["metrics"]:
                name, turn_id, value = metric.get("name"), metric.get("turnId"), metric.get("valueMs")
                if isinstance(name, str) and isinstance(value, (int, float)) and turn_id:
                    by_turn[turn_id][name] = value
            for turn_id, metrics in by_turn.items():
                audio = metrics.get("browser_voice_end_to_first_audio_ms")
                interrupt = metrics.get("browser_interruption_to_silent_ms")
                writer.writerow({"run_id": report["runId"], "case_id": row.get("caseId"), "session_id": row.get("sessionId"),
                    "turn_id": turn_id, "voice_end_to_first_audio_ms": audio if audio is not None else "",
                    "first_audio_pass": "" if audio is None else str(audio <= report["targets"]["firstAudioMs"]).lower(),
                    "interruption_to_silent_ms": interrupt if interrupt is not None else "",
                    "interruption_pass": "" if interrupt is None else str(interrupt <= report["targets"]["interruptMs"]).lower(),
                    "stale_text_dropped": metrics.get("browser_stale_text_dropped", ""),
                    "stale_pcm_dropped": metrics.get("browser_stale_pcm_dropped", ""),
                    "stale_done_dropped": metrics.get("browser_stale_done_dropped", ""),
                    "report_status": row["result"].get("reportStatus", ""), "scenario_pass": str(bool(row.get("scenarioPass"))).lower()})


def write_markdown(run_dir: Path, report: dict[str, Any]) -> None:
    """Формирует основной читаемый отчёт с разделением acceptance и diagnostics."""

    session_scenarios = [row["result"].get("scenario", {}).get("title") for row in load_session_artifacts(run_dir)
                         if isinstance(row["result"].get("scenario"), dict)]
    provider = report.get("preflight", {}).get("avatar_provider", "NOT_MEASURED")
    scenario = ", ".join(sorted(set(value for value in session_scenarios if value))) or "NOT_MEASURED"
    lines = ["# Demo Acceptance Results", "", f"Run: `{report['runId']}`", f"Date: {report['generatedAt']}",
             f"Avatar provider: {provider}", f"Scenario: {scenario}", "",
             "## Summary", "", "| Metric | Target | Result | Status |", "| --- | --- | --- | --- |"]
    first, interrupt, cancellation, scenarios, reports, lip = (report[key] for key in ("firstAudio", "interrupt", "cancellation", "scenarios", "reports", "lipSync"))
    lines += [
        f"| First audio | ≤{report['targets']['firstAudioMs']} ms | p95 {fmt(first['p95Ms'])} ms; max {fmt(first['maxMs'])} ms | {first['status']} |",
        f"| Interruption | ≤{report['targets']['interruptMs']} ms | p95 {fmt(interrupt['p95Ms'])} ms; max {fmt(interrupt['maxMs'])} ms | {interrupt['status']} |",
        f"| Cancellation | No stale playback | accepted {fmt(cancellation['staleEventsAccepted'])} | {cancellation['status']} |",
        f"| Scenario | ≥{report['targets']['scenarioSuccessRequired']}/{scenarios['total']} | {scenarios['passed']}/{scenarios['total']} | {scenarios['status']} |",
        f"| Reports | Every FINISHED session READY | {reports['ready']}/{reports['finished']} READY | {reports['status']} |",
        f"| Lip-sync | ≤{report['targets']['lipSyncMs']} ms | {('max ' + str(lip.get('maxMs')) + ' ms') if lip['status'] != 'MANUAL_VALIDATION_REQUIRED' else 'MANUAL_VALIDATION_REQUIRED'} | {lip['status']} |",
        "", "## First audio", "", f"N: {first['n']}", f"Median: {fmt(first['medianMs'])} ms", f"P95 (nearest-rank): {fmt(first['p95Ms'])} ms", f"Max: {fmt(first['maxMs'])} ms", f"Pass rate: {fmt(first['passRate'])}%", "",
        "## Interruption", "", f"N: {interrupt['n']}", f"Median: {fmt(interrupt['medianMs'])} ms", f"P95 (nearest-rank): {fmt(interrupt['p95Ms'])} ms", f"Max: {fmt(interrupt['maxMs'])} ms", f"Pass rate: {fmt(interrupt['passRate'])}%", f"Reason: {interrupt.get('reason', '—')}", "",
        "## Cancellation", "", f"Dropped stale text/PCM/done: {cancellation['staleTextDropped']}/{cancellation['stalePcmDropped']}/{cancellation['staleDoneDropped']}", f"Stale events accepted: {fmt(cancellation['staleEventsAccepted'])}", f"Manual old audio resumed: {fmt(cancellation['manualOldAudioResume'])}", f"Manual old caption reappeared: {fmt(cancellation['manualOldCaptionResume'])}", "",
        "## Scenario cases", ""]
    for session in report["sessions"]:
        verdict = "PASS" if session.get("scenarioPass") and session.get("status") == "FINISHED" else "FAIL"
        lines.append(f"- Case {session.get('caseId')}: {verdict} — session `{session.get('sessionId')}`")
    lines += ["", "## Report generation", "", f"READY reports / FINISHED sessions: {reports['ready']} / {reports['finished']} ({reports['status']})", "", "## Lip-sync validation", "", lip["methodology"]]
    if lip["status"] == "MANUAL_VALIDATION_REQUIRED":
        lines.append("Status: MANUAL_VALIDATION_REQUIRED. Simli transport proxy is diagnostic only; it is not audio-video lip-sync.")
    else:
        lines += [
            f"Samples (ms): {read_json(run_dir / 'lip-sync.json', {}).get('valuesMs', [])}",
            f"N: {lip['n']}", f"Median: {fmt(lip['medianMs'])} ms",
            f"P95 (nearest-rank): {fmt(lip['p95Ms'])} ms", f"Max: {fmt(lip['maxMs'])} ms",
            f"Pass rate ≤{report['targets']['lipSyncMs']} ms: {fmt(lip['passRate'])}%", f"Status: {lip['status']}",
        ]
    lines += ["", "## Diagnostics", "", "These are diagnostic stages, not acceptance substitutes. `stream_*` values are server-local elapsed durations from input receipt; browser and server clocks are never subtracted from each other.", "", "| Metric | N | Median ms | P95 ms | Max ms |", "| --- | ---: | ---: | ---: | ---: |"]
    for name, summary in report["diagnostics"].items():
        lines.append(f"| `{name}` | {summary['n']} | {fmt(summary['medianMs'])} | {fmt(summary['p95Ms'])} | {fmt(summary['maxMs'])} |")
    limitations = []
    if first["status"] != "PASS": limitations.append("First-audio telemetry is missing or outside target.")
    if interrupt["status"] != "PASS": limitations.append("Interruption telemetry is missing, timed out, or outside target.")
    if lip["status"] == "MANUAL_VALIDATION_REQUIRED": limitations.append("True lip-sync requires manual 60 fps audio/video validation; Simli exposes no phoneme/video timestamps.")
    if not limitations: limitations.append("No automatic limitation detected in recorded artifacts.")
    lines += ["", "## Limitations", ""] + [f"- {value}" for value in limitations] + ["", "## OVERALL ACCEPTANCE STATUS", "", f"**{report['overallStatus']}**", ""]
    (run_dir / "report.md").write_text("\n".join(lines), encoding="utf-8")


def generate(run_dir: Path) -> int:
    """Генерирует JSON, CSV и Markdown artifacts и возвращает documented exit code."""

    report = aggregate(run_dir)
    write_json(run_dir / "report.json", report)
    write_csv(run_dir, report)
    write_markdown(run_dir, report)
    return {"PASS": 0, "PARTIAL": 2, "FAIL": 1}[report["overallStatus"]]


def capture(args: argparse.Namespace) -> int:
    """Загружает safe result endpoint и сохраняет raw ответы без секретов."""

    run_dir = Path(args.run_dir)
    session_id = extract_session_id(args.session)
    url = args.base_url.rstrip("/") + f"/api/sessions/{session_id}/result"
    try:
        with urllib.request.urlopen(url, timeout=15) as response:
            result = json.load(response)
    except (urllib.error.URLError, urllib.error.HTTPError, json.JSONDecodeError) as error:
        print(f"Cannot fetch session result: {error}", file=sys.stderr)
        return 1
    raw_dir = run_dir / "raw"
    result_name = f"session-{args.case_id}-result.json"
    metrics_name = f"session-{args.case_id}-metrics.json"
    write_json(raw_dir / result_name, result)
    write_json(raw_dir / metrics_name, {"sessionId": session_id, "metrics": result.get("metrics", [])})
    manifest = read_json(run_dir / "sessions.json", {"sessions": []})
    rows = [item for item in manifest.get("sessions", []) if item.get("caseId") != args.case_id]
    rows.append({"caseId": args.case_id, "sessionId": session_id, "scenarioPass": args.scenario_pass == "true",
                 "oldAudioResumed": parse_optional_bool(args.old_audio), "oldCaptionReappeared": parse_optional_bool(args.old_caption),
                 "resultFile": result_name, "metricsFile": metrics_name})
    manifest["sessions"] = sorted(rows, key=lambda item: item["caseId"])
    write_json(run_dir / "sessions.json", manifest)
    return 0


def extract_session_id(value: str) -> str:
    """Принимает UUID либо report URL и возвращает строго UUID сессии."""

    import re
    match = re.search(r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}", value)
    if not match:
        raise ValueError("Paste a UUID session ID or report URL containing a UUID")
    return match.group(0)


def parse_optional_bool(value: str | None) -> bool | None:
    """Разбирает optional manual yes/no result без неявного положительного default."""

    if value is None or value == "":
        return None
    return value.lower() in {"true", "y", "yes"}


def main() -> int:
    """Разбирает CLI подкоманды runner-а и локального повторного построения отчёта."""

    parser = argparse.ArgumentParser(description="Generate TalkingHeads demo acceptance artifacts")
    commands = parser.add_subparsers(dest="command", required=True)
    report_parser = commands.add_parser("generate", help="regenerate report from raw artifacts")
    report_parser.add_argument("--run-dir", required=True)
    capture_parser = commands.add_parser("capture-session", help="fetch and save a completed session")
    capture_parser.add_argument("--run-dir", required=True)
    capture_parser.add_argument("--base-url", required=True)
    capture_parser.add_argument("--case-id", type=int, required=True, choices=range(1, 6))
    capture_parser.add_argument("--session", required=True)
    capture_parser.add_argument("--scenario-pass", required=True, choices=("true", "false"))
    capture_parser.add_argument("--old-audio")
    capture_parser.add_argument("--old-caption")
    args = parser.parse_args()
    try:
        return generate(Path(args.run_dir)) if args.command == "generate" else capture(args)
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"Acceptance tooling error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
