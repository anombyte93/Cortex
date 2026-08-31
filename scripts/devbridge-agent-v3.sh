#!/data/data/com.termux/files/usr/bin/bash
# Cortex Termux Dev Bridge - agent v3 (security-redesigned).
#
# Trust model in one line: the job channel is DATA ONLY. A job may name an immutable
# commit that is already reachable from a trusted named ref, and may ask for an
# enumerated Gradle task. It can never name a runtime, a script, a signer, or a ref.
#
# Boundaries enforced here (see docs/TERMUX_DEV_BRIDGE_V3_THREAT_MODEL.md):
#   D1  the signing keystore is never copied into, referenced from, or reachable by
#       repository build logic. Gradle builds unsigned; signing is a separate stage.
#   D2  builds target a full 40-hex commit that must already be reachable from the
#       trusted ref set. No job-controlled ref is ever fetched. Option-like values are
#       rejected, and every git argument list terminates option parsing with `--`.
#   D3  a repository-supplied gradlew is never executed; it is removed from the
#       untrusted worktree. Gradle tasks come from an enumerated allowlist.
#   D4  this agent is delivered as a pinned, integrity-verified release by the
#       bootstrap. It never fetches or executes "latest" anything.
set -u

AGENT_VERSION='3.1.0'
PROTOCOL='CORTEX_DEVBRIDGE_V2'

REPO="${CORTEX_DEVBRIDGE_REPO:-KAN1409/Cortex}"
JOB_BRANCH="${CORTEX_DEVBRIDGE_JOB_BRANCH:-device/termux-dev-bridge-jobs}"
RESULT_BRANCH="${CORTEX_DEVBRIDGE_RESULT_BRANCH:-device/termux-dev-bridge-results}"
ROOT="${CORTEX_DEVBRIDGE_HOME:-$HOME/.cortex-devbridge}"
LOCAL_REPO="${CORTEX_DEVBRIDGE_LOCAL_REPO:-$HOME/Cortex}"

# The signer lives outside every worktree and outside every repository checkout that a
# job can influence. It is read only by the separate signing stage, via apksigner.
SIGNER_SOURCE="${CORTEX_DEVBRIDGE_SIGNER_SOURCE:-$HOME/.cortex-devbridge-signer/cortex-debug.keystore}"
SIGNER_STOREPASS="${CORTEX_DEVBRIDGE_SIGNER_STOREPASS:-android}"
SIGNER_KEYALIAS="${CORTEX_DEVBRIDGE_SIGNER_KEYALIAS:-androiddebugkey}"
SIGNER_KEYPASS="${CORTEX_DEVBRIDGE_SIGNER_KEYPASS:-$SIGNER_STOREPASS}"

if [ -n "${CORTEX_DEVBRIDGE_REMOTE:-}" ]; then
  REMOTE="$CORTEX_DEVBRIDGE_REMOTE"
elif [ -d "$LOCAL_REPO/.git" ] && git -C "$LOCAL_REPO" remote get-url origin >/dev/null 2>&1; then
  REMOTE="$(git -C "$LOCAL_REPO" remote get-url origin)"
else
  REMOTE="https://github.com/${REPO}.git"
fi

MIRROR="$ROOT/mirror"
RESULTS="$ROOT/results"
STATE="$ROOT/processed.txt"
LOGS="$ROOT/logs"
WORK="$ROOT/work"
STAGE="$ROOT/stage"

# --- Enumerated allowlists. Shape checks are not allowlists. -------------------------

PKG_ALLOW=(
  'com.kareem.cortex'
  'com.kareem.cortex.rebuild'
  'com.kareem.secondbrain'
)

CAP_ALLOW=(
  PING GIT_STATUS BUILD BUILD_INSTALL_SMOKE INSTALL_UPDATE
  LAUNCH STOP LOGCAT DUMPSYS_PACKAGE
)

# The only Gradle tasks this runtime will ever execute. Adding one is a reviewed change
# to this file, which is delivered as a pinned release - not a job-time decision.
TASK_ALLOW=(
  ':app:assembleDebug'
  ':app:compileDebugAndroidTestJavaWithJavac'
  ':app:assembleDebugAndroidTest'
)

# Commits must be reachable from one of these. The set is fixed here, never job-supplied.
TRUSTED_REFS_DEFAULT='main migration/cognitive-brain-v2-step1-2'
read -r -a TRUSTED_REFS <<< "${CORTEX_DEVBRIDGE_TRUSTED_REFS:-$TRUSTED_REFS_DEFAULT}"

