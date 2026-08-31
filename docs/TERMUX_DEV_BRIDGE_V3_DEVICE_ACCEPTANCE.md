# Termux Dev Bridge V3 — Device Acceptance

Validated on the real Android device on 2026-08-30 after the one-time V2 supervisor bootstrap.

## Topology proven

`ChatGPT -> GitHub control branch -> Termux supervisor -> hot-loaded bounded agent -> rish/Shizuku -> Android -> GitHub result branch -> ChatGPT`

Control branch: `infra/termux-dev-bridge-v1`  
Result branch: `device/termux-dev-bridge-results`

## Real-device gates

### 1. Bootstrap / Shizuku PING — PASS

`job_bootstrap_probe_v1`

- status: `SUCCESS`
- exit code: `0`
- Shizuku/rish authority: Android shell UID
- device response received through the result branch
- persistent Cortex signer source present

### 2. Isolated Git worktree — PASS

`job_bridge_gitstatus_20260830_01`

- status: `SUCCESS`
- exact requested Cortex head: `32870cd69a0c30ceb0bc134cd91c8eaebd00823f`
- signer overlay: present
- normal `~/Cortex` worktree was not switched/reset/stashed/cleaned

### 3. Hot-loaded V3 runtime — PASS

`job_bridge_v3_ping_20260830_01`

- status: `SUCCESS`
- agent version: `3`
- Shizuku/rish shell authority confirmed after the supervisor hot-update
- no second manual bootstrap was required

### 4. Real Termux Cortex build — PASS

`job_bridge_v3_build_20260830_01`

- status: `SUCCESS`
- repository audit: `PASS`, 318 tracked source/config files, zero warnings/failures
- build ref: `migration/cognitive-brain-v2-step1-2` at `32870cd69a0c30ceb0bc134cd91c8eaebd00823f`
- signing overlay: present
- build runner: Termux Gradle fallback (the repository intentionally has no root `gradlew` on this branch)
- task: `:app:assembleDebug`
- no install, uninstall, clear-data or app-state mutation was used for this acceptance gate

The known Termux `aapt2` `No package ID 7f` diagnostics remained non-fatal; the Android build completed successfully, matching prior device build behavior.

### 5. Bounded Android package introspection — PASS

`job_bridge_v3_package_probe_20260830_01`

- capability: `DUMPSYS_PACKAGE`
- status: `SUCCESS`
- exit code: `0`
- result is deliberately filtered before publication rather than returning an unrestricted dumpsys payload

### 6. Independent consumer proof — PASS

A separate job, `job_relay_c5_probe_20260830_0926`, was added after bootstrap and completed successfully through agent V3 for the allowlisted Relay package. This proves the channel is reusable after bootstrap rather than being limited to the original acceptance jobs.

## Safety boundary

The bridge exposes only named capabilities. It intentionally has no arbitrary remote-shell, uninstall, clear-data, merge, signing-key generation, or signing-key replacement capability. Install flows remain update-in-place and signer-checked before `pm install -r`.

## Operational state

The Termux supervisor is persistent in the current Termux runtime and has a Termux:Boot hook. It refreshes the bounded agent from the control branch before each pass, so bridge fixes can normally be delivered remotely without another Termux command.

A device reboot can still require Shizuku itself to become available again according to the phone's Shizuku startup mode; the bridge fails closed while `rish` is unavailable.

`CORTEX_TERMUX_DEV_BRIDGE_V3_DEVICE_ACCEPTANCE=PASS`
