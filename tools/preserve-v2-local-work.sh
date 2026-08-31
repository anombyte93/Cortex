#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="${1:-$(git rev-parse --show-toplevel 2>/dev/null || pwd)}"
cd "$ROOT"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="${2:-$HOME/cortex-v2-local-preserve-$STAMP}"
mkdir -p "$OUT/untracked"

printf 'Cortex V2 local preservation snapshot\n' > "$OUT/README.txt"
printf 'created_at=%s\n' "$(date -Iseconds 2>/dev/null || date)" >> "$OUT/README.txt"
printf 'repo=%s\n' "$ROOT" >> "$OUT/README.txt"
printf 'branch=%s\n' "$(git branch --show-current 2>/dev/null || echo detached)" >> "$OUT/README.txt"
printf 'head=%s\n' "$(git rev-parse HEAD 2>/dev/null || echo unknown)" >> "$OUT/README.txt"

git status --short --untracked-files=all > "$OUT/status.txt"
git diff --binary > "$OUT/tracked-working.patch"
git diff --cached --binary > "$OUT/staged.patch"
git ls-files --others --exclude-standard > "$OUT/untracked-all.txt"

# Preserve source/config/build helpers but skip generated caches, compiled packages and huge logs.
while IFS= read -r path; do
  [ -z "$path" ] && continue
  case "$path" in
    .gradle/*|.kotlin/*|**/build/*|build/*|downloads/*|*.apk|*.aab|*.apks|termux-build-last.log)
      continue
      ;;
  esac
  if [ -f "$path" ]; then
    mkdir -p "$OUT/untracked/$(dirname "$path")"
    cp -p "$path" "$OUT/untracked/$path"
  fi
done < "$OUT/untracked-all.txt"

# The update-in-place signer is intentionally NOT copied into the snapshot.
# Record only its fingerprint so later builds can prove signer continuity.
if [ -f app/cortex-debug.keystore ]; then
  sha256sum app/cortex-debug.keystore > "$OUT/local-signer.sha256"
else
  printf 'MISSING app/cortex-debug.keystore\n' > "$OUT/local-signer.sha256"
fi

# Record the local V2 files most likely to matter during reconciliation.
{
  printf '=== TRACKED/UNTRACKED V2 CANDIDATES ===\n'
  find app/src/main/java/com/kareem/cortex/ui -type f 2>/dev/null | sort || true
  [ -f app/build.gradle.kts ] && printf '%s\n' app/build.gradle.kts
  [ -f gradlew ] && printf '%s\n' gradlew
  [ -f gradlew.bat ] && printf '%s\n' gradlew.bat
  [ -d gradle ] && find gradle -type f 2>/dev/null | sort || true
} > "$OUT/v2-candidates.txt"

ARCHIVE="$OUT.tar.gz"
rm -f "$ARCHIVE"
tar -czf "$ARCHIVE" -C "$(dirname "$OUT")" "$(basename "$OUT")"

printf '\nCORTEX_V2_LOCAL_PRESERVE_OK\n'
printf 'Snapshot: %s\n' "$OUT"
printf 'Archive: %s\n' "$ARCHIVE"
printf 'No files in the working tree were changed, stashed, reset, or deleted.\n'
printf 'The archive contains source/config diffs only; signing key bytes and app databases are not copied.\n'