SHA_RE='^[0-9a-f]{40}$'
JOB_RE='^job_[A-Za-z0-9_-]{3,120}$'

mkdir -p "$ROOT" "$LOGS" "$WORK" "$STAGE"
touch "$STATE"

in_list(){
  local needle="${1:-}"; shift
  local item
  for item in "$@"; do [ "$item" = "$needle" ] && return 0; done
  return 1
}

# Any value that could be read as an option by any downstream command is refused
# outright, in addition to the positive-shape checks. Defence in depth for D2.
not_option_like(){
  case "${1:-}" in
    ''|-*) return 1 ;;
    *) return 0 ;;
  esac
}

safe_pkg(){ not_option_like "${1:-}" && in_list "${1:-}" "${PKG_ALLOW[@]}"; }
safe_cap(){ not_option_like "${1:-}" && in_list "${1:-}" "${CAP_ALLOW[@]}"; }
safe_task(){ not_option_like "${1:-}" && in_list "${1:-}" "${TASK_ALLOW[@]}"; }
safe_commit(){ not_option_like "${1:-}" && [[ "${1:-}" =~ $SHA_RE ]]; }

# --- Mirror + provenance -------------------------------------------------------------

# Only trusted named refs are ever fetched. A job-supplied value is never passed to
# `git fetch`, so an option-like or attacker-named ref has no path into git at all.
sync_mirror(){
  local ref
  if [ ! -d "$MIRROR/.git" ]; then
    rm -rf "$MIRROR"
    git clone --filter=blob:none --no-tags "$REMOTE" "$MIRROR" >/dev/null 2>&1 || return 1
  fi
  git -C "$MIRROR" remote set-url origin "$REMOTE" >/dev/null 2>&1 || return 1
  for ref in "${TRUSTED_REFS[@]}" "$JOB_BRANCH"; do
    git -C "$MIRROR" fetch --prune --no-tags origin \
      "+refs/heads/$ref:refs/remotes/origin/$ref" >/dev/null 2>&1 || true
  done
  return 0
}

# A commit is trusted only if it is an ancestor of (or equal to) a trusted named ref.
verify_provenance(){
  local commit="$1" ref
  git -C "$MIRROR" cat-file -e "${commit}^{commit}" 2>/dev/null || { echo DENY_UNKNOWN_COMMIT; return 1; }
  for ref in "${TRUSTED_REFS[@]}"; do
    if git -C "$MIRROR" rev-parse --verify --quiet "refs/remotes/origin/$ref" >/dev/null 2>&1; then
      if git -C "$MIRROR" merge-base --is-ancestor "$commit" "refs/remotes/origin/$ref" 2>/dev/null; then
        echo "provenance_ref=$ref"
        return 0
      fi
    fi
  done
  echo DENY_UNTRUSTED_PROVENANCE
  return 1
}

# --- Untrusted worktree --------------------------------------------------------------

# The worktree contains attacker-influenceable content. Two things are true of it:
# it never receives signing material (D1), and nothing executable inside it is ever
# run by this agent (D3).
neutralize_worktree(){
  local dir="$1" removed=0
  local p
  for p in gradlew gradlew.bat gradle/wrapper/gradle-wrapper.jar gradle/wrapper/gradle-wrapper.properties; do
    if [ -e "$dir/$p" ]; then rm -rf -- "$dir/${p:?}"; removed=1; fi
  done
  [ "$removed" -eq 1 ] && echo "gradlew_ignored=true" || echo "gradlew_ignored=false"
  # A repository-supplied keystore is attacker material, not our signer. Remove it so
  # no build logic can read bytes and claim they are the real signer.
  if [ -e "$dir/app/cortex-debug.keystore" ]; then
    rm -f -- "$dir/app/cortex-debug.keystore"
    echo "repo_supplied_keystore_removed=true"
  fi
  echo "signer_overlay=absent"
}

prepare_worktree(){
  local job="$1" commit="$2" dir="$WORK/$job"
  sync_mirror || return 1
  verify_provenance "$commit" >&2 || return 1
  if [ -e "$dir" ]; then
    git -C "$MIRROR" worktree remove --force -- "$dir" >/dev/null 2>&1 || true
    rm -rf -- "$dir"
  fi
  git -C "$MIRROR" worktree add --detach -- "$dir" "$commit" >/dev/null 2>&1 || return 1
  neutralize_worktree "$dir" >&2
  printf '%s' "$dir"
}

