#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

PKG="com.kareem.cortex"
BRANCH="migration/cognitive-brain-v2-step1-2"
PREF="shared_prefs/cortex_cognitive_flags.xml"
RUNTIME_PREF="shared_prefs/cortex_local_runtime.xml"
MODEL_SHA="d2387ca2dbfee2ffabce7120d3770dadca0b293052bc2f0e138fdc940d9bc7b5"
MODEL_FILE="/sdcard/Android/data/com.kareem.cortex/files/Download/Qwen3-1.7B-Q4_K_M.gguf"
SQLITE="$(command -v sqlite3 2>/dev/null || true)"
if [ -z "$SQLITE" ] && [ -x /data/data/com.termux/files/usr/bin/sqlite3 ]; then
  SQLITE=/data/data/com.termux/files/usr/bin/sqlite3
fi

fail() {
  echo
  echo "❌ $*"
  exit 1
}

ensure_adb_device() {
  adb start-server >/dev/null 2>&1 || true
  if [ "$(adb get-state 2>/dev/null || true)" = "device" ]; then
    return 0
  fi

  echo "↻ ADB disconnected; trying Wireless debugging auto-reconnect..."

  # First retry any endpoint the local adb server still remembers.
  local remembered
  remembered="$(adb devices 2>/dev/null | awk 'NR>1 && $1!="" && $2!="device" {print $1}')"
  if [ -n "$remembered" ]; then
    while IFS= read -r endpoint; do
      [ -n "$endpoint" ] || continue
      adb connect "$endpoint" >/dev/null 2>&1 || true
      if [ "$(adb get-state 2>/dev/null || true)" = "device" ]; then
        echo "✅ ADB reconnected ($endpoint)"
        return 0
      fi
    done <<< "$remembered"
  fi

  # Android Wireless debugging advertises the current connect port over mDNS.
  local services target
  if command -v timeout >/dev/null 2>&1; then
    services="$(timeout 5 adb mdns services 2>/dev/null || true)"
  else
    services="$(adb mdns services 2>/dev/null || true)"
  fi
  target="$(printf '%s\n' "$services" | awk '/_adb-tls-connect\._tcp/ {print $NF; exit}')"
  if [ -n "$target" ]; then
    adb connect "$target" >/dev/null 2>&1 || true
    sleep 1
  fi

  if [ "$(adb get-state 2>/dev/null || true)" = "device" ]; then
    echo "✅ ADB auto-reconnected${target:+ ($target)}"
    return 0
  fi

  fail "No connected ADB device. Turn ON Developer options > Wireless debugging, then rerun this same command; the script will reconnect automatically if this phone is already paired."
}

write_flags() {
  local percent="$1"
  printf '%s\n' \
    "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>" \
    '<map>' \
    '    <boolean name="cognitive_v2_authority_canary" value="true" />' \
    "    <int name=\"cognitive_v2_canary_percent\" value=\"$percent\" />" \
    '</map>' \
  | adb shell "run-as $PKG sh -c 'cat > $PREF'"
}

read_flags() {
  adb exec-out run-as "$PKG" cat "$PREF"
}

restore5() {
  adb shell am force-stop "$PKG" >/dev/null 2>&1 || true
  write_flags 5 >/dev/null 2>&1 || true
}

trap restore5 EXIT INT TERM

cd "$(git rev-parse --show-toplevel 2>/dev/null || pwd)"
[ "$(git rev-parse --abbrev-ref HEAD)" = "$BRANCH" ] || fail "Wrong branch. Expected $BRANCH"
command -v adb >/dev/null 2>&1 || fail "adb not found"
command -v gradle >/dev/null 2>&1 || fail "gradle not found"
[ -n "$SQLITE" ] || fail "sqlite3 not found"
ensure_adb_device

HEAD="$(git rev-parse HEAD)"
echo "===== CORTEX COGNITIVE MAJOR VALIDATION ====="
echo "branch=$BRANCH"
echo "head=$HEAD"

RUNTIME="$(adb exec-out run-as "$PKG" cat "$RUNTIME_PREF" 2>/dev/null || true)"
echo "$RUNTIME" | grep -q '<string name="state">ready</string>' || fail "Local runtime is not ready"
echo "$RUNTIME" | grep -q "$MODEL_SHA" || fail "Local runtime SHA is not Qwen3-1.7B"
MODEL_BYTES="$(adb shell stat -c %s "$MODEL_FILE" 2>/dev/null | tr -d '\r' || true)"
[ -n "$MODEL_BYTES" ] || fail "Qwen3-1.7B model file missing"
[ "$MODEL_BYTES" -ge 1200000000 ] || fail "Qwen3-1.7B model file incomplete: $MODEL_BYTES bytes"
echo "✅ Qwen3-1.7B ready ($MODEL_BYTES bytes)"

