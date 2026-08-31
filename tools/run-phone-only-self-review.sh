#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

printf 'Cortex phone-only self review\n'
printf 'repo: %s\n' "$ROOT"
printf 'branch: %s\n' "$(git rev-parse --abbrev-ref HEAD 2>/dev/null || true)"
printf 'head: %s\n\n' "$(git rev-parse --short HEAD 2>/dev/null || true)"

printf '[1/2] Building app APK only...\n'
gradle :app:assembleDebug
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || { echo "APK not found: $APK" >&2; exit 2; }
ls -lh "$APK"

printf '\n[2/2] Opening Android package installer from Termux...\n'
printf 'Tap Update/Install in the installer. No Shizuku, adb, PC or Wi-Fi debugging is required.\n'
if command -v termux-open >/dev/null 2>&1; then
  termux-open --view "$APK" >/dev/null 2>&1 || termux-open "$APK" >/dev/null 2>&1 || true
else
  echo 'termux-open is unavailable. Install Termux:API/termux-tools support or open the APK from a file manager.' >&2
fi

cat <<'EOF'

After Android finishes the update, return to Termux and run:

  termux-open-url 'cortex://self-test'

If termux-open-url is unavailable, try:

  am start -W -a android.intent.action.VIEW -d 'cortex://self-test'

Cortex will run the review inside its own process and save the ZIP to:
  Downloads/Cortex/FullCortexPhoneOnlyReview_<timestamp>.zip

The phone-only bundle contains V1 structural/runtime checks plus V2/V3/V5 production cognitive review artifacts.
EOF
