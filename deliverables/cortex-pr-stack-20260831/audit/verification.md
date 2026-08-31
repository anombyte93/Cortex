# Card V — Independent verification of Cards A, B and C

Worker: Card V (verification), kanban `t_662b52d9`, branch `wt/verification`.
Date: 2026-08-31.
Method rule applied throughout: **a control that cannot fail proves nothing.** Every green gate
below carries a positive control that was watched going red. Nothing in this report is copied from
a worker's pasted output; every command was re-run by me.

Checkouts used: fresh detached worktrees of my own at
`work/cortex-pr-stack-20260831/verify/{A,B,C}` plus a scratch worktree for per-head audits. No
verification ran inside a worker's own worktree.

## Verdicts

| Card | Branch | Head | Verdict |
|------|--------|------|---------|
| A | `wt/devbridge-v3-security` | `0041072d` | **FAIL** — one named remainder (§A5), everything else independently confirmed |
| B | `wt/supersession-audit` | `950fbdd5` | **PASS** |
| C | `wt/relay-v2-rebase` | `aa7efb3b` | **PASS** |

Card A's failure is narrow and non-security: one of 70 operational records was not carried into the
evidence store. Its security claims all held under independent attack. I am reporting it as FAIL
because the commission defines a partial pass as a FAIL with a named remainder, and §A5's claim was
"nothing of durable audit value was destroyed".

---

# CARD A — PR #70 Dev Bridge V3 security redesign — **FAIL (one remainder)**

Head verified: `0041072d0a0ca28ed1d12a67f46f795aeef57108`, base `dc92484c`, one commit,
89 files changed, +766/−2843.

**Premature-review check:** the commit exists and is the branch head. `git log dc92484c..HEAD`
returns exactly one real commit whose tree contains every claimed change. Not a phantom report.

## A7 (taken first — hard stop) — signing key: **CLEAN**

I ran this before anything else because the commission makes it a stop condition.

Method: enumerate every *new* blob object A's commit introduced that its base did not have, and
inspect each for PKCS#12 / JKS magic bytes and long base64 runs. Six new blobs, all source or docs:

```
.github/workflows/build-apk.yml            3073   magic=6e616d65  CLEAN
.gitignore                                  496   magic=2320416e  CLEAN
docs/TERMUX_DEV_BRIDGE_V1.md               3868   magic=23205465  CLEAN
docs/TERMUX_DEV_BRIDGE_V3_THREAT_MODEL.md 11939   magic=23205465  CLEAN
scripts/devbridge-agent-v3.sh             22973   magic=23212f64  CLEAN
scripts/devbridge-bootstrap-v3.sh          9222   magic=23212f64  CLEAN
```

**Positive control — FIRED.** The same detector run against `main:app/cortex-debug.keystore.b64`
(the real committed keystore, 3556 bytes) reports `magic=4d49494b LONG_BASE64_LINES=1`. The
detector demonstrably recognises real key material, so its silence above is evidence rather than
blindness.

Full-diff textual sweep (190,529 bytes of diff):
- `BEGIN PRIVATE KEY` / `BEGIN CERTIFICATE` lines: **0**
- base64 runs ≥200 chars: **0**
- `keytool` / `openssl pkcs12` / `-genkey` / `-genkeypair` / `-importkeystore` in **added** lines: **none**
  (every `keytool` hit in the diff is on a `-` line, i.e. code being *removed*)
- the only added line that copies a keystore is `+cp -f "$SIGNER_SOURCE" ...` inside a fenced
  markdown block in the threat model quoting the **old, vulnerable** code. It is documentation of
  the defect, not code.
- passwords: `SIGNER_STOREPASS` defaults to the literal `android`, the standard Android debug
  keystore password. Not a secret and not newly introduced.

**Dangling-object sweep.** The reflog alone is insufficient — a rebase or amend can leave key bytes
unreachable but still present. I scanned all 7 dangling blobs in the object store. One hit
(`7ffa8d79`, 2049 bytes, long base64) turned out to be base64 of a run of `A` characters — Card C's
placeholder control bytes, byte-compared and confirmed **not** the keystore. Positive control fired
here too.