echo
echo "===== BUILD + ANDROID TEST COMPILE ====="
gradle \
  :app:assembleDebug \
  :app:compileDebugAndroidTestJavaWithJavac \
  --stacktrace \
  --console=plain

echo
echo "===== FAST TYPE-ONLY PARSER CONTRACT ====="
gradle :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.kareem.cortex.FastCognitiveResultParserTest \
  --stacktrace \
  --console=plain
echo "✅ tolerant type-only parser contract passed"

echo
echo "===== ISOLATE PRODUCTION CANARY DURING BENCHMARK ====="
adb shell am force-stop "$PKG"
write_flags 0
read_flags | grep -q 'cognitive_v2_canary_percent" value="0"' || fail "Could not set temporary 0% benchmark isolation"
echo "✅ production canary temporarily isolated at 0%"

adb logcat -c
BENCH_LOG="$HOME/cortex-cognitive-major-benchmark-$HEAD.log"

echo
echo "===== S26 LATENCY + CLASSIFICATION BENCHMARK ====="
if ! gradle :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.kareem.cortex.CognitiveLatencyBenchmark \
  --stacktrace \
  --console=plain; then
  adb logcat -d | grep -E 'CognitiveLatencyBenchmark|Local model returned invalid cognitive output' > "$BENCH_LOG" || true
  echo "Benchmark telemetry saved to $BENCH_LOG"
  echo
  echo "===== BENCHMARK FAILURE TELEMETRY ====="
  cat "$BENCH_LOG" || true
  fail "CognitiveLatencyBenchmark failed. Major update gate remains RED."
fi
adb logcat -d | grep 'CognitiveLatencyBenchmark' > "$BENCH_LOG" || true
cat "$BENCH_LOG"
echo "✅ benchmark assertions passed"

# The benchmark may reinstall/restart the debug app. Re-check the model/runtime before E2E.
RUNTIME="$(adb exec-out run-as "$PKG" cat "$RUNTIME_PREF" 2>/dev/null || true)"
echo "$RUNTIME" | grep -q '<string name="state">ready</string>' || fail "Local runtime lost ready state after benchmark"
echo "$RUNTIME" | grep -q "$MODEL_SHA" || fail "Local runtime SHA changed after benchmark"

echo
echo "===== CONTROLLED REAL AUTHORITY E2E ====="
TITLE="CORTEX MAJOR E2E $(date +%s)"
BODY="Please remind me tomorrow at 10:00 to call Ahmed and confirm the appointment."
START_MS="$(date +%s%3N)"

adb shell am force-stop "$PKG"
write_flags 100
read_flags | grep -q 'cognitive_v2_canary_percent" value="100"' || fail "Could not arm temporary 100% canary"

adb logcat -c
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null
sleep 5

PID="$(adb shell pidof "$PKG" | tr -d '\r')"
[ -n "$PID" ] || fail "Cortex process did not start"
read_flags | grep -q 'cognitive_v2_canary_percent" value="100"' || fail "Canary changed away from 100% after process start"
adb shell settings get secure enabled_notification_listeners | tr ':' '\n' | grep -q "$PKG" || fail "Cortex notification listener is not enabled"

echo "Posting: $TITLE"
adb shell "cmd notification post -t '$TITLE' cortex_major_e2e '$BODY'" >/dev/null
sleep 25

# Roll back BEFORE reading results. This is intentionally duplicated with the EXIT trap.
restore5
FLAGS="$(read_flags)"
echo "$FLAGS"
echo "$FLAGS" | grep -q 'cognitive_v2_canary_percent" value="5"' || fail "5% rollback verification failed"
echo "✅ canary restored to 5%"

DB="$HOME/cortex-cognitive-major-e2e-$HEAD.db"
adb exec-out run-as "$PKG" cat databases/cortex.db > "$DB"

SIGNAL_ID="$($SQLITE "$DB" "SELECT id FROM raw_signals WHERE title='$TITLE' ORDER BY id DESC LIMIT 1;")"
[ -n "$SIGNAL_ID" ] || fail "Controlled notification was not captured by Cortex"

SIGNAL_ROW="$($SQLITE -separator '|' "$DB" "SELECT cognitive_state,cognitive_version,COALESCE(final_reason,''),thread_id FROM raw_signals WHERE id=$SIGNAL_ID;")"
STATE="$(printf '%s' "$SIGNAL_ROW" | cut -d'|' -f1)"
VERSION="$(printf '%s' "$SIGNAL_ROW" | cut -d'|' -f2)"
REASON="$(printf '%s' "$SIGNAL_ROW" | cut -d'|' -f3)"
THREAD_ID="$(printf '%s' "$SIGNAL_ROW" | cut -d'|' -f4)"

