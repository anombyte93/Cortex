#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"; cd "$ROOT"
echo "Cortex instrumented review V1 + V2 + V3 + V5 Same-Packet Differential"
echo "repo: $ROOT"; echo "branch: $(git branch --show-current 2>/dev/null || true)"; echo "head: $(git rev-parse --short HEAD 2>/dev/null || true)"
command -v gradle >/dev/null 2>&1 || { echo "ERROR: gradle is not installed in Termux"; exit 2; }
command -v rish >/dev/null 2>&1 || { echo "ERROR: rish/Shizuku is required"; exit 3; }
[[ -d "$HOME/storage/downloads" ]] || { echo "ERROR: Run termux-setup-storage once"; exit 3; }

echo; echo "[1/7] Building app + instrumentation APK..."
gradle :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace
APP_APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"; TEST_APK="$ROOT/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
[[ -f "$APP_APK" ]] || { echo "Missing $APP_APK"; exit 4; }; [[ -f "$TEST_APK" ]] || { echo "Missing $TEST_APK"; exit 5; }
STAGE_TERMUX="$HOME/storage/downloads/CortexSelfUserTestStage"; STAGE_ANDROID="/sdcard/Download/CortexSelfUserTestStage"; SHELL_STAGE="/data/local/tmp/cortex-self-user-test"
mkdir -p "$STAGE_TERMUX"; cp -f "$APP_APK" "$STAGE_TERMUX/cortex-app-debug.apk"; cp -f "$TEST_APK" "$STAGE_TERMUX/cortex-test-debug.apk"

echo; echo "[2/7] Installing update-in-place app and test APK..."
rish -c "rm -rf '$SHELL_STAGE'; mkdir -p '$SHELL_STAGE'; cp '$STAGE_ANDROID/cortex-app-debug.apk' '$SHELL_STAGE/cortex-app-debug.apk'; cp '$STAGE_ANDROID/cortex-test-debug.apk' '$SHELL_STAGE/cortex-test-debug.apk'; chmod 644 '$SHELL_STAGE/'*.apk; ls -lh '$SHELL_STAGE/'*.apk"
set +e
APP_INSTALL="$(rish -c "pm install -r -t -d '$SHELL_STAGE/cortex-app-debug.apk'" 2>&1 | tr -d '\r')"; APP_STATUS=$?
TEST_INSTALL="$(rish -c "pm install -r -t -d '$SHELL_STAGE/cortex-test-debug.apk'" 2>&1 | tr -d '\r')"; TEST_STATUS=$?
set -e
echo "app install output:"; echo "${APP_INSTALL:-<no output>}"; [[ $APP_STATUS -eq 0 && "$APP_INSTALL" == *Success* ]] || { echo "ERROR: app APK install failed"; exit 8; }
echo "test install output:"; echo "${TEST_INSTALL:-<no output>}"; [[ $TEST_STATUS -eq 0 && "$TEST_INSTALL" == *Success* ]] || { echo "ERROR: instrumentation APK install failed"; exit 9; }
INSTRUMENTATION="$(rish -c "pm list instrumentation" 2>&1 | tr -d '\r')"; echo "$INSTRUMENTATION" | grep -F "com.kareem.cortex.test/androidx.test.runner.AndroidJUnitRunner" >/dev/null || { echo "ERROR: AndroidJUnitRunner not registered"; echo "$INSTRUMENTATION"; exit 10; }
echo "instrumentation: registered"; mkdir -p "$ROOT/build"

run_test(){
  local label="$1" cls="$2" log="$3"
  echo; echo "$label"
  set +e
  rish -c "am instrument -w -r -e class $cls com.kareem.cortex.test/androidx.test.runner.AndroidJUnitRunner" | tee "$log"
  local shell_status=${PIPESTATUS[0]}
  set -e
  # `am instrument` can exit 0 even when JUnit reports a failed assertion. Treat the
  # instrumentation text as authoritative so cognitive failures cannot be painted green.
  if [[ $shell_status -ne 0 ]]; then return "$shell_status"; fi
  if grep -Eq 'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_STATUS: Error=|Process crashed|shortMsg=Process crashed|Tests run: .*Failures: [1-9]' "$log"; then
    return 97
  fi
  if ! grep -Eq 'OK \([0-9]+ test|OK \([0-9]+ tests' "$log"; then
    echo "ERROR: instrumentation did not report an explicit JUnit OK result: $cls" >&2
    return 98
  fi
  return 0
}

V1=0; V2=0; V3=0; V5=0
run_test "[3/7] Running V1 runtime/navigation/surface health..." "com.kareem.cortex.FullApplicationSelfUserTest" "$ROOT/build/instrumented-self-user-test-v1-console.txt" || V1=$?
run_test "[4/7] Running V2 Cognitive/Product Adjudication..." "com.kareem.cortex.CognitiveProductAdjudicationTest" "$ROOT/build/instrumented-self-user-test-v2-console.txt" || V2=$?
run_test "[5/7] Running V3 Teacher/Student Cognitive Differential..." "com.kareem.cortex.TeacherStudentCognitiveDifferentialTest" "$ROOT/build/instrumented-self-user-test-v3-console.txt" || V3=$?

