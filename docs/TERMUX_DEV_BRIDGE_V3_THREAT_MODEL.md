# Termux Dev Bridge V3 — threat-model delta

Status: security redesign of the V3 Dev Bridge shipped in PR #70.
Scope: `scripts/devbridge-agent-v3.sh` and `scripts/devbridge-bootstrap-v3.sh`.
Audience: reviewers of PR #70.

The Dev Bridge lets an authorised operator drive a build/install/smoke cycle on a
physical Termux + Shizuku device from a Git branch. Its whole security question is
therefore: **what does write access to a branch buy an attacker?**

Before this change the answer was "arbitrary code execution on the device and the app
signing key". After it, the answer is intended to be "the ability to request an
enumerated build task against a commit that was already reviewed onto a trusted
branch".

The one-line invariant: **the job channel is data only.** A job may select an immutable
commit and an allowlisted task. It can never select a ref, a script, a runtime, a
Gradle binary, or a signer.

---

## D1 — signing keystore copied into the repo-controlled build worktree

**Before.** `prepare_worktree()` copied the persistent signer into the untrusted
worktree before any build:

```
SIGNER_SOURCE="${CORTEX_DEVBRIDGE_SIGNER_SOURCE:-$LOCAL_REPO/app/cortex-debug.keystore}"   # L9
cp -f "$SIGNER_SOURCE" "$dir/app/cortex-debug.keystore"                                     # L57
```

`$dir` is a worktree checked out from a fetched, attacker-influenceable ref, and
`app/build.gradle` in that ref is executed as Groovy by Gradle.

**Exploit authority.** Push access to any branch the device would build. A four-line
`doFirst` block in `app/build.gradle` reads `cortex-debug.keystore` and exfiltrates it.
Losing that key means an attacker can sign updates that Android accepts as in-place
updates of the installed Cortex app — full app impersonation, permanently, because a
debug-signed app's signer cannot be rotated in place.

**After.** The signer path defaults to `$HOME/.cortex-devbridge-signer/`, outside every
checkout. Gradle builds **unsigned** in the untrusted worktree; the keystore path is
never exported to Gradle, never copied, and never printed. Signing is a separate stage,
`sign_artifact()`, that receives only the finished APK and invokes `apksigner` directly.
The worktree is destroyed before the signed artifact reaches the device. A
repository-supplied `app/cortex-debug.keystore` is *deleted* from the worktree by
`neutralize_worktree()` so attacker-supplied bytes cannot masquerade as the real signer.
`sign_artifact()` additionally refuses outright if `SIGNER_SOURCE` resolves inside
`$WORK`.

**Why blocked.** Repository build logic and signing material now never coexist in one
filesystem or one process. There is no ordering of Gradle tasks that can read a file
the build never receives. Signer continuity is preserved by the unchanged
`install_update()` check: a candidate whose signer digest differs from the installed
app's is refused, so the separate signing stage cannot silently change app identity.

---

## D2 — mutable ref, and option-like ref injection

**Before.**

```
REF_ALLOW='^[A-Za-z0-9_./-]+$'                       # L25
git -C "$CONTROL" fetch origin "$ref"                # L50
git -C "$CONTROL" worktree add --detach "$dir" FETCH_HEAD
```

Two distinct defects. First, `$ref` is a *branch name*: what gets built is whatever the
branch tip says at fetch time, so review of a commit does not constrain what is built.
Second, `-` is inside the character class, so `--upload-pack=...` and similar
option-shaped values satisfy `REF_ALLOW` and are then passed to `git fetch` in option
position.

**Exploit authority.** Write access to the job branch alone. `--upload-pack=<cmd>` makes
`git fetch` execute an arbitrary command; no repository write access is needed at all.
Even without the option injection, TOCTOU on a mutable branch means an approved job
builds unreviewed code.