**Verdict A7: the signing key was never generated, rotated, replaced, committed, printed or copied
on this branch.** Separately confirmed: the real keystore is present at `main` and absent at
`dc92484c`, `0041072d`, `32870cd6` and `aa7efb3b`.

## A1 — repo audit → exit 0, and the gate can fail

```
$ bash scripts/cortex-repo-audit.sh      # at 0041072d, my own clean checkout
AUDIT PASS: exactly one GitHub Actions workflow
Files scanned: 323  Warnings: 1  Failures: 0
CORTEX_REPO_AUDIT=PASS                    exit 0
```

**Positive control — FIRED.** I added `.github/workflows/zz-positive-control.yml` and, critically,
`git add -f`'d it, because the check reads `git ls-files` and not the working tree — a control
written against the working tree alone would have produced a false green.

```
AUDIT FAIL: expected one workflow, found 2
CORTEX_REPO_AUDIT=FAIL                    exit 2
```

Removed; audit returned to exit 0 and `git status` clean. The script was never modified.

## A2 — `bash -n`

Two devbridge scripts remain (`devbridge-agent-v3.sh`, `devbridge-bootstrap-v3.sh`); both parse
clean. The 12 removed scripts are genuinely gone from the tree.

## A3 — local CI build reproduces, cold

A's first claim of a green build was reproduced, but my first run returned
`49 actionable tasks: 49 up-to-date` — a warm cache is a no-op, not a compile. I ran
`gradle clean` and rebuilt:

```
BUILD SUCCESSFUL in 1m 1s
49 actionable tasks: 49 executed        <- executed, not up-to-date
app/build/outputs/apk/debug/app-debug.apk   42,344,668 bytes
497 .class files produced
```

Toolchain: JDK 17.0.18, Gradle 8.9 from `work/.../toolchain/gradle-8.9`, `ANDROID_HOME=~/Android/Sdk`.
The `platforms;android-35` requirement is real (`app/build.gradle:55 compileSdk 35`) and the
platform is installed — the box now carries android-34, **android-35** and android-36. A's
toolchain recipe reproduces.

**Positive control — FIRED.** Appending invalid Groovy to `app/build.gradle` produced
`FAILURE: Build failed with an exception`, exit 1. Restored; tree clean.

## A4 — I attacked the redesign myself

I did not run A's harness. I wrote my own
(`verify/harness/attack-A.sh`) against a fresh git fixture, the **real** agent script at A's head,
and a stub "trusted gradle" that *observes the worktree at execution time* rather than trusting the
agent's own log lines.

My first run scored 10/28 and I nearly reported those as refusals. They were not: my job ids
(`job_a1`) failed the agent's `job_id` regex, so the agent was refusing for the **wrong reason** and
every "refused" line was measuring the adjacent object. Fixed to `job_attack1` etc. Final result,
**27 OK / 1 BAD**, where the 1 BAD is a deliberately-wrong assertion proving the harness can fail:

| Attack | Result |
|---|---|
| SELF-TEST: honest build must succeed | `BUILD_SUCCESS` — harness is not vacuously denying |
| Mutable branch name as `commit` | `DENY_COMMIT_NOT_FULL_SHA` |
| Abbreviated (valid, 12-hex) SHA | `DENY_COMMIT_NOT_FULL_SHA` |
| `--upload-pack=touch /tmp/pwn`, `-x`, `--output=/tmp/pwn` | all `DENY_COMMIT_NOT_FULL_SHA` |
| Option-like `package` (`--help`) | `DENY_PACKAGE` |
| Job carrying a `ref` field at all | `DENY_REF_FIELD_REMOVED` |
| Commit reachable only from an untrusted branch | `DENY_UNTRUSTED_PROVENANCE` |
| Tasks `:app:pwn`, `assembleRelease`, `--init-script=/tmp/evil.gradle` | all `DENY_GRADLE_TASK` |
| **Attacker `gradlew` payload merged INTO the trusted ref** | see below |
| Repo-planted `app/cortex-debug.keystore` | `repo_supplied_keystore_removed=true` |
| Signer path resolving inside `$WORK` | refused before signing |
| `BAD_PROTOCOL` / `BAD_REPO` / `BAD_OWNER` / traversal `job_id` | all refused |
| Capability `SHELL` | `DENY_CAPABILITY` |
| `apk_path=../../../../etc/passwd` | `DENY_APK_PATH` |
| MUST-FAIL self-check | reported BAD, as designed |