cleanup_worktree(){
  local dir="${1:-}"
  [ -n "$dir" ] || return 0
  git -C "$MIRROR" worktree remove --force -- "$dir" >/dev/null 2>&1 || true
  git -C "$MIRROR" worktree prune >/dev/null 2>&1 || true
  rm -rf -- "$dir"
}

# --- Build stage (unprivileged, unsigned) --------------------------------------------

ensure_build_env(){
  command -v java >/dev/null 2>&1 || { echo JAVA_NOT_FOUND; return 50; }
  if [ -z "${ANDROID_HOME:-}" ]; then
    local d
    for d in "$HOME/android-sdk" "${PREFIX:-/usr}/share/android-sdk" "$HOME/Android/Sdk"; do
      if [ -d "$d" ]; then export ANDROID_HOME="$d"; break; fi
    done
  fi
  [ -n "${ANDROID_HOME:-}" ] || { echo ANDROID_HOME_NOT_FOUND; return 51; }
  export ANDROID_SDK_ROOT="$ANDROID_HOME"
  if [ -z "${JAVA_HOME:-}" ]; then
    local jbin
    jbin="$(readlink -f "$(command -v java)")"
    JAVA_HOME="$(dirname "$(dirname "$jbin")")"
    export JAVA_HOME
  fi
  if command -v aapt2 >/dev/null 2>&1; then
    mkdir -p "$HOME/.gradle"
    touch "$HOME/.gradle/gradle.properties"
    local prop="android.aapt2FromMavenOverride=$(command -v aapt2)"
    if grep -q '^android.aapt2FromMavenOverride=' "$HOME/.gradle/gradle.properties"; then
      sed -i "s|^android.aapt2FromMavenOverride=.*|$prop|" "$HOME/.gradle/gradle.properties"
    else
      printf '%s\n' "$prop" >> "$HOME/.gradle/gradle.properties"
    fi
  fi
}

# The Gradle binary is chosen by the runtime, never by the repository. An explicit
# CORTEX_DEVBRIDGE_GRADLE wins; otherwise a Gradle already installed on the device.
trusted_gradle(){
  if [ -n "${CORTEX_DEVBRIDGE_GRADLE:-}" ] && [ -x "${CORTEX_DEVBRIDGE_GRADLE}" ]; then
    printf '%s' "$CORTEX_DEVBRIDGE_GRADLE"; return 0
  fi
  command -v gradle 2>/dev/null
}

run_gradle(){
  local dir="$1"; shift
  local gradle_bin
  ensure_build_env || return $?
  gradle_bin="$(trusted_gradle)"
  [ -n "$gradle_bin" ] || { echo TRUSTED_GRADLE_NOT_FOUND; return 52; }
  echo "gradle_runner=trusted"
  echo "gradle_bin=$gradle_bin"
  (cd "$dir" && "$gradle_bin" --no-daemon --console=plain "$@")
}

# --- Signing stage (separate, artifact-only) -----------------------------------------

apksigner_bin(){
  if command -v apksigner >/dev/null 2>&1; then command -v apksigner; return 0; fi
  find "${ANDROID_HOME:-$HOME}" -type f -path '*/build-tools/*/apksigner' 2>/dev/null | sort | tail -n1
}

zipalign_bin(){
  if command -v zipalign >/dev/null 2>&1; then command -v zipalign; return 0; fi
  find "${ANDROID_HOME:-$HOME}" -type f -path '*/build-tools/*/zipalign' 2>/dev/null | sort | tail -n1
}

