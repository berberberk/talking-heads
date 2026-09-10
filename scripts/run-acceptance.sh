#!/usr/bin/env bash
# Оркестрирует ручной acceptance-проверки без новой product telemetry architecture.
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

usage() {
  printf '%s\n' "Usage: ./scripts/run-acceptance.sh [--skip-build] [--continue-on-test-failure]" \
    "       ./scripts/run-acceptance.sh --report-only <run-dir>" \
    "" "One-command interactive acceptance run." \
    "  --skip-build                 Do not run build and unit-test gate." \
    "  --continue-on-test-failure   Continue E2E after a failed gate." \
    "  --report-only <run-dir>      Regenerate report from raw artifacts."
}

SKIP_BUILD=false; CONTINUE_ON_TEST_FAILURE=false; REPORT_ONLY=""
while (($#)); do
  case "$1" in
    --skip-build) SKIP_BUILD=true ;;
    --continue-on-test-failure) CONTINUE_ON_TEST_FAILURE=true ;;
    --report-only) REPORT_ONLY="${2:-}"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "Unknown option: $1" >&2; usage >&2; exit 1 ;;
  esac
  shift
done
if [[ -n "$REPORT_ONLY" ]]; then
  [[ -d "$REPORT_ONLY" ]] || { echo "Run directory does not exist: $REPORT_ONLY" >&2; exit 1; }
  set +e
  python3 scripts/acceptance_report.py generate --run-dir "$REPORT_ONLY"
  exit_code=$?
  set -e
  echo "Report: $REPORT_ONLY/report.md"
  exit "$exit_code"
fi

RUN_ID="$(date +%Y%m%d-%H%M%S)"; RUN_DIR="$ROOT_DIR/artifacts/acceptance/$RUN_ID"
mkdir -p "$RUN_DIR/raw"; touch "$RUN_DIR/run.log"
exec > >(tee -a "$RUN_DIR/run.log") 2>&1
echo "acceptanceRunId: $RUN_ID"
BACKEND_PID=""; FRONTEND_PID=""
cleanup() {
  local status=$?
  [[ -n "$BACKEND_PID" ]] && kill "$BACKEND_PID" 2>/dev/null || true
  [[ -n "$FRONTEND_PID" ]] && kill "$FRONTEND_PID" 2>/dev/null || true
  [[ -n "$BACKEND_PID" ]] && wait "$BACKEND_PID" 2>/dev/null || true
  [[ -n "$FRONTEND_PID" ]] && wait "$FRONTEND_PID" 2>/dev/null || true
  exit "$status"
}
trap cleanup EXIT INT TERM
record_preflight() {
  python3 - "$RUN_DIR/preflight.json" "$1" "$2" <<'PY'
import json, sys
from pathlib import Path
p = Path(sys.argv[1]); d = json.loads(p.read_text()) if p.exists() else {}; d[sys.argv[2]] = sys.argv[3]
p.write_text(json.dumps(d, ensure_ascii=False, indent=2) + "\n")
PY
}
fail() { echo "ACCEPTANCE PREFLIGHT FAILED: $*" >&2; exit 1; }

command -v java >/dev/null || fail "java is required"
JAVA_MAJOR="$(java -version 2>&1 | awk -F '"' '/version/ { split($2, parts, "."); print parts[1]; exit }')"
[[ "$JAVA_MAJOR" =~ ^[0-9]+$ && "$JAVA_MAJOR" -ge 17 ]] || fail "Java 17+ is required"
record_preflight java "PASS (Java $JAVA_MAJOR)"
[[ -x ./gradlew ]] || fail "./gradlew must be executable"; record_preflight gradlew PASS
for command in node npm python3 uv; do command -v "$command" >/dev/null || fail "$command is required"; record_preflight "$command" PASS; done
[[ -f .env ]] || fail "Create .env from .env.example and configure Simli credentials"
for variable in GEMINI_API_KEY ELEVENLABS_API_KEY SIMLI_API_KEY SIMLI_FACE_ID ELEVENLABS_VOICE_ID; do
  if [[ "$(python3 scripts/dotenv_exec.py .env --status "$variable")" == configured ]]; then echo "$variable: configured"; record_preflight "$variable" configured
  else echo "$variable: missing"; record_preflight "$variable" missing; fail "$variable is required for Simli acceptance"; fi
done
python3 scripts/dotenv_exec.py .env --equals AVATAR_PROVIDER simli || fail "AVATAR_PROVIDER=simli is required"; record_preflight avatar_provider simli
port_free() {
  python3 - "$1" <<'PY'
import socket, sys
s=socket.socket()
try: s.bind(("127.0.0.1", int(sys.argv[1])))
except OSError: raise SystemExit(1)
finally: s.close()
PY
}
for port in 8080 8000; do port_free "$port" || fail "localhost:$port is in use; runner will not stop user processes"; record_preflight "port_$port" free; done

run_gate() {
  local label="$1"; shift; echo "==> $label: $*"
  if "$@"; then record_preflight "$label" PASS; return 0; fi
  record_preflight "$label" FAIL; echo "FAILED COMMAND: $*" >&2; return 1
}
if ! $SKIP_BUILD; then
  gate_failed=false
  run_gate gradle_test ./gradlew test || gate_failed=true
  run_gate gradle_build ./gradlew build || gate_failed=true
  run_gate frontend_test npm run test:frontend || gate_failed=true
  run_gate frontend_build npm run build:frontend || gate_failed=true
  run_gate acceptance_report_test python3 -m unittest scripts/test_acceptance_report.py scripts/test_dotenv_exec.py || gate_failed=true
  run_gate javascript_syntax node --check static/app.js || gate_failed=true
  run_gate simli_stream_syntax node --check static/simli-stream-client.js || gate_failed=true
  run_gate git_diff_check git diff --check || gate_failed=true
  if $gate_failed && ! $CONTINUE_ON_TEST_FAILURE; then fail "Build/test gate failed; exact failed command is above"; fi