AUTH_ROW="$($SQLITE -separator '|' "$DB" "SELECT id,state,latency_ms,round(confidence,3),COALESCE(json_extract(output_json,'$.queue_wait_ms'),-1),COALESCE(json_extract(output_json,'$.native_total_ms'),-1),COALESCE(json_extract(output_json,'$.total_ms'),-1),COALESCE(json_extract(output_json,'$.tokens_generated'),-1),COALESCE(json_extract(output_json,'$.wire_schema'),'') FROM model_runs WHERE role='cognitive_authority' AND route='cognitive_v2_canary' AND CAST(json_extract(output_json,'$.signal_id') AS INTEGER)=$SIGNAL_ID ORDER BY id DESC LIMIT 1;")"
[ -n "$AUTH_ROW" ] || fail "No cognitive_authority run persisted for signal $SIGNAL_ID"

AUTH_ID="$(printf '%s' "$AUTH_ROW" | cut -d'|' -f1)"
AUTH_STATE="$(printf '%s' "$AUTH_ROW" | cut -d'|' -f2)"
AUTH_LATENCY="$(printf '%s' "$AUTH_ROW" | cut -d'|' -f3)"
AUTH_CONF="$(printf '%s' "$AUTH_ROW" | cut -d'|' -f4)"
QUEUE_MS="$(printf '%s' "$AUTH_ROW" | cut -d'|' -f5)"
NATIVE_MS="$(printf '%s' "$AUTH_ROW" | cut -d'|' -f6)"
TOTAL_MS="$(printf '%s' "$AUTH_ROW" | cut -d'|' -f7)"
TOKENS="$(printf '%s' "$AUTH_ROW" | cut -d'|' -f8)"
WIRE="$(printf '%s' "$AUTH_ROW" | cut -d'|' -f9)"

echo "signal_id=$SIGNAL_ID thread_id=$THREAD_ID cognitive_state=$STATE cognitive_version=$VERSION"
echo "authority_run=$AUTH_ID state=$AUTH_STATE latency_ms=$AUTH_LATENCY confidence=$AUTH_CONF queue_wait_ms=$QUEUE_MS native_total_ms=$NATIVE_MS total_ms=$TOTAL_MS tokens=$TOKENS wire=$WIRE"
echo "final_reason=$REASON"

[ "$VERSION" = "cognitive_v2_canary_001" ] || fail "Signal did not persist V2 canary authority"
[ "$STATE" = "DERIVED" ] || fail "Expected ACTION notification to persist DERIVED, got $STATE"
[ "$AUTH_STATE" = "complete" ] || fail "Authority model run did not complete: $AUTH_STATE"
[ "$WIRE" = "fast_cognitive_001" ] || fail "Unexpected wire schema: $WIRE"
[ "$TOKENS" -ge 0 ] && [ "$TOKENS" -le 96 ] || fail "Generated token count outside 0..96: $TOKENS"
[ "$TOTAL_MS" -ge 0 ] && [ "$TOTAL_MS" -le 12000 ] || fail "Real authority total exceeded 12s: $TOTAL_MS ms"
if printf '%s' "$REASON" | grep -qi 'fallback'; then
  fail "Legacy fallback is recorded in final_reason"
fi

LEGACY_EVALS="$($SQLITE "$DB" "SELECT COUNT(*) FROM relevance_evaluations WHERE signal_id=$SIGNAL_ID AND COALESCE(model_run_id,0)>0;")"
[ "$LEGACY_EVALS" = "0" ] || fail "Legacy relevance model also evaluated the controlled signal ($LEGACY_EVALS row(s))"

echo
echo "===== MAJOR UPDATE DEVICE GATE ====="
echo "✅ Build + androidTest compile"
echo "✅ Tolerant type-only parser contract"
echo "✅ Qwen3-1.7B runtime/model identity"
echo "✅ 10-case benchmark with Shadow OFF"
echo "✅ 10-case benchmark with Shadow ON"
echo "✅ Canary-eligible confidence gate"
echo "✅ Authority queue/latency/token assertions"
echo "✅ Real notification -> V2 authority -> Qwen3-1.7B"
echo "✅ V2 persistence without Legacy fallback"
echo "✅ Canary restored to 5%"
echo "DB snapshot: $DB"
echo "Benchmark log: $BENCH_LOG"
echo
echo "CORTEX_COGNITIVE_MAJOR_UPDATE_DEVICE_PASS"

trap - EXIT INT TERM