The `gradlew` attack is the important one, so I made provenance **legitimately pass**: I merged the
attacker payload (a `gradlew` that writes a PWNED marker, plus a wrapper jar) onto the trusted
branch, so D3 and D1 were tested on their own merits rather than being masked by D2. Observed from
*inside* the worktree at Gradle-execution time:

```
GRADLE_INVOKED args=--no-daemon --console=plain :app:assembleDebug :app:compileDebugAndroidTestJavaWithJavac
GRADLEW_PRESENT_AT_BUILD_TIME=no
WRAPPERJAR_PRESENT=no
KEYSTORE_IN_WORKTREE=
KEYSTORE_READABLE_FROM_BUILD=
attacker marker file: 0 bytes
```

The repo-supplied `gradlew` was **stripped, not merely unused**, the wrapper jar with it, the
attacker's marker never fired, and no signing material was present in the build worktree at any
point during the build.

Controller replacement and the Cortex0101 bypass, checked by reading code and tree:
- the supervisor executes exactly one file (`agent.installed.sh`) and **re-verifies its sha256
  against the pin on every tick** (`CORTEX_DEVBRIDGE_RUNTIME_TAMPERED`); it never fetches or
  evaluates code from any branch. Job data cannot change what runs.
- `scripts/devbridge-cortex0101-direct-export.sh` existed at `dc92484c` and is **gone** at
  `0041072d`. The only surviving mention of `Cortex0101` in the tree is prose in the threat model.
- `.github/workflows/export-relay-c6.yml` removed; one workflow remains.

## A5 — `.devbridge/` records: **THE REMAINDER — 1 of 70 lost**

70 `.devbridge/` records at `dc92484c`; 0 at A's head. I byte-compared every one of the 70 against
the evidence store at `evidence/devbridge-pr70/job-history/`:

```
IDENTICAL=69   DIFFERS=0   MISSING=1
MISSING FROM EVIDENCE: .devbridge/relay-candidate6-finalize.json
```

**Positive control — FIRED**: the same lookup for a deliberately absent filename returns empty, so
the MISSING branch is reachable and the 69 IDENTICAL results are real byte comparisons.

