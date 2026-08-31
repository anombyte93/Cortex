#!/data/data/com.termux/files/usr/bin/bash
# Cortex Termux Dev Bridge - bootstrap v3 (pinned runtime).
#
# What changed from v2, and why:
#   v2 fetched the control branch every 20s and executed whatever
#   scripts/devbridge-agent-v2.sh happened to be at its tip, gated only by `bash -n`.
#   `bash -n` is a syntax check, not a security boundary: write access to the control
#   branch was write access to the device. That is D4.
#
#   v3 installs a PINNED runtime. The operator supplies exactly two values - a runtime
#   commit and the sha256 of the agent script at that commit - and the supervisor will
#   run nothing else. There is no "latest". A job branch carries data only and can
#   never change what code runs here.
#
# Update procedure (deliberately a human, reviewed act):
#   1. Review the new scripts/devbridge-agent-v3.sh on a trusted named ref.
#   2. Record the merge commit SHA and `sha256sum scripts/devbridge-agent-v3.sh`.
#   3. Re-run this bootstrap with:
#        CORTEX_DEVBRIDGE_RUNTIME_COMMIT=<40-hex> \
#        CORTEX_DEVBRIDGE_RUNTIME_SHA256=<64-hex> \
#        bash scripts/devbridge-bootstrap-v3.sh
#      The pin is written to $ROOT/runtime.pin and survives reboots.
#   A pin that does not verify is refused; the previously installed runtime keeps running.
set -euo pipefail

REPO="${CORTEX_DEVBRIDGE_REPO:-KAN1409/Cortex}"
JOB_BRANCH="${CORTEX_DEVBRIDGE_JOB_BRANCH:-device/termux-dev-bridge-jobs}"
RESULT_BRANCH="${CORTEX_DEVBRIDGE_RESULT_BRANCH:-device/termux-dev-bridge-results}"
TRUSTED_REFS="${CORTEX_DEVBRIDGE_TRUSTED_REFS:-main migration/cognitive-brain-v2-step1-2}"
ROOT="${CORTEX_DEVBRIDGE_HOME:-$HOME/.cortex-devbridge}"
LOCAL_REPO="${CORTEX_DEVBRIDGE_LOCAL_REPO:-$HOME/Cortex}"
SIGNER_HOME="${CORTEX_DEVBRIDGE_SIGNER_HOME:-$HOME/.cortex-devbridge-signer}"
POLL="${CORTEX_DEVBRIDGE_POLL_SECONDS:-20}"
AGENT_PATH='scripts/devbridge-agent-v3.sh'

if [ -n "${CORTEX_DEVBRIDGE_REMOTE:-}" ]; then
  REMOTE="$CORTEX_DEVBRIDGE_REMOTE"
elif [ -d "$LOCAL_REPO/.git" ] && git -C "$LOCAL_REPO" remote get-url origin >/dev/null 2>&1; then
  REMOTE="$(git -C "$LOCAL_REPO" remote get-url origin)"
else
  REMOTE="https://github.com/${REPO}.git"
fi

fail(){ printf 'CORTEX_DEVBRIDGE_V3_BOOTSTRAP_FAIL: %s\n' "$*" >&2; exit 1; }

# ---- runtime pin: shape validation -----------------------------------------------
# Deliberately FIRST. Validating the pin needs no dependencies and no device, so a
# malformed pin is rejected identically everywhere, including on a non-Termux host
# where the harness exercises this path.

PIN_FILE="$ROOT/runtime.pin"
RUNTIME_COMMIT="${CORTEX_DEVBRIDGE_RUNTIME_COMMIT:-}"
RUNTIME_SHA256="${CORTEX_DEVBRIDGE_RUNTIME_SHA256:-}"
if [ -z "$RUNTIME_COMMIT" ] || [ -z "$RUNTIME_SHA256" ]; then
  if [ -f "$PIN_FILE" ]; then
    # shellcheck disable=SC1090
    . "$PIN_FILE"
    RUNTIME_COMMIT="${RUNTIME_COMMIT:-}"
    RUNTIME_SHA256="${RUNTIME_SHA256:-}"
  fi