# Remember the previous V5 artifact. The run must create a different directory; otherwise
# export would silently reuse stale evidence from an older binary.
PRE_V5="$(rish -c "ls -td /sdcard/Android/data/com.kareem.cortex/files/self-user-test/CognitivePacketDifferentialV5_* 2>/dev/null | head -n 1" | tr -d '\r')"
run_test "[6/7] Running V5 same-packet cognitive differential..." "com.kareem.cortex.CognitivePacketDifferentialV5Test" "$ROOT/build/instrumented-self-user-test-v5-console.txt" || V5=$?

echo; echo "[7/7] Exporting combined evidence package..."
LATEST_V1="$(rish -c "ls -td /sdcard/Android/data/com.kareem.cortex/files/self-user-test/FullCortexSelfUserTest_* 2>/dev/null | head -n 1" | tr -d '\r')"
LATEST_V2="$(rish -c "ls -td /sdcard/Android/data/com.kareem.cortex/files/self-user-test/CognitiveProductAdjudication_* 2>/dev/null | head -n 1" | tr -d '\r')"
LATEST_V3="$(rish -c "ls -td /sdcard/Android/data/com.kareem.cortex/files/self-user-test/TeacherStudentCognitiveDifferential_* 2>/dev/null | head -n 1" | tr -d '\r')"
LATEST_V5="$(rish -c "ls -td /sdcard/Android/data/com.kareem.cortex/files/self-user-test/CognitivePacketDifferentialV5_* 2>/dev/null | head -n 1" | tr -d '\r')"
[[ -n "$LATEST_V1" && -n "$LATEST_V2" && -n "$LATEST_V3" && -n "$LATEST_V5" ]] || { echo "ERROR: report directory missing. V1=$LATEST_V1 V2=$LATEST_V2 V3=$LATEST_V3 V5=$LATEST_V5"; exit 6; }
if [[ -n "$PRE_V5" && "$LATEST_V5" == "$PRE_V5" ]]; then
  echo "ERROR: V5 produced no fresh artifact; refusing to package stale cognitive evidence: $LATEST_V5"
  exit 11
fi
if [[ ! -n "$PRE_V5" && -z "$LATEST_V5" ]]; then
  echo "ERROR: V5 produced no artifact"
  exit 11
fi

STAMP="$(date +%Y%m%d_%H%M%S)"; BUNDLE="FullCortexInstrumentedReview_${STAMP}"; ANDROID_BUNDLE="/sdcard/Download/$BUNDLE"
rish -c "rm -rf '$ANDROID_BUNDLE'; mkdir -p '$ANDROID_BUNDLE'; cp -R '$LATEST_V1' '$ANDROID_BUNDLE/'; cp -R '$LATEST_V2' '$ANDROID_BUNDLE/'; cp -R '$LATEST_V3' '$ANDROID_BUNDLE/'; cp -R '$LATEST_V5' '$ANDROID_BUNDLE/'; cp '$ROOT/build/instrumented-self-user-test-v5-console.txt' '$ANDROID_BUNDLE/V5_console.txt' 2>/dev/null || true; rm -rf '$SHELL_STAGE'"
cat > "$HOME/storage/downloads/$BUNDLE/README.txt" <<EOF
Cortex Instrumented Review Bundle
V1 = Android/runtime/navigation/surface health
V2 = Cognitive/Product Adjudication
V3 = Full-fidelity Teacher/Student Cognitive Differential from database world
V5 = Same-packet ChatGPT-teacher vs Cortex-student cognitive contract
V3/V5 redaction = NONE
V5 cognitive_packet.json = exact shared input
V5 teacher_prompt.txt = ChatGPT adjudication prompt
V5 student_decision.json = fresh Cortex reasoner output under the same contract
V1 exit: $V1
V2 exit: $V2
V3 exit: $V3
V5 exit: $V5
V5 previous artifact: ${PRE_V5:-<none>}
V5 current artifact: $LATEST_V5
Git head: $(git rev-parse HEAD 2>/dev/null || true)
EOF
command -v zip >/dev/null 2>&1 || pkg install -y zip >/dev/null
cd "$HOME/storage/downloads"; rm -f "$BUNDLE.zip"; zip -qr "$BUNDLE.zip" "$BUNDLE"; rm -rf "$STAGE_TERMUX"
echo; echo "DONE"; echo "Bundle folder: Downloads/$BUNDLE"; echo "ZIP: Downloads/$BUNDLE.zip"; echo "V1=$V1 V2=$V2 V3=$V3 V5=$V5"; echo "Fresh V5: $LATEST_V5"; echo; echo "Send $BUNDLE.zip back to ChatGPT. V5 must be adjudicated from cognitive_packet.json before student_decision.json is revealed/evaluated."
[[ $V1 -eq 0 ]] || exit "$V1"; [[ $V2 -eq 0 ]] || exit "$V2"; [[ $V3 -eq 0 ]] || exit "$V3"; exit "$V5"
