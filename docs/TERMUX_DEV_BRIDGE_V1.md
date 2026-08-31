# Termux Dev Bridge V1

> **SUPERSEDED — historical record only. Do not follow these instructions.**
>
> The V1/V2 architecture described below was withdrawn during the PR #70 security
> review. It fetched a *mutable* control branch, executed the latest agent script from
> it after only a `bash -n` syntax check, and copied the app signing keystore into a
> build worktree checked out from an attacker-influenceable ref. Write access to the
> control branch was equivalent to arbitrary code execution on the device and loss of
> the signing key.
>
> The supported runtime is **V3**: `scripts/devbridge-agent-v3.sh` driven by
> `scripts/devbridge-bootstrap-v3.sh`. Jobs now name an immutable 40-hex commit that
> must be reachable from a trusted named ref, Gradle tasks come from an enumerated
> allowlist, signing happens in a separate stage that never exposes the keystore to
> build logic, and the runtime itself is a pinned, digest-verified release.
>
> Read **`docs/TERMUX_DEV_BRIDGE_V3_THREAT_MODEL.md`** for the current design, the job
> schema, the refusal codes, and the runtime update procedure. `scripts/devbridge-bootstrap.sh`,
> `scripts/devbridge-bootstrap-v2.sh` and `scripts/devbridge-agent-v2.sh` no longer exist.

Termux Dev Bridge is a bounded remote development executor for Cortex-class Android projects.

## Topology

`ChatGPT -> GitHub control branch -> Termux agent -> rish/Shizuku -> Android`

Results return through a separate Git branch:

`Android/Termux -> device/termux-dev-bridge-results -> ChatGPT`

Control jobs live at `.devbridge/jobs/*.json` on `infra/termux-dev-bridge-v1`. Results live at `.devbridge/results/<job_id>.json` on `device/termux-dev-bridge-results`.

The agent uses its own clone and detached Git worktrees under `~/.cortex-devbridge`. It does not switch, reset, stash, clean, or otherwise mutate the user's normal `~/Cortex` worktree.

## V1 capabilities

- `PING`
- `GIT_STATUS`
- `BUILD`
- `BUILD_INSTALL_SMOKE`
- `INSTALL_UPDATE`
- `LAUNCH`
- `STOP`
- `LOGCAT`
- `DUMPSYS_PACKAGE`

There is intentionally no arbitrary remote-shell capability.

## Safety invariants

- No uninstall operation exists.
- No clear-data operation exists.
- No merge operation exists.
- No signing-key generation or replacement operation exists.
- Package actions are allowlisted to `com.kareem.cortex` and `com.kareem.secondbrain`.
- App installation is update-in-place only (`pm install -r`).
- Before installation, the agent extracts the signer SHA-256 from the candidate APK and from the currently installed base APK and fails closed on a mismatch.
- Builds run from the exact requested Git ref in an isolated worktree.
- Job IDs, refs, Gradle tasks, package names and APK paths are constrained before execution.
- Device operations are routed through the existing `rish`/Shizuku boundary.
- Results are auditable JSON plus a bounded log tail.

## Bootstrap

`scripts/devbridge-bootstrap.sh` installs the local agent, verifies `rish`, creates a Termux:Boot hook if Termux:Boot is present, and starts the agent immediately. It does not touch Cortex app data or signing material.

A bootstrap `PING` job is already queued. Once the agent is active it will process that job and push a structured result to the result branch, proving the end-to-end channel.

## Job contract

```json
{
  "protocol": "CORTEX_DEVBRIDGE_V1",
  "job_id": "job_example_001",
  "repo": "KAN1409/Cortex",
  "authorized_owner": "KAN1409",
  "capability": "BUILD",
  "ref": "migration/cognitive-brain-v2-step1-2",
  "package": "com.kareem.cortex",
  "params": {
    "gradle_tasks": [":app:assembleDebug", ":app:assembleDebugAndroidTest"]
  }
}
```

After bootstrap, ChatGPT can add code on a development branch, enqueue a bounded job, read the device result and continue the fix/build/test loop without requiring copy/paste Termux commands for each iteration.