fi

echo "Starting runner-owned backend and frontend with isolated file session storage."
python3 scripts/dotenv_exec.py .env --override STORAGE_BACKEND=file --override DATA_DIR="$RUN_DIR/data" -- ./gradlew run >"$RUN_DIR/backend.log" 2>&1 & BACKEND_PID=$!
python3 scripts/dotenv_exec.py .env -- uv run --with-requirements requirements.txt uvicorn app:app --port 8000 >"$RUN_DIR/frontend.log" 2>&1 & FRONTEND_PID=$!
wait_http() {
  local url="$1" label="$2"
  for _ in $(seq 1 30); do
    if python3 - "$url" <<'PY'
import sys, urllib.request
try:
    with urllib.request.urlopen(sys.argv[1], timeout=1) as r: raise SystemExit(0 if r.status < 500 else 1)
except Exception: raise SystemExit(1)
PY
    then echo "$label: ready"; record_preflight "$label" PASS; return 0; fi
    sleep 1
  done
  echo "$label did not become healthy. Last backend log lines:" >&2; tail -n 40 "$RUN_DIR/backend.log" >&2 || true; return 1
}
wait_http http://localhost:8080/health backend_health || exit 1
wait_http http://localhost:8000/ frontend_health || exit 1
URL=http://localhost:8000/
printf '\n========================================\nACCEPTANCE RUN READY\n========================================\nTraining URL: %s\n' "$URL"
if command -v open >/dev/null; then open "$URL" || true; elif command -v xdg-open >/dev/null; then xdg-open "$URL" || true; fi

for case_id in 1 2 3 4 5; do
  printf '\n----------------------------------------\nCASE %s/5\n----------------------------------------\n' "$case_id"
  sed -n "/## CASE $case_id /,/## CASE /p" scripts/acceptance-cases.md | sed '$d' || true
  printf '%s\n' "1. Run the dialogue through PTT in the browser." "2. Finish training and wait for report page."
  read -r -p "Press ENTER when ready to paste session ID or report URL: " _
  while true; do
    read -r -p "Paste session ID or report URL: " session_ref
    [[ -n "$session_ref" ]] || { echo "A session reference is required."; continue; }
    read -r -p "Did the dialogue complete correctly? [Y/n] " scenario_answer; scenario_answer="${scenario_answer:-Y}"
    scenario_pass=false; [[ "$scenario_answer" =~ ^[Yy] ]] && scenario_pass=true
    old_audio=""; old_caption=""
    if [[ "$case_id" == 4 ]]; then
      read -r -p "Did any OLD avatar phrase resume after interruption? [y/N] " answer; [[ "$answer" =~ ^[Yy] ]] && old_audio=true || old_audio=false
      read -r -p "Did an OLD caption reappear after interruption? [y/N] " answer; [[ "$answer" =~ ^[Yy] ]] && old_caption=true || old_caption=false
    fi
    if python3 scripts/acceptance_report.py capture-session --run-dir "$RUN_DIR" --base-url http://localhost:8080 --case-id "$case_id" --session "$session_ref" --scenario-pass "$scenario_pass" --old-audio "$old_audio" --old-caption "$old_caption"; then break; fi
    echo "Could not capture session; check the URL and try again."
  done
done

read -r -p "Lip-sync samples available from a 60 fps screen recording? [y/N] " lip_available
if [[ "$lip_available" =~ ^[Yy] ]]; then
  echo "Enter one observed offset in ms per line; empty line completes input."; samples=()
  while true; do read -r -p "ms: " sample; [[ -z "$sample" ]] && break; [[ "$sample" =~ ^[0-9]+([.][0-9]+)?$ ]] || { echo "Use a non-negative number."; continue; }; samples+=("$sample"); done
  python3 - "$RUN_DIR/lip-sync.json" "${samples[@]}" <<'PY'
import json, sys
from pathlib import Path
Path(sys.argv[1]).write_text(json.dumps({"valuesMs": [float(v) for v in sys.argv[2:]]}, indent=2) + "\n")
PY
else
  python3 - "$RUN_DIR/lip-sync.json" <<'PY'
import json, sys
from pathlib import Path
Path(sys.argv[1]).write_text(json.dumps({"valuesMs": []}, indent=2) + "\n")
PY
fi
set +e; python3 scripts/acceptance_report.py generate --run-dir "$RUN_DIR"; RESULT_CODE=$?; set -e
printf '\n========================================\nACCEPTANCE RESULTS\n========================================\n'
python3 - "$RUN_DIR/report.json" <<'PY'
import json, sys
r=json.load(open(sys.argv[1]))
def fmt_ms(value): return "NOT_MEASURED" if value is None else f"{value}ms"
for title,key in (("First audio","firstAudio"),("Interruption","interrupt")):
 i=r[key]; print(f"{title}: median {fmt_ms(i['medianMs'])}; p95 {fmt_ms(i['p95Ms'])}; max {fmt_ms(i['maxMs'])} — {i['status']}")
c=r['cancellation']; print(f"Cancellation: {('NOT_MEASURED' if c['staleEventsAccepted'] is None else c['staleEventsAccepted'])} stale events accepted — {c['status']}")
s=r['scenarios']; print(f"Scenario: {s['passed']} / {s['total']} — {s['status']}")
x=r['reports']; print(f"Reports: {x['ready']} / {x['finished']} READY — {x['status']}")
print(f"Lip-sync: {r['lipSync']['status']}"); print(f"Overall: {r['overallStatus']}")
PY
echo "Report: $RUN_DIR/report.md"
exit "$RESULT_CODE"