The missing record is 577 bytes and still recoverable from git history
(`git show dc92484c:.devbridge/relay-candidate6-finalize.json`). It contains a job id, package,
version, expected cert SHA-256, artifact URL and two artifact hashes — operational provenance for
the candidate-6 finalize job. Nothing secret; but it is exactly the class of durable audit record
§A5 promised was moved rather than deleted, and A's metadata states `before: 70, after: 0,
preserved_at: evidence/.../job-history/` — which is true for 69 of 70.

**Remainder to clear:** copy `.devbridge/relay-candidate6-finalize.json` from `dc92484c` into the
evidence store, then A's §5 claim is fully true. This is a one-command fix and I deliberately did
not perform it — modifying a worker's evidence to make its claim pass is exactly what the
commission forbids.

## A6 — threat model D1–D4 matches the code

`docs/TERMUX_DEV_BRIDGE_V3_THREAT_MODEL.md` exists, 233 lines. Each of D1–D4 carries a real
before → exploit authority → after → why-blocked structure, and I checked each "after" against the
code I had just read and attacked:

| | Claim | Code check |
|---|---|---|
| D1 | signer defaults outside every checkout; never copied into the worktree | `agent-v3.sh:31` default is `$HOME/.cortex-devbridge-signer/`; count of `cp -f "$SIGNER_SOURCE"` in the script: **0**; `sign_artifact()` refuses `$WORK/*` at L248 |
| D2 | full 40-hex only, no job-derived ref reaches git, `--` everywhere | `SHA_RE` L76, `safe_commit` L101, `sync_mirror` fetches only fixed `TRUSTED_REFS` L114-117, `--` present on `worktree add`/`remove` |
| D3 | no `gradlew` execution; enumerated task allowlist | count of `./gradlew` in the script: **0**; `TASK_ALLOW` is an array L66-70 matched by exact string in `in_list()` |
| D4 | pinned digest-verified runtime, per-tick recheck | bootstrap L108-127 provenance + sha256, supervisor L149-164 re-verifies each tick, `RUNTIME_TAMPERED` present |

The document's own "what this change does not claim" section is honest and matches what I found:
Gradle still evaluates untrusted `build.gradle` (I confirmed this — my fake-gradle was invoked
inside the attacker's worktree), git history is not remediated, and device paths were not exercised.

## A — unresolved observation, not a failure

The reflog shows `refs/remotes/fork/security/termux-dev-bridge-v3-hardening ... update by push` at
18:40:47, while A's metadata says `pushed: false`. `fork` is
`https://github.com/anombyte93/Cortex.git` — a personal fork, **not** upstream `KAN1409/Cortex`.
Upstream is untouched: `origin/main` = local `main` = `c8c49ccc`, no origin reflog entries, 90
upstream heads, and PRs #64–#70 are all still `OPEN`. The hard constraint (never push to upstream,
never touch a PR) is honoured; A's `pushed: false` is nonetheless inaccurate as stated.

---

# CARD B — supersession audit — **PASS**

Head `950fbdd5`, report committed at
`deliverables/cortex-pr-stack-20260831/audit/supersession-audit.md` (647 lines). Commit exists;
not a phantom review.

## B1 — I tried hard to falsify the #65 and #8 unique-value claims

**#65.** B's headline is that #65 holds exactly one genuinely unique, still-needed behaviour
(`RawSignalStore.evaluateTrustedEnrichment` + its 5-case regression). Recomputed by me, merge-base
correct (`mb(#66,#65) = c38b2e21`, `mb(main,#65) = c8c49ccc`):

```
main      c8c49ccc  evaluateTrustedEnrichment hits = 0
#64       c38b2e21                                 = 0
#65       9816a7ef                                 = 10
#66       32870cd6                                 = 0
#67       4cfb4536                                 = 10
#68       9f7e18d9                                 = 0
#69       3d542308                                 = 0
```

**Positive control — FIRED**: the same grep for `RawSignalStore` returns 6/22/19/25 across those
heads, so the instrument is not blind and the zeros are real absences.

The regression test exists only at #65 (and #67), and its five methods are exactly the five
behaviours B lists:

```
richerWhatsAppDeadlineRequestCrossesDurableBoundary
acknowledgementDoesNotRecreateHistoricalRequestAsNewAction
richerSecurityTextCanPromoteEvenWhenPreviewMissedIt
arbitraryAppCannotUseConversationRulesJustBecauseTextSaysPleaseSend
explicitMessageMetadataAllowsUnknownMessagingPackage
```

`evaluateTrustedEnrichment`, `TIER0_POLICY` and `CONNECTOR_POLICY` are all **0** at both #66 and
#69: the behaviour is pinned by no test anywhere on the canonical line. **B's most consequential
claim survives my attempt to falsify it, and it is the finding that most deserves action.**

One refinement B did not state: #67's `RawSignalStore.java` is **byte-identical** to #65's
(`773a02f1…` at both). So the unique behaviour lives at #65 *and* #67, and closing #67 as
"superseded by #69" drops it too. That does not contradict B's disposition (#67's *Relay payload*
is what #69 supersedes, and B does say #67's remaining delta is #65), but the enrichment obligation
attaches to both branches, not only #65.

**#8.** Recomputed independently:
- `mb(main,#8) = 00eb0567`, 2026-08-21 vs main 2026-08-27 — 6 days stale, confirmed.
- commits ahead of merge base: **125** (B's report says 128; a 3-commit discrepancy, immaterial to
  the disposition but recorded here since I re-derived it).
- `git merge-tree main #8` → **7 conflicts**, exactly the seven files B names.
- merged tree carries **12** GitHub Actions workflows (11 from #8 + `android-build.yml` from main)
  against a gate limit of 1. Unmergeable by construction: confirmed.
- `SystemAudioTranscriber.java` is identical on main and #66 (`0adf27c6…`) and **absent** at #8's
  head — #8 deleted it. Confirmed.
- `CloudTranscriptionRetryWorker`: 4 hits at #8, **0 on main**. The capability gap B flags is real.
  Positive control: `AnalysisQueue` returns 14 hits on main, so the zero is a real absence.
- `backend/transcribe.php`: 8 `getenv()` uses, no literal credential patterns. B's "no committed
  credentials" holds.

I found no unique, still-needed behaviour in #8 that B missed.

## B2 — #64 stale-CI finding: confirmed first-hand

Audit at `c38b2e21` re-run by me in my own scratch worktree: **exit 0, 260 files, 0 failures.**

Run `33140254640` fetched live from the API:

```
createdAt 2026-08-28T03:54:26Z   updatedAt 03:54:30Z   conclusion failure
jobs: [ validate-and-build  started 03:54:27  completed 03:54:29  steps: [] ]
```

One job, **zero steps**, 2 seconds. B's characterisation is exactly right. The runner-side *cause*
remains unproven (logs expired) — B says so, and I agree; the zero-steps shape is the evidence, the
cause is inference.

## B3 — #66 findings confirmed first-hand

`RawSignalStore.capture(this, db, signal)` is preserved: present at
`#66:NotificationCaptureService.java:65` and at `#69:NotificationCaptureService.java:65`, with #69
additionally routing connector traffic through `CortexConnectorIngestV1.java:72`
`RawSignalStore.capture(context, db, signal)`. The production path uses the 3-arg context form.

The `build/v2-brain-primary-v54` bypass in `build-apk.yml` is real and exactly as characterised: at
`effd492f`, when `GITHUB_REF_NAME` is that branch the job runs a single `grep` for
`DEFAULT_AUTHORITY_MODE = CognitiveAuthorityMode.V2_PRIMARY` **instead of** the whole structural
audit. I re-ran the full audit at `effd492f` myself: **exit 0, 318 files, 0 failures** — the bypass
is unnecessary. #69 already carries **0** occurrences of it.

## B4 — audit table re-run end to end, and the negative control is genuine

I re-ran `cortex-repo-audit.sh` at every head myself rather than trusting B's table:

| head | my exit | files | failures | B's table | match |
|---|---|---|---|---|---|
| main `c8c49ccc` | **2** | 223 | 6 | 2 / 223 / 6 | ✅ |
| #64 `c38b2e21` | 0 | 260 | 0 | 0 / 260 / 0 | ✅ |
| #65 `9816a7ef` | 0 | 373 | 0 | 0 / 373 / 0 | ✅ |
| #66 `32870cd6` | 0 | 318 | 0 | 0 / 318 / 0 | ✅ |
| #67 `4cfb4536` | 0 | 379 | 0 | 0 / 379 / 0 | ✅ |
| #68 `9f7e18d9` | 0 | 319 | 0 | 0 / 319 / 0 | ✅ |
| #69 `3d542308` | 0 | 329 | 0 | 0 / 329 / 0 | ✅ |
| v54 `effd492f` | 0 | 318 | 0 | 0 / 318 / 0 | ✅ |
| #8 `8baa00a6` | n/a — script absent at that head | | | n/a | ✅ |

Every number reproduces. Main's 6 failures are the six palette assertions B names, which I read
directly from my own run output. This is a real negative control: **the audit gate provably goes
red**, so green at the other heads is a signal.

## B5 — nothing destroyed, nothing touched upstream

90 upstream heads over the network; 91 local tracking refs (the extra is `origin` itself). PRs
#8 and #64–#70 are all still `OPEN` per the API. No branch deleted, no PR opened/closed/commented/
merged.

**#67/#68 byte-identity to #69, checked by tree-hash rather than a path-filtered diff** (which can
be vacuous): `CortexRelayBridgeV2.java` and `CortexRelayV2DiagnosticsActivity.java` are SAME at
both #67 and #68 versus #69. **Positive control — FIRED**: `app/build.gradle` differs between #69
and #66 under the identical comparison, so SAME is meaningful.

---

# CARD C — PR #69 rebase onto the final #66 line — **PASS**

Head `aa7efb3b`, 12 commits on top of #66. Commits exist; not a phantom review.

## C1 — rebased onto the FINAL #66 head, not a stale SHA

```
merge-base(C, #66) = 32870cd69a0c30ceb0bc134cd91c8eaebd00823f
#66 head           = 32870cd69a0c30ceb0bc134cd91c8eaebd00823f
```

#66's head is an ancestor of C. B's handoff named `32870cd6` as the final #66 head; C landed on
exactly that. The pre-rebase stale base `0dd8453a` also remains an ancestor, which is expected —
it is an ancestor of `32870cd6` itself, not evidence of a stale cut.

## C5 — nothing cherry-picked from #67 or #68

Patch-id comparison of C's 12 exclusive commits against every commit exclusive to #67 (379) and
#68 (4): **0 matches from either.**

**Positive control — FIRED**: the same matcher finds **8** of C's commits patch-id-matching #69's
exclusive commits, which is exactly what a rebase from #69 should produce. The matcher demonstrably
detects a shared patch, so the two zeros are meaningful rather than a broken comparison.

**Keystore deletion survived the rebase** — the failure mode C's own handoff warned about:

```
main     c8c49ccc   keystore files in tree: 1
#66      32870cd6                          : 0
#69      3d542308                          : 1
C        aa7efb3b                          : 0
```

C carries #66's deletion forward and does not resurrect #69's committed keystore.

## C2 — gates re-run by me, with a negative control

- `bash scripts/cortex-repo-audit.sh` at `aa7efb3b`: **exit 0**, `CORTEX_REPO_AUDIT=PASS`.
- `bash -n` on all 8 tracked shell scripts: clean.
- Cold build: `BUILD SUCCESSFUL in 1m 15s`, `49 actionable tasks: 49 executed`,
  `app-debug.apk` 42,367,556 bytes. JDK 17.0.18 / Gradle 8.9.
- **Positive control — FIRED**: I `git add -f`'d a decoy `.apk`; audit went
  `AUDIT FAIL: compiled APK binaries must not be tracked in git`, **exit 2**. Removed; exit 0
  restored; tree clean.

## C3 — the five Relay invariants, verified by reading code

I did not use C's table. Where my first grep found nothing (invariant 1 returned no hits inside
`*Relay*.java`), I widened the search rather than concluding absence — the enforcement lives in
`CortexConnectorRegistryV1` and `CortexLocalBusService`, not in the Relay classes.

1. **Caller UID + signer authentication.** `CortexLocalBusService.handle()` L46 resolves
   `CortexConnectorRegistryV1.resolve(this, msg.sendingUid)` on **every** message; a null identity
   is logged and replied `UNAUTHORIZED_CALLER` before any dispatch. `CortexConnectorRegistryV1`
   L21-27 maps the UID to packages, requires the exact package `com.kareem.secondbrain` **and** a
   signing certificate whose SHA-256 equals the pinned `fd402eef…`, via
   `GET_SIGNING_CERTIFICATES` with a pre-28 `GET_SIGNATURES` fallback. `handleHello` L90 also
   refuses a claimed `connector_id` that disagrees with the caller UID (`IDENTITY_MISMATCH`).
   **Confirmed.**
2. **V2 negotiation with V1 fallback.** `handleHello` L96-98 selects V2 only when the identity is
   `second_brain` **and** the relay advertises `CORTEX_SIGNAL_V2`; otherwise
   `CortexLocalBusProtocolV1.PROTOCOL`. `handleV2Ingest` L146-150 refuses `MSG_INGEST_V2` outside a
   negotiated authenticated session (`V2_NOT_NEGOTIATED`), and V1 ingest remains the baseline path.
   `CortexRelayBridgeV2:36,197` default to the V1 protocol when no session is selected.
   **Confirmed.**
3. **Exact event IDs.** Dispatch is on exact constants — `MSG_PING`, `MSG_HELLO`,
   `MSG_ACTION_RESULT`, `MSG_POLICY_RESULT`, `MSG_INGEST_V2`, `MSG_INGEST` — with an explicit
   `UNKNOWN_MESSAGE` refusal for anything else. Outbound uses `MSG_ACTION_REQUEST` /
   `MSG_POLICY_UPDATE`. **Confirmed.**
4. **Ingest / dedupe / ACK.** `ingestCanonical` L177 calls
   `CortexLocalBusStoreV1.alreadyAccepted(db, event.eventId)` and, on a repeat, returns the
   existing signal id logged as `DUPLICATE_ACCEPTED` — idempotent ACK rather than a second
   ingest. Both V1 and V2 paths funnel through this one function, and both validate that the
   payload's `connector_id` matches the authenticated caller. **Confirmed.**
5. **Explicit confirmation for executable actions.** `CortexRelayV2DiagnosticsActivity` L72 states
   nothing executes until confirmed, L173-179 gate the action behind a
   `Confirm & send to Relay` button wired to `confirmAction(...)`, and L125 reports when a
   notification exposed no executable action at all. **Confirmed.**

## C4 — control-result correlation: IMPLEMENTED, and I attacked it

The decision is not ambiguous, which the commission requires: correlation is a **precondition of
authority**, implemented in `CortexRelayControlCorrelatorV2` (5903 bytes) and genuinely wired in —
`registerOutstanding` at `CortexRelayBridgeV2:105` before any send (an uncorrelatable id makes the
send **fail** with `REQUEST_ID_NOT_CORRELATABLE`), `correlate` at `:154` on every inbound result,
`reset()` at `:46` on session teardown. Only `ACCEPTED_FIRST` writes `last_<kind>_result`;
everything else is quarantined to `last_<kind>_uncorrelated_result` and rendered in the UI under
"Uncorrelated results (diagnostic only)".

I wrote my own falsification harness (`verify/harness/CorrelatorFalsify.java`), compiled against
the real class from C's head, run on a plain JVM. **15 OK / 1 BAD**, the BAD being a deliberate
must-fail self-check:

| Attack | Verdict | Authoritative |
|---|---|---|
| SELF-TEST: honest correlated result | `ACCEPTED_FIRST` | **true** (harness not vacuous) |
| Result Cortex never asked for | `UNKNOWN_REQUEST` | false |
| Replay: 2nd and 3rd copy | `DUPLICATE_REPLAY` | false |
| Right id, wrong kind | `KIND_MISMATCH` | false |
| Arrival past the 10-min TTL | `UNKNOWN_REQUEST` | false |
| Clock moved **backwards** past TTL | `UNKNOWN_REQUEST` | false |
| null / empty / whitespace id | `MISSING_REQUEST_ID` | false |
| Oversized id (230 chars vs 180 bound) | `MISSING_REQUEST_ID` | false |
| Flood past MAX_OUTSTANDING then answer the evicted id | `UNKNOWN_REQUEST` | false (outstanding capped at exactly 64) |
| Reuse an already-outstanding id | registration **refused** | — |
| Result after session `reset()` | `UNKNOWN_REQUEST` | false |
| MUST-FAIL self-check | reported BAD, as designed | — |

No uncorrelated, replayed, expired, mis-kinded, oversized or post-teardown result obtained
authority. The clock-skew and eviction cases are mine, not C's.

---

# What remains UNPROVEN — plainly worded

These are things nobody has demonstrated, by me or by any worker. None of them is a defect; all of
them are gaps between what was tested and what will happen in production.

1. **No Android device or emulator ran any of this.** Nothing was installed, launched or smoke-
   tested on hardware. For Card A that means `install_update`, `launch_pkg`, the smoke loop and
   real `apksigner` signing have never executed. For Card C it means the instrumented test suite
   was **compiled but never run** — `connectedDebugAndroidTest` was never invoked. Every device
   claim in both cards is a code-path argument, not an observation. **Neither card claimed
   otherwise, and I found no false device-validation claim.** A device acceptance run is required
   before either ships.
2. **The Termux runtime was never exercised on Termux.** My adversarial harness ran the agent
   script on Linux with a stub Gradle. The refusal logic is real and really refused, but Termux-
   specific behaviour (`rish`, Shizuku, the boot hook, the supervisor loop under Android's process
   management) is untested.
3. **Upstream CI has never run any of this.** The repo is pull-only. My build is a local
   reproduction of the exact CI command; the two new/changed `build-apk.yml` steps — including A's
   new "Dev Bridge runtime surface invariants" step — have never executed on GitHub Actions.
4. **Gradle still evaluates untrusted `build.gradle`.** A's threat model states this as a residual
   and I confirmed it directly: my fake Gradle was invoked with the attacker's worktree as its cwd.
   An attacker who gets a malicious commit onto a trusted ref still gets code execution at build
   time. The signing key is out of its reach; the process boundary is not.
5. **The runner-side cause of run `33140254640` is unknown.** Logs have expired. The zero-steps,
   2-second shape is proven; *why* the runner produced it is inference.
6. **Whether the committed keystore at `main` is still live was deliberately not tested.** Nobody
   decoded it. It remains committed at `main`, `#64`, `#65`, `#67`, `#68` and `#69` — absent only
   at `#66` and on Cards A and C's heads. This is pre-existing exposure that this stack neither
   caused nor remediates, and it is the single largest outstanding security item in the repo.
7. **Git history is not remediated.** The audit's pre-existing tombstone warning stands.
8. **`.devbridge/relay-candidate6-finalize.json` is not in the evidence store** (§A5). It is still
   recoverable from git history at `dc92484c`, so nothing is permanently lost, but the "moved, not
   deleted" claim is not yet true as stated.
9. **The five trusted-enrichment behaviours are pinned by no test anywhere.** Verified by me
   independently. Whether #69's authority path reproduces those five outcomes is unknown and needs
   a device run.
10. **A's `pushed: false` is inaccurate**: a push to the personal fork `anombyte93/Cortex`
    occurred. Upstream `KAN1409/Cortex` was not pushed to and no PR was touched — I verified both —
    so no hard constraint was breached, but the metadata does not describe what happened.

# Evidence produced by this card

All under `work/cortex-pr-stack-20260831/verify/`:

- `harness/attack-A.sh` + `attack-A-output.txt` — my independent adversarial harness, 27 OK / 1 BAD
- `harness/CorrelatorFalsify.java` + `correlator-falsify-output.txt` — my correlator attack, 15 OK / 1 BAD
- `harness/check-signing-key.sh` + `signing-key-output.txt` — gate-7 key sweep with a fired control
- `harness/check-dangling-keys.sh` — dangling-object key sweep with a fired control
- `harness/check-devbridge-moved.sh` — the 70-record byte comparison that found the missing one
- `harness/check-B.sh`, `check-B2.sh` + outputs — B's claims recomputed from git
- `harness/check-C.sh` + output — C's rebase base, patch-id cherry-pick check, invariant locations
- `A/`, `B/`, `C/`, `scratch/` — my own detached worktrees, never a worker's

# Errors I made and corrected

Recorded because an uncorrected instrument error is how a verification card produces a confident
wrong answer:

1. **My first Card A harness run scored 10/28 and every "refusal" was for the wrong reason.** My
   job ids failed the agent's `job_id` regex, so the agent refused at `BAD_JOB_ID` before ever
   reaching the control under test. I was measuring the adjacent object. Fixed; re-run; 27/28.
2. **My first Card A build "passed" with `49 up-to-date`** — a warm cache, not a compile. Cleaned
   and rebuilt cold to get `49 executed` and 497 class files.
3. **My first workflow positive control would have produced a false green**: the audit reads
   `git ls-files`, so a control written only to the working tree is invisible to it. I used
   `git add -f`.

Every harness in this report carries a deliberate must-fail self-check, so a harness whose
assertions silently no-op cannot report a perfect score.