**After.** Jobs carry `commit`, a full 40-hex SHA validated by `safe_commit()`. A `ref`
field is not merely ignored — its presence is a hard refusal (`DENY_REF_FIELD_REMOVED`),
so a downgraded V1 job cannot be replayed. No job-derived value is ever passed to
`git fetch`: `sync_mirror()` fetches only the fixed `TRUSTED_REFS` set with explicit
`+refs/heads/X:refs/remotes/origin/X` refspecs. `verify_provenance()` then requires the
commit to be an ancestor of a trusted named ref *before* any build step runs. Every
git argument list that carries a variable terminates option parsing with `--`, and
`not_option_like()` rejects any leading-`-` value on every validated field.

**Why blocked.** The attacker's input reaches git only as a commit-ish that has already
been proven reachable from a reviewed branch, and only after `--`. The class of "value
read as a flag" is closed by construction, not by a character-class blocklist.

---

## D3 — arbitrary code execution via `gradlew`, and a task character class

**Before.**

```
TASK_ALLOW='^[A-Za-z0-9_.:-]+$'                                      # L26
if [ -f "$dir/gradlew" ]; then chmod +x "$dir/gradlew"
  (cd "$dir" && ./gradlew --no-daemon --console=plain "$@")          # L104-107
```

The repository contains **no** `gradlew`. So this branch of the conditional is dead for
honest input and live only for an attacker: a ref that merely *adds* a `gradlew` file
gets it `chmod +x`'d and executed. It is a shell script; Gradle is never involved. The
task check is a shape check that admits every possible task name, including
`--init-script`-adjacent and custom tasks defined by the attacker's own build files.

**Exploit authority.** Push access to any buildable ref. Full unsandboxed Termux
execution as the device user, before any Gradle logic runs — the repository audit at
L241 does not help, because `gradlew` executes after it.

**After.** `run_gradle()` executes only `trusted_gradle()`: an explicitly configured
`CORTEX_DEVBRIDGE_GRADLE`, or a Gradle already installed on the device. The wrapper
branch is gone. `neutralize_worktree()` proactively deletes `gradlew`, `gradlew.bat`
and `gradle/wrapper/*` from the untrusted worktree and reports `gradlew_ignored=true`,
so the file is not merely unused but absent. `TASK_ALLOW` is now an enumerated array
(`:app:assembleDebug`, `:app:compileDebugAndroidTestJavaWithJavac`,
`:app:assembleDebugAndroidTest`) checked by exact string match in `in_list()`.

**Why blocked.** There is no code path from repository content to `exec`. Adding a task
to the allowlist is a reviewed edit to a file that is delivered as a pinned release
(see D4), not a job-time decision.

**Residual risk, stated plainly.** Gradle still *evaluates* `build.gradle` from the
untrusted commit, and Groovy build scripts are arbitrary code. This redesign does not
sandbox that; it removes the signing key from its reach (D1) and requires the commit to
have been reviewed onto a trusted ref (D2). An attacker who can get a malicious commit
merged to `main` still gets code execution at build time. Constraining that would need
a container/user boundary the device does not currently provide, and is out of scope
for this change.

---

## D4 — controller trust boundary, and the side-channel execution path

**Before.** `devbridge-bootstrap-v2.sh` installed a supervisor that, every 20 seconds:

```
git -C "$CONTROL" show "origin/$CONTROL_BRANCH:scripts/devbridge-agent-v2.sh" > "$NEXT"
  && bash -n "$NEXT" && chmod 700 "$NEXT" && mv -f "$NEXT" "$CURRENT" && "$CURRENT" --once
```

`bash -n` is a *syntax* check. A syntactically valid malicious script passes it. Worse,
`devbridge-agent-v2.sh` was itself a shim that fetched and ran
`devbridge-cortex0101-direct-export.sh` before handing off to v3 — a second execution
path outside the capability dispatcher, with no authorization, validation, logging or
result contract.

**Exploit authority.** Write access to the control branch equals persistent arbitrary
code execution on the device, acquired within 20 seconds and surviving reboot via the
Termux boot hook. Because the control branch also carried the job records, anyone who
could file a job could also ship a runtime.