# Receives ONLY a finished artifact. The keystore path is never exported to Gradle,
# never copied, never printed, never placed inside a repository-controlled directory.
sign_artifact(){
  local job="$1" src="$2" out="$3" aligned signer za
  [ -f "$src" ] || { echo UNSIGNED_APK_NOT_FOUND; return 60; }
  [ -f "$SIGNER_SOURCE" ] || { echo SIGNER_SOURCE_NOT_FOUND; return 61; }
  case "$SIGNER_SOURCE" in
    "$WORK"/*) echo SIGNER_SOURCE_INSIDE_WORKTREE_REFUSE; return 62 ;;
  esac
  signer="$(apksigner_bin)"
  [ -n "$signer" ] || { echo APKSIGNER_NOT_FOUND; return 63; }
  aligned="$STAGE/$job-aligned.apk"
  rm -f -- "$aligned" "$out"
  za="$(zipalign_bin)"
  if [ -n "$za" ]; then
    "$za" -p -f 4 "$src" "$aligned" >/dev/null 2>&1 || cp -f -- "$src" "$aligned"
  else
    cp -f -- "$src" "$aligned"
  fi
  "$signer" sign \
    --ks "$SIGNER_SOURCE" \
    --ks-pass "pass:$SIGNER_STOREPASS" \
    --ks-key-alias "$SIGNER_KEYALIAS" \
    --key-pass "pass:$SIGNER_KEYPASS" \
    --out "$out" "$aligned" >/dev/null 2>&1 || { rm -f -- "$aligned"; echo APKSIGNER_SIGN_FAIL; return 64; }
  rm -f -- "$aligned"
  echo "signed_stage=separate"
  echo "signed_apk_sha256=$(sha256sum "$out" | awk '{print $1}')"
}

signer_sha(){
  local signer apk digest
  apk="$1"
  signer="$(apksigner_bin)"
  if [ -n "$signer" ]; then
    digest="$("$signer" verify --print-certs "$apk" 2>/dev/null | sed -n 's/^Signer #1 certificate SHA-256 digest: //p' | head -n1 | tr -d ':[:space:]' | tr 'A-F' 'a-f')"
    if [ -n "$digest" ]; then printf '%s' "$digest"; return 0; fi
  fi
  return 1
}

# --- Device operations ---------------------------------------------------------------

install_update(){
  local pkg="$1" apk="$2" installed_path installed_tmp candidate_sha installed_sha staged out rc code_path
  safe_pkg "$pkg" || { echo DENY_PACKAGE; return 22; }
  [ -f "$apk" ] || { echo APK_NOT_FOUND; return 23; }
  candidate_sha="$(signer_sha "$apk")" || { echo APKSIGNER_CANDIDATE_FAIL; return 24; }
  installed_path="$(rish -c "/system/bin/pm path '$pkg'" 2>/dev/null | tr -d '\r' | sed -n 's/^package://p' | grep 'base.apk$' | head -n1)"
  if [ -z "$installed_path" ]; then
    installed_path="$(rish -c "cmd package path '$pkg'" 2>/dev/null | tr -d '\r' | sed -n 's/^package://p' | grep 'base.apk$' | head -n1)"
  fi
  if [ -z "$installed_path" ]; then
    code_path="$(rish -c "dumpsys package '$pkg'" 2>/dev/null | tr -d '\r' | sed -n 's/^[[:space:]]*codePath=//p' | head -n1)"
    [ -n "$code_path" ] && installed_path="${code_path%/}/base.apk"
  fi
  [ -n "$installed_path" ] || { echo INSTALLED_APK_NOT_FOUND; return 25; }
  installed_tmp="$ROOT/installed-$pkg.apk"
  rish -c "cat '$installed_path'" > "$installed_tmp" 2>/dev/null || { rm -f "$installed_tmp"; echo INSTALLED_APK_COPY_FAIL; return 26; }
  installed_sha="$(signer_sha "$installed_tmp")" || { rm -f "$installed_tmp"; echo APKSIGNER_INSTALLED_FAIL; return 27; }
  rm -f "$installed_tmp"
  echo "candidate_signer_sha256=$candidate_sha"
  echo "installed_signer_sha256=$installed_sha"
  # Update-in-place continuity: a candidate that is not signed by the existing signer
  # is refused, so the separate signing stage cannot silently change identity.
  [ "$candidate_sha" = "$installed_sha" ] || { echo SIGNER_MISMATCH_REFUSE_INSTALL; return 28; }
  staged="/data/local/tmp/cortex-devbridge-$$.apk"
  cat "$apk" | rish -c "cat > '$staged'" >/dev/null 2>&1 || { echo APK_STAGE_FAIL; return 29; }
  out="$(rish -c "pm install -r '$staged'" 2>&1)"; rc=$?
  rish -c "rm -f '$staged'" >/dev/null 2>&1 || true
  printf '%s\n' "$out"
  [ $rc -eq 0 ] && printf '%s' "$out" | grep -q 'Success' || return 30
  echo "apk_sha256=$(sha256sum "$apk" | awk '{print $1}')"
}

launch_pkg(){
  local pkg="$1"
  safe_pkg "$pkg" || return 22
  rish -c "ACT=\$(cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER '$pkg' 2>/dev/null | tail -n1); [ -n \"\$ACT\" ] && am start -W -n \"\$ACT\""
}

# --- Job execution -------------------------------------------------------------------

resolve_built_apk(){
  local dir="$1" jobfile="$2" apk_rel apk
  apk_rel="$(jq -r '.params.apk_path // empty' "$jobfile")"
  if [ -n "$apk_rel" ]; then
    not_option_like "$apk_rel" || { echo DENY_APK_PATH >&2; return 1; }
    [[ "$apk_rel" != /* && "$apk_rel" != *'..'* ]] || { echo DENY_APK_PATH >&2; return 1; }
    apk="$dir/$apk_rel"
  else
    apk="$(find "$dir/app/build/outputs/apk" -type f -name '*.apk' ! -name '*androidTest.apk' 2>/dev/null | sort | tail -n1)"
  fi
  [ -n "$apk" ] || return 1
  printf '%s' "$apk"
}

run_capability(){
  local jobfile="$1" job cap commit pkg dir='' apk='' signed='' wait_s rc crashes
  local -a tasks
  job="$(jq -r '.job_id // empty' "$jobfile")"
  cap="$(jq -r '.capability // empty' "$jobfile")"
  commit="$(jq -r '.commit // empty' "$jobfile")"
  pkg="$(jq -r '.package // empty' "$jobfile")"

  [ "$(jq -r '.protocol // empty' "$jobfile")" = "$PROTOCOL" ] || { echo BAD_PROTOCOL; return 10; }
  [ "$(jq -r '.repo // empty' "$jobfile")" = "$REPO" ] || { echo BAD_REPO; return 11; }
  [ "$(jq -r '.authorized_owner // empty' "$jobfile")" = 'KAN1409' ] || { echo BAD_OWNER; return 12; }
  [[ "$job" =~ $JOB_RE ]] || { echo BAD_JOB_ID; return 13; }
  safe_cap "$cap" || { echo DENY_CAPABILITY; return 14; }
  # A job may not name a ref under any key. Only an immutable commit is accepted.
  [ "$(jq -r '.ref // empty' "$jobfile")" = '' ] || { echo DENY_REF_FIELD_REMOVED; return 17; }
  if [ -n "$pkg" ]; then safe_pkg "$pkg" || { echo DENY_PACKAGE; return 16; }; fi

  case "$cap" in
    GIT_STATUS|BUILD|BUILD_INSTALL_SMOKE|INSTALL_UPDATE)
      safe_commit "$commit" || { echo DENY_COMMIT_NOT_FULL_SHA; return 15; }
      ;;
  esac

  case "$cap" in
    PING)
      echo pong=true
      echo "agent_version=$AGENT_VERSION"
      echo "signer_source_present=$([ -f "$SIGNER_SOURCE" ] && echo true || echo false)"
      rish -c 'id' 2>/dev/null | head -n1
      ;;
    GIT_STATUS)
      dir="$(prepare_worktree "$job" "$commit")" || return 40
      echo "head=$(git -C "$dir" rev-parse HEAD)"
      git -C "$dir" status --short
      cleanup_worktree "$dir"
      ;;
    BUILD|BUILD_INSTALL_SMOKE)
      dir="$(prepare_worktree "$job" "$commit")" || return 40
      mapfile -t tasks < <(jq -r '.params.gradle_tasks[]?' "$jobfile")
      [ ${#tasks[@]} -gt 0 ] || tasks=(':app:assembleDebug' ':app:compileDebugAndroidTestJavaWithJavac')
      local task
      for task in "${tasks[@]}"; do safe_task "$task" || { cleanup_worktree "$dir"; echo DENY_GRADLE_TASK; return 41; }; done
      if [ -f "$dir/scripts/cortex-repo-audit.sh" ]; then
        bash "$dir/scripts/cortex-repo-audit.sh" "$dir" || { rc=$?; cleanup_worktree "$dir"; echo REPO_AUDIT_FAILED; return $rc; }
      fi
      run_gradle "$dir" "${tasks[@]}" || { rc=$?; cleanup_worktree "$dir"; return $rc; }
      echo BUILD_SUCCESS
      if [ "$cap" = BUILD_INSTALL_SMOKE ]; then
        [ -n "$pkg" ] || { cleanup_worktree "$dir"; echo PACKAGE_REQUIRED; return 42; }
        apk="$(resolve_built_apk "$dir" "$jobfile")" || { cleanup_worktree "$dir"; echo APK_NOT_FOUND; return 44; }
        signed="$STAGE/$job-signed.apk"
        sign_artifact "$job" "$apk" "$signed" || { rc=$?; cleanup_worktree "$dir"; return $rc; }
        # The untrusted worktree is gone before the signed artifact touches the device.
        cleanup_worktree "$dir"; dir=''
        install_update "$pkg" "$signed" || { rc=$?; rm -f -- "$signed"; return $rc; }
        rish -c 'logcat -b crash -c' >/dev/null 2>&1 || true
        launch_pkg "$pkg" || { rc=$?; rm -f -- "$signed"; return $rc; }
        wait_s="$(jq -r '.params.smoke_wait_seconds // 6' "$jobfile")"
        [[ "$wait_s" =~ ^[0-9]+$ ]] || wait_s=6
        [ "$wait_s" -le 20 ] || wait_s=20
        sleep "$wait_s"
        echo "pid=$(rish -c "pidof '$pkg'" 2>/dev/null | tr -d '\r')"
        rish -c "dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity' | grep -F '$pkg' | head -n4" 2>/dev/null || true
        crashes="$(rish -c 'logcat -b crash -d -t 160' 2>/dev/null | grep -E "AndroidRuntime|FATAL EXCEPTION|Process: $pkg|Caused by:|at $pkg" | tail -n120)"
        printf '%s\n' "$crashes"
        rm -f -- "$signed"
        if printf '%s' "$crashes" | grep -q "$pkg" && printf '%s' "$crashes" | grep -Eq 'FATAL EXCEPTION|Process:'; then
          echo SMOKE_CRASH; return 45
        fi
      fi
      [ -n "$dir" ] && cleanup_worktree "$dir"
      ;;
    INSTALL_UPDATE)
      dir="$(prepare_worktree "$job" "$commit")" || return 40
      apk="$(resolve_built_apk "$dir" "$jobfile")" || { cleanup_worktree "$dir"; echo APK_PATH_REQUIRED; return 46; }
      signed="$STAGE/$job-signed.apk"
      sign_artifact "$job" "$apk" "$signed" || { rc=$?; cleanup_worktree "$dir"; return $rc; }
      cleanup_worktree "$dir"
      install_update "$pkg" "$signed"; rc=$?
      rm -f -- "$signed"
      return $rc
      ;;
    LAUNCH)
      launch_pkg "$pkg"
      ;;
    STOP)
      safe_pkg "$pkg" || return 22
      rish -c "am force-stop '$pkg'"
      ;;
    LOGCAT)
      safe_pkg "$pkg" || return 22
      rish -c 'logcat -b crash -d -t 500' 2>/dev/null | grep -E "AndroidRuntime|FATAL EXCEPTION|Process: $pkg|Caused by:|at $pkg" | tail -n220 || true
      ;;
    DUMPSYS_PACKAGE)
      safe_pkg "$pkg" || return 22
      rish -c "dumpsys package '$pkg'" 2>/dev/null | grep -E 'versionCode=|versionName=|firstInstallTime=|lastUpdateTime=' | head -n20 || true
      ;;
  esac
}

public_log_tail(){
  local cap="$1" log="$2"
  case "$cap" in
    LOGCAT|BUILD_INSTALL_SMOKE)
      grep -E 'started_at=|finished_at=|agent_version=|BUILD_SUCCESS|candidate_signer_sha256=|installed_signer_sha256=|apk_sha256=|signed_apk_sha256=|signed_stage=|provenance_ref=|gradlew_ignored=|Success|pid=|mResumedActivity|topResumedActivity|AndroidRuntime|FATAL EXCEPTION|Process: com\.kareem\.|Caused by:|at com\.kareem\.|SMOKE_CRASH|APKSIGNER_|SIGNER_MISMATCH|DENY_|APK_' "$log" 2>/dev/null | tail -n300 || true
      ;;
    DUMPSYS_PACKAGE)
      grep -E 'started_at=|finished_at=|agent_version=|versionCode=|versionName=|firstInstallTime=|lastUpdateTime=' "$log" 2>/dev/null | tail -n100 || true
      ;;
    *)
      tail -c 50000 "$log" 2>/dev/null || true
      ;;
  esac
}

publish_result(){
  local job="$1" cap="$2" status="$3" rc="$4" log="$5" result_file tailtext
  if [ ! -d "$RESULTS/.git" ]; then
    rm -rf "$RESULTS"
    git clone --filter=blob:none --no-tags "$REMOTE" "$RESULTS" >/dev/null 2>&1 || return 1
  fi
  git -C "$RESULTS" remote set-url origin "$REMOTE" >/dev/null 2>&1 || return 1
  git -C "$RESULTS" fetch --prune --no-tags origin \
    "+refs/heads/$RESULT_BRANCH:refs/remotes/origin/$RESULT_BRANCH" >/dev/null 2>&1 || return 1
  git -C "$RESULTS" checkout -B "$RESULT_BRANCH" "refs/remotes/origin/$RESULT_BRANCH" >/dev/null 2>&1 || return 1
  mkdir -p "$RESULTS/.devbridge/results"
  result_file="$RESULTS/.devbridge/results/$job.json"
  tailtext="$(public_log_tail "$cap" "$log")"
  jq -n \
    --arg protocol "$PROTOCOL" \
    --arg agent_version "$AGENT_VERSION" \
    --arg job_id "$job" \
    --arg capability "$cap" \
    --arg status "$status" \
    --argjson exit_code "$rc" \
    --arg finished_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --arg log_tail "$tailtext" \
    '{protocol:$protocol,agent_version:$agent_version,job_id:$job_id,capability:$capability,status:$status,exit_code:$exit_code,finished_at:$finished_at,log_tail:$log_tail}' > "$result_file"
  git -C "$RESULTS" add ".devbridge/results/$job.json"
  git -C "$RESULTS" diff --cached --quiet && return 0
  git -C "$RESULTS" -c user.name='Cortex Dev Bridge' -c user.email='cortex-devbridge@localhost' commit -m "devbridge(result): $job ${status,,}" >/dev/null || return 1
  git -C "$RESULTS" push origin "HEAD:$RESULT_BRANCH" >/dev/null 2>&1
}

# Jobs are read from a data-only branch that carries no scripts and no workflows. The
# job branch cannot update this runtime: the bootstrap installs pinned releases only.
process_once(){
  local path tmp job cap log rc status
  sync_mirror || return 1
  git -C "$MIRROR" rev-parse --verify --quiet "refs/remotes/origin/$JOB_BRANCH" >/dev/null 2>&1 || return 0
  while IFS= read -r path; do
    [ -n "$path" ] || continue
    tmp="$ROOT/current-job.json"
    git -C "$MIRROR" show "refs/remotes/origin/$JOB_BRANCH:$path" > "$tmp" 2>/dev/null || continue
    job="$(jq -r '.job_id // empty' "$tmp")"
    cap="$(jq -r '.capability // empty' "$tmp")"
    [ -n "$job" ] || continue
    [[ "$job" =~ $JOB_RE ]] || continue
    grep -Fxq "$job" "$STATE" && continue
    log="$LOGS/$job.log"
    printf 'started_at=%s\nagent_version=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$AGENT_VERSION" > "$log"
    if run_capability "$tmp" >> "$log" 2>&1; then rc=0; status=SUCCESS; else rc=$?; status=FAILED; fi
    printf 'finished_at=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$log"
    if publish_result "$job" "$cap" "$status" "$rc" "$log"; then
      printf '%s\n' "$job" >> "$STATE"
    fi
  done < <(git -C "$MIRROR" ls-tree -r --name-only "refs/remotes/origin/$JOB_BRANCH" '.devbridge/jobs' | grep '\.json$' || true)
}

# Single-job entry point used by the adversarial test harness and by manual operation.
run_job_file(){
  local jobfile="${1:-}"
  [ -f "$jobfile" ] || { echo JOB_FILE_NOT_FOUND >&2; return 2; }
  run_capability "$jobfile"
}

case "${1:---once}" in
  --once) process_once ;;
  --version) printf 'agent_version=%s\nprotocol=%s\n' "$AGENT_VERSION" "$PROTOCOL" ;;
  --job) shift; run_job_file "${1:-}" ;;
  *) echo "usage: $0 [--once|--version|--job <file>]" >&2; exit 2 ;;
esac
