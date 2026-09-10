"""Unit-тесты честной агрегации acceptance artifacts."""

from __future__ import annotations

import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import acceptance_report as report


class AcceptanceReportTest(unittest.TestCase):
    """Проверяет пороги и отсутствующие данные без обращения к реальным provider API."""

    def setUp(self) -> None:
        self.temp_dir = tempfile.TemporaryDirectory()
        self.run_dir = Path(self.temp_dir.name) / "20260907-120000"
        (self.run_dir / "raw").mkdir(parents=True)

    def tearDown(self) -> None:
        self.temp_dir.cleanup()

    def write_sessions(self, rows: list[dict]) -> None:
        """Создаёт минимальные raw result/metric artifacts для набора case-ов."""

        sessions = []
        for row in rows:
            case_id = row["caseId"]
            result_name, metrics_name = f"session-{case_id}-result.json", f"session-{case_id}-metrics.json"
            result = {"status": row.get("status", "FINISHED"), "reportStatus": row.get("reportStatus", "READY"),
                      "report": {"overallScore": 4}, "messages": [{"role": "USER"}]}
            (self.run_dir / "raw" / result_name).write_text(json.dumps(result), encoding="utf-8")
            (self.run_dir / "raw" / metrics_name).write_text(json.dumps({"metrics": row.get("metrics", [])}), encoding="utf-8")
            sessions.append({"caseId": case_id, "sessionId": f"00000000-0000-4000-8000-{case_id:012d}",
                             "scenarioPass": row.get("scenarioPass", True), "oldAudioResumed": row.get("oldAudioResumed"),
                             "oldCaptionReappeared": row.get("oldCaptionReappeared"), "resultFile": result_name, "metricsFile": metrics_name})
        (self.run_dir / "sessions.json").write_text(json.dumps({"sessions": sessions}), encoding="utf-8")

    @staticmethod
    def metrics(case_id: int, voice: int = 1000, interrupt: int = 100, accepted: int = 0) -> list[dict]:
        """Возвращает полный измеренный browser turn с correlation-id."""

        turn = f"00000000-0000-4000-8000-{case_id:012d}"
        return [
            {"name": "browser_voice_end_to_first_audio_ms", "valueMs": voice, "turnId": turn},
            {"name": "browser_interruption_to_silent_ms", "valueMs": interrupt, "turnId": turn},
            {"name": "browser_stale_events_accepted", "valueMs": accepted, "turnId": turn},
        ]

    def complete_rows(self) -> list[dict]:
        """Формирует пять успешных manual cases, включая interruption case 4."""

        rows = []
        for index in range(1, 6):
            metrics = self.metrics(index)
            if index != 4:
                metrics = [metric for metric in metrics if "interruption" not in metric["name"]]
            rows.append({"caseId": index, "metrics": metrics, "oldAudioResumed": False if index == 4 else None,
                         "oldCaptionReappeared": False if index == 4 else None})
        return rows

    def test_nearest_rank_median_and_p95(self) -> None:
        self.assertEqual(report.nearest_rank([1, 2, 3, 4], 0.5), 2)
        self.assertEqual(report.nearest_rank([1, 2, 3, 4], 0.95), 4)

    def test_missing_values_are_not_measured(self) -> None:
        self.assertEqual(report.metric_summary([], 3000)["status"], "NOT_MEASURED")

    def test_first_audio_threshold(self) -> None:
        self.assertEqual(report.metric_summary([3000], 3000)["status"], "PASS")
        self.assertEqual(report.metric_summary([3001], 3000)["status"], "FAIL")

    def test_interrupt_threshold(self) -> None:
        self.assertEqual(report.metric_summary([300], 300)["status"], "PASS")
        self.assertEqual(report.metric_summary([301], 300)["status"], "FAIL")

    def test_interrupt_without_silent_event_fails(self) -> None:
        rows = self.complete_rows()
        rows[3]["metrics"] = [metric for metric in rows[3]["metrics"] if "interruption" not in metric["name"]]
        self.write_sessions(rows)
        self.assertEqual(report.aggregate(self.run_dir)["interrupt"].get("reason"), "NO_SILENT_EVENT")

    def test_four_of_five_scenarios_pass(self) -> None:
        rows = self.complete_rows()
        rows[4]["scenarioPass"] = False
        self.write_sessions(rows)
        self.assertEqual(report.aggregate(self.run_dir)["scenarios"]["status"], "PASS")

    def test_three_of_five_scenarios_fail(self) -> None:
        rows = self.complete_rows()
        rows[2]["scenarioPass"] = rows[3]["scenarioPass"] = False
        self.write_sessions(rows)
        self.assertEqual(report.aggregate(self.run_dir)["scenarios"]["status"], "FAIL")

    def test_reports_all_ready_and_one_failed(self) -> None:
        self.write_sessions(self.complete_rows())
        self.assertEqual(report.aggregate(self.run_dir)["reports"]["status"], "PASS")
        rows = self.complete_rows()
        rows[0]["reportStatus"] = "FAILED"
        self.write_sessions(rows)
        self.assertEqual(report.aggregate(self.run_dir)["reports"]["status"], "FAIL")

    def test_lip_sync_missing_is_partial_when_automatic_checks_pass(self) -> None:
        self.write_sessions(self.complete_rows())
        result = report.aggregate(self.run_dir)
        self.assertEqual(result["lipSync"]["status"], "MANUAL_VALIDATION_REQUIRED")
        self.assertEqual(result["overallStatus"], "PARTIAL")

    def test_lip_sync_pass_and_failure(self) -> None:
        self.write_sessions(self.complete_rows())
        (self.run_dir / "lip-sync.json").write_text('{"valuesMs": [83, 117, 200]}', encoding="utf-8")
        self.assertEqual(report.aggregate(self.run_dir)["overallStatus"], "PASS")
        (self.run_dir / "lip-sync.json").write_text('{"valuesMs": [201]}', encoding="utf-8")
        self.assertEqual(report.aggregate(self.run_dir)["overallStatus"], "FAIL")

    def test_stale_accepted_fails(self) -> None:
        rows = self.complete_rows()
        rows[3]["metrics"] = self.metrics(4, accepted=1)
        self.write_sessions(rows)
        self.assertEqual(report.aggregate(self.run_dir)["cancellation"]["status"], "FAIL")

    def test_generate_creates_all_presentation_artifacts(self) -> None:
        self.write_sessions(self.complete_rows())
        self.assertEqual(report.generate(self.run_dir), 2)
        for filename in ("report.md", "report.json", "metrics.csv"):
            self.assertTrue((self.run_dir / filename).is_file())

    def test_report_only_runner_regenerates_artifacts(self) -> None:
        self.write_sessions(self.complete_rows())
        completed = subprocess.run(
            [str(report.ROOT / "scripts" / "run-acceptance.sh"), "--report-only", str(self.run_dir)],
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(completed.returncode, 2)
        self.assertIn("Report:", completed.stdout)
        self.assertTrue((self.run_dir / "report.md").exists())


if __name__ == "__main__":
    unittest.main()