**After.** `scripts/devbridge-bootstrap-v3.sh` installs a **pinned** runtime. The
operator supplies `CORTEX_DEVBRIDGE_RUNTIME_COMMIT` (40-hex) and
`CORTEX_DEVBRIDGE_RUNTIME_SHA256`. The bootstrap verifies that the commit is reachable
from a trusted named ref, extracts `scripts/devbridge-agent-v3.sh` at exactly that
commit, and refuses unless the sha256 matches the pin. The pin is persisted to
`$ROOT/runtime.pin`. The supervisor executes exactly one file, `agent.installed.sh`,
and **re-verifies its digest against the pin on every tick** — on-disk tampering stops
execution (`CORTEX_DEVBRIDGE_RUNTIME_TAMPERED`) rather than being run. It never fetches
or evaluates code from any branch.

Channels are separated: `CORTEX_DEVBRIDGE_JOB_BRANCH` defaults to
`device/termux-dev-bridge-jobs` (data only, no scripts, no workflows) and is distinct
from the trusted refs the runtime is pinned against. The v2 shim, the v1/v2 bootstraps
and all nine one-off exporter/finalizer scripts are removed from the tracked runtime
surface, so the Cortex0101 escape no longer exists. `build-apk.yml` gained a
`Dev Bridge runtime surface invariants` step that fails CI if a second controller or a
tracked `.devbridge/` record reappears.

**Why blocked.** Jobs are data and cannot update the controller: the only way to change
what code runs on the device is a human re-running the bootstrap with a new commit and
digest, both of which are checked against a trusted ref. "Latest wins" is gone.

### Runtime update procedure

1. Review the change to `scripts/devbridge-agent-v3.sh` and merge it to a trusted ref
   (`main`, or `migration/cognitive-brain-v2-step1-2`).
2. Record the merge commit SHA and `sha256sum scripts/devbridge-agent-v3.sh` at it.
3. On the device:
   ```
   CORTEX_DEVBRIDGE_RUNTIME_COMMIT=<40-hex> \
   CORTEX_DEVBRIDGE_RUNTIME_SHA256=<64-hex> \
   bash scripts/devbridge-bootstrap-v3.sh
   ```
4. A pin that fails provenance, digest, or syntax is refused and the previously
   installed runtime keeps running.

---

## Job schema change (V1 → V2)

```jsonc
{
  "protocol": "CORTEX_DEVBRIDGE_V2",   // was CORTEX_DEVBRIDGE_V1
  "repo": "KAN1409/Cortex",
  "authorized_owner": "KAN1409",
  "job_id": "job_...",
  "capability": "BUILD",
  "commit": "<full 40-hex SHA>",       // replaces "ref"; a "ref" key is now a refusal
  "package": "com.kareem.cortex",
  "params": { "gradle_tasks": [":app:assembleDebug"] }   // allowlisted tasks only
}
```

## Refusal codes added

| Code | Meaning |
|---|---|
| `DENY_COMMIT_NOT_FULL_SHA` | `commit` absent, short, non-hex, or option-like |
| `DENY_REF_FIELD_REMOVED` | job carries a `ref` key (V1 downgrade attempt) |
| `DENY_UNKNOWN_COMMIT` | commit not present in the trusted mirror |
| `DENY_UNTRUSTED_PROVENANCE` | commit not reachable from any trusted named ref |
| `DENY_GRADLE_TASK` | task not in the enumerated allowlist |
| `SIGNER_SOURCE_INSIDE_WORKTREE_REFUSE` | signer path resolves inside the build worktree |
| `TRUSTED_GRADLE_NOT_FOUND` | no runtime-controlled Gradle; repo `gradlew` is never a fallback |
| `CORTEX_DEVBRIDGE_RUNTIME_TAMPERED` | installed runtime digest no longer matches the pin |

## What this change does not claim

- It does not sandbox Gradle's evaluation of untrusted `build.gradle` (see D3 residual).
- It does not remediate Git history; the audit's existing tombstone warning about
  historical encoded signing material is unchanged and still stands.
- The device-side behaviour of the v3 runtime was **not** exercised on hardware in this
  change: no Termux, Shizuku/`rish`, or Android device was available. Boundary
  enforcement was proven by a host-side adversarial harness against real git and real
  refusal paths; the device capability paths (`install_update`, `launch_pkg`, smoke)
  are unchanged from the reviewed V3 behaviour apart from receiving a separately signed
  artifact.
