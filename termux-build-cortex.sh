#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

REPO_DIR="${CORTEX_REPO_DIR:-$HOME/Cortex}"
BUILD_LOG=""
BUILD_LOG_OUT="/sdcard/Download/Cortex-build-last.log"
CLEAN_BUILD="${CORTEX_CLEAN_BUILD:-0}"
AUTO_INSTALL="${CORTEX_AUTO_INSTALL:-0}"
SYNC_SOURCE="${CORTEX_SYNC_SOURCE:-0}"

log(){ printf '\n==> %s\n' "$*"; }
fail(){ printf '\nERROR: %s\n' "$*" >&2; exit 1; }

log "Preparing Termux build environment"
command -v pkg >/dev/null || fail "Run this inside Termux"
MISSING=()
command -v git >/dev/null || MISSING+=(git)
command -v java >/dev/null || MISSING+=(openjdk-17)
command -v gradle >/dev/null || MISSING+=(gradle)
command -v aapt2 >/dev/null || MISSING+=(aapt2)
if [ ${#MISSING[@]} -gt 0 ]; then log "Installing: ${MISSING[*]}"; pkg update -y; pkg install -y "${MISSING[@]}"; fi

if [ ! -d "$REPO_DIR/.git" ]; then
  if [ -d "$HOME/Cortex/.git" ]; then REPO_DIR="$HOME/Cortex"
  elif [ -d "$HOME/cortex/.git" ]; then REPO_DIR="$HOME/cortex"
  else fail "Cortex repo not found. Set CORTEX_REPO_DIR or clone it first."
  fi
fi
cd "$REPO_DIR"
BUILD_LOG="$REPO_DIR/termux-build-last.log"
CURRENT_REF="$(git branch --show-current 2>/dev/null || true)"
[ -n "$CURRENT_REF" ] || CURRENT_REF="detached"
TARGET_REF="${CORTEX_BUILD_REF:-$CURRENT_REF}"

# Source mutation is opt-in. By default this script builds exactly the working tree the user has.
# This protects local V2/Compose work from implicit stash/checkout/pull behavior.
if [ "$SYNC_SOURCE" = "1" ]; then
  [ "$TARGET_REF" != "detached" ] || fail "CORTEX_SYNC_SOURCE=1 needs a named CORTEX_BUILD_REF"
  if [ -n "$(git status --porcelain --untracked-files=all)" ]; then
    fail "Refusing to sync a dirty working tree. Preserve/reconcile local V2 changes first; this script will not stash, reset, or overwrite them."
  fi
  log "Syncing clean source: $TARGET_REF"
  git fetch origin "$TARGET_REF"
  if [ "$CURRENT_REF" != "$TARGET_REF" ]; then git checkout "$TARGET_REF"; fi
  git pull --ff-only origin "$TARGET_REF"
else
  log "Building current working tree without fetch/checkout/stash: $CURRENT_REF"
  if [ -n "${CORTEX_BUILD_REF:-}" ] && [ "$TARGET_REF" != "$CURRENT_REF" ]; then
    fail "CORTEX_BUILD_REF=$TARGET_REF differs from current branch $CURRENT_REF. Set CORTEX_SYNC_SOURCE=1 on a clean tree, or checkout explicitly yourself."
  fi
fi

log "Running Cortex repository audit"
[ -f "$REPO_DIR/scripts/cortex-repo-audit.sh" ] || fail "Repository audit script is missing"
bash "$REPO_DIR/scripts/cortex-repo-audit.sh" "$REPO_DIR" || fail "Repository audit failed before Gradle"

if [ -z "${ANDROID_HOME:-}" ]; then
  for d in "$HOME/android-sdk" "$PREFIX/share/android-sdk" "$HOME/Android/Sdk"; do
    if [ -d "$d" ]; then export ANDROID_HOME="$d"; break; fi
  done
fi
[ -n "${ANDROID_HOME:-}" ] || fail "ANDROID_HOME is not set and Android SDK was not found."
export ANDROID_SDK_ROOT="$ANDROID_HOME"
if [ -z "${JAVA_HOME:-}" ]; then JBIN="$(readlink -f "$(command -v java)")"; export JAVA_HOME="$(dirname "$(dirname "$JBIN")")"; fi
mkdir -p "$HOME/.gradle"
AAPT_PROP="android.aapt2FromMavenOverride=$PREFIX/bin/aapt2"
touch "$HOME/.gradle/gradle.properties"
if grep -q '^android.aapt2FromMavenOverride=' "$HOME/.gradle/gradle.properties"; then
  sed -i "s|^android.aapt2FromMavenOverride=.*|$AAPT_PROP|" "$HOME/.gradle/gradle.properties"
else
  printf '%s\n' "$AAPT_PROP" >> "$HOME/.gradle/gradle.properties"
fi

VERSION_NAME="$(sed -nE "s/^[[:space:]]*versionName[[:space:]]+['\"]([^'\"]+)['\"].*/\1/p" app/build.gradle | head -n 1)"
[ -n "$VERSION_NAME" ] || VERSION_NAME="debug"
APK_SRC="$REPO_DIR/app/build/outputs/apk/debug/app-debug.apk"
APK_OUT="${CORTEX_APK_OUT:-/sdcard/Download/Cortex-${VERSION_NAME}-debug.apk}"
INSTALL_TMP="/data/local/tmp/Cortex-${VERSION_NAME}-debug.apk"

log "Java: $(java -version 2>&1 | head -n 1)"
log "Android SDK: $ANDROID_HOME"
log "AAPT2: $(aapt2 version 2>&1 | head -n 1)"
if [ "$CLEAN_BUILD" = "1" ]; then
  log "Removing generated build outputs only"
  rm -rf "$REPO_DIR/app/build" "$REPO_DIR/build"
fi

log "Building Cortex ${VERSION_NAME} + instrumentation APK from current source"
rm -f "$BUILD_LOG"
set +e
if [ -x ./gradlew ]; then RUN=(./gradlew); else RUN=(gradle); fi
if [ "$CLEAN_BUILD" = "1" ]; then
  "${RUN[@]}" :app:clean :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace --console=plain 2>&1 | tee "$BUILD_LOG"
else
  "${RUN[@]}" :app:assembleDebug :app:assembleDebugAndroidTest --stacktrace --console=plain 2>&1 | tee "$BUILD_LOG"
fi
BUILD_RC=${PIPESTATUS[0]}
set -e
mkdir -p /sdcard/Download
cp -f "$BUILD_LOG" "$BUILD_LOG_OUT" 2>/dev/null || true
if [ "$BUILD_RC" -ne 0 ]; then
  printf '\n================ CORTEX BUILD ERROR SUMMARY ================\n' >&2
  grep -n -E '(^|[[:space:]])error:|FAILURE:|Execution failed for task|What went wrong:|Caused by:|cannot find symbol|method .* cannot be applied|incompatible types|package .* does not exist' "$BUILD_LOG" 2>/dev/null | tail -n 100 >&2 || true
  printf '=============================================================\n' >&2
  printf 'Full log: %s\n' "$BUILD_LOG_OUT" >&2
  exit "$BUILD_RC"
fi

[ -f "$APK_SRC" ] || fail "Build finished but APK was not found at $APK_SRC"
log "Copying convenience APK to Downloads"
cp -f "$APK_SRC" "$APK_OUT"
sha256sum "$APK_OUT" | tee "$APK_OUT.sha256"
log "Build complete"
printf 'APK: %s\n' "$APK_OUT"

if [ "$AUTO_INSTALL" = "1" ]; then
  command -v rish >/dev/null 2>&1 || fail "CORTEX_AUTO_INSTALL=1 requested, but rish is unavailable"
  log "Installing update-in-place through Shizuku/rish using /data/local/tmp"
  cat "$APK_SRC" | rish -c "cat > '$INSTALL_TMP' && chmod 644 '$INSTALL_TMP' && pm install -r '$INSTALL_TMP'"
else
  printf 'Install only over the existing Cortex package; never uninstall or clear data.\n'
fi