fi
[ -n "$RUNTIME_COMMIT" ] || fail "no runtime pin: set CORTEX_DEVBRIDGE_RUNTIME_COMMIT (40-hex commit)"
[ -n "$RUNTIME_SHA256" ] || fail "no runtime pin: set CORTEX_DEVBRIDGE_RUNTIME_SHA256 (sha256 of $AGENT_PATH)"
case "$RUNTIME_COMMIT" in
  *[!0-9a-f]*) fail "runtime commit must be a full 40-hex SHA, got: $RUNTIME_COMMIT" ;;
esac
[ "${#RUNTIME_COMMIT}" -eq 40 ] || fail "runtime commit must be a full 40-hex SHA, got: $RUNTIME_COMMIT"
case "$RUNTIME_SHA256" in
  *[!0-9a-f]*) fail "runtime sha256 must be 64 hex characters" ;;
esac
[ "${#RUNTIME_SHA256}" -eq 64 ] || fail "runtime sha256 must be 64 hex characters"

# ---- dependencies ------------------------------------------------------------------

command -v pkg >/dev/null 2>&1 || fail "Termux pkg not found"

missing=()
command -v git >/dev/null 2>&1 || missing+=(git)
command -v jq >/dev/null 2>&1 || missing+=(jq)
command -v sha256sum >/dev/null 2>&1 || missing+=(coreutils)
if [ ${#missing[@]} -gt 0 ]; then
  printf 'CORTEX_DEVBRIDGE_INSTALLING_DEPS: %s\n' "${missing[*]}"
  pkg install -y "${missing[@]}" || fail "dependency installation failed: ${missing[*]}"
fi
command -v git >/dev/null 2>&1 || fail "git unavailable"
command -v jq >/dev/null 2>&1 || fail "jq unavailable"
command -v sha256sum >/dev/null 2>&1 || fail "sha256sum unavailable"
command -v rish >/dev/null 2>&1 || fail "rish not found"
rish -c 'id' >/dev/null 2>&1 || fail "Shizuku/rish unavailable"

mkdir -p "$ROOT" "$ROOT/logs" "$ROOT/work" "$ROOT/stage" "$SIGNER_HOME" "$HOME/.termux/boot"
chmod 700 "$SIGNER_HOME" 2>/dev/null || true

# ---- runtime pin: provenance + integrity -------------------------------------------

MIRROR="$ROOT/mirror"
if [ ! -d "$MIRROR/.git" ]; then
  rm -rf "$MIRROR"
  git clone --filter=blob:none --no-tags "$REMOTE" "$MIRROR" >/dev/null 2>&1 || fail "clone failed"
fi
git -C "$MIRROR" remote set-url origin "$REMOTE" >/dev/null 2>&1 || fail "remote set-url failed"
for ref in $TRUSTED_REFS; do
  git -C "$MIRROR" fetch --prune --no-tags origin "+refs/heads/$ref:refs/remotes/origin/$ref" >/dev/null 2>&1 || true
done

# The pinned commit must already be reachable from a trusted named ref. A commit that
# only exists on some other branch is refused even if its digest matches.
git -C "$MIRROR" cat-file -e "${RUNTIME_COMMIT}^{commit}" 2>/dev/null || fail "pinned runtime commit not present on any trusted ref"
provenance=''
for ref in $TRUSTED_REFS; do
  if git -C "$MIRROR" rev-parse --verify --quiet "refs/remotes/origin/$ref" >/dev/null 2>&1 &&
     git -C "$MIRROR" merge-base --is-ancestor "$RUNTIME_COMMIT" "refs/remotes/origin/$ref" 2>/dev/null; then
    provenance="$ref"; break
  fi
done
[ -n "$provenance" ] || fail "pinned runtime commit is not reachable from any trusted ref ($TRUSTED_REFS)"

CANDIDATE="$ROOT/agent.candidate.sh"
git -C "$MIRROR" show "${RUNTIME_COMMIT}:${AGENT_PATH}" > "$CANDIDATE" 2>/dev/null || fail "pinned runtime does not contain $AGENT_PATH"
actual="$(sha256sum "$CANDIDATE" | awk '{print $1}')"
if [ "$actual" != "$RUNTIME_SHA256" ]; then
  rm -f "$CANDIDATE"
  fail "runtime integrity check failed: expected $RUNTIME_SHA256 got $actual"
fi
bash -n "$CANDIDATE" >/dev/null 2>&1 || { rm -f "$CANDIDATE"; fail "pinned runtime failed syntax check"; }
chmod 700 "$CANDIDATE"
mv -f "$CANDIDATE" "$ROOT/agent.installed.sh"

cat > "$PIN_FILE" <<PIN
RUNTIME_COMMIT=$RUNTIME_COMMIT
RUNTIME_SHA256=$RUNTIME_SHA256
INSTALLED_AT=$(date -u +%Y-%m-%dT%H:%M:%SZ)
PROVENANCE_REF=$provenance
PIN
chmod 600 "$PIN_FILE"

# ---- supervisor ----------------------------------------------------------------------
# The supervisor executes ONE file: the runtime installed above. It re-verifies that
# file's digest against the pin on every tick, so tampering on disk stops execution
# rather than being run. It never fetches or evaluates code from any branch.

cat > "$ROOT/supervisor.sh" <<'SUPERVISOR'
#!/data/data/com.termux/files/usr/bin/bash
set -u
ROOT="${CORTEX_DEVBRIDGE_HOME:-$HOME/.cortex-devbridge}"
POLL="${CORTEX_DEVBRIDGE_POLL_SECONDS:-20}"
PIN_FILE="$ROOT/runtime.pin"
AGENT="$ROOT/agent.installed.sh"
while true; do
  RUNTIME_SHA256=''
  [ -f "$PIN_FILE" ] && . "$PIN_FILE"
  if [ -n "$RUNTIME_SHA256" ] && [ -x "$AGENT" ]; then
    actual="$(sha256sum "$AGENT" 2>/dev/null | awk '{print $1}')"
    if [ "$actual" = "$RUNTIME_SHA256" ]; then
      "$AGENT" --once >> "$ROOT/agent.stdout.log" 2>> "$ROOT/agent.stderr.log" || true
    else
      printf 'CORTEX_DEVBRIDGE_RUNTIME_TAMPERED expected=%s actual=%s\n' \
        "$RUNTIME_SHA256" "$actual" >> "$ROOT/supervisor.stderr.log"
    fi
  else
    printf 'CORTEX_DEVBRIDGE_RUNTIME_NOT_PINNED\n' >> "$ROOT/supervisor.stderr.log"
  fi
  sleep "$POLL"
done
SUPERVISOR
chmod 700 "$ROOT/supervisor.sh"

cat > "$HOME/.termux/boot/20-cortex-devbridge" <<EOF
#!/data/data/com.termux/files/usr/bin/bash
command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock || true
nohup "$ROOT/supervisor.sh" >> "$ROOT/supervisor.stdout.log" 2>> "$ROOT/supervisor.stderr.log" < /dev/null &
echo \$! > "$ROOT/supervisor.pid"
EOF
chmod 700 "$HOME/.termux/boot/20-cortex-devbridge"

for pidfile in "$ROOT/agent.pid" "$ROOT/supervisor.pid"; do
  if [ -f "$pidfile" ]; then
    oldpid="$(cat "$pidfile" 2>/dev/null || true)"
    if [ -n "$oldpid" ] && kill -0 "$oldpid" 2>/dev/null; then
      kill "$oldpid" 2>/dev/null || true
      sleep 1
    fi
  fi
done

command -v termux-wake-lock >/dev/null 2>&1 && termux-wake-lock || true
nohup "$ROOT/supervisor.sh" >> "$ROOT/supervisor.stdout.log" 2>> "$ROOT/supervisor.stderr.log" < /dev/null &
echo $! > "$ROOT/supervisor.pid"
sleep 3
kill -0 "$(cat "$ROOT/supervisor.pid")" 2>/dev/null || fail "supervisor did not stay running"

# The signer path is reported, never its contents. If the operator has not yet placed
# the keystore outside every checkout, say so plainly instead of falling back to a
# repository-relative path.
signer="$SIGNER_HOME/cortex-debug.keystore"
printf 'CORTEX_DEVBRIDGE_V3_BOOTSTRAP_OK\npid=%s\njobs=%s\nresults=%s\ntrusted_refs=%s\nruntime_commit=%s\nruntime_provenance=%s\nsigner_path=%s\nsigner_present=%s\n' \
  "$(cat "$ROOT/supervisor.pid")" "$JOB_BRANCH" "$RESULT_BRANCH" "$TRUSTED_REFS" \
  "$RUNTIME_COMMIT" "$provenance" "$signer" \
  "$([ -f "$signer" ] && echo true || echo false)"
